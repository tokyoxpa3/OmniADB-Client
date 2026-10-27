package com.omniadb.client;

import android.os.SystemClock;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.github.muntashirakon.adb.AdbStream;

/**
 * ADB 的 <b>SYNC 協定</b> —— 就是 {@code adb push} / {@code adb pull} 用的那條通道。
 *
 * <p><b>為什麼要自己寫這一層</b>：libadb 1.0.1 只做到「開一條具名服務的 stream」，
 * 沒有封裝 sync。而 {@code AdbClient} 原本上傳 APK 走的是
 * {@code shell:head -c N} 這種土砲路線，因為「ADB stream 沒有 half-close」。
 * 那個限制對 shell 成立，對 sync <b>不成立</b> —— sync 有自己的
 * {@code DONE} 封包，收檔端不必等 EOF 就知道結束了。所以：
 *
 * <table border="1">
 *   <tr><th></th><th>{@code shell:head -c}</th><th>{@code sync:}</th></tr>
 *   <tr><td>怎麼知道結束</td><td>遠端印記號，我們掃字串</td><td>協定有 {@code DONE}</td></tr>
 *   <tr><td>一次能傳幾個檔</td><td>一條 stream 一個檔</td><td><b>一條 stream 傳到底</b></td></tr>
 *   <tr><td>能不能下載</td><td>要自己數位元組</td><td>有 {@code RECV}</td></tr>
 *   <tr><td>能不能列目錄</td><td>解析 {@code ls -l}</td><td>有 {@code LIST}（結構化）</td></tr>
 * </table>
 *
 * <p><b>「一條 stream 傳到底」是這裡最重要的設計</b>：{@link AdbClient} 的註解記錄過，
 * 寫完 ~50 MB 之後那條 {@code AdbConnection} 會「半死」，再開新 stream 會卡在
 * {@code AdbConnection.open()} 裡沒有逾時的 {@code wait()}。檔案總管如果每傳一個檔
 * 就開一條 stream，傳到第三個檔就會卡死。所以這裡<b>整段會話只開一條 sync stream</b>，
 * 之後所有的 LIST / STAT / RECV / SEND 都排在這條上面跑。
 *
 * <p><b>不要跟鏡像共用連線</b>：大檔傳輸會把連線弄成半死狀態，而那條連線正扛著
 * 60fps 的影像。檔案相關的操作一律用<b>另一個</b> {@link AdbClient}。
 *
 * <p><b>逾時怎麼處理</b>：{@code AdbStream.read()} 卡住時無法中斷（libadb 的老毛病，
 * 見 {@code AdbClient#shell} 的說明），唯一的解法是從<b>外面</b>把 stream 關掉。
 * 這裡用一條看門狗：只要「有操作在跑」且「超過 {@link #IDLE_TIMEOUT_MS} 沒有任何
 * 位元組進出」，就關掉 stream 把讀者踢醒。關掉之後這個物件就報廢了
 * （{@link #isDead()}），呼叫端要重新 {@link AdbClient#sync()}。
 */
public class AdbSync implements Closeable {

    private static final String TAG = "OmniADB-Client";

    // ------------------------------------------------------------------ 協定常數
    //
    // 封包 = 4 bytes 的 ID + 4 bytes 的 little-endian 長度 + 內容。
    // 這 8 bytes 的標頭在每一個封包前面，請求與回應都一樣。

    private static final String ID_LIST = "LIST";
    private static final String ID_DENT = "DENT";
    private static final String ID_RECV = "RECV";
    private static final String ID_SEND = "SEND";
    private static final String ID_STAT = "STAT";
    private static final String ID_DATA = "DATA";
    private static final String ID_DONE = "DONE";
    private static final String ID_OKAY = "OKAY";
    private static final String ID_FAIL = "FAIL";

    /**
     * 單筆 {@code DATA} 的位元組上限。
     *
     * <p>adbd 端的 {@code SYNC_DATA_MAX} 是 64 KiB，<b>而且上限算的是整個 payload</b>
     * （SEND 時 payload 還包含路徑與結尾的 {@code \0}）。抓 32 KiB 留一半餘裕，
     * 免得多帶幾個字元的路徑就超過 —— 超過的話 adbd 直接斷線，症狀是「大檔傳到一半就失敗」。
     */
    private static final int CHUNK = 32 * 1024;

    /** 有操作在跑的時候，超過這麼久沒有位元組進出就當作卡死。 */
    private static final long IDLE_TIMEOUT_MS = 15_000;

    // ------------------------------------------------------------------ 資料結構

    /** 目錄裡的一項。{@code mode} 是 Unix 的 st_mode，用 {@link #isDir()} 判斷就好。 */
    public static final class Entry {
        public final String name;
        /** 完整路徑（由 {@link #list(String)} 接出來，省得呼叫端自己拼）。 */
        public final String path;
        public final int mode;
        public final long size;
        /** 修改時間，單位<b>秒</b>（協定就是給秒）。 */
        public final long mtime;

        Entry(String name, String path, int mode, long size, long mtime) {
            this.name = name;
            this.path = path;
            this.mode = mode;
            this.size = size;
            this.mtime = mtime;
        }

        public boolean isDir() {
            // S_IFDIR = 0040000
            return (mode & 0040000) != 0;
        }

        public boolean isLink() {
            // S_IFLNK = 0120000
            return (mode & 0120000) == 0120000;
        }
    }

    /** 傳輸進度。{@code total} 可能是 -1（RECV 時協定不先講檔案多大）。 */
    public interface Progress {
        void onBytes(long done, long total);
    }

    // ------------------------------------------------------------------ 狀態

    private final AdbStream stream;
    private final InputStream in;
    private final OutputStream out;

    /** 上一次有位元組進出的時刻（{@link SystemClock#elapsedRealtime()}）。 */
    private volatile long lastIo;
    /** 現在有沒有操作在跑 —— 看門狗只在這個旗標為 true 時才動手。 */
    private volatile boolean busy;
    private volatile boolean dead;
    private volatile boolean closed;

    // ------------------------------------------------------------------ 建構

    AdbSync(AdbClient client) throws Exception {
        // "sync:" 是 adbd 的服務名，跟 "shell:" 同一個層級。
        // 送出去之後這條 stream 就變成「封包模式」，不再是一條位元組流。
        this.stream = client.openStream("sync:");
        this.in = stream.openInputStream();
        this.out = stream.openOutputStream();
        this.lastIo = SystemClock.elapsedRealtime();

        Thread watchdog = new Thread(this::watch, "omniadb-sync-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        DiagLog.i(TAG, "檔案通道已建立（sync）");
    }

    /**
     * 看門狗：只在「有操作在跑」時計時。
     *
     * <p>不能無條件計時 —— 檔案總管開著不動一分鐘是很正常的，
     * 那時候通道閒著不是故障，關掉它只會讓使用者下次點資料夾時莫名失敗。
     */
    private void watch() {
        while (!closed) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            if (busy && SystemClock.elapsedRealtime() - lastIo > IDLE_TIMEOUT_MS) {
                DiagLog.w(TAG, "檔案通道閒置逾時 " + IDLE_TIMEOUT_MS + " ms，主動關閉");
                dead = true;
                closeQuietly();
                return;
            }
        }
    }

    public boolean isDead() {
        return dead || closed;
    }

    // ------------------------------------------------------------------ 對外操作

    /**
     * 列出目錄。
     *
     * <p>用協定的 {@code LIST} 而不是 {@code shell:ls -l}：{@code ls} 的輸出格式
     * 每個 ROM 都長得不太一樣（toybox / busybox / 有沒有限制寬度），而中文檔名
     * 還可能被截斷。{@code LIST} 給的是結構化的 mode / size / time / name，
     * 不必猜。
     *
     * <p>注意 {@code LIST} <b>不含 {@code .} 與 {@code ..}</b>，也不會遞迴 ——
     * 兩者都剛好是我們要的（上層由 UI 的「上一頁」處理）。
     */
    public synchronized List<Entry> list(String dir) throws IOException {
        begin();
        try {
            sendRequest(ID_LIST, dir);
            List<Entry> result = new ArrayList<>();
            while (true) {
                String id = readId();
                if (ID_DONE.equals(id)) {
                    // ★ 這裡是整份協定<b>最容易被文件誤導</b>的地方。
                    //
                    // 文件（跟大部分網路上的實作）都說 DONE 是
                    // 「id + 4 bytes 長度」，所以大家都只讀 4 bytes。錯。
                    //
                    // adbd 的 {@code do_list} 是直接寫一整個 {@code sync_dent}
                    // 結構當結尾：{@code id + mode + size + time + namelen}，
                    // 也就是 <b>id 之後還有 16 bytes</b>（全 0）。只讀 4 bytes 的話，
                    // 剩下 12 bytes 會留在緩衝區裡，<b>下一條請求的回應就整個錯位</b>
                    // —— 症狀是「列完一個目錄，之後所有操作都失敗或亂掉」，
                    // 而且 log 看起來一切正常。
                    //
                    // 實測：Android 13（SM-G9810）與 Android 15（POCO）都是 16 bytes。
                    // RECV 的結尾就真的是 4 bytes（那裡用的是 {@code sync_msg}），
                    // 兩個不能混用。
                    skipBytes(16);
                    return result;
                }
                if (ID_FAIL.equals(id)) {
                    throw fail("列出目錄失敗：" + dir);
                }
                if (!ID_DENT.equals(id)) {
                    throw new IOException("預期 DENT，收到 " + id);
                }
                int mode = readInt();
                long size = readInt() & 0xffffffffL;
                long time = readInt() & 0xffffffffL;
                int nameLen = readInt();
                if (nameLen <= 0) {
                    // 有些 adbd 版本用「長度 0 的 DENT」當結尾，而不是 DONE。
                    // 兩種都收，免得換一支手機就整個目錄讀不出來。
                    return result;
                }
                byte[] raw = new byte[nameLen];
                readFully(raw, 0, nameLen);
                String name = new String(raw, StandardCharsets.UTF_8);
                if (name.isEmpty()) return result;
                result.add(new Entry(name, join(dir, name), mode, size, time));
            }
        } finally {
            end();
        }
    }

    /**
     * 取單一檔案／目錄的屬性。不存在回 {@code null}（不是丟例外 ——
     * 「這個路徑還在不在」是 UI 每天都要問的問題，不該用例外表達）。
     */
    public synchronized Entry stat(String path) throws IOException {
        begin();
        try {
            sendRequest(ID_STAT, path);
            String id = readId();
            if (ID_FAIL.equals(id)) {
                // FAIL 之後連線就沒了（見 fail() 的說明），所以這裡不能回 null 就當沒事
                fail("查詢檔案失敗：" + path);
                return null;
            }
            if (!ID_STAT.equals(id)) throw new IOException("預期 STAT，收到 " + id);
            int mode = readInt();
            long size = readInt() & 0xffffffffL;
            long time = readInt() & 0xffffffffL;
            // 檔案不存在時 adbd 不會回 FAIL，而是回一個全 0 的 STAT
            // （實測 Android 13 / 15 都一樣）。mode 0 在 Unix 上本來就不是合法檔案型別，
            // 拿它當「不存在」的判據是安全的。
            if (mode == 0) return null;
            int slash = path.lastIndexOf('/');
            String name = slash < 0 ? path : path.substring(slash + 1);
            return new Entry(name, path, mode, size, time);
        } finally {
            end();
        }
    }

    /**
     * 從遠端下載一個檔案。
     *
     * <p>先寫到 {@code local.part} 再改名：中途中斷時不會留下一個「看起來正常
     * 但其實只有一半」的檔案 —— 那比直接失敗更糟，因為它會被當成成功的下載。
     */
    public synchronized void recv(String remote, File local, Progress progress) throws IOException {
        begin();
        File tmp = new File(local.getAbsolutePath() + ".part");
        try {
            long done;
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                done = recvTo(remote, fos, progress);
            }
            if (!tmp.renameTo(local)) {
                // renameTo 在少數檔案系統上會失敗（目標已存在且不可覆蓋），
                // 那就退回「複製再刪」，慢一點但一定成。
                copyFile(tmp, local);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            DiagLog.i(TAG, "已下載 " + remote + " → " + local.getName() + "（" + done + " bytes）");
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        } finally {
            end();
        }
    }

    /**
     * 下載到一個現成的輸出串流（檔案總管用）。
     *
     * <p>為什麼要有這個版本：本機那一側是 SAF 的文件（{@code content://}），
     * 不是一個可以直接寫的 {@code File}。走這條就能<b>邊收邊寫</b>，
     * 不必先落到暫存檔再複製一份 —— 傳一個 1 GB 的影片時差別很明顯。
     *
     * <p>注意：這裡<b>沒有</b> {@link #recv(String, File, Progress)} 的
     * 「先寫 .part 再改名」保護。SAF 的文件建立後就存在了，中途失敗會留下一個
     * 不完整的檔案 —— 呼叫端要自己負責刪掉它（檔案總管那邊有做）。
     */
    public synchronized void recv(String remote, OutputStream os, Progress progress) throws IOException {
        begin();
        try {
            long done = recvTo(remote, os, progress);
            DiagLog.i(TAG, "已下載 " + remote + "（" + done + " bytes）");
        } finally {
            end();
        }
    }

    /** 協定的收檔迴圈。回傳總位元組數。呼叫端負責 begin/end 與串流的開關。 */
    private long recvTo(String remote, OutputStream os, Progress progress) throws IOException {
        sendRequest(ID_RECV, remote);
        long done = 0;
        byte[] buf = new byte[CHUNK];
        while (true) {
            String id = readId();
            if (ID_DONE.equals(id)) {
                readInt();
                return done;
            }
            if (ID_FAIL.equals(id)) {
                throw fail("下載失敗：" + remote);
            }
            if (!ID_DATA.equals(id)) {
                throw new IOException("預期 DATA，收到 " + id);
            }
            int len = readInt();
            int left = len;
            while (left > 0) {
                int n = Math.min(left, buf.length);
                readFully(buf, 0, n);
                os.write(buf, 0, n);
                left -= n;
                done += n;
                if (progress != null) progress.onBytes(done, -1);
            }
        }
    }

    /**
     * 上傳一個檔案。
     *
     * <p><b>協定的三個細節，寫錯就是 {@code FAIL: missing , in ID_SEND_V1}</b>
     * （這個錯誤訊息完全看不出問題在哪，所以直接寫在這裡）：
     *
     * <ol>
     *   <li>請求的路徑不是純路徑，而是 <b>{@code /path/to/file,0644}</b> ——
     *       逗號後面是八進位權限。adbd 的 {@code do_send_v1} 拿
     *       {@code find_last_of(',')} 切開，切不到就回上面那句。</li>
     *   <li>{@code DATA} 封包的 payload 就是<b>純檔案內容</b>，
     *       不含路徑、也不含 {@code \0}。（網路上很多舊文件寫「路徑 + \0 + 資料」，
     *       那是十年前的協定，現在這樣寫會把路徑當成檔案內容寫進去。）</li>
     *   <li>結尾 {@code DONE} 的<b>時間戳直接放在 size 欄位裡</b>，後面沒有額外 payload。
     *       adbd 是 {@code *timestamp = msg.data.size} 這樣讀的；多寫 4 bytes 會留在
     *       緩衝區裡變成下一條請求的開頭。</li>
     * </ol>
     */
    public synchronized void send(File local, String remote, Progress progress) throws IOException {
        if (!local.isFile()) throw new IOException("本機檔案不存在：" + local);
        begin();
        try (InputStream is = new FileInputStream(local)) {
            long sent = sendFrom(is, local.length(), remote, progress);
            DiagLog.i(TAG, "已上傳 " + local.getName() + " → " + remote + "（" + sent + " bytes）");
        } finally {
            end();
        }
    }

    /**
     * 從一個現成的輸入串流上傳（檔案總管用）。
     *
     * <p>本機那一側是 SAF 的文件，只能拿到 {@code InputStream}；
     * 有了這個版本就不必先複製到暫存檔再傳。
     *
     * @param size 檔案大小。{@code -1} 表示不知道 —— 協定本身不需要先講大小
     *             （資料是一塊一塊送、最後才 DONE），所以不影響正確性，
     *             只是進度條沒辦法算百分比。
     */
    public synchronized void send(InputStream is, long size, String remote, Progress progress) throws IOException {
        begin();
        try {
            long sent = sendFrom(is, size, remote, progress);
            DiagLog.i(TAG, "已上傳 → " + remote + "（" + sent + " bytes）");
        } finally {
            end();
        }
    }

    /** 協定的送檔迴圈。回傳總位元組數。呼叫端負責 begin/end 與串流的開關。 */
    private long sendFrom(InputStream is, long total, String remote, Progress progress)
            throws IOException {
        // 0644：一般檔案。刻意不用本機的權限位 —— 手機上的「檔案」幾乎都是
        // 媒體或文件，執行權限帶過去沒有意義，反而可能被 SELinux 擋。
        sendRequest(ID_SEND, remote + ",0644");

        long sent = 0;
        byte[] buf = new byte[CHUNK];
        int n;
        while ((n = is.read(buf)) > 0) {
            writeId(ID_DATA);
            writeInt(n);
            out.write(buf, 0, n);
            out.flush();
            sent += n;
            lastIo = SystemClock.elapsedRealtime();
            if (progress != null) progress.onBytes(sent, total);
        }

        // DONE：修改時間<b>直接塞在 size 欄位</b>，後面不要再補 4 bytes。
        // adbd 是 `*timestamp = msg.data.size` 這樣讀的；多寫 4 bytes 會留在
        // 緩衝區裡，下一條請求的第一個欄位就會吃到它 ——
        // 症狀是莫名其妙的 `FAIL: path too long`（因為那 4 bytes 被當成路徑長度）。
        writeId(ID_DONE);
        writeInt((int) (System.currentTimeMillis() / 1000L));
        out.flush();
        lastIo = SystemClock.elapsedRealtime();

        // adbd 對整個檔案回一個 OKAY 或 FAIL（不是每個 chunk 都回）
        String reply = readId();
        readInt();
        if (ID_OKAY.equals(reply)) return sent;
        if (ID_FAIL.equals(reply)) throw fail("上傳失敗：" + remote);
        throw new IOException("上傳結束但收到預期外的回應：" + reply);
    }

    // ------------------------------------------------------------------ 收尾

    @Override
    public void close() {
        closed = true;
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            stream.close();
        } catch (Exception ignored) {
            // 已經沒了
        }
        DiagLog.i(TAG, "檔案通道已關閉");
    }

    // ------------------------------------------------------------------ 底層 I/O

    private void begin() throws IOException {
        if (isDead()) throw new IOException("檔案通道已失效，請重新整理");
        busy = true;
        lastIo = SystemClock.elapsedRealtime();
    }

    private void end() {
        busy = false;
    }

    private void sendRequest(String id, String path) throws IOException {
        byte[] p = path.getBytes(StandardCharsets.UTF_8);
        writeId(id);
        writeInt(p.length);
        out.write(p);
        out.flush();
        lastIo = SystemClock.elapsedRealtime();
    }

    private void writeId(String id) throws IOException {
        out.write(id.getBytes(StandardCharsets.US_ASCII));
    }

    /** 協定是 little-endian（主機端與裝置端都這樣寫，別改成 big-endian）。 */
    private void writeInt(int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }

    private String readId() throws IOException {
        byte[] b = new byte[4];
        readFully(b, 0, 4);
        return new String(b, StandardCharsets.US_ASCII);
    }

    private int readInt() throws IOException {
        byte[] b = new byte[4];
        readFully(b, 0, 4);
        return (b[0] & 0xff) | ((b[1] & 0xff) << 8) | ((b[2] & 0xff) << 16) | ((b[3] & 0xff) << 24);
    }

    /**
     * 讀滿 {@code len} 個位元組。
     *
     * <p>不能假設一次 {@code read} 就讀滿 —— {@code AdbStream} 是把收到的封包
     * 塞進佇列，長度取決於對端怎麼切，跟我們要的 4 bytes 標頭完全無關。
     * 這裡沒讀滿就繼續讀，直到滿或串流死掉。
     */
    private void readFully(byte[] b, int off, int len) throws IOException {
        int left = len;
        int pos = off;
        while (left > 0) {
            int n;
            try {
                n = in.read(b, pos, left);
            } catch (IOException e) {
                // 看門狗關掉 stream 時也是走這裡（libadb 丟 "Stream closed."）
                dead = true;
                throw new IOException("檔案通道讀取失敗：" + e.getMessage(), e);
            }
            if (n < 0) {
                dead = true;
                throw new IOException("遠端關閉了檔案通道");
            }
            if (n == 0) {
                // 暫時沒資料。不 sleep 會變成忙迴圈把 CPU 吃滿；
                // 睡一下也讓看門狗有機會在真的卡死時動手。
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("檔案傳輸被中斷", e);
                }
                continue;
            }
            pos += n;
            left -= n;
            lastIo = SystemClock.elapsedRealtime();
        }
    }

    /** 讀掉 {@code n} 個位元組（不看內容）。 */
    private void skipBytes(int n) throws IOException {
        byte[] buf = new byte[Math.min(n, 64)];
        int left = n;
        while (left > 0) {
            int take = Math.min(left, buf.length);
            readFully(buf, 0, take);
            left -= take;
        }
    }

    /** 讀掉一個 {@code FAIL} 封包的內容並組成例外。 */
    private IOException fail(String what) throws IOException {
        // ★ adbd 送 FAIL 之後會<b>直接把這條 sync 連線收掉</b>（它回 false，
        // 服務就跟著結束）。所以通道此刻已經死了，之後任何操作都會撞到 EOF。
        // 標記起來，讓 {@link AdbClient#sync()} 下次重建一條新的。
        dead = true;
        String msg = drainFail();
        return new IOException(msg.isEmpty() ? what : what + " —— " + msg);
    }

    private String drainFail() throws IOException {
        int len = readInt();
        if (len <= 0) return "";
        if (len > 64 * 1024) len = 64 * 1024;      // 不合理的長度就當作壞掉，別把記憶體吃光
        byte[] buf = new byte[len];
        readFully(buf, 0, len);
        return new String(buf, StandardCharsets.UTF_8).trim();
    }

    // ------------------------------------------------------------------ 小工具

    private static String join(String dir, String name) {
        if (dir.endsWith("/")) return dir + name;
        return dir + "/" + name;
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream is = new FileInputStream(from);
             OutputStream os = new FileOutputStream(to)) {
            byte[] buf = new byte[CHUNK];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }
}
