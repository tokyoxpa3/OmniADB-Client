package com.omniadb.client;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.conscrypt.Conscrypt;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbAuthenticationFailedException;
import io.github.muntashirakon.adb.AdbStream;

/**
 * 遠端 ADB 用戶端 —— 這支 App 就是一台「ADB 主機」，只是它管的不是自己，是別台手機。
 *
 * <p>血統：直接移植自 {@code OmniADB-Host} 的 {@code AdbClient}（同一套 libadb +
 * 同樣的 BouncyCastle/Conscrypt provider 初始化 + 同樣的金鑰產生邏輯）。
 * 差別只有兩處，但兩處都很關鍵：
 *
 * <ol>
 *   <li><b>Host 版本每次呼叫都 new 一個 manager 再 close</b> —— 適合「跑一條 shell
 *       就結束」。鏡像要的是一條長連線同時掛多條 stream（影像一條、控制一條），
 *       所以這裡把 manager 留著重用。重連一次 TLS 握手約 200~400 ms，60fps 的
 *       影像管線不可能每條 stream 都重握一次。</li>
 *   <li><b>補上傳檔</b>。Host 不需要傳檔（它只下 tcpip 指令），控制端要把
 *       Host APK 和 scrcpy-server 推過去。這裡走 {@code shell:head -c N}，
 *       不是正統的 SYNC 協定 —— 原因見 {@link #installApk(File)}。</li>
 * </ol>
 *
 * <p><b>ADB stream 沒有 half-close</b>：{@code AdbStream.close()} 送的是 CLSE，
 * 直接把整條 stream 收掉，做不到「只關寫入端表示 EOF」。所以
 * {@code pm install -S <size>} 那條路（寫完位元組再關 stdin）走不通。
 * 解法是叫遠端跑 {@code head -c <size>}：它<b>收滿指定位元組就自己結束</b>，
 * 串流由<b>對端</b>關閉，正好補上這個缺口。
 */
public class AdbClient implements Closeable {

    private static final String TAG = "OmniADB-Client";

    /** SYNC 協定單筆 DATA 上限。adbd 端是 64 KiB，超過會直接被切斷。 */
    private static final int SYNC_DATA_MAX = 64 * 1024;

    /** 推檔暫存目錄。shell 使用者（uid 2000）在此有完整讀寫權。 */
    private static final String TMP_DIR = "/data/local/tmp";

    private static boolean sProvidersReady;

    private final Context dpContext;
    private final String host;
    private final int port;

    /**
     * 目前的連線管理器。
     *
     * <p>加 {@code volatile}：{@link #awaitAuth(long)} 會在背景執行緒讀它，
     * 而它是在 {@code synchronized} 的 {@link #connect()} 裡被換掉的。
     */
    private volatile SimpleAdbManager manager;

    /**
     * 手上這條連線正在「撐著」等對方按允許。
     *
     * <p>這個旗標的用途只有一個：<b>阻止再開第二條連線</b>。
     * 再開一條只會讓對方多彈一個框，而且原本那條一斷，舊框立刻變孤兒。
     */
    private volatile boolean holdingForAuth;

    /**
     * 檔案通道（SYNC 協定）。<b>整段會話只開一條</b>，見 {@link AdbSync} 的說明。
     *
     * <p>刻意不跟鏡像共用這個物件：呼叫端要傳檔就自己 new 一個 {@code AdbClient}，
     * 傳完關掉。大檔會把連線弄成「半死」，而那條連線在鏡像那邊扛著 60fps 的影像。
     */
    private AdbSync syncChannel;

    public AdbClient(Context context, String host, int port) {
        this.dpContext = context.getApplicationContext();
        this.host = host;
        this.port = port;
        ensureProviders();
    }

    /**
     * 初始化加密 provider。
     *
     * <p>刻意做成 static 只跑一次：每台裝置都會建一個 AdbClient，
     * 若在建構子裡 insertProviderAt，清單會隨著裝置數不斷長大
     * （同一個 provider 被插入 N 次），而且 {@code removeProvider("BC")}
     * 會把前一台剛插好的拔掉。
     */
    private static synchronized void ensureProviders() {
        if (sProvidersReady) return;
        try {
            Security.removeProvider("BC");
            Security.insertProviderAt(new BouncyCastleProvider(), 1);
            Security.insertProviderAt(Conscrypt.newProvider(), 2);
            sProvidersReady = true;
        } catch (Exception e) {
            DiagLog.e(TAG, "初始化加密 provider 失敗", e);
        }
    }

    // ------------------------------------------------------------------ 配對

    /**
     * 用配對碼跟遠端手機配對（一次性）。
     *
     * <p>{@code pairingPort} 是「使用配對碼配對裝置」對話框上顯示的埠，
     * <b>不是</b>之後要連的 5555。兩者完全不同，混用必定失敗。
     */
    public static boolean pair(Context context, String host, int pairingPort, String code) {
        SimpleAdbManager m = null;
        try {
            m = new SimpleAdbManager(context.getApplicationContext());
            m.setApi(Build.VERSION.SDK_INT);
            m.pair(host, pairingPort, code);
            DiagLog.i(TAG, "配對成功 " + host + ":" + pairingPort);
            return true;
        } catch (Exception e) {
            DiagLog.e(TAG, "配對失敗 " + host + ":" + pairingPort, e);
            return false;
        } finally {
            closeQuietly(m);
        }
    }

    // ------------------------------------------------------------------ 連線

    /**
     * 連上了，但金鑰還沒被對方接受 —— 連線<b>仍然活著</b>，對方手機上正掛著授權框。
     *
     * <p>刻意做成例外而不是回傳值：呼叫端原本就是靠「連線失敗」這條路徑
     * 去標記「待授權」，用它接最自然，不必改一輪 API。
     *
     * <p><b>收到這個例外時千萬不要 close()。</b>連線一斷，那個授權框就變成
     * 沒有收件人的孤兒：還畫在螢幕上，但按「允許」完全沒反應，而且會把
     * {@code system_server} 的 {@code AdbDebuggingManager} 佔住 ——
     * 之後任何連線都彈不出框，只能重開機。正確做法是交給
     * {@link #awaitAuth(long)} 輪詢它。
     */
    public static class NeedAuthException extends IOException {
        public NeedAuthException() {
            super("remote device is waiting for authorization");
        }
    }

    /**
     * 完成 {@code A_CNXN} 握手給多久。
     *
     * <p><b>逾時不等於失敗</b>。已授權的裝置通常 200~600 ms 就握手完成；
     * 超過這個時間還沒完成，就代表對端把連線掛著在等人按「允許」
     * —— 這時要保留連線去等，而不是當成錯誤關掉。
     */
    private static final long HANDSHAKE_MS = 2_000;

    /** 撐住期間的輪詢間隔。150 ms 對「人按下按鈕」這種事已經足夠即時。 */
    private static final long POLL_MS = 150;

    /**
     * 先自己開一條普通 TCP 連線確認埠開著，最多等這麼久。
     *
     * <p>libadb 的 {@code AdbConnection} 建構子用的是 {@code new Socket(host, port)}，
     * <b>沒有逾時</b> —— 對端把 SYN 丟掉時會一路掛到作業系統放棄（實測兩分鐘）。
     * 那條路卡住時，我們連「關掉它」都做不到（關閉要排在同一條連線上），
     * 卡片就永遠停在「檢查中」。先探一次埠，失敗就快速、明確地失敗。
     */
    private static final int PORT_CHECK_MS = 2_500;

    /** 一般 shell 指令的讀取上限。逾時就當失敗，不要讓卡片掛在「檢查中」。 */
    private static final long SHELL_TIMEOUT_MS = 20_000;

    /** 建立（或重用）到遠端裝置的長連線。 */
    public synchronized void connect() throws Exception {
        if (manager != null && manager.isConnected()) {
            holdingForAuth = false;
            return;
        }

        // 已經有一條連線撐著在等授權 —— 絕對不能再開一條。
        // 新連線會讓對方多彈一個框，而舊的那條一旦被我們丟掉，
        // 原本那個框就變成孤兒。沿用它就好（呼叫端會去 awaitAuth）。
        if (holdingForAuth && manager != null) throw new NeedAuthException();

        if (manager == null) manager = newManager();
        manager.setTimeout(HANDSHAKE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

        // 先確認埠開著。這一步同時補上 libadb「new Socket() 沒有逾時」那個洞。
        ensurePortOpen();

        boolean ok;
        try {
            ok = manager.connect(host, port);
        } catch (Exception e) {
            // 這條路是「根本連不上」：埠沒開、TLS 被拒、對端斷線。
            // 沒有任何框在等我們，關掉是安全的。
            closeQuietly(manager);
            manager = null;
            throw e;
        }

        if (ok) {
            holdingForAuth = false;
            DiagLog.i(TAG, "已連上 " + host + ":" + port);
            return;
        }

        // ★★ 握手沒完成，而且沒有丟例外 —— 這是「對方把連線掛著在等人按允許」。
        //
        // 這裡絕對不能 close。授權框綁在發起請求的這條 transport 上：
        // 一斷線，框就變成孤兒（還畫著、但按不動），而且會把 system_server
        // 的 AdbDebuggingManager 佔死，之後任何連線都彈不出框，只能重開機。
        // 留著它，讓 awaitAuth() 去輪詢 —— 對方一按「允許」，
        // 同一個 socket 上就會收到 A_CNXN，isConnected() 立刻變 true。
        holdingForAuth = true;
        DiagLog.i(TAG, "握手未完成，保留連線等待授權 " + host + ":" + port);
        throw new NeedAuthException();
    }

    /**
     * 在已經撐著的連線上等對方按「允許」。
     *
     * <p>只輪詢 {@link AbsAdbConnectionManager#isConnected()}，<b>不重連、不重開</b>。
     * 這一點是整個授權流程的關鍵，理由見 {@link NeedAuthException}。
     *
     * <p>為什麼輪詢就夠：libadb 的連線執行緒收到 {@code A_CNXN} 時會把
     * {@code mConnectionEstablished} 設成 true 並 {@code notifyAll()}，
     * 而 {@code isConnected()} 正是「socket 沒關 && 握手完成」的組合。
     * 那個欄位是執行緒間共享的，不需要我們再去連一次。
     *
     * <p>（舊版這裡是「短逾時 + 重連重試」。重連會讓對方多彈一個框、
     * 舊框變孤兒，反而把對方搞死，已全部移除。）
     *
     * @return true 表示對方已接受，這條連線可以直接用了
     */
    public boolean awaitAuth(long timeoutMs) {
        final SimpleAdbManager m = manager;
        if (m == null) return false;

        final long deadline = System.currentTimeMillis() + timeoutMs;
        DiagLog.i(TAG, "等待對方按「允許」 " + host + ":" + port + "，最多 " + timeoutMs + " ms");
        while (System.currentTimeMillis() < deadline) {
            if (m.isConnected()) {
                holdingForAuth = false;
                DiagLog.i(TAG, "對方已按下「允許」 " + host + ":" + port
                        + "（同一條連線完成握手，未重連）");
                return true;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        DiagLog.w(TAG, "等不到對方按「允許」 " + host + ":" + port);
        return false;
    }

    public synchronized boolean isConnected() {
        return manager != null && manager.isConnected();
    }

    /**
     * 請求授權：連上去，等使用者在對方手機上按「允許」。
     *
     * <p>這是唯一會讓對方彈出授權對話框的入口，必須由使用者主動觸發 ——
     * 那個框只停留約 10~30 秒，亂彈等於浪費。
     *
     * <p>流程只有兩步，<b>沒有重試迴圈</b>：
     * <ol>
     *   <li>開一條連線，等 {@link #HANDSHAKE_MS}。已授權的裝置這裡就結束了。</li>
     *   <li>沒完成就代表對方正在等人按「允許」—— <b>保留這條連線</b>，
     *       用 {@link #awaitAuth(long)} 輪詢它，直到對方按下或逾時。</li>
     * </ol>
     *
     * <p><b>為什麼把「短逾時 + 重連重試」整段刪掉</b>：那個做法每重連一次，
     * 對方就多彈一個授權框；而我們同時把上一條連線關掉，上一輪的框立刻變成
     * 孤兒（畫著但按不動），孤兒框會把 {@code system_server} 的
     * {@code AdbDebuggingManager} 佔死 —— 之後連框都彈不出來，只能重開機。
     * 這正是「刪掉憑證後再也連不上、必須重啟對方手機」的真正原因，
     * 是我們自己製造的，不是 Android 的限制。
     *
     * <p>不需要對 libadb 打任何補丁：上游版本收到 {@code A_AUTH} 時就會送出
     * 公鑰，裝置端因此才會彈框（實測 Android 13 + 5555 明文埠可重現）。
     *
     * @param timeoutMs 最多等多久（建議 45 秒，留餘裕給人走過去拿手機）
     * @return true 表示已連上（金鑰已被對方接受）
     */
    public boolean authorize(long timeoutMs) throws Exception {
        close();                       // 丟掉舊連線；此時不可能有活著的授權框
        SimpleAdbManager m = newManager();
        manager = m;
        m.setTimeout(HANDSHAKE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

        // 埠沒開就別開連線 —— 否則會卡在沒有逾時的 new Socket() 裡。
        // 這裡丟出的例外帶「埠沒開 / 連線逾時」，呼叫端據此決定要不要換 5555 再試。
        ensurePortOpen();

        boolean ok;
        try {
            ok = m.connect(host, port);
        } catch (Exception e) {
            closeQuietly(m);
            manager = null;
            throw e;
        }
        if (ok) {
            DiagLog.i(TAG, "授權成功 " + host + ":" + port + "（免等，金鑰早已被接受）");
            return true;
        }

        holdingForAuth = true;
        if (awaitAuth(timeoutMs)) return true;

        // 逾時：框早就消失了，這時才收連線是安全的
        close();
        return false;
    }

    /** 開一條 stream。呼叫端負責 close。 */
    public synchronized AdbStream openStream(String destination) throws Exception {
        connect();
        return manager.openStream(destination);
    }

    // ------------------------------------------------------------------ 指令

    /**
     * 跑一條 shell 指令並取回完整輸出。
     *
     * <p><b>絕對不能等 EOF。</b>libadb 1.0.1 的 {@code AdbStream.read()} 有一個死角：
     * 對端送 CLSE 時如果讀佇列裡還有沒讀完的資料，它只會設 {@code mPendingClose}
     * 而不設 {@code mIsClosed}；讀者把資料讀乾之後再呼叫 {@code read()}，
     * 就卡在 {@code mReadQueue.wait()} 上<b>永遠不返回</b> —— 而且救不回來，
     * 因為那條 stream 已經被移出 {@code mOpenedStreams}，{@code close()} 的
     * {@code cleanupStreams()} 再也碰不到它。
     *
     * <p>這正是「卡片永遠停在檢查中」的真凶（實測執行緒堆疊）：
     * 三條探測執行緒全部卡死在 {@code readAll}，共用的三執行緒池被佔滿，
     * 之後<b>所有</b>探測與「請求授權」都只是排隊 —— 所以畫面不動、對方也不彈框，
     * 只有「鏡像」（自己開執行緒）還活著。
     *
     * <p>解法跟安裝 APK 一樣：<b>看結束記號，不看 EOF</b>，另外再掛一條守門執行緒，
     * 時間到就 {@code stream.close()}（它會 {@code notifyAll()}，把卡住的讀者踢醒）。
     */
    public String shell(String command) throws Exception {
        return shell(command, null, SHELL_TIMEOUT_MS);
    }

    /**
     * @param marker    遠端輸出裡一定要出現的結束記號；{@code null} 表示這條指令沒有記號，
     *                  只能讀到串流結束（那就回到「可能卡住」的老問題，非不得已別用）
     * @param timeoutMs 硬上限。時間到會主動關掉串流並丟 {@link IOException}
     */
    public String shell(String command, String marker, long timeoutMs) throws Exception {
        AdbStream stream = openStream("shell:" + command);
        try {
            return readStreamUntil(stream, stream.openInputStream(), marker, timeoutMs);
        } finally {
            closeStreamQuietly(stream);
        }
    }

    /**
     * 心跳記號。<b>刻意用可視字串而不是控制字元</b>：{@code printf '\001'} 這種
     * 八進位轉義要看遠端是哪個 shell（mksh／toybox sh／某些 ROM 自帶的），
     * 不支援時會原封不動印出 {@code \001} 四個字元，反而混進正常輸出裡。
     * 用純 ASCII 的 {@code @@HB@} 就沒有這個問題，剔掉也只是字串替換。
     */
    public static final String HEARTBEAT = "@@HB@";

    /**
     * 跑一條<b>不知道要多久</b>的遠端指令（複製、搬移、刪除整棵目錄）。
     *
     * <p>為什麼不能給固定秒數：{@code cp -r} 一個 200 MB 的相簿在慢速 eMMC 上
     * 可能要三分鐘，同一個指令換成 2 GB 的影片就是半小時。猜一個「夠大」的數字，
     * 猜小了正常操作會被砍掉，猜大了真的卡死又要等半天才知道。
     *
     * <p>做法：把指令丟到背景，另一條子 shell 每秒吐一個 {@link #HEARTBEAT} 當心跳，
     * 主 shell {@code wait} 指令並回報退出碼。呼叫端「還有位元組進來就繼續等」，
     * 連續 {@code idleMs} 沒有任何資料才判定通道死了 ——
     * <b>按實際進度等，不按時鐘等</b>。
     *
     * <p>回傳值是 marker 之前的所有輸出，其中混著心跳，呼叫端要自己剔除。
     *
     * @param idleMs 閒置上限。心跳是 1 秒一次，10~15 秒就非常寬鬆了
     */
    public String shellAlive(String command, String marker, long idleMs) throws Exception {
        // { ... ; } 包起來是為了讓「用 ; 串起來的多條指令」變成一個背景工作，
        // 否則 $! 只會是最後那一條的 pid。
        String wrapped = "{ " + command + " ; } 2>&1 & pid=$!; "
                + "( while true; do printf " + HEARTBEAT + "; sleep 1; done ) & beat=$!; "
                + "wait $pid; rc=$?; kill $beat 2>/dev/null; "
                + "echo \"@@OMNIADB:RC:$rc\"; echo " + marker;
        AdbStream stream = openStream("shell:" + wrapped);
        try {
            return readStream(stream, stream.openInputStream(), marker, idleMs, true);
        } finally {
            closeStreamQuietly(stream);
        }
    }

    /**
     * 讀到出現 {@code marker}（或串流結束）為止，並保證在 {@code timeoutMs} 內返回。
     *
     * <p>守門執行緒只做一件事：時間到就 {@code stream.close()}。那會讓卡在
     * {@code mReadQueue.wait()} 的讀者收到 {@code notifyAll} 並看到 {@code mIsClosed}，
     * 於是丟出 {@code IOException("Stream closed.")}。沒有這一步，
     * libadb 那條 read 是<b>無法中斷</b>的。
     */
    private static String readStreamUntil(AdbStream stream, InputStream in, String marker,
                                          long timeoutMs) throws IOException {
        return readStream(stream, in, marker, timeoutMs, false);
    }

    /**
     * 讀到出現 {@code marker}（或串流結束）為止。
     *
     * <p>兩種逾時語意：
     * <ul>
     *   <li>{@code idle=false}：<b>絕對上限</b>。從開始計時，時間到就斷。
     *       適合「本來就該在一兩秒內結束」的短指令。</li>
     *   <li>{@code idle=true}：<b>閒置上限</b>。只要還有位元組進來就一直等，
     *       連續 {@code timeoutMs} 沒有任何新資料才判定卡死。
     *       適合「多久結束不知道，但只要活著就該有動靜」的長指令 ——
     *       前提是遠端會定期吐心跳（見 {@link #shellAlive}）。</li>
     * </ul>
     *
     * <p>守門執行緒只做一件事：判定逾時就 {@code stream.close()}。那會讓卡在
     * {@code mReadQueue.wait()} 的讀者收到 {@code notifyAll} 並看到 {@code mIsClosed}，
     * 於是丟出 {@code IOException("Stream closed.")}。沒有這一步，
     * libadb 那條 read 是<b>無法中斷</b>的。
     */
    private static String readStream(AdbStream stream, InputStream in, String marker,
                                     long timeoutMs, boolean idle) throws IOException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        // 最後一次收到資料的時間。idle 模式靠它判斷「是不是真的沒動靜了」
        final AtomicLong lastByte = new AtomicLong(System.currentTimeMillis());

        Thread guard = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;                     // 正常結束
                }
                long now = System.currentTimeMillis();
                boolean dead = idle ? (now - lastByte.get() > timeoutMs) : (now >= deadline);
                if (dead) {
                    DiagLog.w(TAG, (idle ? "閒置逾時 " : "讀取逾時 ") + timeoutMs
                            + " ms，主動關閉串流");
                    closeStreamQuietly(stream);
                    return;
                }
            }
        }, "omniadb-read-guard");
        guard.setDaemon(true);
        guard.start();

        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        try {
            while (true) {
                int n;
                try {
                    n = in.read(buf);
                } catch (IOException e) {
                    // 對端關閉，或守門執行緒動的手 —— 兩種都當作結束
                    if ("Stream closed.".equals(e.getMessage())) break;
                    throw e;
                }
                if (n < 0) break;
                lastByte.set(System.currentTimeMillis());
                if (n == 0) {
                    // 有些實作「暫時沒資料」會回 0，別讓它變成忙迴圈
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                if (marker != null) {
                    int at = sb.indexOf(marker);
                    if (at >= 0) return sb.substring(0, at);
                }
            }
        } finally {
            guard.interrupt();
        }

        String out = sb.toString();
        boolean timedOut = idle
                ? (System.currentTimeMillis() - lastByte.get() > timeoutMs)
                : (System.currentTimeMillis() >= deadline);
        if (timedOut) {
            throw new IOException((idle ? "閒置逾時（" : "讀取逾時（") + timeoutMs + " ms）");
        }
        if (marker != null && out.indexOf(marker) < 0) {
            throw new IOException("指令沒有回報結束記號（" + marker + "）");
        }
        return out;
    }

    private static void closeStreamQuietly(AdbStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (Exception ignored) {
            // 串流早就沒了
        }
    }

    /**
     * 先用一條普通 TCP 連線確認埠開著。
     *
     * <p>只為了「快速失敗」而存在：成功之後真正的連線由 libadb 自己建。
     * 多一次 TCP 握手在區網上大約 1~3 ms，換來的是「埠沒開」一秒內就有結論。
     */
    private void ensurePortOpen() throws IOException {
        try (java.net.Socket probe = new java.net.Socket()) {
            probe.connect(new java.net.InetSocketAddress(host, port), PORT_CHECK_MS);
        } catch (java.net.SocketTimeoutException e) {
            throw new IOException("連線逾時 —— 對方沒有回應（" + port + "）", e);
        } catch (java.net.ConnectException e) {
            throw new IOException("埠沒開 —— 無線偵錯可能已關閉（" + port + "）", e);
        }
    }

    // ------------------------------------------------------------------ 傳檔

    /**
     * 把本機檔案寫到遠端（{@code shell:head -c <size> > <path>}）。
     *
     * <p>跟 {@link #installApk(File)} 同一套原理：ADB stream 沒有 half-close，
     * 不能「寫完關掉寫入端表示 EOF」，所以叫遠端跑 {@code head -c}，
     * 它<b>收滿剛好 N bytes 就自己結束</b>，缺口由對端補上。
     *
     * <p>結束判斷也不靠 EOF，靠遠端最後印出的 {@link #DONE_MARK}
     * —— 原因見該常數的說明。
     *
     * <p>用完請把這個 client 關掉（見 {@link ScrcpySession} 的用法）：
     * 大檔寫過之後同一條連線會「半死」，留給後續的 stream 只會卡住。
     */
    public void pushFile(File local, String remote) throws Exception {
        if (!local.isFile()) throw new IOException("本機檔案不存在：" + local);
        final long total = local.length();

        String script = "head -c " + total + " > " + remote + " || exit 1; echo " + DONE_MARK;
        try (AdbStream stream = openStream("shell:" + script)) {
            InputStream in = stream.openInputStream();
            OutputStream out = stream.openOutputStream();

            long sent = 0;
            try (InputStream fin = new FileInputStream(local)) {
                byte[] buf = new byte[SYNC_DATA_MAX];
                int n;
                while ((n = fin.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sent += n;
                }
            }
            out.flush();
            if (sent != total) {
                throw new IOException("本機檔案在傳輸中變動了（" + sent + "/" + total + "）");
            }
            readUntil(in, DONE_MARK, 60_000);
            DiagLog.i(TAG, "已推送 " + local.getName() + " → " + remote + "（" + total + " bytes）");
        }
    }

    // ------------------------------------------------------------------ 檔案通道

    /**
     * 取得檔案通道（必要時建立）。
     *
     * <p>同一個 {@code AdbClient} 只會有一條 —— 理由見 {@link AdbSync}：
     * 開第二條 stream 在大量寫入之後會卡死，而檔案總管本來就是連續傳很多檔。
     *
     * <p>通道死掉（逾時被看門狗關掉、對方重開 adbd）之後會自動重建一條，
     * 呼叫端不必自己判斷，只要在 {@code IOException} 時重新呼叫這個方法就好。
     */
    public synchronized AdbSync sync() throws Exception {
        if (syncChannel != null && !syncChannel.isDead()) return syncChannel;
        closeSyncQuietly();
        syncChannel = new AdbSync(this);
        return syncChannel;
    }

    private synchronized void closeSyncQuietly() {
        if (syncChannel == null) return;
        try {
            syncChannel.close();
        } catch (Exception ignored) {
            // 已經死了
        }
        syncChannel = null;
    }

    /**
     * 把對方<b>現在</b>的螢幕抓成一張 PNG。
     *
     * <p><b>為什麼不從解碼後的畫面截</b>：鏡像那條串流是縮過的
     * （{@code max_size} 上限，1220x2712 的機器在標準畫質下只有 574x1280），
     * 而且還帶著我們自己的工具列。使用者按「截圖」要的是對方原本那張圖，
     * 所以走 {@code screencap} —— 原生解析度、沒有我們的 UI。
     *
     * <p><b>為什麼要落地再拉回來</b>：{@code screencap -p} 直接吐在 stdout 的話，
     * 我們只能「讀到串流關閉」才知道結束，而那正是 libadb 會永久阻塞的地方
     * （見 {@link #shell(String)} 的說明）。寫成檔案再用 SYNC 的 {@code RECV}
     * 拉回來，協定自己會告訴我們還剩幾個位元組。
     *
     * @param local 本機要存的位置（會被覆寫）
     * @return 寫入的位元組數
     */
    public long screenshot(File local) throws Exception {
        final String remote = TMP_DIR + "/omniadb-shot-" + System.currentTimeMillis() + ".png";
        try {
            // 記號照樣要帶：screencap 本身很快，但「對方正在忙」時也可能卡住
            String out = shell("screencap -p " + remote + " ; echo " + DONE_MARK,
                    DONE_MARK, SCREENSHOT_TIMEOUT_MS);
            String low = out.toLowerCase();
            if (low.contains("error") || low.contains("failed") || low.contains("denied")) {
                throw new IOException("對方拒絕截圖：" + out.trim());
            }
            sync().recv(remote, local, null);
            long size = local.length();
            if (size <= 0) throw new IOException("截圖檔案是空的");
            DiagLog.i(TAG, "已截圖 " + remote + " → " + local.getName() + "（" + size + " bytes）");
            return size;
        } finally {
            // 暫存檔一定要清掉。對方 /data/local/tmp 空間不大，
            // 而且截圖一張就是 3~4 MB，留著幾張就爆了。
            try {
                shell("rm -f " + remote + " ; echo " + DONE_MARK, DONE_MARK, 10_000);
            } catch (Exception ignored) {
                // 清不掉不影響這次的結果
            }
        }
    }

    /** 截圖的硬上限。實測 1220x2712 的機器約 1.5 秒，給到 20 秒是為了慢機器。 */
    private static final long SCREENSHOT_TIMEOUT_MS = 20_000;

    // ------------------------------------------------------------------ 安裝

    /**
     * 安裝進度回呼。
     *
     * <p>只有兩個階段，因為<b>協定上就只知道這麼多</b>：位元組是我們自己寫進串流的，
     * 所以傳輸階段可以算得很準；但 {@code pm install} 之後就是對方的事 ——
     * dex2oat、簽章檢查、小米那個確認框都可能卡住，沒有任何進度可問。
     * 第二階段做成「不確定」而不是硬湊一個假百分比。
     */
    public interface Progress {
        /** 正在把位元組寫進遠端（0 → total）。 */
        int PHASE_PUSH = 0;
        /** 位元組送完了，正在等對方的 {@code pm install} 跑完。 */
        int PHASE_INSTALL = 1;
        /** 全部結束（成功或失敗都算）。 */
        int PHASE_DONE = 2;

        void onProgress(long sent, long total, int phase);
    }

    /**
     * 安裝 APK：傳檔、{@code pm install}、代按小米的安裝確認框、清理暫存，
     * <b>全部在同一條 shell 串流裡</b>做完。
     *
     * <p>沒有直接用 {@code install} 這個 adb 服務，因為那是 adb <b>server</b>
     * 實作的功能，libadb 只做到協定層，沒有這層便利封裝。
     *
     * <p><b>為什麼要擠成一條指令（這是踩過的坑，別拆開）</b>：
     * 寫完 ~50 MB 之後，這條 {@code AdbConnection} 會變成「半死」狀態 ——
     * 再開新 stream 會卡在 {@code AdbConnection.open()} 裡那個
     * <b>沒有逾時的 {@code wait()}</b>，永遠不返回。
     * 換一個全新的 {@code AdbClient}（= 另一條 TLS session）也一樣卡：
     * 實測新 session 確實連上了，但它的第一條 stream 要等到安裝那條關閉才拿得到回應
     * —— 那時候安裝早就因為沒人按確認框而失敗了。所以唯一穩定的做法是
     * <b>全程只用一條 stream</b>，連「看門狗」都丟到對方機器上跑。
     *
     * <p>整條指令長這樣（簡化版）：
     * <pre>
     * head -c &lt;size&gt; &gt; /data/local/tmp/x.apk
     * pm install -r -d /data/local/tmp/x.apk &gt; out 2&gt;&amp;1 &amp;
     * while 安裝還沒結束; do 看到 USB 安裝提醒就按「繼續安裝」; done
     * cat out ; rm -f x.apk out
     * </pre>
     *
     * <p>{@code head -c} 收滿剛好 N bytes 就自己結束（串流由<b>對端</b>關閉，
     * 補上 ADB 沒有 half-close 的缺口）。我們這邊只要把 N bytes 寫完、
     * {@code flush()}、然後一路讀到 EOF，就代表「安裝流程跑完了」，
     * 輸出裡有 {@code Success} 才算成功。
     *
     * @return {@code pm install} 的原始輸出，呼叫端判斷是否含 {@code Success}
     */
    public String installApk(File apk) throws Exception {
        return installApk(apk, null);
    }

    /** @param onProgress 可為 null。回呼在<b>背景執行緒</b>，呼叫端自己丟回 UI。 */
    public String installApk(File apk, Progress onProgress) throws Exception {
        if (!apk.isFile()) throw new IOException("本機檔案不存在：" + apk);
        if (!apk.canRead()) throw new IOException("本機檔案無法讀取：" + apk);

        final long total = apk.length();
        final String remote = TMP_DIR + "/omniadb-install.apk";

        DiagLog.i(TAG, "開始安裝 " + apk.getName() + "（" + total + " bytes）");
        report(onProgress, 0, total, Progress.PHASE_PUSH);

        try (AdbStream stream = openStream("shell:" + buildInstallScript(total, remote))) {
            InputStream in = stream.openInputStream();
            OutputStream out = stream.openOutputStream();

            long sent = 0;
            long mark = 0;
            // 進度條更新太密會把 UI 執行緒塞爆（一次 post 幾毫秒，寫檔是每 64 KB 一次）。
            // 每 1% 才回呼一次，60 MB 的 APK 大約 100 次，夠順也不會卡。
            long step = Math.max(1, total / 100);
            try (InputStream fin = new FileInputStream(apk)) {
                byte[] buf = new byte[SYNC_DATA_MAX];
                int n;
                while ((n = fin.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sent += n;
                    if (sent - mark >= step) {
                        mark = sent;
                        DiagLog.d(TAG, "傳輸進度 " + (sent * 100 / total) + "%");
                        report(onProgress, sent, total, Progress.PHASE_PUSH);
                    }
                }
            }
            out.flush();
            DiagLog.i(TAG, "已送完 " + sent + " bytes，等遠端安裝");
            if (sent != total) throw new IOException("本機檔案在傳輸中變動了（" + sent + "/" + total + "）");
            report(onProgress, total, total, Progress.PHASE_INSTALL);

            // 等結束記號（不是等 EOF，原因見 DONE_MARK 的說明）。
            // 另外掛一個保險：真的卡住就把串流關掉，讓 read() 拋例外收場，
            // 不要讓整個安裝流程無限懸在那裡。
            final AdbStream watched = stream;
            Thread guard = new Thread(() -> {
                try {
                    Thread.sleep(INSTALL_READ_TIMEOUT_MS);
                    DiagLog.w(TAG, "安裝讀取逾時，強制關閉串流");
                    watched.close();
                } catch (InterruptedException ignored) {
                    // 正常結束
                } catch (Exception e) {
                    DiagLog.w(TAG, "強制關閉串流失敗：" + e.getMessage());
                }
            }, "omniadb-install-guard");
            guard.setDaemon(true);
            guard.start();

            String output;
            try {
                output = readUntil(in, DONE_MARK, INSTALL_READ_TIMEOUT_MS);
            } finally {
                guard.interrupt();
            }

            if (output.contains(CONFIRMED_MARK)) {
                DiagLog.i(TAG, "對方手機跳出「USB 安裝提醒」，已代按「繼續安裝」");
                output = output.replace(CONFIRMED_MARK, "");
            }
            DiagLog.i(TAG, "pm install 輸出：" + output.trim());
            return output;
        } finally {
            // 成功、失敗、例外都要收掉進度條，不然它會永遠停在 100% 卻沒人關
            report(onProgress, total, total, Progress.PHASE_DONE);
            // 寫過幾十 MB 之後這條連線已經半死，留給 probe 只會卡住，直接丟掉。
            close();
        }
    }

    private static void report(Progress p, long sent, long total, int phase) {
        if (p == null) return;
        try {
            p.onProgress(sent, total, phase);
        } catch (Exception e) {
            // 進度回呼不該影響安裝本身
            DiagLog.w(TAG, "進度回呼丟出例外：" + e.getMessage());
        }
    }

    // ------------------------------------------------------- 小米/HyperOS 安裝確認

    /**
     * MIUI/HyperOS 的 adb 安裝確認視窗（{@code com.miui.securitycenter} 裡的 Activity）。
     *
     * <p>標題是「USB 安裝提醒」，內容是「…正在通過USB安裝此應用程式，是否繼續？」，
     * 兩顆按鈕是「繼續安裝」與「拒絕（倒數）」。<b>倒數只有 10 秒</b>，沒人按就自動拒絕，
     * {@code pm install} 於是以 {@code INSTALL_FAILED_USER_RESTRICTED:
     * Install canceled by user} 收場 —— 這個訊息完全看不出真正原因是「沒人按按鈕」。
     *
     * <p>這跟開發者選項裡的「USB 偵錯（安全設定）」<b>無關</b>（那項是給 adb
     * 改權限／模擬輸入用的），也不是 {@code adb_install_need_confirm}
     * —— 那個值本來就是 0，框還是照跳。它就是一個每次都要人按的確認框。
     */
    private static final String MIUI_INSTALL_ACTIVITY = "AdbInstallActivity";

    /**
     * 「繼續安裝」在 MIUI 對話框裡的 resource-id。
     *
     * <p>用 id 而不是文字：繁體是「繼續安裝」、簡體是「继续安装」、英文機又是另一套字，
     * 比文字一定漏。實測 {@code buttonPanel} 裡
     * <b>button1 = 拒絕、button2 = 繼續安裝</b>（跟一般 Android 的順序相反，別憑直覺猜）。
     */
    private static final String MIUI_CONFIRM_BUTTON_ID = "android:id/button2";

    /** 看門狗按到按鈕時印出的記號，回到本機後會從輸出裡挑掉。 */
    private static final String CONFIRMED_MARK = "OMNIADB_CONFIRMED";

    /**
     * 腳本最後一行固定印出的結束記號。
     *
     * <p><b>為什麼不能靠 EOF 判斷結束</b>：遠端腳本跑完（暫存檔都清掉了）
     * 之後，libadb 這邊的 {@code read()} 還是<b>一直阻塞、等不到 EOF</b>
     * —— 實測 thread CPU 0%，不是忙迴圈，就是純粹等不到串流關閉通知。
     * 所以改成「看到記號就當作結束」，完全不等 EOF。
     */
    private static final String DONE_MARK = "OMNIADB_DONE";

    /** 等安裝跑完的上限。大 APK 在慢機器上裝幾分鐘很正常。 */
    private static final long INSTALL_READ_TIMEOUT_MS = 10 * 60 * 1000;

    private static final String UI_DUMP_PATH = TMP_DIR + "/omniadb-ui.xml";
    private static final String PM_OUT_PATH = TMP_DIR + "/omniadb-pm.out";

    /**
     * 組出「上傳 → 安裝 → 代按確認框 → 清理」的完整 shell 腳本。
     *
     * <p><b>為什麼代按是必要的</b>：這是「A 機控 B 機」的遠端工具，B 機通常不在手邊，
     * 沒有人能去點那個 10 秒倒數的框。使用者按下的「安裝」本身就是明確意圖，
     * 這個框只是把同一件事再問一次，所以由控制端代按，並在 log 裡留下記錄。
     *
     * <p><b>為什麼看門狗在對方機器上跑</b>：見 {@link #installApk(File)} 的說明 ——
     * 這條連線在大量寫入之後開不了新 stream，所以只能把輪詢迴圈丟過去執行。
     * 附帶好處是每次輪詢不必來回一趟網路。
     */
    private static String buildInstallScript(long total, String remote) {
        return "head -c " + total + " > " + remote + " || exit 1; "
                // 安裝輸出導到檔案，最後才 cat —— 這樣 stdout 不會跟看門狗的輸出打架。
                // stdin 一定要接 /dev/null：那條 stream 的 stdin 是我們正在上傳的位元組。
                + "pm install -r -d " + remote + " > " + PM_OUT_PATH + " 2>&1 < /dev/null & "
                + "PID=$!; "
                // 看門狗：安裝結束（PID 消失）就收工，最多跑 2 分鐘
                + "i=0; R=; "
                + "while [ $i -lt 600 ] && kill -0 $PID 2>/dev/null; do "
                + "if dumpsys window | grep " + MIUI_INSTALL_ACTIVITY + " >/dev/null 2>&1; then "
                + "uiautomator dump " + UI_DUMP_PATH + " >/dev/null 2>&1; "
                + "N=$(grep -o '<node[^>]*" + MIUI_CONFIRM_BUTTON_ID + "[^>]*' "
                + UI_DUMP_PATH + " 2>/dev/null | head -1); "
                + "case \"$N\" in *com.miui.securitycenter*) "
                + "B=$(echo \"$N\" | grep -o 'bounds=\"\\[[0-9]*,[0-9]*\\]\\[[0-9]*,[0-9]*\\]\"'); "
                + "set -- $(echo \"$B\" | tr -cs '0-9' ' '); "
                + "if [ -n \"$1\" ] && [ -n \"$3\" ]; then "
                + "input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); "
                // 這裡只能用 break 不能用 exit：exit 會連後面的
                // wait／cat（拿安裝結果）／rm（清 50 MB 暫存）一起跳過。
                + "R=" + CONFIRMED_MARK + "; break; "
                + "fi;; "
                + "esac; "
                + "fi; "
                + "i=$((i+1)); sleep 0.2; "
                + "done; "
                + "wait $PID; cat " + PM_OUT_PATH + "; "
                + "rm -f " + remote + " " + PM_OUT_PATH + " " + UI_DUMP_PATH + "; "
                + "echo \"$R\"; echo " + DONE_MARK;
    }

    // ------------------------------------------------------------------ 收尾

    /**
     * 把 libadb 的例外翻譯成人看得懂的一句話。
     *
     * <p>libadb 在授權失敗時只丟 {@code IOException("Connection failed")}，
     * 對使用者完全沒有指引。而「未授權」其實有<b>兩條</b>解法，要一起講：
     *
     * <ol>
     *   <li>在對方手機上按「允許」（系統會彈 RSA 指紋對話框）—— 前提是有人在那台機器前面；</li>
     *   <li>用配對碼配對（SPAKE2）—— 適合無人職守、或對話框沒彈出來的情況。</li>
     * </ol>
     *
     * <p>實測（Android 15，連 5555 明文埠、全新金鑰）：{@code adb connect} 回
     * {@code failed to authenticate}，裝置端狀態 {@code unauthorized}，
     * 而 adb 自己的提示就是 "check for a confirmation dialog on your device"
     * —— 也就是說<b>對話框確實會彈</b>，配對不是唯一的路。
     */
    public static String describeError(Throwable t) {
        if (t == null) return "未知錯誤";
        if (t instanceof NeedAuthException) {
            // 這不是失敗 —— 連線還活著，正在等對方按「允許」
            return "待授權 —— 對方手機上應已彈出授權框";
        }
        String msg = t.getMessage() == null ? "" : t.getMessage();
        String low = msg.toLowerCase();
        if (isUnauthorized(t)) {
            return "未授權 —— 在對方手機上按「允許」，或用配對碼配對";
        }
        if (isTlsRejected(t)) {
            // mDNS 掃到的隨機 connect 埠走的是 TLS，沒有憑證就在握手階段被拒，
            // adbd 根本不會彈那個「允許」框 —— 只能走配對碼（或改連 5555）。
            return "憑證不被信任 —— 這個埠要走配對碼配對（或改連 5555 埠請求授權）";
        }
        if (low.contains("refused")) return "埠沒開 —— 無線偵錯可能已關閉";
        if (low.contains("timed out") || low.contains("timeout")) return "連線逾時";
        if (msg.isEmpty()) return t.getClass().getSimpleName();
        return msg;
    }

    /**
     * 是不是 TLS 握手階段就被拒（憑證不互信）。
     *
     * <p>libadb 會把 SSL 例外包成 {@code IOException("Connection failed")}，
     * 所以要沿著 cause 鏈找 {@link javax.net.ssl.SSLException}，不能只看最外層訊息。
     */
    public static boolean isTlsRejected(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof javax.net.ssl.SSLException) return true;
            String low = c.getMessage() == null ? "" : c.getMessage().toLowerCase();
            if (low.contains("certificate_unknown") || low.contains("sslv3")
                    || low.contains("sslhandshake")) return true;
            if (c.getCause() == c) break;
        }
        return false;
    }

    /**
     * 這個失敗值不值得「換一個埠再試一次」——尤其是換到 5555 明文埠。
     *
     * <p>背景：只有<b>明文埠（5555）</b>走 {@code A_AUTH}，adbd 才會彈那個
     * 「允許 USB 偵錯嗎？」的 RSA 對話框；mDNS 掃到的隨機 connect 埠是 TLS，
     * 憑證不被信任時在握手階段就被打回，<b>對方連框都不會彈</b>。
     * 所以「掃到的埠連不上」幾乎都該退回 5555 再試 —— 那是唯一能觸發授權的路。
     *
     * <p>「待授權」<b>不</b>算：那代表框已經彈出來了，換埠只會讓對方多彈一個框，
     * 而舊框會變孤兒（畫著但按不動），反而更糟。
     */
    public static boolean shouldTryPlainPort(Throwable t) {
        if (t == null) return false;
        if (isUnauthorized(t)) return false;
        if (isTlsRejected(t)) return true;
        String low = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
        return low.contains("refused") || low.contains("timeout") || low.contains("timed out")
                || low.contains("連線逾時") || low.contains("埠沒開")
                || low.contains("connection failed") || low.contains("沒有回應");
    }

    /**
     * 這個例外是不是「金鑰沒被授權」。
     *
     * <p>現在的主要判據是 {@link NeedAuthException} —— 它由 {@link #connect()}
     * 在「握手沒完成、但也沒被拒絕」時主動拋出，代表對方正在等人按「允許」。
     *
     * <p>另外也認 libadb 的 {@code AdbAuthenticationFailedException}。看過 1.0.1
     * 的位元組碼，它只在 {@code mAbortOnUnauthorised=true} 且收到第二次
     * {@code A_AUTH} 時拋出。我們現在一律設 false（理由見 {@code newManager()}），
     * 所以正常不會再看到它，但保留判據以免哪天有人把那個開關打開。
     *
     * <p>以前把 {@code "connection failed"} 也算成未授權，結果<b>埠沒開、逾時、
     * 對方 adbd 剛重啟</b>都會顯示成「請在對方手機按允許」，把人指到完全錯的方向。
     */
    public static boolean isUnauthorized(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof NeedAuthException) return true;
            if (c instanceof AdbAuthenticationFailedException) return true;
            if (c.getCause() == c) break;
        }
        String low = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
        return low.contains("unauthor") || low.contains("authenticat");
    }

    @Override
    public synchronized void close() {
        holdingForAuth = false;
        closeSyncQuietly();
        closeQuietly(manager);
        manager = null;
    }

    // ------------------------------------------------------------------ 內部

    private SimpleAdbManager newManager() throws Exception {
        SimpleAdbManager m = new SimpleAdbManager(dpContext);
        m.setApi(Build.VERSION.SDK_INT);
        // ★ 這裡<b>一定</b>是 false，沒有任何情況該設 true。
        //
        // 設 true 時，libadb 收到第二次 A_AUTH（也就是「adbd 正要彈授權框」
        // 的那一刻）就會設 mAuthorisationFailed 並結束連線執行緒。
        // 框才剛要出來、連線就沒人讀了 —— 那個框立刻變成孤兒，
        // 而且會把 system_server 的 AdbDebuggingManager 佔住。
        //
        // 設 false 時，第二次 A_AUTH 會再送一次公鑰（這是 adbd 觸發彈框的
        // 正常流程），連線執行緒繼續讀，等對方按下「允許」後在同一條 socket
        // 上收 A_CNXN。這才是我們要的行為。
        m.setThrowOnUnauthorised(false);
        m.setTimeout(HANDSHAKE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        return m;
    }

    /**
     * 一直讀到出現 {@code marker} 為止（或 EOF、或逾時）。
     *
     * <p>跟 {@link #readStreamUntil} 的差別：這裡不帶 stream 參照、也沒有守門執行緒，
     * 所以<b>真的卡住時救不回來</b>。只有已經自帶看門狗（自己會 {@code close()}
     * 那條串流）的呼叫端才可以用 —— 目前是 {@link #pushFile(File, String)} 與
     * {@link #installApk(File)}。
     *
     * <p>回傳值已經把 {@code marker} 之後的內容切掉了。
     */
    private static String readUntil(InputStream in, String marker, long timeoutMs) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int n;
            try {
                n = in.read(buf);
            } catch (IOException e) {
                // 串流被關掉（含上面那個保險 thread 動的手）也當作結束
                if ("Stream closed.".equals(e.getMessage())) break;
                throw e;
            }
            if (n < 0) break;
            if (n == 0) {
                // 有些實作在「暫時沒資料」時回 0，別讓它變成忙迴圈
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            if (sb.indexOf(marker) >= 0) break;
        }
        String s = sb.toString();
        int at = s.indexOf(marker);
        return at >= 0 ? s.substring(0, at) : s;
    }

    private static void closeQuietly(SimpleAdbManager m) {
        if (m == null) return;
        try {
            m.close();
        } catch (Exception ignored) {
            // 關閉失敗沒有補救手段
        }
    }

    // ================================================================== 金鑰

    /**
     * libadb 的連線管理器，負責金鑰的產生、保存與載入。
     *
     * <p>金鑰存在 App 私有目錄，<b>所有裝置共用同一把</b> —— 這樣遠端手機的
     * 「已授權裝置」清單裡只有一條 OmniADB-Client，而不是每次連線都多一條。
     */
    private static class SimpleAdbManager extends AbsAdbConnectionManager {

        private final File keyFile;
        private final File pubKeyFile;
        private final File certFile;

        private PrivateKey privateKey;
        private PublicKey publicKey;
        private X509Certificate certificate;

        SimpleAdbManager(Context context) throws Exception {
            setApi(Build.VERSION.SDK_INT);
            File dir = context.getFilesDir();
            keyFile = new File(dir, "adb_key");
            pubKeyFile = new File(dir, "adb_key.pub");
            certFile = new File(dir, "adb_cert");
            loadOrGenerate();
        }

        private void loadOrGenerate() throws Exception {
            if (keyFile.exists() && pubKeyFile.exists() && certFile.exists()) {
                try {
                    KeyFactory kf = KeyFactory.getInstance("RSA");
                    privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(keyFile)));
                    publicKey = kf.generatePublic(new X509EncodedKeySpec(readAll(pubKeyFile)));
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    certificate = (X509Certificate) cf.generateCertificate(
                            new ByteArrayInputStream(readAll(certFile)));
                    return;
                } catch (Exception e) {
                    DiagLog.e(TAG, "既有金鑰載入失敗，重新產生", e);
                }
            }
            generate();
        }

        private void generate() throws Exception {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            KeyPair kp = gen.generateKeyPair();
            privateKey = kp.getPrivate();
            publicKey = kp.getPublic();
            certificate = selfSigned(kp);

            writeAll(keyFile, privateKey.getEncoded());
            writeAll(pubKeyFile, publicKey.getEncoded());
            writeAll(certFile, certificate.getEncoded());
        }

        private X509Certificate selfSigned(KeyPair kp) throws Exception {
            X500Name name = new X500Name("CN=OmniADB-Client");
            BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
            Date from = new Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000);
            Date to = new Date(System.currentTimeMillis() + 3650L * 24 * 60 * 60 * 1000);
            SubjectPublicKeyInfo spki =
                    SubjectPublicKeyInfo.getInstance(kp.getPublic().getEncoded());

            X509v3CertificateBuilder builder =
                    new X509v3CertificateBuilder(name, serial, from, to, name, spki);
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider("BC")
                    .build(kp.getPrivate());
            X509CertificateHolder holder = builder.build(signer);
            return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
        }

        private static byte[] readAll(File f) throws IOException {
            byte[] out = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < out.length) {
                    int n = in.read(out, off, out.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            return out;
        }

        private static void writeAll(File f, byte[] data) throws IOException {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
        }

        @Override
        @NonNull
        protected PrivateKey getPrivateKey() {
            return privateKey;
        }

        @Override
        @NonNull
        protected Certificate getCertificate() {
            return certificate;
        }

        @Override
        @NonNull
        protected String getDeviceName() {
            return "OmniADB-Client";
        }
    }
}
