package com.omniadb.client;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * 會寫進檔案的 logcat —— 這支 App 出事時沒有電腦可以接 {@code adb logcat}。
 *
 * <p><b>為什麼不是「把 logcat 抓出來」</b>：Android 11 之後 {@code READ_LOGS} 只給
 * 系統／shell 用，一般 App 讀不到別人的 log，連自己的也要 {@code adb} 才拿得到。
 * 所以只能自己寫一份 —— 把 {@code Log.i/w/d/e} 換成這裡的靜態方法，
 * 一邊送 logcat（開發時照樣看得到），一邊追加到
 * {@code /data/data/com.omniadb.client/files/logs/omniadb.log}。
 *
 * <p><b>為什麼要留在檔案而不是只有記憶體</b>：真正要查的是「上一次連線為什麼斷」。
 * 使用者按「匯出」時，那段歷史早就滾出螢幕也滾出記憶體了。
 *
 * <p><b>為什麼每寫一行就 flush</b>：這份 log 的價值在於「App 被砍掉／對方 adbd
 * 掛掉」之後還在。緩衝在記憶體裡的日誌，正好會在那一刻全部消失。
 * 寫入頻率是每秒幾行等級（鏡像的逐幀路徑<b>沒有</b>走這裡），
 * 一次 write+flush 幾十微秒，換得到崩潰前的完整現場。
 *
 * <p>容量上限 {@link #MAX_BYTES}，滿了就改名成 {@code omniadb.log.1}（只留一代），
 * 不會無聲無息吃掉幾百 MB。
 */
public final class DiagLog {

    private static final String DIR = "logs";
    private static final String FILE_NAME = "omniadb.log";
    private static final String ROTATED = "omniadb.log.1";

    /** 單檔上限。手機上的日誌超過 2 MB 大概也讀不完了。 */
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    /** 記憶體裡留最近幾行，給「日誌」對話框即時看。 */
    private static final int RING_MAX = 500;

    private static final Object LOCK = new Object();
    private static final Deque<String> RING = new ArrayDeque<>();
    /** 時間只到毫秒，而且只在寫入時用 —— 共用一個 formatter 比每次 new 省很多。 */
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static File dir;
    private static FileOutputStream out;

    private DiagLog() {
    }

    /**
     * 指定日誌目錄。<b>只有第一次有效</b>：{@code Application.onCreate} 會呼叫，
     * 之後各個 Activity 再叫也不會把檔案搬到別的地方。
     */
    public static void init(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (dir != null) return;
            File d = new File(context.getApplicationContext().getFilesDir(), DIR);
            if (!d.isDirectory() && !d.mkdirs()) {
                Log.w("OmniADB-Client", "無法建立日誌目錄：" + d);
                return;
            }
            dir = d;
        }
    }

    public static boolean ready() {
        synchronized (LOCK) {
            return dir != null;
        }
    }

    public static File file() {
        synchronized (LOCK) {
            return dir == null ? null : new File(dir, FILE_NAME);
        }
    }

    // ------------------------------------------------------------------ 等級

    public static void d(String tag, String msg) {
        write('D', tag, msg, null);
    }

    public static void i(String tag, String msg) {
        write('I', tag, msg, null);
    }

    public static void w(String tag, String msg) {
        write('W', tag, msg, null);
    }

    public static void w(String tag, String msg, Throwable t) {
        write('W', tag, msg, t);
    }

    public static void e(String tag, String msg) {
        write('E', tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable t) {
        write('E', tag, msg, t);
    }

    // ------------------------------------------------------------------ 讀取

    /** 記憶體裡最近的日誌（新的在後面），給對話框預覽用。 */
    public static String recent() {
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder();
            for (String line : RING) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    /**
     * 把「上一代 + 目前這一代」打包成一個檔案，回傳它。
     *
     * <p>刻意寫到 {@code cacheDir} 而不是直接分享 {@code files/logs} 那個檔：
     * 一來分享的是<b>快照</b>（分享出去的瞬間之後不會再變），
     * 二來可以順手在開頭補上機型／版本，收到日誌的人不必再問一輪。
     */
    public static File export(Context context, String header) throws IOException {
        File target = new File(context.getCacheDir(),
                "omniadb-log-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                        .format(new Date()) + ".log");
        try (OutputStream os = new FileOutputStream(target)) {
            if (header != null && !header.isEmpty()) {
                os.write(header.getBytes(StandardCharsets.UTF_8));
                if (!header.endsWith("\n")) os.write('\n');
                os.write('\n');
            }
            synchronized (LOCK) {
                if (dir != null) {
                    copyInto(new File(dir, ROTATED), os);
                    copyInto(new File(dir, FILE_NAME), os);
                }
            }
            if (target.length() == 0) {
                os.write("（沒有日誌）\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        return target;
    }

    /** 清掉兩代日誌與記憶體那份。 */
    public static void clear() {
        synchronized (LOCK) {
            RING.clear();
            closeQuietly();
            if (dir == null) return;
            //noinspection ResultOfMethodCallIgnored
            new File(dir, FILE_NAME).delete();
            //noinspection ResultOfMethodCallIgnored
            new File(dir, ROTATED).delete();
        }
    }

    // ------------------------------------------------------------------ 內部

    private static void write(char level, String tag, String msg, Throwable t) {
        String safeTag = tag == null ? "OmniADB-Client" : tag;
        String safeMsg = msg == null ? "" : msg;

        // ① logcat：開發時照樣用 logcat 看，行為跟以前一樣
        switch (level) {
            case 'D': Log.d(safeTag, safeMsg, t); break;
            case 'W': Log.w(safeTag, safeMsg, t); break;
            case 'E': Log.e(safeTag, safeMsg, t); break;
            default:  Log.i(safeTag, safeMsg, t); break;
        }

        // ② 檔案
        StringBuilder sb = new StringBuilder(96);
        synchronized (LOCK) {
            sb.append(TIME.format(new Date()))
                    .append(' ').append(level)
                    .append('/').append(safeTag)
                    .append(" (").append(Thread.currentThread().getName()).append(") ")
                    .append(safeMsg);
            if (t != null) sb.append('\n').append(Log.getStackTraceString(t));
            String line = sb.toString();

            RING.addLast(line);
            while (RING.size() > RING_MAX) RING.removeFirst();

            if (dir == null) return;
            try {
                if (out == null) out = new FileOutputStream(new File(dir, FILE_NAME), true);
                if (out.getChannel().size() >= MAX_BYTES) rotate();
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                // 寫不進去（空間滿、目錄被清）就放棄這一行，不要讓 log 把 App 拖垮
                closeQuietly();
            }
        }
    }

    /** 滿了就換代。<b>只留一代</b>：舊的覆蓋掉，不累積。 */
    private static void rotate() {
        closeQuietly();
        File cur = new File(dir, FILE_NAME);
        File old = new File(dir, ROTATED);
        //noinspection ResultOfMethodCallIgnored
        old.delete();
        //noinspection ResultOfMethodCallIgnored
        cur.renameTo(old);
        // 下一次 write 會重新開檔（rename 之後舊的 handle 寫的是已改名的檔案）
    }

    private static void closeQuietly() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
            // 關不掉也沒有補救手段
        }
        out = null;
    }

    private static void copyInto(File src, OutputStream os) throws IOException {
        if (!src.isFile()) return;
        try (InputStream in = new FileInputStream(src)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }
}
