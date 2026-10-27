package com.omniadb.client;


import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

import io.github.muntashirakon.adb.AdbStream;

/**
 * 一條<b>只做音訊</b>的 scrcpy 連線（{@code video=false}）。
 *
 * <p><b>為什麼音訊要獨立一條 session：</b>scrcpy 的 {@code audio_dup} 決定擷取的聲音
 * 要不要同時從<b>對方喇叭</b>放出來，這個值在伺服器<b>啟動時就固定</b>，要換只能重啟。
 * 綁在影像 session 上就代表「切一次對方靜音 → 整個鏡像重連、畫面黑 1~2 秒」；
 * 拆成獨立 session 之後，切換只重啟這一條，<b>畫面完全不動</b>，
 * 代價只有約 1 秒的聲音空窗。
 *
 * <p><b>★ 這條 session 用的是 scrcpy-server 4.1（跟影像同一顆 jar，2026-10-26 統一）</b>
 * （{@code audio_source=playback} + {@code audio_dup}）。2.x 的 {@code audio_dup}
 * 在三星 A13 / POCO A15 上完全沒作用（對方一定變安靜）；4.1 換了實作才真的
 * 「喇叭照響、同時擷取」。完整對照表見 {@link ScrcpyServer#VERSION}。
 *
 * <p><b>★ 這裡的量測結論（2026-10-26，POCO / Android 15 實測）：</b>
 * 播放擷取是「<b>音量之後</b>」的訊號 —— 把對方的 STREAM_MUSIC 調 0 或
 * {@code cmd audio adj-mute 3}，<b>擷取到的資料也會變全零</b>（RMS 601 → 0）。
 * 所以「對方喇叭安靜、但我這邊還聽得到」<b>不能</b>靠改對方音量來做，
 * 唯一的辦法就是 {@code audio_dup=false}：只擷取、不 render。
 *
 * <p><b>★ 純音訊時的握手跟影像 session 不同：</b>只有一條 socket，而且
 * {@code dummy(1) + 裝置名(64)} 寫在<b>這條音訊 socket</b> 上（有影像時才寫在影像 socket），
 * 之後才是 4 bytes codec id。照影像那套順序讀，會把裝置名當成 codec id。
 */
final class AudioSession implements Closeable {

    private static final String TAG = "OmniADB-Client";

    /** 裝置名長度（UTF-8，後面補 0）。 */
    private static final int DEVICE_NAME_LEN = 64;

    /** 音訊封包大小上限。實測 raw 是 4096 bytes（≈21 ms）。 */
    private static final int MAX_AUDIO_PACKET = 64 * 1024;

    /**
     * 裸 PCM 的旁路（給錄影用）。
     *
     * <p>在<b>音訊讀取執行緒</b>上呼叫，一包約 21 ms。<b>不可以阻塞</b> ——
     * 擋住這裡等於擋住播放（同一個執行緒還要寫 {@link AudioPlayer}）。
     * 實作見 {@link Recorder#onPcm}：它只是複製一份丟進佇列。
     *
     * @param ptsUs 這一包的時間戳（微秒，<b>對方裝置的開機時間</b>，跟影像同一套時鐘）
     */
    interface PcmTap {
        void onPcm(byte[] data, int len, long ptsUs);
    }

    /** 等音訊伺服器把 socket 開起來的上限。純音訊不用等編碼器，比影像快。 */
    private static final long SERVER_START_TIMEOUT_MS = 8_000;

    /** 兩包間隔超過這個值就記一筆（正常 21.3 ms）。 */
    private static final long AUDIO_GAP_WARN_MS = 200;

    /**
     * <b>共用主 session 的 ADB 連線，不自己開一條。</b>
     *
     * <p>每開一條新的 ADB 連線，對方裝置的無線偵錯就會再跳一次
     * 「無線偵錯已連線」的通知 —— 切一次聲音跳一次，很煩。
     * 用同一條連線開 stream 完全不影響功能（主 session 的影像與控制本來就是
     * 同一條連線上的兩條 stream），只是少一次 TLS 握手，切換也快一點。
     *
     * <p>因此這條 session <b>不擁有</b>連線，{@link #close()} 不會關它。
     */
    private final AdbClient sharedClient;

    /**
     * {@code audio_dup}：true ＝ 對方喇叭照樣出聲；false ＝ 只擷取、對方安靜。
     * 這個值在 session 建立時就固定了，要換得重開一條。
     */
    private final boolean hostAudible;

    private AdbClient client;
    private AdbStream shellStream;
    private AdbStream stream;
    private AudioPlayer player;
    private Thread reader;
    private Thread logThread;

    private volatile boolean running;

    /** 音訊有沒有真的開起來（對方 < Android 13、或擷取被拒絕時是 false）。 */
    private volatile boolean active;

    private volatile String deviceName = "";

    /** 這一條 session 專用的 abstract socket 後綴（每次重開都不同，見 {@link ScrcpyServer#audioScid()}）。 */
    private final String scid = ScrcpyServer.audioScid();

    /** 伺服器最後幾行日誌，失敗時拿來當錯誤訊息。 */
    private final Deque<String> serverLog = new ArrayDeque<>();

    /** 裸 PCM 的旁路（錄影用）；沒在錄影時是 null。見 {@link PcmTap}。 */
    private volatile PcmTap pcmTap;

    AudioSession(AdbClient sharedClient, boolean hostAudible) {
        this.sharedClient = sharedClient;
        this.hostAudible = hostAudible;
    }

    boolean hostAudible() {
        return hostAudible;
    }

    boolean isActive() {
        return active;
    }

    String deviceName() {
        return deviceName;
    }

    /**
     * 掛上裸 PCM 的旁路（錄影用）。
     *
     * <p><b>要在 {@link #start()} 之前呼叫</b>：讀取執行緒是 start() 裡起來的，
     * 之後才掛會漏掉開頭幾包。
     */
    void setPcmTap(PcmTap tap) {
        this.pcmTap = tap;
    }

    // ================================================================== 啟動

    /**
     * 建立連線、讀握手、開喇叭。<b>會阻塞</b>（約 1~1.5 秒），請在背景執行緒呼叫。
     *
     * <p>「對方根本不能給音訊」不算錯誤 —— 那時 {@link #isActive()} 會是 false，
     * 上層把按鈕變暗就好。真的連不上才拋例外。
     */
    void start() throws Exception {
        running = true;

        client = sharedClient;
        if (client == null) throw new IOException("ADB 連線已關閉，開不了音訊");
        shellStream = client.openStream("shell:" + ScrcpyServer.audioCommandLine(hostAudible, scid));
        startLogDrain();
        DiagLog.i(TAG, "音訊 session 已啟動（audio_dup=" + hostAudible + "，scid=" + scid + "）");

        stream = openWithRetry();
        InputStream in = stream.openInputStream();

        // 純音訊：dummy byte 和裝置名都走這條 socket
        byte[] one = new byte[1];
        readFully(in, one, 1);
        if (one[0] != 0) DiagLog.w(TAG, "音訊 session 的 dummy byte 不是 0（" + one[0] + "），仍繼續");

        byte[] nameBuf = new byte[DEVICE_NAME_LEN];
        readFully(in, nameBuf, DEVICE_NAME_LEN);
        String raw = new String(nameBuf, StandardCharsets.UTF_8);
        int zero = raw.indexOf('\u0000');
        deviceName = (zero >= 0 ? raw.substring(0, zero) : raw).trim();

        byte[] hdr = new byte[4];
        readFully(in, hdr, 4);
        int codecId = readIntBE(hdr, 0);
        if (codecId == 0) {
            DiagLog.w(TAG, "對方停用音訊（codec id = 0）—— 多半是 Android 13 以下");
            closeStream();
            return;
        }
        String codec = new String(hdr, StandardCharsets.US_ASCII).trim();
        DiagLog.i(TAG, "音訊串流標頭：codec=" + codec + "（0x" + Integer.toHexString(codecId) + "）");
        if (!"raw".equals(codec)) {
            // 我們只請求 raw。真的遇到別的編碼就別硬播（會變噪音）。
            DiagLog.w(TAG, "音訊編碼不是 raw（" + codec + "），跳過播放");
            closeStream();
            return;
        }

        AudioPlayer p = new AudioPlayer();
        try {
            p.start();
        } catch (Throwable e) {
            // 喇叭開不起來（音訊裝置被佔用）不該拖垮鏡像
            DiagLog.w(TAG, "開不了音訊輸出：" + e);
            closeStream();
            return;
        }
        player = p;
        active = true;
        startReader(in);
    }

    private AdbStream openWithRetry() throws Exception {
        long deadline = System.currentTimeMillis() + SERVER_START_TIMEOUT_MS;
        Exception last = null;
        int attempt = 0;
        while (running && System.currentTimeMillis() < deadline) {
            attempt++;
            try {
                return client.openStream(ScrcpyServer.audioSocket(scid));
            } catch (Exception e) {
                last = e;
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IOException("連不上音訊伺服器（試了 " + attempt + " 次）"
                + (last == null ? "" : "：" + last.getMessage()) + lastServerLog());
    }

    // ================================================================== 讀取

    /**
     * 音訊讀取執行緒。
     *
     * <p>封包格式跟影像一樣：12 bytes 標頭（8 bytes pts ＋ 旗標、4 bytes 長度）再接 payload，
     * 只是 payload 是裸 PCM。<b>不需要節拍器</b>：{@link AudioPlayer#write} 內部阻塞寫
     * {@code AudioTrack}，播放速度自然把讀取速度壓回即時（背壓一路傳回 socket）。
     */
    private void startReader(InputStream in) {
        reader = new Thread(() -> {
            byte[] hdr = new byte[12];
            byte[] buf = new byte[8192];
            long lastAt = android.os.SystemClock.uptimeMillis();
            long lastGapLog = 0;
            long packets = 0;
            try {
                while (running) {
                    readFully(in, hdr, 12);
                    long ptsFlags = readLongBE(hdr, 0);
                    int size = readIntBE(hdr, 8);
                    if (size < 0 || size > MAX_AUDIO_PACKET) {
                        throw new IOException("音訊封包大小不合理：" + size);
                    }
                    if (size > buf.length) buf = new byte[size];
                    readFully(in, buf, size);

                    // config 包（旗標 bit63）是給解碼器用的初始資料。raw 沒有，
                    // 但協定上可能出現，跳過即可 —— 當成 PCM 播會是雜音。
                    if ((ptsFlags & (1L << 63)) == 0) {
                        // 先給錄影（它只複製、不阻塞），再寫喇叭。
                        // 順序反過來的話，AudioTrack 的背壓會讓錄影的音訊晚一包。
                        PcmTap tap = pcmTap;
                        if (tap != null) {
                            long ptsUs = ptsFlags & ~(1L << 63);
                            try {
                                tap.onPcm(buf, size, ptsUs);
                            } catch (Exception e) {
                                // 錄影壞了不該把播放一起帶走
                                DiagLog.w(TAG, "音訊旁路失敗", e);
                            }
                        }
                        AudioPlayer p = player;
                        if (p != null) p.write(buf, size);
                        packets++;
                    }

                    long now = android.os.SystemClock.uptimeMillis();
                    long gap = now - lastAt;
                    if (gap > AUDIO_GAP_WARN_MS && now - lastGapLog > 5_000) {
                        lastGapLog = now;
                        DiagLog.w(TAG, "音訊中斷 " + gap + " ms（累計 " + packets
                                + " 包）—— 網路卡住，或對方根本沒在播");
                    }
                    lastAt = now;
                }
            } catch (Exception e) {
                if (running) DiagLog.w(TAG, "音訊串流結束：" + e.getMessage());
            }
        }, "omniadb-audio-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void startLogDrain() {
        logThread = new Thread(() -> {
            try (InputStream in = shellStream.openInputStream()) {
                StringBuilder line = new StringBuilder();
                int c;
                while ((c = in.read()) >= 0) {
                    if (c == '\n') {
                        String s = line.toString().trim();
                        line.setLength(0);
                        if (!s.isEmpty()) recordLog(s);
                    } else if (c != '\r') {
                        line.append((char) c);
                    }
                }
            } catch (Exception e) {
                DiagLog.d(TAG, "音訊伺服器日誌串流結束：" + e.getMessage());
            }
        }, "omniadb-audio-log");
        logThread.setDaemon(true);
        logThread.start();
    }

    private void recordLog(String line) {
        synchronized (serverLog) {
            serverLog.addLast(line);
            while (serverLog.size() > 8) serverLog.removeFirst();
        }
        DiagLog.i(TAG, "音訊伺服器：" + line);
    }

    private String lastServerLog() {
        synchronized (serverLog) {
            if (serverLog.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("（伺服器日誌：");
            for (String s : serverLog) sb.append('\n').append(s);
            return sb.append('）').toString();
        }
    }

    // ================================================================== 收尾

    @Override
    public void close() {
        running = false;
        active = false;
        pcmTap = null;

        // 先關喇叭：AudioTrack 一釋放，阻塞在 write() 的音訊執行緒就會退出
        AudioPlayer p = player;
        player = null;
        if (p != null) p.stop();

        closeStream();
        closeQuietly(shellStream);
        shellStream = null;
        // 連線是主 session 的，不關（關了鏡像就斷了）
        client = null;
        DiagLog.i(TAG, "音訊 session 已關閉（audio_dup=" + hostAudible + "）");
    }

    private void closeStream() {
        closeQuietly(stream);
        stream = null;
    }

    // ================================================================== 工具

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
            // 關閉失敗沒有補救手段
        }
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new EOFException("串流已結束（" + off + "/" + len + "）");
            off += n;
        }
    }

    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static long readLongBE(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }
}
