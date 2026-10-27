package com.omniadb.client;

import android.content.Context;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

import io.github.muntashirakon.adb.AdbStream;

/**
 * 一次鏡像連線的完整生命週期。
 *
 * <p>一條 ADB 連線上掛<b>三條 stream</b>（音訊不在這裡，見 {@link AudioSession}）：
 *
 * <pre>
 *   shell:CLASSPATH=… app_process …      啟動 scrcpy-server，這條要一直開著（關了伺服器就死）
 *   localabstract:scrcpy  （第 1 條）     影像：H.264
 *   localabstract:scrcpy  （第 2 條）     控制：觸控 / 按鍵 / 剪貼簿（雙向）
 * </pre>
 *
 * <p><b>兩條 socket 都連同一個名字，順序不能顛倒。</b>這是 scrcpy 伺服器的實作方式：
 * {@code tunnel_forward=true} 時它開一個 {@code LocalServerSocket("scrcpy")}，
 * 然後依序 {@code accept()}（影像、控制）。連錯順序的症狀是
 * 「把觸控寫進影像串流」—— 畫面不動、對方亂點。
 *
 * <p><b>★ 而且兩條都 accept 完，伺服器才送裝置名與 stream meta。</b>
 * 所以「先連影像 → 讀 meta → 再連控制」這種順序會直接卡死
 * （實測：讀 meta 10 秒逾時，log 卻什麼都沒有）。正確順序是
 * 「連影像 → 讀 dummy byte → 連控制 → 才讀 meta」。
 *
 * <p><b>★ 4.1 的標頭比 2.7 多一段：</b>{@code dummy(1) + name(64) + codec id(4)
 * + session meta(12)}。session meta 帶著<b>目前的影像尺寸</b>，而且旋轉／resize 時
 * 會在同一條 socket 上再插一筆 —— 觸控的 {@code screenW/H} 必須跟它一致，
 * 否則伺服器整筆丟掉。細節見 {@link #startVideoReader(InputStream)}。
 *
 * <p><b>★ 音訊為什麼不在這條 session 上：</b>音訊的 {@code audio_dup}
 * （對方喇叭要不要出聲）只能啟動時決定，綁在這裡就代表「切一次靜音＝畫面重連」。
 * 拆到 {@link AudioSession} 之後切換只重啟音訊那條，畫面不受影響。
 *
 * <p>每條連線開頭還有握手資料（見 {@link #start(Surface, int)} 的說明），
 * 全部讀完才會進入穩定的資料流。
 */
final class ScrcpySession implements Closeable {

    private static final String TAG = "OmniADB-Client";

    /** 影像與控制都用這個 abstract socket 名（見類別說明的順序要求）。 */
    private static final String SOCKET = "localabstract:scrcpy";

    /** 等伺服器把 socket 開起來的上限。app_process 啟動 ART 大約 0.5~1.5 秒。 */
    private static final long SERVER_START_TIMEOUT_MS = 12_000;

    /** 握手標頭長度：裝置名 64 bytes（UTF-8，補 0）。 */
    private static final int DEVICE_NAME_LEN = 64;

    /** 影像 stream meta：只有 4 bytes codec id（4.1 起；2.7 是 12 bytes 含尺寸）。 */
    private static final int CODEC_ID_LEN = 4;

    /**
     * session meta：{@code flags(4) + width(4) + height(4)}，共 12 bytes。
     *
     * <p>跟封包標頭<b>共用同樣的 12 bytes 位置</b>，靠第一個 int 的最高位分辨：
     * 是 1 就是 session meta（寬高就接在後面），是 0 就是一張畫面的 pts。
     * 正常封包的 pts 是微秒（約 9×10¹⁰），永遠碰不到 bit63，所以這個判準是安全的。
     */
    private static final int SESSION_META_LEN = 12;

    /** 4.1 的 frame flag（2.7 是 CONFIG=bit63、KEY=bit62，位置整個往下移了一位）。 */
    private static final long FLAG_SESSION = 1L << 63;
    private static final long FLAG_CONFIG = 1L << 62;
    private static final long FLAG_KEY_FRAME = 1L << 61;

    // ---------------------------------------------------------------- 剪貼簿

    /** 裝置→電腦 的剪貼簿訊息（{@code TYPE_CLIPBOARD}）：1 byte 型別 + 4 bytes 長度 + UTF-8。 */
    private static final int DEVICE_MSG_CLIPBOARD = 0;

    /** 裝置→電腦 的 ACK（{@code TYPE_ACK_CLIPBOARD}）：1 byte 型別 + 8 bytes sequence。 */
    private static final int DEVICE_MSG_ACK_CLIPBOARD = 1;

    /** 裝置→電腦 的 UHID 輸出（我們沒用，但遇到要能跳過，不然整條串流會錯位）。 */
    private static final int DEVICE_MSG_UHID_OUTPUT = 2;

    /**
     * 控制訊息：請對方重置影像編碼（{@code TYPE_RESET_VIDEO}，scrcpy 4.1 的 17 號）。
     *
     * <p>伺服器收到會呼叫 {@code CaptureControl.reset(RESET_REASON_CLIENT_RESET)} ——
     * 效果是<b>立刻產生一個關鍵幀</b>（並重送 config 包）。scrcpy 自己用它來處理
     * 「影片暫時停掉後要重新接上」的情況。
     *
     * <p>我們用它來讓<b>錄影</b>可以馬上開始：MP4 的第一個樣本必須是 IDR，
     * 而編碼器的 GOP 是 10 秒，不主動要一個的話，按下錄影後最多要空等 10 秒
     * （見 {@link Recorder}）。
     */
    private static final byte CONTROL_TYPE_RESET_VIDEO = 17;

    /**
     * 剪貼簿字串的長度上限（bytes）。
     *
     * <p>scrcpy 2.7 的 {@code ControlMessageReader.CLIPBOARD_TEXT_MAX_LENGTH}
     * ＝ 256 KB 扣掉封包標頭。超過這個長度伺服器會直接丟例外、把控制通道收掉，
     * 所以我們自己在送出去之前就截斷（截在 UTF-8 字元邊界，不要切出半個中文字）。
     */
    private static final int MAX_CLIPBOARD_BYTES = (1 << 18) - 14;

    /**
     * 輪詢對方剪貼簿的間隔。
     *
     * <p><b>為什麼是輪詢而不是等對方推：</b>scrcpy 的 {@code clipboard_autosync=true}
     * 只會「變化時推」，而 {@code GET_CLIPBOARD} 在 autosync 開啟時是 no-op ——
     * 也就是說<b>連線前就複製好的內容永遠拿不到</b>。改成 {@code clipboard_autosync=false}
     * 之後，對方不會主動推，但我們可以隨時 {@code GET_CLIPBOARD} 讀到現況，
     * 連線前後的內容一視同仁。
     *
     * <p>代價是延遲（最多一個週期）與一點流量；600 ms 對「複製完再貼上」這種
     * 有幾秒緩衝的操作沒感覺，也不會讓對方每秒被讀幾十次剪貼簿。
     */
    private static final long CLIPBOARD_POLL_MS = 600;

    /** 等 {@code SET_CLIPBOARD} 被對方 ACK 的上限。 */
    private static final long CLIPBOARD_ACK_TIMEOUT_MS = 1_500;


    /**
     * 控制訊息佇列。
     *
     * <p><b>為什麼不能直接在觸控回呼裡寫 socket</b>：那是 UI 執行緒，Android 的
     * StrictMode 會直接丟 {@link android.os.NetworkOnMainThreadException} ——
     * 而且不是「慢」而已，是<b>閃退</b>（實測：手指一碰畫面 App 就掛）。
     *
     * <p>所以觸控只負責把位元組塞進佇列，真正的寫入交給一條專屬執行緒。
     * 這樣也順便保證了順序：DOWN → MOVE → UP 一定按進佇列的順序送出去。
     */
    private final java.util.concurrent.LinkedBlockingQueue<byte[]> controlQueue =
            new java.util.concurrent.LinkedBlockingQueue<>(256);

    private Thread controlWriter;

    /**
     * 現在是不是<b>把對方的顯示器電源關掉了</b>（{@code setScreenPowerMode(false)}）。
     *
     * <p>記在 session 上是刻意的：關閉連線的路徑有很多條（使用者退出、測試結束、
     * 錯誤、重連），若只讓 Activity 自己記，任何一條漏了都會把對方留在黑屏。
     * 由 {@link #close()} 統一開回去最保險。
     */
    private volatile boolean displayPowerOff;

    interface Listener {
        void onVideoSize(int width, int height);

        void onReady();

        void onError(String message);

        void onEnded();

        /**
         * 對方的剪貼簿有<b>新的</b>內容（同一筆只會回報一次）。
         *
         * <p>在控制通道的讀取執行緒上呼叫，<b>不是 UI 執行緒</b>；
         * 也不要在這裡直接碰 socket 或做長時間的事。
         */
        void onHostClipboard(String text);

        /**
         * 音訊 session 的狀態變了（連上、重建完成、或這台根本給不了音訊）。
         *
         * <p>在音訊管理執行緒上呼叫，<b>不是 UI 執行緒</b>。
         *
         * @param active      音訊串流有沒有真的在跑
         * @param hostAudible 對方喇叭現在有沒有出聲（{@code audio_dup}）
         */
        void onAudioState(boolean active, boolean hostAudible);
    }

    private final Context context;
    private final String host;
    private final int port;
    private final Listener listener;

    private AdbClient client;
    private AdbStream shellStream;
    private AdbStream videoStream;
    private AdbStream controlStream;
    private OutputStream controlOut;
    private InputStream controlIn;
    private VideoDecoder decoder;
    private Thread videoThread;
    private Thread logThread;
    private Thread controlReader;
    private Thread clipboardPoller;

    /** 送給對方、還在等 ACK 的序號。{@code SET_CLIPBOARD} 的 sequence 從 1 起算。 */
    private final AtomicLong clipboardSeq = new AtomicLong();

    /** 對方最後一次 ACK 的序號；配 {@link #ackLock} 使用。 */
    private long clipboardAcked;

    private final Object ackLock = new Object();

    /**
     * 最後一次「已知在對方剪貼簿裡」的內容。
     *
     * <p>兩個用途：<br>
     * ① 我們自己送過去的內容，對方輪詢時會原封不動讀回來 —— 比對這欄就當成回音丟掉，
     *    不然會變成「Client 推給 Host → Host 讀到 → 套回 Client → 又推給 Host」的回圈。<br>
     * ② 對方剪貼簿沒變時，每一次輪詢都會拿到同一筆，比對這欄就不會重複回報給上層。
     */
    private volatile String lastClipboardText;

    private volatile boolean running;
    private volatile int videoWidth;
    private volatile int videoHeight;

    /** 有沒有收到過 session meta（＝尺寸有權威來源，SPS 只當後備）。 */
    private volatile boolean sessionSizeKnown;
    private volatile String deviceName = "";

    /** 音訊那條獨立 session（null ＝ 還沒起來，或已經關掉）。 */
    private volatile AudioSession audio;

    /**
     * 這條連線要不要有音訊 session。
     *
     * <p>跟 {@code hostAudible} 是<b>兩件事</b>：
     * <ul>
     *   <li>{@code audioOn=false}：完全沒有音訊 session。對方喇叭走它原本的路徑
     *       （我們不介入），我這邊聽不到。</li>
     *   <li>{@code audioOn=true} + {@code hostAudible=true}：dup=true，兩邊都有聲。</li>
     *   <li>{@code audioOn=true} + {@code hostAudible=false}：dup=false，只有我聽得到。</li>
     * </ul>
     * 分開的理由：{@code audio_dup} 只能在 session 啟動時決定，所以「對方喇叭要不要出聲」
     * 一定得有一條 session 才控制得了。把「不要聲音」做成「沒有 session」之後，
     * 那顆「有聲／靜音」按鈕就必須還能<b>把 session 接回來</b>，否則等於把對方的喇叭
     * 丟在一個誰都管不到的狀態。
     */
    private volatile boolean audioOn = true;

    /** 使用者要的狀態：對方的喇叭要不要出聲。預設 true（連上就兩邊都聽得到）。 */
    private volatile boolean hostAudible = true;

    /**
     * 音訊切換請求佇列。
     *
     * <p>切換＝重啟音訊 session（約 1~1.5 秒）。連按兩下不該跑兩輪，所以由一條
     * 專屬執行緒序列化處理，並且<b>只做最後一個請求</b>。
     */
    private final java.util.concurrent.LinkedBlockingQueue<Boolean> audioRequests =
            new java.util.concurrent.LinkedBlockingQueue<>();

    private Thread audioWorker;

    /** 最近一次<b>真的建立成功</b>的 {@code audio_dup}；失敗時清成 null，下次切換會重試。 */
    private volatile Boolean audioApplied;

    /** 是不是我們把對方的旋轉鎖住了（斷線時要負責還原）。 */
    private volatile boolean rotationOwned;

    /**
     * 錄影器（沒在錄影時是 null，或存在但 {@code isRecording()==false}）。
     *
     * <p>由 Activity 建立並在 {@link #start(Surface, int)} 之前掛上來 —— 影像讀取執行緒
     * 只負責把封包轉手給它，本身不做任何封裝。見 {@link Recorder}。
     */
    private volatile Recorder recorder;

    /** 掛上錄影器。<b>要在 {@link #start(Surface, int)} 之前呼叫</b>，否則會漏掉開頭幾個封包。 */
    void setRecorder(Recorder r) {
        this.recorder = r;
    }

    /** 伺服器最後幾行日誌。連線失敗時拿它當錯誤訊息，比「逾時」有用得多。 */
    private final Deque<String> serverLog = new ArrayDeque<>();

    ScrcpySession(Context context, String host, int port, Listener listener) {
        this.context = context.getApplicationContext();
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    // ================================================================== 啟動

    /**
     * 建立連線並開始解碼。<b>會阻塞</b>，請在背景執行緒呼叫。
     *
     * <p>流程與每一步為什麼這樣做：
     *
     * <ol>
     *   <li><b>推 jar。</b>用一個<b>獨立</b>的 {@link AdbClient}，推完立刻關掉。
     *       理由跟安裝 APK 一樣：大量寫入之後那條連線會「半死」，之後開新 stream
     *       會卡在 libadb 裡沒有逾時的 {@code wait()}。寧可多花一次 TLS 握手
     *       （約 300ms），也不要讓整個鏡像卡在第一步。</li>
     *   <li><b>起伺服器。</b>這條 shell stream 要一直開著 —— 它一關，adbd 就對
     *       子進程發 SIGHUP，scrcpy-server 跟著死。</li>
     *   <li><b>連影像 → 控制。</b>順序見類別說明；<b>兩條連完才能讀 meta</b>。
     *       伺服器剛啟動時 socket 還沒建好，所以第一條要用重試而不是一次定生死。</li>
     *   <li><b>讀握手。</b>影像 socket 開頭是：1 byte 的 dummy（0）、64 bytes 裝置名、
     *       4 bytes codec id、12 bytes session meta（4.1 起）。dummy 那顆是 scrcpy 拿來
     *       「確認連線真的通了」用的，不讀掉它後面全部會偏移一個 byte ——
     *       症狀是裝置名變成亂碼、尺寸變成天文數字。</li>
     *   <li><b>音訊另外開一條 session</b>（{@link AudioSession}），在影像就緒之後
     *       才開始，不拖慢畫面。</li>
     * </ol>
     */
    void start(Surface surface, int lockRotation) throws Exception {
        running = true;

        // ---- 1. 推 jar（獨立連線，用完就丟）----
        // 影像／控制與音訊共用同一顆 4.1（2026-10-26 統一，原因見 ScrcpyServer.VERSION）。
        File jar = ScrcpyServer.extract(context);
        AdbClient pusher = new AdbClient(context, host, port);
        try {
            pusher.pushFile(jar, ScrcpyServer.REMOTE_JAR);
            // ---- 1.5 先鎖對方方位，再起伺服器 ----
            //
            // <p><b>為什麼一定要在「起伺服器」之前：</b>伺服器一啟動就照<b>當下</b>的
            // 顯示器方向編碼，尺寸也直接寫進 session meta。等連上之後才送旋轉指令，
            // 第一批畫面已經是錯的方向 —— 這就是切換畫質重連後「先出現一條橫的、
            // 1~2 秒後才轉正」的來源（對方 close 時被還原成自由旋轉，依它自己的感測器
            // 轉了，而我們那時還沒連上，來不及阻止）。
            //
            // <p>用 pusher 這條現成的連線送，省一次 TLS 握手。鎖完之後伺服器還要
            // 啟動約 1 秒，足夠讓顯示器把旋轉真的套用下去。
            if (lockRotation >= 0) {
                if (lockRotation(pusher, lockRotation)) {
                    rotationOwned = true;
                    DiagLog.i(TAG, "開場前已鎖定對方方向 " + lockRotation);
                }
            }
        } finally {
            pusher.close();
        }
        if (!running) return;

        // ---- 2. 起伺服器 ----
        client = new AdbClient(context, host, port);
        String cmd = ScrcpyServer.commandLine(Settings.video(context), Settings.stayAwake(context));
        shellStream = client.openStream("shell:" + cmd);
        startLogDrain();
        DiagLog.i(TAG, "scrcpy-server 已啟動：" + cmd);

        // ---- 3. 影像 → 控制（順序固定，見類別說明）----
        videoStream = openWithRetry();
        InputStream vin = videoStream.openInputStream();

        // 4. 握手：dummy byte
        byte[] one = new byte[1];
        readFully(vin, one, 1);
        if (one[0] != 0) {
            DiagLog.w(TAG, "dummy byte 不是 0（" + one[0] + "），仍繼續");
        }

        // 5. 第二條＝控制。伺服器此刻正卡在 accept() 等它，所以不會失敗；
        //    但**不能**在這裡讀 meta —— 那要等兩條都連上、伺服器開始編碼才會有資料。
        controlStream = client.openStream(SOCKET);
        controlOut = controlStream.openOutputStream();
        // 控制通道以前只寫不讀（觸控是單向的）。剪貼簿不一樣：對方回報的內容
        // 從同一條 socket 回來，所以一定要有讀取執行緒，否則資料積在佇列裡
        // 誰也不知道，而且 AdbStream 的讀取佇列會無限長大。
        controlIn = controlStream.openInputStream();
        startControlReader();
        startControlWriter();

        // 6. 兩條都連上了，伺服器才會送裝置名 + stream meta
        byte[] nameBuf = new byte[DEVICE_NAME_LEN];
        readFully(vin, nameBuf, DEVICE_NAME_LEN);
        // 名字是 C 字串（後面補 0），要從第一個 0 截斷 —— 用 trim() 切不掉 \0
        String raw = new String(nameBuf, StandardCharsets.UTF_8);
        int zero = raw.indexOf('\u0000');
        deviceName = (zero >= 0 ? raw.substring(0, zero) : raw).trim();

        // 4.1：先 4 bytes codec id，再 12 bytes session meta（2.7 是合在一起的 12 bytes）
        byte[] codecBuf = new byte[CODEC_ID_LEN];
        readFully(vin, codecBuf, CODEC_ID_LEN);
        String codecId = new String(codecBuf, StandardCharsets.US_ASCII);

        byte[] sm = new byte[SESSION_META_LEN];
        readFully(vin, sm, SESSION_META_LEN);
        int smFlags = readIntBE(sm, 0);
        int headerW = readIntBE(sm, 4);
        int headerH = readIntBE(sm, 8);
        if ((smFlags & 0x80000000) == 0) {
            // 不是 session meta ⇒ 幾乎一定是版本／參數沒對上，硬讀下去只會得到一堆垃圾
            throw new IOException("影像標頭不是 session meta（flags=0x"
                    + Integer.toHexString(smFlags) + "），伺服器版本與 jar 可能不一致");
        }
        sessionSizeKnown = true;
        DiagLog.i(TAG, "握手完成：device=" + deviceName + " codec=" + codecId
                + " " + headerW + "x" + headerH);

        if (!"h264".equals(codecId)) {
            throw new IOException("對方編碼器不是 H.264（" + codecId + "）");
        }

        // 7. 解碼器 + 讀取執行緒
        videoWidth = headerW;
        videoHeight = headerH;
        decoder = new VideoDecoder(surface, new VideoDecoder.Callback() {
            @Override
            public void onVideoSize(int w, int h) {
                // 4.1 之後尺寸的權威來源是 session meta（伺服器就是拿它來驗觸控事件），
                // SPS 只在還沒收到 session meta 時當後備。兩者若不一致，以 session meta 為準，
                // 否則觸控會整批被伺服器丟掉（它的日誌：Ignore positional event ...）。
                if (sessionSizeKnown) {
                    if (w != videoWidth || h != videoHeight) {
                        DiagLog.w(TAG, "SPS 尺寸 " + w + "x" + h + " 與 session meta "
                                + videoWidth + "x" + videoHeight + " 不一致，以 session meta 為準");
                    }
                    return;
                }
                videoWidth = w;
                videoHeight = h;
                if (listener != null) listener.onVideoSize(w, h);
            }

            @Override
            public void onDecoderError(String message) {
                if (listener != null) listener.onError("解碼器：" + message);
            }
        });
        decoder.start();
        // 先給 UI 一個初始尺寸，畫面才不會在第一批封包到達前是一片空白
        if (listener != null) listener.onVideoSize(headerW, headerH);

        startVideoReader(vin);
        if (listener != null) listener.onReady();

        // 8. 音訊（獨立 session）。放在畫面之後才起，不讓它拖慢第一幀；
        //    它自己會再花約 1~1.5 秒（另一個 app_process），好了才回報 onAudioState。
        //    使用者選了「不要聲音」就整條都不開 —— raw PCM 是 1.5 Mbps，
        //    在只看畫面（例如操作另一支手機）的時候是純浪費。
        //    注意：之後按「有聲」不需要重連畫面，{@link #setAudioOn} 會把這條補起來。
        //
        //    ★ 起始狀態一律以 Settings 為準，不從 Activity 傳進來：
        //    兩邊各記一份的話，「選單改了設定 → 重連 → Activity 那份還是舊的」
        //    就會出現「明明選了不要聲音，重連後又自己響起來」。
        int mode = Settings.audioMode(context);
        hostAudible = mode == Settings.AUDIO_BOTH;
        if (mode != Settings.AUDIO_OFF) {
            audioOn = true;
            startAudioWorker();
            audioRequests.offer(hostAudible);
        } else {
            audioOn = false;
            DiagLog.i(TAG, "設定為不要聲音 —— 不啟動音訊 session（對方喇叭不受影響）");
            if (listener != null) listener.onAudioState(false, true);
        }
    }

    private AdbStream openWithRetry() throws Exception {
        long deadline = System.currentTimeMillis() + SERVER_START_TIMEOUT_MS;
        Exception last = null;
        int attempt = 0;
        while (running && System.currentTimeMillis() < deadline) {
            attempt++;
            try {
                return client.openStream(SOCKET);
            } catch (Exception e) {
                last = e;
                // 伺服器還在啟動時這裡一定失敗（socket 還沒建好），屬正常
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IOException("連不上 scrcpy 伺服器（試了 " + attempt + " 次）"
                + (last == null ? "" : "：" + last.getMessage()) + lastServerLog());
    }

    // ================================================================== 影像

    private void startVideoReader(InputStream in) {
        videoThread = new Thread(() -> {
            byte[] hdr = new byte[SESSION_META_LEN];
            try {
                while (running) {
                    readFully(in, hdr, SESSION_META_LEN);
                    int first = readIntBE(hdr, 0);

                    // session meta 與封包標頭共用 12 bytes，靠 bit63 分辨：
                    // 1 ⇒ flags + width + height（旋轉／resize 時會再插一筆）
                    if ((first & 0x80000000) != 0) {
                        int w = readIntBE(hdr, 4);
                        int h = readIntBE(hdr, 8);
                        if (w > 0 && h > 0) {
                            boolean changed = !sessionSizeKnown || w != videoWidth || h != videoHeight;
                            sessionSizeKnown = true;
                            videoWidth = w;
                            videoHeight = h;
                            if (changed) {
                                DiagLog.i(TAG, "影像尺寸 → " + w + "x" + h);
                                if (listener != null) listener.onVideoSize(w, h);
                            }
                        }
                        continue;
                    }

                    long ptsAndFlags = readLongBE(hdr, 0);
                    int size = readIntBE(hdr, 8);
                    boolean config = (ptsAndFlags & FLAG_CONFIG) != 0;
                    boolean key = (ptsAndFlags & FLAG_KEY_FRAME) != 0;
                    long pts = ptsAndFlags & ~(FLAG_SESSION | FLAG_CONFIG | FLAG_KEY_FRAME);

                    if (size < 0 || size > 8 * 1024 * 1024) {
                        throw new IOException("封包大小不合理：" + size);
                    }
                    byte[] payload = new byte[size];
                    readFully(in, payload, size);
                    // 錄影只是「轉手」：入列就回來，不在這條執行緒上碰磁碟（見 Recorder）
                    Recorder rec = recorder;
                    if (rec != null) {
                        rec.onPacket(payload, pts, config, key, videoWidth, videoHeight);
                    }
                    decoder.submit(VideoDecoder.Packet.frame(payload, pts, config));
                }
            } catch (Exception e) {
                if (running) {
                    DiagLog.w(TAG, "影像串流結束：" + e.getMessage());
                    if (listener != null) listener.onEnded();
                }
            }
        }, "omniadb-video-reader");
        videoThread.setDaemon(true);
        videoThread.start();
    }

    // ================================================================== 音訊

    /**
     * 重開音訊 session 前，等舊的 {@code app_process} 收掉的時間。
     *
     * <p>實測 800 ms 夠（舊伺服器在 shell 串流關閉後約 100~200 ms 退出，
     * 加上 AudioPolicy 註銷與路由重算）。見 {@link #applyHostAudible(boolean)}。
     */
    private static final long AUDIO_RESTART_GAP_MS = 800;

    /**
     * 音訊那條 session 的管理執行緒。
     *
     * <p>切換「對方靜音／有聲」＝ 換一個 {@code audio_dup} 重開 session（約 1~1.5 秒），
     * 這件事<b>不能</b>在 UI 執行緒做。用佇列而不是「按一次開一條執行緒」，是因為
     * 連按兩下會同時跑兩輪、兩條 session 搶同一個 abstract socket。
     */
    private void startAudioWorker() {
        if (audioWorker != null) return;   // 只准一條；之後用 setAudioOn() 補開
        audioWorker = new Thread(() -> {
            while (running) {
                Boolean want;
                try {
                    want = audioRequests.take();
                } catch (InterruptedException e) {
                    break;
                }
                // 只做最後一個請求：連按四下就只重開一次
                Boolean newer;
                while ((newer = audioRequests.poll()) != null) want = newer;
                if (!running) break;
                if (!audioOn) continue;    // 這期間被關掉了（佇列裡還有殘留請求）
                applyHostAudible(want);
            }
        }, "omniadb-audio-switch");
        audioWorker.setDaemon(true);
        audioWorker.start();
    }

    /**
     * 依 {@code audio_dup} 重建音訊 session。
     *
     * <p>已經是這個狀態就不動 —— 否則每次回到前景都白白斷 1 秒聲音。
     */
    private void applyHostAudible(boolean want) {
        hostAudible = want;
        AudioSession old = audio;
        if (old != null && Boolean.valueOf(want).equals(audioApplied)) {
            // 狀態沒變（例如失敗後又按回原值），保留現有 session
            return;
        }

        // ★ 換 audio_dup 等於把對方的音訊路由搬一次（SPEAKER ↔ REMOTE_SUBMIX）。
        // 從 REMOTE_SUBMIX 搬回 SPEAKER 這一步，系統會發 ACTION_AUDIO_BECOMING_NOISY
        // —— ExoPlayer 系的 App（YouTube、B 站……）收到就暫停，而且不會自己恢復。
        // 實測：YouTube 在 dup=false 時是 PLAYING，切成 dup=true 後變 PAUSED 且卡住。
        // 所以重建完一律補送一次 play（無條件，不先探測）。

        audio = null;
        if (old != null) {
            old.close();
            // 等舊的 app_process 真的收掉再開新的。
            //
            // ★ 這段等待不是保險，是必要的：實測（POCO / Android 15）如果緊接著重開，
            // 新的 AudioPolicy 會抓不到聲音 —— 對方的播放器被留在喇叭上並標成
            // mutedState:streamVolume，我方收到的全是零（RMS 557 → 0，且不會自己恢復）。
            // 舊伺服器沒死透時它的 mix 還掛著，新舊兩個 policy 打架。
            try {
                Thread.sleep(AUDIO_RESTART_GAP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 共用主 session 的 ADB 連線：每開一條新連線，對方的無線偵錯就會再跳一次
        // 「無線偵錯已連線」通知，切一次聲音跳一次很煩。
        AudioSession s = new AudioSession(client, want);
        // 錄影要同一份 PCM：在 start() 之前掛上去（讀取執行緒是 start() 裡起來的）。
        // 沒在錄影時 onPcm 直接 return，成本等於一次 boolean 判斷。
        s.setPcmTap(recorder);
        boolean active = false;
        try {
            s.start();
            if (s.isActive()) {
                // ★ 建立期間（1~1.5 秒）使用者可能已經選了「不要聲音」。
                // 這時 audio 早就被 setAudioOn(false) 清成 null，這條剛起來的
                // session 會變成沒人管得到的一條 —— 它的 audio_dup 還鎖著對方喇叭，
                // 我方卻以為「沒有音訊」。必須當場收掉。
                if (!audioOn || !running) {
                    s.close();
                    audioApplied = null;
                    DiagLog.i(TAG, "音訊 session 建立期間已被關閉，直接收掉");
                    if (recorder != null) recorder.setAudioOn(false);
                    if (listener != null) listener.onAudioState(false, true);
                    return;
                }
                audio = s;
                active = true;
            } else {
                // 這台給不了音訊（Android 12 以下、或擷取被拒絕）——
                // 伺服器留在對方機器上也沒用，直接收掉
                s.close();
            }
            audioApplied = want;
        } catch (Exception e) {
            DiagLog.w(TAG, "音訊 session 建立失敗（audio_dup=" + want + "）", e);
            s.close();
            // 失敗就清掉，下次切換會重試
            audioApplied = null;
        }
        // 錄影要據此決定「這一段要不要帶音軌」。沒音訊時只錄影像（跟 scrcpy --record 一樣）。
        if (recorder != null) recorder.setAudioOn(active);
        // 收尾：把被路由切換打斷的播放接回去（無條件）
        resumeHostPlayback();

        if (listener != null) listener.onAudioState(active, want);
    }

    /** 等路由穩定下來再補 play（太早送會被還沒到的 becoming-noisy 蓋過去）。 */
    private static final long RESUME_PROBE_DELAY_MS = 400;

    /**
     * 補送一次播放鍵。
     *
     * <p><b>為什麼要無條件送：</b>切換「對方有聲／靜音」一定要重開音訊 session
     * （{@code audio_dup} 只能在啟動時決定），而重開會把對方的音訊路由
     * 從 REMOTE_SUBMIX 搬回 SPEAKER。系統把這視為「輸出裝置變了」，發出
     * {@code ACTION_AUDIO_BECOMING_NOISY}（耳機拔掉時的那個廣播）；ExoPlayer 系的
     * App 依規範收到就 {@code pause()}，而且<b>不會自己恢復</b> —— 使用者看到的就是
     * 「一開聲音，影片就停了」。
     *
     * <p>原本的做法是「先探測有沒有人在播、只有被打斷才補」，但那要多跑兩次
     * {@code dumpsys media_session}（每次切換多花 200~400 ms），而且探測與補送之間
     * 還有時間差。現在直接送：{@code dispatch play} 只對「有 media session 的 App」
     * 生效，沒有人在播時是 no-op，不會亂開 App。
     *
     * <p>代價：如果使用者是<b>刻意暫停</b>之後才切換聲音，會被這一下叫回播放。
     */
    private void resumeHostPlayback() {
        AdbClient c = client;
        if (c == null) return;
        try {
            Thread.sleep(RESUME_PROBE_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            c.shell("cmd media_session dispatch play; echo @@OMNIADB:RESUME",
                    "@@OMNIADB:RESUME", 2_500);
            DiagLog.i(TAG, "已補送 play（無條件）");
        } catch (Exception e) {
            DiagLog.w(TAG, "補送 play 失敗", e);
        }
    }

    /** 音訊有沒有真的在播（對方 < Android 13、或擷取被拒絕時是 false）。 */
    boolean isAudioActive() {
        AudioSession a = audio;
        return a != null && a.isActive();
    }

    /**
     * 設定「對方的喇叭要不要出聲」。
     *
     * <p>true ＝ 兩邊都聽得到（{@code audio_dup=true}）；
     * false ＝ 只有我這邊聽得到（{@code audio_dup=false}）。
     * <b>不管哪一種，本機都收得到聲音</b> —— 這正是不能改用「調對方音量」的原因：
     * 播放擷取是音量之後的訊號，對方調 0 我們這邊也會變成全零（實測）。
     *
     * <p>不會阻塞，實際重開在背景執行緒做。
     */
    void setHostAudible(boolean audible) {
        hostAudible = audible;
        if (!audioOn) return;   // 沒有音訊 session，切 dup 沒有意義（見 setAudioOn）
        audioRequests.offer(audible);
    }

    /** 這條連線現在有沒有音訊（false ＝ 使用者選了「不要聲音」）。 */
    boolean isAudioOn() {
        return audioOn;
    }

    /**
     * 開／關音訊那條 session（<b>不會</b>動到畫面）。
     *
     * <p>音訊本來就是獨立的一條 session，所以「接回聲音」不需要重連影像 ——
     * 這正是當初把它拆出去的理由。關掉時會把現有 session 收乾淨，
     * 對方的音訊路由自動回到原本的輸出（喇叭），我們不再介入。
     *
     * @param on           要不要音訊
     * @param hostAudible  {@code on=true} 時，對方喇叭要不要同時出聲
     */
    void setAudioOn(boolean on, boolean hostAudible) {
        this.hostAudible = hostAudible;
        if (on) {
            if (audioOn && audio != null) {
                // 已經有 session：只切 dup
                audioRequests.offer(hostAudible);
                return;
            }
            audioOn = true;
            startAudioWorker();
            audioRequests.offer(hostAudible);
            DiagLog.i(TAG, "音訊接回（audio_dup=" + hostAudible + "）");
            return;
        }

        audioOn = false;
        AudioSession old = audio;
        audio = null;
        audioApplied = null;
        if (old != null) {
            // close() 會等 AudioTrack 收尾，不能在 UI 執行緒做
            Thread t = new Thread(() -> {
                old.close();
                // ★ 收掉 session 也是一次路由切換（REMOTE_SUBMIX → SPEAKER），
                // 一樣會發 ACTION_AUDIO_BECOMING_NOISY。不補 play 的話，對方正在播的
                // 影片會停在暫停 —— 使用者看到的就是「一選不要聲音，對方的聲音也沒了」，
                // 而且畫面那顆按鈕還救不回來（見 MirrorActivity#toggleSound）。
                resumeHostPlayback();
            }, "omniadb-audio-off");
            t.setDaemon(true);
            t.start();
        }
        DiagLog.i(TAG, "音訊關閉 —— 對方喇叭回到原本的輸出");
        if (listener != null) listener.onAudioState(false, true);
    }

    // ================================================================== 日誌

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
                DiagLog.d(TAG, "伺服器日誌串流結束：" + e.getMessage());
            }
        }, "omniadb-server-log");
        logThread.setDaemon(true);
        logThread.start();
    }

    private void recordLog(String line) {
        DiagLog.i(TAG, "server| " + line);
        synchronized (serverLog) {
            serverLog.addLast(line);
            while (serverLog.size() > 8) serverLog.removeFirst();
        }
    }

    private String lastServerLog() {
        synchronized (serverLog) {
            if (serverLog.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("\n");
            for (String s : serverLog) sb.append(s).append('\n');
            return sb.toString().trim();
        }
    }

    // ================================================================== 控制

    /** 目前的影像尺寸 —— 觸控座標的參考座標系。 */
    int videoWidth() {
        return videoWidth;
    }

    int videoHeight() {
        return videoHeight;
    }

    /**
     * 把（新的）Surface 掛到解碼器上。
     *
     * <p>Activity 被切到背景再回來時，SurfaceView 會拿到<b>另一個</b> Surface，
     * 舊的那個已經被系統銷毀。解碼器握著死 Surface 會讓 MediaCodec 直接
     * {@code NO_INIT} 永久失效，所以一定要重新掛。
     */
    void attachSurface(Surface surface) {
        if (decoder != null) decoder.attachSurface(surface);
    }

    /** Surface 被系統銷毀 —— 先放掉解碼器，等下一次 attach。 */
    void detachSurface() {
        if (decoder != null) decoder.detachSurface();
    }

    String deviceName() {
        return deviceName;
    }

    boolean isRunning() {
        return running;
    }

    /**
     * 送一個觸控事件。
     *
     * <p>座標必須是<b>影像座標系</b>（0..videoWidth / 0..videoHeight），
     * 而且 {@code screenWidth/screenHeight} 要跟著送 —— 伺服器會拿它跟自己
     * 目前的畫面尺寸比對，<b>不一致就整筆丟掉</b>（4.1 的日誌是
     * "Ignore positional event generated for size … (current size is …)"，
     * 而且是 VERBOSE 等級，預設看不到 —— 只會表現成「觸控沒反應」）。
     *
     * <p>尺寸的來源是 <b>session meta</b>（{@link #startVideoReader(InputStream)}），
     * 不是 SPS：伺服器自己就是拿 session meta 的尺寸在比對。實測送錯 100 px
     * 立刻被忽略、送對就完全正常。
     *
     * @param action       {@code MotionEvent.ACTION_*}
     * @param actionButton {@code MotionEvent.BUTTON_*}，只有 DOWN 需要給
     * @param buttons      目前按著的按鍵集合
     */
    void sendTouch(int action, long pointerId, int x, int y,
                   float pressure, int actionButton, int buttons) {
        byte[] msg = new byte[32];
        int p = 0;
        msg[p++] = 2;                                   // TYPE_INJECT_TOUCH_EVENT
        msg[p++] = (byte) action;
        p = putLong(msg, p, pointerId);
        p = putInt(msg, p, x);
        p = putInt(msg, p, y);
        p = putShort(msg, p, videoWidth);
        p = putShort(msg, p, videoHeight);
        p = putShort(msg, p, (int) (pressure * 0xFFFF));  // u16 定點數
        p = putInt(msg, p, actionButton);
        putInt(msg, p, buttons);
        write(msg);
    }

    /** 按鍵（送 down + up 兩筆）。 */
    void sendKey(int keycode) {
        writeKeyEvent(0, keycode);   // ACTION_DOWN
        writeKeyEvent(1, keycode);   // ACTION_UP
    }

    /**
     * 按鍵，但帶著修飾鍵（{@code KeyEvent.META_CTRL_ON} 之類）。
     *
     * <p>Ctrl+V 這種組合鍵一定要兩個都送：只送 keycode 的話對方收到的是「v」，
     * 修飾鍵狀態是<b>每筆事件</b>各自帶的，不是「按住」的概念。
     */
    void sendKeyWithMeta(int keycode, int metaState) {
        writeKeyEvent(0, keycode, 0, metaState);
        writeKeyEvent(1, keycode, 0, metaState);
    }

    private void writeKeyEvent(int action, int keycode) {
        writeKeyEvent(action, keycode, 0, 0);
    }

    /**
     * 送一筆按鍵事件（可帶 repeat 與 metaState）。
     *
     * <p>格式（4.1／2.7 相同）：{@code type(1) + action(1) + keycode(4) + repeat(4)
     * + metaState(4)}，共 14 bytes。{@code keycode} 用的是 <b>Android 的 KeyEvent 常數</b>
     * —— 剛好跟 Client 自己（也是 Android）拿到的值一模一樣，不需要任何對照表。
     */
    private void writeKeyEvent(int action, int keycode, int repeat, int metaState) {
        byte[] msg = new byte[14];
        int p = 0;
        msg[p++] = 0;                       // TYPE_INJECT_KEYCODE
        msg[p++] = (byte) action;
        p = putInt(msg, p, keycode);
        p = putInt(msg, p, repeat);
        putInt(msg, p, metaState);
        write(msg);
    }

    /**
     * 實體鍵盤直通：把 Client 這邊的按鍵原樣送到對方。
     *
     * <p>為什麼需要：對方<b>沒有輸入法</b>（車機、TV、精簡 ROM）時，點螢幕上的輸入框
     * 什麼都不會發生，唯一的辦法就是從這邊打字。就算對方有輸入法，用遠端點虛擬鍵盤
     * 一個字一次往返也很痛苦。
     *
     * <p>{@code metaState} 要跟著送（Shift、Ctrl、Alt）—— 少了它大寫和符號會不對。
     */
    void sendHardwareKey(int action, int keycode, int repeat, int metaState) {
        writeKeyEvent(action, keycode, repeat, metaState);
    }

    /**
     * 送一段文字（{@code TYPE_INJECT_TEXT}）。
     *
     * <p>伺服器是逐字用 {@code KeyCharacterMap} 轉成按鍵事件再注入，所以：
     * <ul>
     *   <li>中文／emoji 送不進去 —— 那是輸入法的責任，不是按鍵。</li>
     *   <li>太長會很慢（每個字都要注入一次），所以切成小段送，讓畫面跟得上。</li>
     * </ul>
     * 這也是為什麼「實體鍵盤直通」與「送文字」兩條都要有：一個處理鍵盤、一個處理貼上。
     */
    void sendText(String text) {
        if (text == null || text.isEmpty()) return;
        final int chunk = 200;   // 伺服器端的硬上限是 300 字，留一點餘裕
        for (int i = 0; i < text.length(); i += chunk) {
            String part = text.substring(i, Math.min(text.length(), i + chunk));
            byte[] raw = part.getBytes(StandardCharsets.UTF_8);
            byte[] msg = new byte[5 + raw.length];
            msg[0] = 1;                       // TYPE_INJECT_TEXT
            putInt(msg, 1, raw.length);
            System.arraycopy(raw, 0, msg, 5, raw.length);
            write(msg);
        }
    }

    /**
     * 「返回」或「喚醒」。
     *
     * <p>比直接送 KEYCODE_BACK 好：螢幕熄著的時候會先把螢幕點亮，
     * 不會把 BACK 送給鎖屏介面。
     */
    void sendBackOrScreenOn(int action) {
        byte[] msg = new byte[2];
        msg[0] = 4;                 // TYPE_BACK_OR_SCREEN_ON
        msg[1] = (byte) action;
        write(msg);
    }

    /** 旋轉對方螢幕（等同開啟自動旋轉後轉 90°）。 */
    void rotateDevice() {
        write(new byte[]{11});      // TYPE_ROTATE_DEVICE
    }

    /**
     * 用 shell 把對方螢幕<b>鎖</b>到指定方向：0＝豎、1＝橫、2＝倒豎、3＝倒橫。
     *
     * <p><b>為什麼不用 {@link #rotateDevice()}</b>（實測 2026-10-25，POCO / Android 15）：
     * scrcpy 的 {@code Device.rotateDevice()} 是「freezeRotation 之後，如果自動旋轉
     * 是開的就立刻 thawRotation」。thaw 一執行就把方向還原成感測器方向 ——
     * 症狀是「指令送了、log 也印了 Device rotation requested，畫面紋絲不動」。
     * 而且 scrcpy 2.4 更慘：它只試 {@code freezeRotation(int)}，Android 14 起這個
     * 方法改名成 {@code freezeDisplayRotation(int, int)}，直接
     * {@code NoSuchMethodException}。2.7 修了簽名，但 freeze→thaw 的問題還在。
     *
     * <p>{@code wm user-rotation lock N} 才是乾淨的「鎖到某個方向」，實測 0/1/2/3
     * 都生效（1/3 仍會被前台 App 的 {@code screenOrientation} 擋掉 —— 那是 Android
     * 的正常行為，不是我們的問題）。
     *
     * <p><b>會阻塞</b>（開一條 shell 串流），必須在背景執行緒呼叫。
     *
     * @return 指令有沒有送達（不代表對方真的轉了 —— 前台 App 鎖方向時不會轉）
     */
    boolean setHostRotation(int rotation) {
        AdbClient c = client;
        if (c == null) return false;
        if (!lockRotation(c, rotation)) return false;
        rotationOwned = true;
        DiagLog.i(TAG, "已要求對方鎖定方向 " + rotation);
        return true;
    }

    /**
     * 送 {@code wm user-rotation lock N}。<b>會阻塞</b>，要在背景執行緒呼叫。
     *
     * <p>抽成靜態方法，是因為它有兩個呼叫點，用的連線不一樣：開場前用推 jar 那條
     * （{@link #start(Surface, int)}），連線中用它自己的 {@link #client}
     * （{@link #setHostRotation(int)}）。指令本身與逾時必須完全一致，
     * 否則會出現「開場鎖得住、後來鎖不住」這種鬼故事。
     */
    private static boolean lockRotation(AdbClient c, int rotation) {
        try {
            // 一定要帶結束記號：libadb 的 read() 在對端 CLSE 之後可能永遠不返回
            c.shell("wm user-rotation lock " + rotation + "; echo @@OMNIADB:ROT",
                    "@@OMNIADB:ROT", 3_000);
            return true;
        } catch (Exception e) {
            DiagLog.w(TAG, "設定對方方向失敗（" + rotation + "）", e);
            return false;
        }
    }

    /**
     * 把對方的自動旋轉還原。
     *
     * <p>只在自己改過（{@link #rotationOwned}）時才動手 —— 使用者原本若鎖著旋轉，
     * 我們不該在斷線時擅自幫他放開。判斷不出原始值時，{@code free}（跟隨感測器）
     * 是手機的預設狀態，也是代價最小的選擇。
     */
    private void releaseHostRotation() {
        if (!rotationOwned) return;
        rotationOwned = false;
        AdbClient c = client;
        if (c == null) return;
        try {
            c.shell("wm user-rotation free; echo @@OMNIADB:FREE",
                    "@@OMNIADB:FREE", 2_000);
            DiagLog.i(TAG, "已把對方的自動旋轉還原");
        } catch (Exception e) {
            DiagLog.w(TAG, "還原對方自動旋轉失敗", e);
        }
    }

    /** 展開通知欄。 */
    void expandNotificationPanel() {
        write(new byte[]{5});       // TYPE_EXPAND_NOTIFICATION_PANEL
    }

    /**
     * 請對方立刻重吐一個關鍵幀（見 {@link #CONTROL_TYPE_RESET_VIDEO}）。
     *
     * <p>非阻塞：只是把 1 個 byte 丟進控制佇列。連線還沒好時靜默失敗 ——
     * 錄影那邊本來就會一直等到關鍵幀為止，少這一次重置頂多慢一點。
     */
    void requestVideoReset() {
        write(new byte[]{CONTROL_TYPE_RESET_VIDEO});
    }

    /**
     * 螢幕電源：{@code on=false} 關閉、{@code true} 打開。
     *
     * <p><b>4.1 把這個訊息從「mode 位元組」改成 boolean</b>
     * （{@code TYPE_SET_DISPLAY_POWER}，1 byte 的 0/1）。2.7 的
     * {@code TYPE_SET_SCREEN_POWER_MODE} 是 0=off、2=normal，照舊送 2 在 4.1 上
     * 因為「非 0 即 true」剛好也能用，但那是巧合，不值得依賴。
     *
     * <p>關掉之後面板不亮，但 SurfaceFlinger 照樣合成，所以鏡像與觸控都正常。
     * <b>一定要在連線結束前開回來</b> —— 這件事由 {@link #close()} 負責，
     * 呼叫端不用自己記得（見 {@link #displayPowerOff}）。
     *
     * @return 訊息是否真的進得了佇列；連線還沒好時是 {@code false}，
     *         呼叫端<b>不該</b>把 UI 狀態切換過去。
     */
    boolean setScreenPowerMode(boolean on) {
        boolean queued = write(new byte[]{10, (byte) (on ? 1 : 0)});
        if (queued) displayPowerOff = !on;
        return queued;
    }

    /**
     * 對方顯示器現在是不是被我們關掉的。
     *
     * <p>選單那個「關閉螢幕省電」的勾要跟它對齊 —— 勾＝面板關、不勾＝面板亮。
     * 注意這只是<b>我們的認知</b>（這次連線送過什麼）；App 若被強制停止，
     * 對方會留在關閉狀態而這裡是 false。所以送出的一律是<b>絕對值</b>
     * （見 {@link #setScreenPowerMode}），取消勾選一定送「開」，不會愈按愈暗。
     */
    boolean isDisplayPowerOff() {
        return displayPowerOff;
    }

    private boolean write(byte[] data) {
        if (!running || controlOut == null) return false;
        // 佇列滿了（網路卡住）就丟掉這一筆，不要讓 UI 執行緒跟著卡住。
        // 觸控是「最新狀態才有意義」的資料，丟舊的比塞爆好。
        if (!controlQueue.offer(data)) {
            DiagLog.w(TAG, "控制佇列已滿，丟棄一筆 " + data.length + " bytes 的訊息");
            return false;
        }
        return true;
    }

    /** 專屬的控制寫入執行緒。{@code take()} 阻塞等訊息，關閉時靠 interrupt 收工。 */
    private void startControlWriter() {
        controlWriter = new Thread(() -> {
            while (running) {
                byte[] msg;
                try {
                    msg = controlQueue.take();
                } catch (InterruptedException e) {
                    break;
                }
                OutputStream out = controlOut;
                if (out == null) break;
                try {
                    out.write(msg);
                    out.flush();
                } catch (IOException e) {
                    if (running) DiagLog.w(TAG, "控制訊息寫入失敗：" + e.getMessage());
                    break;
                }
            }
        }, "omniadb-control-writer");
        controlWriter.setDaemon(true);
        controlWriter.start();
    }

    // ================================================================== 剪貼簿

    /**
     * 開始雙向剪貼簿同步。<b>連線就緒之後</b>才能呼叫（控制通道要活著、伺服器要開始收訊息）。
     *
     * <p>一開始先抓一次、之後每 {@link #CLIPBOARD_POLL_MS} 抓一次。為什麼是「抓」而不是
     * 等對方「推」，見 {@link #CLIPBOARD_POLL_MS} 的說明 —— 一句話：
     * <b>只有主動讀，才拿得到連線前就複製好的內容。</b>
     */
    void startClipboardSync() {
        if (!running || controlOut == null) return;
        requestHostClipboard();
        clipboardPoller = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(CLIPBOARD_POLL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                if (!running) break;
                requestHostClipboard();
            }
        }, "omniadb-clipboard-poll");
        clipboardPoller.setDaemon(true);
        clipboardPoller.start();
    }

    /** 請對方回報目前的剪貼簿內容；有的話會走 {@link Listener#onHostClipboard}。 */
    private void requestHostClipboard() {
        // TYPE_GET_CLIPBOARD(8) + copyKey(0)。copyKey=0 很重要：非 0 會順便
        // 對對方 Injection 一個 COPY/CUT 鍵，等於在使用者的 App 裡亂按鍵。
        write(new byte[]{8, 0});
    }

    /**
     * 把本機剪貼簿的內容送給對方。<b>會阻塞</b>到對方 ACK（最多
     * {@link #CLIPBOARD_ACK_TIMEOUT_MS}），請在背景執行緒呼叫。
     *
     * <p>{@code paste=false}：只設定對方的剪貼簿，<b>不</b>幫使用者按「貼上」——
     * 要貼到哪個輸入框是使用者的決定，我們只負責讓他有東西可以貼。
     *
     * @return 對方有沒有 ACK（true ＝ 它真的收下並設定了）
     */
    boolean sendClipboard(String text) {
        return sendClipboard(text, false);
    }

    /**
     * 同上，但可以順便請對方<b>貼上</b>。
     *
     * <p><b>為什麼需要 {@code paste=true}：</b>中文／emoji 沒辦法走
     * {@link #sendText(String)}（那是逐字轉按鍵，輸入法才做得到組字）。
     * 唯一可靠的路是把文字放進對方剪貼簿，再請對方「貼到目前有焦點的輸入框」——
     * 這也是電腦版 scrcpy 在打非 ASCII 時做的事。
     *
     * <p>伺服器收到 {@code paste=1} 會自己按貼上（4.x 用 {@code KEYCODE_PASTE}，
     * Android 11+ 才有；更舊的系統由伺服器退回 Ctrl+V）。所以呼叫端不需要
     * 自己補按鍵 —— 補了反而會貼兩次。
     *
     * <p><b>會阻塞</b>（等 ACK），只能在背景執行緒呼叫。
     */
    boolean sendClipboard(String text, boolean paste) {
        if (text == null) return false;

        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        int len = truncateUtf8(raw);
        long seq = clipboardSeq.incrementAndGet();

        byte[] msg = new byte[1 + 8 + 1 + 4 + len];
        int p = 0;
        msg[p++] = 9;                                   // TYPE_SET_CLIPBOARD
        p = putLong(msg, p, seq);
        msg[p++] = (byte) (paste ? 1 : 0);              // paste
        p = putInt(msg, p, len);
        System.arraycopy(raw, 0, msg, p, len);

        // 先記下來：對方之後把同一筆讀回來時，會被當成「回音」丟掉。
        // 記的是「實際送出去的那一份」——超過上限被截斷時，對方手上的內容是截斷版。
        lastClipboardText = new String(raw, 0, len, StandardCharsets.UTF_8);
        if (!write(msg)) return false;

        synchronized (ackLock) {
            long deadline = android.os.SystemClock.uptimeMillis() + CLIPBOARD_ACK_TIMEOUT_MS;
            while (running && clipboardAcked < seq) {
                long wait = deadline - android.os.SystemClock.uptimeMillis();
                if (wait <= 0) {
                    DiagLog.w(TAG, "剪貼簿：等不到對方 ACK（seq=" + seq + "）");
                    return false;
                }
                try {
                    ackLock.wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return clipboardAcked >= seq;
    }

    /**
     * 控制通道的讀取執行緒。
     *
     * <p>以前這條 socket <b>只寫不讀</b>（觸控是單向的），現在剪貼簿的回報從同一條
     * 回來，所以一定要有人把它讀掉 —— 除了我們要的資料，{@code AdbStream} 的讀取
     * 佇列如果沒人消費也會一直長大。
     *
     * <p>訊息格式（scrcpy 2.7，{@code DeviceMessageWriter}）：
     * <pre>
     *   TYPE_CLIPBOARD(0)      + 4 bytes 長度 + UTF-8 內容
     *   TYPE_ACK_CLIPBOARD(1)  + 8 bytes sequence
     *   TYPE_UHID_OUTPUT(2)    + 2 bytes id + 2 bytes 長度 + 內容（我們沒用，跳過）
     * </pre>
     *
     * <p>結束方式：{@link #close()} 關掉 {@code AdbStream} 時，libadb 會喚醒阻塞在
     * {@code read()} 的執行緒並丟 {@code IOException}（注意：關 {@code InputStream}
     * 本身沒用，它的 {@code close()} 是空實作）。
     */
    private void startControlReader() {
        controlReader = new Thread(() -> {
            DataInputStream in = new DataInputStream(new BufferedInputStream(controlIn));
            try {
                while (running) {
                    int type = in.readUnsignedByte();
                    switch (type) {
                        case DEVICE_MSG_CLIPBOARD: {
                            int len = in.readInt();
                            if (len < 0 || len > MAX_CLIPBOARD_BYTES) {
                                throw new IOException("剪貼簿封包長度不合理：" + len);
                            }
                            byte[] buf = new byte[len];
                            in.readFully(buf);
                            String text = new String(buf, StandardCharsets.UTF_8);
                            // 沒變（每次輪詢都會讀到同一筆），或是我們自己剛送過去的回音
                            if (text.equals(lastClipboardText)) continue;
                            lastClipboardText = text;
                            if (listener != null) listener.onHostClipboard(text);
                            break;
                        }
                        case DEVICE_MSG_ACK_CLIPBOARD: {
                            long seq = in.readLong();
                            synchronized (ackLock) {
                                if (seq > clipboardAcked) clipboardAcked = seq;
                                ackLock.notifyAll();
                            }
                            break;
                        }
                        case DEVICE_MSG_UHID_OUTPUT: {
                            in.readUnsignedShort();                     // id
                            int len = in.readUnsignedShort();
                            byte[] skip = new byte[len];
                            in.readFully(skip);
                            break;
                        }
                        default:
                            throw new IOException("未知的裝置訊息型別：" + type);
                    }
                }
            } catch (Exception e) {
                if (running) DiagLog.w(TAG, "控制通道讀取結束：" + e.getMessage());
            }
        }, "omniadb-control-reader");
        controlReader.setDaemon(true);
        controlReader.start();
    }

    /**
     * 把長度截到 UTF-8 字元邊界。
     *
     * <p>直接 {@code raw[MAX]} 切下去可能切在中文字或 emoji 的中間，對方解出來的
     * 最後一個字會是問號。所以往回退到「不是續位元組（10xxxxxx）」的位置。
     */
    private static int truncateUtf8(byte[] raw) {
        if (raw.length <= MAX_CLIPBOARD_BYTES) return raw.length;
        int end = MAX_CLIPBOARD_BYTES;
        while (end > 0 && (raw[end] & 0xC0) == 0x80) end--;
        return end;
    }

    // ================================================================== 收尾

    @Override
    public void close() {
        if (!running && client == null) return;
        running = false;

        // 先收音訊：它有自己的連線與 app_process，不關就會留在對方機器上跑
        // （AudioTrack 一釋放，阻塞在 write() 的音訊執行緒也會跟著退出）
        audioRequests.clear();
        if (audioWorker != null) {
            audioWorker.interrupt();
            audioWorker = null;
        }
        AudioSession a = audio;
        audio = null;
        if (a != null) a.close();

        // 先關控制 —— 伺服器的 Controller 執行緒讀到 EOF 就會收工，
        // 它自己結束才不會留下孤兒進程。
        if (clipboardPoller != null) {
            clipboardPoller.interrupt();
            clipboardPoller = null;
        }
        if (controlWriter != null) {
            controlWriter.interrupt();
            try {
                // 給它一點時間把手上那筆寫完，免得等一下的「開回螢幕」和它交錯成半個訊息
                controlWriter.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            controlWriter = null;
        }
        restoreDisplayPower();
        controlQueue.clear();
        // 關 stream（不是關 InputStream）才會讓阻塞在 read() 的讀取執行緒醒過來
        closeQuietly(controlStream);
        controlStream = null;
        controlIn = null;
        controlOut = null;
        // 叫醒還在等 ACK 的執行緒，不然它要卡到逾時才回來
        synchronized (ackLock) {
            ackLock.notifyAll();
        }

        closeQuietly(videoStream);
        videoStream = null;

        if (decoder != null) {
            decoder.stop();
            decoder = null;
        }

        // 關 shell 串流 = adbd 對子進程發 SIGHUP，scrcpy-server 正式收掉
        // 要在關 shell 串流之前還原旋轉 —— 那條指令還需要 client 活著
        releaseHostRotation();
        closeQuietly(shellStream);
        shellStream = null;

        if (client != null) {
            client.close();
            client = null;
        }
        DiagLog.i(TAG, "鏡像連線已關閉");
    }

    /**
     * 收工前把對方螢幕開回來。
     *
     * <p><b>為什麼直接寫串流、不走控制佇列</b>：呼叫時佇列馬上就要被清掉，
     * 寫入執行緒也已經被 interrupt，排進去的訊息永遠送不出去 —— 對方就會留在
     * 黑屏（面板不亮、按鍵有反應，使用者只覺得「手機壞了」）。
     */
    private void restoreDisplayPower() {
        if (!displayPowerOff) return;
        displayPowerOff = false;
        OutputStream out = controlOut;
        if (out == null) return;
        try {
            out.write(new byte[]{10, 1});   // TYPE_SET_DISPLAY_POWER = true
            out.flush();
            DiagLog.i(TAG, "已把對方螢幕開回來");
        } catch (IOException e) {
            DiagLog.w(TAG, "開回對方螢幕失敗：" + e.getMessage());
        }
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

    private static int putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >> 24);
        b[off + 1] = (byte) (v >> 16);
        b[off + 2] = (byte) (v >> 8);
        b[off + 3] = (byte) v;
        return off + 4;
    }

    private static int putShort(byte[] b, int off, int v) {
        b[off] = (byte) (v >> 8);
        b[off + 1] = (byte) v;
        return off + 2;
    }

    private static int putLong(byte[] b, int off, long v) {
        for (int i = 7; i >= 0; i--) {
            b[off + i] = (byte) v;
            v >>= 8;
        }
        return off + 8;
    }
}
