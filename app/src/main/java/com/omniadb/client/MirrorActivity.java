package com.omniadb.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 鏡像畫面 —— 把對方手機的螢幕顯示出來，並把觸控送回去。
 *
 * <p>這個 Activity 只做三件事，其餘都在 {@link ScrcpySession} 裡：
 *
 * <ul>
 *   <li>提供一個<b>等比例</b>的 Surface 給解碼器畫（見 {@link AspectSurfaceView}）</li>
 *   <li>把螢幕座標換算成影像座標，轉發觸控</li>
 *   <li>一條會自己收起來的工具列（返回 / 主頁 / 多工 / 有聲 / 鍵盤 / 設置 / 離開）</li>
 * </ul>
 *
 * <p>工具列刻意只留每天都會按的幾顆：旋轉、跟隨、音量、電源這些「調一次就不動」
 * 的都收進「設置」選單（見 {@link #showMoreMenu(View)}），否則每顆按鈕都被擠成
 * 90px、字折成兩行，反而更難按。
 *
 * <p>刻意<b>不接管系統返回鍵</b>：那是使用者離開鏡像最直覺的方式。
 * 要按對方的返回鍵，用工具列上那顆「返回」。
 */
public class MirrorActivity extends Activity {

    private static final String TAG = "OmniADB-Client";

    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_LABEL = "label";

    /** 工具列自動收起的時間。擋在畫面上不動會讓人以為對方螢幕上緣壞了。 */
    private static final long BAR_AUTO_HIDE_MS = 5000;

    /** MotionEvent 的按鍵常數（不 import 整個 MotionEvent 的常數表，這裡只用得到這幾個）。 */
    private static final int BTN_PRIMARY = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private AspectSurfaceView surfaceView;
    private LinearLayout bar;
    private TextView status;
    private TextView handle;
    private Button soundButton;
    /** 工具列那顆「鍵盤」——要能改顏色表示目前開著。 */
    private Button keyboardButton;

    /**
     * 隱形的輸入框：<b>本機輸入法的接收端</b>。
     *
     * <p>這是「像本地一樣打字」的關鍵。本機的注音／拼音／Gboard 只會對
     * {@link android.view.inputmethod.InputConnection} 送出組字與 commit 事件，
     * 不會產生 KeyEvent —— 所以一定要有一個真的可編輯的 View 取得焦點，
     * 輸入法才會把字交給我們。它只有 1dp、完全透明，使用者看不到。
     *
     * <p>收到的字怎麼處理見 {@link #sendTextToRemote(String)}。
     */
    private EditText imeSink;
    private boolean keyboardOn;
    /** 組字結束後的「補送」排程（見 {@link #scheduleFlush(long)}）。 */
    private Runnable pendingFlush;

    /**
     * 本機這端「已經打出來、但還沒送到對方」的字。
     *
     * <p>不用 EditText 自己的 Editable 當緩衝 —— 實測 {@code BaseInputConnection.getEditable()}
     * 在我們的匿名子類裡回 null，{@code super.setComposingText()} 之類全部靜默失敗。
     * 自己維護反而乾淨，而且能精確處理「組字中被覆蓋」「組字後追加」兩種 commit 語意。
     */
    private final StringBuilder imeWord = new StringBuilder();
    /** 組字區在 {@link #imeWord} 裡的起點；-1 表示現在沒有在組字。 */
    private int imeCompStart = -1;
    /** 「點一下對方輸入框再打字」那句提示只講一次（每次啟動講一次就夠了）。 */
    private static boolean keyboardHintShown;

    /**
     * 打字相關的序：<b>單執行緒</b>。
     *
     * <p>貼上要走剪貼簿（{@code sendClipboard} 會阻塞等對方 ACK），不能佔 UI 執行緒；
     * 但用多執行緒池會讓「先打的字」跟「後打的字」有機會亂序 —— 一定要排隊。
     */
    private java.util.concurrent.ExecutorService inputBg;

    /**
     * 對方的喇叭要不要出聲（工具列那顆按鈕顯示的就是這個）。
     *
     * <p><b>注意方向</b>：這是「遠端」的開關，不是本機音量 —— 本機聽不聽得到請用自己的
     * 音量鍵。連線重建（轉向、回前景）後要沿用，不能自己跳回有聲。
     *
     * <p>真正的事實來源是 {@link Settings#audioMode}，這裡只是它的快取：
     * 重連時 {@link ScrcpySession} 直接讀 Settings，不靠這個欄位（見該處註解）。
     */
    private boolean hostAudible = true;

    private ScrcpySession session;
    private Thread starter;
    private String host;
    private int port;

    /** 剪貼簿雙向同步（政策在本類別之外，見 {@link ClipboardSync}）。 */
    private ClipboardSync clipboardSync;

    /** 把本機擺放方向同步給對方（規則見 {@link OrientationSync}）。 */
    private OrientationSync orientationSync;

    /**
     * 錄影（把碼流封裝成 MP4，見 {@link Recorder}）。
     *
     * <p>跟著 Activity 活一輩子，重連時只是「換一條 session 掛上去」——
     * 錄影本身不跨連線（連線斷了就收檔），但物件不必重建。
     */
    private Recorder recorder;

    /** 錄影中的紅點。有它使用者才知道還在錄（不然很容易錄了一個小時）。 */
    private TextView recordDot;

    private boolean surfaceReady;
    private boolean sessionStarting;
    /** 已經退到背景（onStop）。啟動流程跑完時要據此決定「這個 session 還要不要」。 */
    private volatile boolean stopped;

    /**
     * Surface 被銷毀後，等這麼久還沒回來才真的放掉解碼器。
     *
     * <p>轉向、下拉狀態列、被別的視窗短暫遮住，都會讓 Surface 銷毀又立刻重建。
     * 一收到銷毀就 {@code releaseCodec()}，等於白白黑掉 100ms 再重建一次 ——
     * 翻屏時看到「閃一下」就是這個。
     */
    private static final long SURFACE_DETACH_GRACE_MS = 500;

    private final Runnable detachTask = () -> {
        ScrcpySession s = session;
        // Surface 已經回來、或 session 已經換掉／關掉，就不必動了
        if (s != null && !surfaceReady) s.detachSurface();
    };

    // ================================================================== 生命週期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        host = getIntent().getStringExtra(EXTRA_HOST);
        port = getIntent().getIntExtra(EXTRA_PORT, 5555);
        if (host == null) {
            finish();
            return;
        }

        // 鏡像時不讓本機螢幕睡著 —— 睡著就等於斷線
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 本機鍵盤彈出來時「縮小」而不是「蓋住」畫面：蓋住的話正在打的那一行
        // 很可能剛好被擋住，等於盲打。縮小後對方整個螢幕還是看得到（比例不變，只是變小）。
        //
        // ALWAYS_HIDDEN 是必要的：畫面裡有個 EditText（我們的 imeSink），
        // 預設的 stateUnspecified 會讓某些機型一進場就自動彈鍵盤；用 STATE_UNCHANGED
        // 更糟 —— 它會沿用「上次開著」的狀態，於是離開再進來鍵盤又自己冒出來。
        // 鍵盤只該由工具列那顆「鍵盤」開。
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        inputBg = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "omniadb-input");
            t.setDaemon(true);
            return t;
        });
        setContentView(buildUi());
        goFullscreen();

        // 畫面方向從現在起由我們自己控制（不再跟隨系統）。理由見 onIntent()。
        setRequestedOrientation(isLandscapeNow()
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        orientationSync = new OrientationSync(this, () -> session, this::onIntent);
        recorder = new Recorder(this,
                (where, message) -> ui.post(() -> onRecordStopped(where, message)));
        clipboardSync = new ClipboardSync(this);
        seedClipboardForTest();
        typeForTest();
        applyAudioExtrasForTest();
        applyRotationExtraForTest();
        applySettingsExtrasForTest();
        // 按鈕要反映上次的選擇（音訊三種模式，見 Settings#AUDIO_BOTH）。
        hostAudible = Settings.audioMode(this) == Settings.AUDIO_BOTH;
    }

    /**
     * 測試用的音訊預置（只在可 debug 的建置生效）。
     *
     * <pre>
     *   -e hostmute 1     一開始就「對方靜音」（audio_dup=false）
     *   -e audioflip 8    8 秒後按一次那顆按鈕（驗證切換路徑）
     * </pre>
     *
     * <p>為什麼需要：{@code input tap} 在 SM-G9810 上完全沒反應，工具列又會自動收起來，
     * 光靠 adb 點不到那顆按鈕。
     */
    private void applyAudioExtrasForTest() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        String mute = getIntent().getStringExtra("hostmute");
        if ("1".equals(mute) || "true".equals(mute)) {
            // 寫進 Settings 而不是只改欄位：重連時 ScrcpySession 讀的是 Settings
            Settings.setAudioMode(this, Settings.AUDIO_CLIENT_ONLY);
            DiagLog.i(TAG, "測試：一開始就讓對方靜音（audio_dup=false）");
        }
        int flip = 0;
        try {
            flip = Integer.parseInt(String.valueOf(getIntent().getStringExtra("audioflip")));
        } catch (NumberFormatException ignored) {
            // 沒帶或不是數字 → 不排
        }
        if (flip > 0) {
            DiagLog.i(TAG, "測試：" + flip + " 秒後切換一次音訊");
            ui.postDelayed(this::toggleSound, flip * 1000L);
        }
    }

    /**
     * 測試用的連線參數預置（只在可 debug 的建置生效）。
     *
     * <pre>
     *   -e quality 0|1|2   畫質（0=省流量 1=標準 2=高畫質），寫進 Settings
     *   -e noaudio 1       這次不要開音訊 session（0 還原成「兩邊都有聲」）
     *   -e audiomode 0|1|2 音訊模式（0=兩邊都有聲 1=只有我有聲 2=不要聲音），連線前套用
     *   -e audiomodeat 6:2 連上 6 秒後<b>在連線中</b>切到模式 2（驗「切音訊不重連畫面」）
     *   -e stayawake 0     這次不要讓對方螢幕常亮
     *   -e sendtext "abc"  連上 3 秒後送出這段文字（驗 TYPE_INJECT_TEXT）
     *   -e sendkey 66      連上 3 秒後送一個 keycode（驗實體鍵盤那條路）
     * </pre>
     *
     * <p>為什麼需要：工具列會自動收起、選單又要連點兩層，用 {@code input tap}
     * 在實機上點得到全靠運氣。這些入口直接走跟 UI 完全相同的程式路徑
     * （{@link Settings} / {@link ScrcpySession#sendText(String)}），
     * 驗的是協定與邏輯，不是座標。
     */
    private void applySettingsExtrasForTest() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        String q = getIntent().getStringExtra("quality");
        if (q != null) {
            try {
                int v = Integer.parseInt(q);
                if (v >= 0 && v <= 2) {
                    Settings.setQuality(this, v);
                    DiagLog.i(TAG, "測試：畫質設為 " + v);
                }
            } catch (NumberFormatException ignored) {
                // 不是數字就不理它
            }
        }
        String noAudio = getIntent().getStringExtra("noaudio");
        if ("1".equals(noAudio) || "0".equals(noAudio)) {
            // 0 也要支援：這些值會寫進 SharedPreferences，測完要能還原，
            // 不然手機上會留著「不要聲音」這種奇怪的預設。
            Settings.setAudioMode(this, "1".equals(noAudio)
                    ? Settings.AUDIO_OFF : Settings.AUDIO_BOTH);
            DiagLog.i(TAG, "測試：noaudio=" + noAudio);
        }
        String audioMode = getIntent().getStringExtra("audiomode");
        if (audioMode != null) {
            try {
                int v = Integer.parseInt(audioMode);
                if (v >= 0 && v <= 2) {
                    Settings.setAudioMode(this, v);
                    DiagLog.i(TAG, "測試：音訊模式設為 " + v);
                }
            } catch (NumberFormatException ignored) {
                // 不是數字就不理它
            }
        }
        String audioModeAt = getIntent().getStringExtra("audiomodeat");
        if (audioModeAt != null) {
            String[] parts = audioModeAt.split(":");
            if (parts.length == 2) {
                try {
                    int sec = Integer.parseInt(parts[0].trim());
                    int mode = Integer.parseInt(parts[1].trim());
                    if (sec > 0 && mode >= 0 && mode <= 2) {
                        DiagLog.i(TAG, "測試：" + sec + " 秒後切到音訊模式 " + mode);
                        ui.postDelayed(() -> {
                            applyAudioMode(mode);
                            DiagLog.i(TAG, "測試：音訊模式已切到 " + mode
                                    + "，畫面 session 是否還在：" + (session != null && session.isRunning()));
                        }, sec * 1000L);
                    }
                } catch (NumberFormatException ignored) {
                    // 格式不對就不理它
                }
            }
        }
        String awake = getIntent().getStringExtra("stayawake");
        if ("0".equals(awake) || "1".equals(awake)) {
            Settings.setStayAwake(this, !"0".equals(awake));
            DiagLog.i(TAG, "測試：stayawake=" + awake);
        }
        // -e screenoff 1        連上 5 秒後關掉對方螢幕
        // -e screenon 1         連上 5 秒後把對方螢幕開回來
        // -e screenoffat 8      連上 8 秒後關掉對方螢幕
        // 只能靠 extra：工具列會自動收起、選單又要連點兩層，adb 點不到。
        String screenOff = getIntent().getStringExtra("screenoff");
        if ("1".equals(screenOff) || "true".equals(screenOff)) {
            ui.postDelayed(() -> setHostScreen(false), 5_000);
        }
        String screenOn = getIntent().getStringExtra("screenon");
        if ("1".equals(screenOn) || "true".equals(screenOn)) {
            ui.postDelayed(() -> setHostScreen(true), 5_000);
        }
        String screenOffAt = getIntent().getStringExtra("screenoffat");
        if (screenOffAt != null) {
            try {
                int sec = Integer.parseInt(screenOffAt.trim());
                if (sec > 0) {
                    DiagLog.i(TAG, "測試：" + sec + " 秒後關閉對方螢幕");
                    ui.postDelayed(() -> setHostScreen(false), sec * 1000L);
                }
            } catch (NumberFormatException ignored) {
                // 格式不對就不理它
            }
        }
        // -e shot 1             連上 5 秒後截圖（走跟選單完全相同的程式路徑）
        // -e shotat 8           連上 8 秒後截圖
        // 截圖不依賴 session，所以就算鏡像還沒好也能先驗 SYNC 通道通不通。
        String shot = getIntent().getStringExtra("shot");
        if ("1".equals(shot) || "true".equals(shot)) {
            ui.postDelayed(this::takeScreenshot, 5_000);
        }
        String shotAt = getIntent().getStringExtra("shotat");
        if (shotAt != null) {
            try {
                int sec = Integer.parseInt(shotAt.trim());
                if (sec > 0) {
                    DiagLog.i(TAG, "測試：" + sec + " 秒後截圖");
                    ui.postDelayed(this::takeScreenshot, sec * 1000L);
                }
            } catch (NumberFormatException ignored) {
                // 格式不對就不理它
            }
        }
        // -e record 5            連上 5 秒後開始錄影
        // -e recordstop 15       連上 15 秒後停止（沒給就一直錄到離開）
        // 錄影那顆在「設置」選單裡，工具列又會自動收起 —— 用 adb 點它全靠運氣，
        // 所以留這兩個入口，走的是跟選單完全相同的 toggleRecord()。
        String record = getIntent().getStringExtra("record");
        if (record != null) {
            try {
                int sec = Integer.parseInt(record.trim());
                if (sec > 0) {
                    DiagLog.i(TAG, "測試：" + sec + " 秒後開始錄影");
                    ui.postDelayed(this::toggleRecord, sec * 1000L);
                }
            } catch (NumberFormatException ignored) {
                // 格式不對就不理它
            }
        }
        String recordStop = getIntent().getStringExtra("recordstop");
        if (recordStop != null) {
            try {
                int sec = Integer.parseInt(recordStop.trim());
                if (sec > 0) {
                    DiagLog.i(TAG, "測試：" + sec + " 秒後停止錄影");
                    ui.postDelayed(() -> {
                        if (recorder != null && recorder.isRecording()) toggleRecord();
                    }, sec * 1000L);
                }
            } catch (NumberFormatException ignored) {
                // 格式不對就不理它
            }
        }
        final String text = getIntent().getStringExtra("sendtext");
        final String key = getIntent().getStringExtra("sendkey");
        if (text != null) testSendText = text;
        if (key != null) {
            try {
                testSendKey = Integer.parseInt(key);
            } catch (NumberFormatException ignored) {
                // 不是數字就不理它
            }
        }
    }

    /** 測試用：連上之後要自動送出的文字／按鍵（見 {@link #applySettingsExtrasForTest()}）。 */
    private String testSendText;
    private Integer testSendKey;

    /** 連上之後才送測試資料 —— 太早送會落在「session 還沒好」的空窗。 */
    private void runPendingTestInput() {
        if (testSendText == null && testSendKey == null) return;
        final String text = testSendText;
        final Integer key = testSendKey;
        testSendText = null;
        testSendKey = null;
        ui.postDelayed(() -> {
            ScrcpySession s = session;
            if (s == null || !s.isRunning()) {
                DiagLog.w(TAG, "測試：session 已經不在，送不出去");
                return;
            }
            if (text != null) {
                DiagLog.i(TAG, "測試：送出文字 " + text.length() + " 字");
                s.sendText(text);
            }
            if (key != null) {
                DiagLog.i(TAG, "測試：送出 keycode " + key);
                s.sendHardwareKey(KeyEvent.ACTION_DOWN, key, 0, 0);
                s.sendHardwareKey(KeyEvent.ACTION_UP, key, 0, 0);
            }
        }, 1500);
    }

    /**
     * 測試用的方向預置（只在可 debug 的建置生效）。
     *
     * <pre>
     *   -e rotatetest 3   3 秒後模擬「本機橫過來」
     * </pre>
     *
     * <p>為什麼需要：這條路徑的輸入源是 {@link android.view.OrientationEventListener}，
     * 只有真的把機器轉 90° 才會觸發。驗證「Host 鎖方向時 Client 也不轉」時，
     * 沒有辦法在 CI／桌面上轉動手機，所以留一個直接走 {@link #onIntent} 的入口。
     */
    private void applyRotationExtraForTest() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        int sec = 0;
        try {
            sec = Integer.parseInt(String.valueOf(getIntent().getStringExtra("rotatetest")));
        } catch (NumberFormatException ignored) {
            // 沒帶或不是數字 → 不排
        }
        if (sec <= 0) return;
        DiagLog.i(TAG, "測試：" + sec + " 秒後模擬本機轉向（方位 1）");
        ui.postDelayed(() -> {
            if (orientationSync != null) orientationSync.requestHost(1);
            onIntent(1);
        }, sec * 1000L);
    }

    /**
     * 測試用的剪貼簿「預置」。
     *
     * <p>剪貼簿同步最難自動驗證的就是<b>連線前</b>那一段：要在連線前把本機剪貼簿弄成
     * 指定內容（或清空），才測得到「先複製、後連線」。所以留了兩個 extra：
     * <pre>
     *   -e clip "要放進剪貼簿的文字"
     *   -e clipclear 1        （清空，用來模擬「本機沒有內容，採用對方的」）
     * </pre>
     *
     * <p>只有可 debug 的建置會理這兩個參數，正式版完全無效。
     */
    private void seedClipboardForTest() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        String seed = getIntent().getStringExtra("clip");
        // 注意：adb 的 -e 一律塞字串，所以 `-e clipclear 1` 用 getBooleanExtra 讀不到。
        // 兩種寫法都接受：`-ez clipclear true` 與 `-e clipclear 1`。
        boolean clear = getIntent().getBooleanExtra("clipclear", false)
                || "1".equals(getIntent().getStringExtra("clipclear"));
        if (seed == null && !clear) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        if (clear && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cm.clearPrimaryClip();
            DiagLog.i(TAG, "測試：已清空本機剪貼簿");
            if (cm.hasPrimaryClip()) {
                // 實測 Samsung（Android 13）會立刻把上一筆還原回來，清不掉
                DiagLog.w(TAG, "測試：清空後仍然有內容（這台會自動還原上一筆）");
            }
        }
        if (seed != null) {
            // 標籤刻意<b>不用</b> ClipboardSync 的 "OmniADB"：那代表「同步寫進來的」，
            // 會被當成不該往回推的舊內容。這裡要模擬的是使用者自己複製的。
            cm.setPrimaryClip(ClipData.newPlainText("test-seed", seed));
            DiagLog.i(TAG, "測試：已預置本機剪貼簿（" + seed.length() + " 字）");
        }
    }

    /**
     * 測試用：連上之後直接把一段文字「打」進對方（走和輸入法完全相同的那條路）。
     *
     * <pre>
     *   -e type "你好 hello"
     *   -e typedelay 4       （連上 4 秒後才送，預設 3 秒）
     *   -e heartbeat 1       （每 3 秒在主執行緒寫一行心跳，用來判斷主執行緒有沒有卡死）
     * </pre>
     *
     * <p>為什麼要這個：本機輸入法沒辦法用 adb 驅動（{@code input text} 走的是
     * KeyEvent，不會經過 {@code InputConnection}），所以中文那條路只能靠這個入口驗。
     * 只有 debuggable 建置有效。
     */
    private void typeForTest() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;

        // -e keyboard 1 → 連上 5 秒後自動按下工具列的「鍵盤」（驗輸入法有沒有真的彈出來）
        String kb = getIntent().getStringExtra("keyboard");
        if (kb != null && !kb.isEmpty()) {
            ui.postDelayed(this::toggleKeyboard, 5000L);
        }

        // -e heartbeat 1 → 每 3 秒在主執行緒寫一行。主執行緒卡死時這行會停，
        // 而背景執行緒（音訊）還在寫 —— 一眼就能分辨「是卡死還是只是沒事件」。
        String hb = getIntent().getStringExtra("heartbeat");
        if (hb != null && !hb.isEmpty()) {
            Runnable beat = new Runnable() {
                int n;

                @Override
                public void run() {
                    DiagLog.d(TAG, "心跳 " + (++n) + "（主執行緒正常）");
                    ui.postDelayed(this, 3000L);
                }
            };
            ui.postDelayed(beat, 3000L);
        }

        final String text = getIntent().getStringExtra("type");
        if (text == null || text.isEmpty()) return;
        int delay = 3;
        try {
            String d = getIntent().getStringExtra("typedelay");
            if (d != null) delay = Integer.parseInt(d.trim());
        } catch (Exception ignored) {
            // 參數壞掉就用預設值
        }
        ui.postDelayed(() -> {
            DiagLog.i(TAG, "測試：送出文字 " + text.length() + " 字");
            sendTextToRemote(text);
        }, delay * 1000L);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 這裡刻意不做任何「同步方向」的動作。
        // 本機畫面轉向是我們自己用 setRequestedOrientation() 決定的結果，
        // 拿它回頭當輸入只會自己餵自己。
        //
        // 唯一要做的是：把「等視窗轉完才改」的長寬比補上（見 onVideoSize）。
        if (pendingAspect > 0 && surfaceView != null) {
            surfaceView.setAspect(pendingAspect);
            pendingAspect = 0;
        }
    }

    /** 目前畫面是不是橫的。 */
    private boolean isLandscapeNow() {
        return getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    // ============================================================ 方向：先轉對方，再轉自己

    /**
     * 等對方跟上來的最長時間（影像比例會變的情況）。
     *
     * <p>實測 {@code wm user-rotation lock N} 約 70ms 送達，對方轉完並送出新的
     * 影像尺寸約 420ms。等超過這個時間還沒看到新比例，就當它<b>不會轉了</b> ——
     * 本機也維持原方向，不自己轉過去。
     */
    private static final long HOST_WAIT_MS = 900;

    /**
     * 等對方跟上來的時間（影像比例<b>不變</b>的情況，例如左右橫互換）。
     *
     * <p>這時不會有黑邊問題，影像尺寸也不會變（都是橫的），所以偵測不到
     * 「對方轉好了」。只能用時間差擋掉「對方還沒轉、畫面是反的」那一小段。
     * 實測對方轉完約 420ms，就照這個數字等。
     */
    private static final long SAME_ASPECT_WAIT_MS = 420;

    /** 已經送出「請對方轉向」，還在等對方的新畫面。null ＝ 沒有在等。 */
    private Integer pendingRotation;

    /**
     * 等不到對方轉、本機壓著沒轉的目標方位。
     *
     * <p>對方被 App 鎖住方向（launcher／相機／遊戲）時就是這個狀態：本機維持原方向，
     * 免得變成「橫屏容器裡放一條豎畫面」的兩片大黑邊 —— 那種畫面不但小，
     * 觸控還只剩中間那一條能用。
     *
     * <p>但對方也可能只是<b>轉得慢</b>（實測有些 ROM 要 1.5 秒），所以這裡留一個待辦：
     * 之後它的畫面比例真的變了，{@link #onVideoSize} 會補上這一轉。
     */
    private Integer deferredRotation;

    /** 上一次提示「對方鎖住方向」的時間。翻來翻去時不要每翻一次就彈一次。 */
    private long lastHostLockedToastAt;

    /** 等本機視窗轉完才要套用的影像長寬比。0 ＝ 沒有待套用的。 */
    private float pendingAspect;

    /** 本機畫面最後一次真的轉向的時間。用來擋掉轉完之後緊接著的反向糾正。 */
    private long lastClientRotateAt;

    private final Runnable hostWaitTimeout = () -> {
        if (pendingRotation == null) return;
        Integer target = pendingRotation;
        pendingRotation = null;

        ScrcpySession s = session;
        // 對方到底轉了沒？只有「畫面真的換了橫豎」才算數。
        //
        // 不能因為指令送達就當它轉了：wm user-rotation 的優先權<b>低於</b>
        // Activity 自己宣告的 screenOrientation，launcher 這類 App 根本不會動
        // （實測 HyperOS launcher：指令成功、mCurrentRotation 也變了，
        //   但它只是被 letterbox 塞進橫屏，內容還是豎的）。
        boolean hostFollowed = s != null && s.videoWidth() > 0 && s.videoHeight() > 0
                && (OrientationSync.isLandscapeRotation(target) == (s.videoWidth() > s.videoHeight()));

        if (hostFollowed) {
            DiagLog.i(TAG, "對方已轉向，本機跟上 " + target);
            rotateClient(target);
            deferredRotation = null;
            return;
        }

        // 對方沒轉（或還沒轉完）：本機也不轉，維持跟它一致。
        // 這才是「看到什麼就是什麼」—— 本機單方面轉過去只會換來兩片大黑邊。
        DiagLog.i(TAG, "對方沒有轉（App 鎖方向？），本機維持方位 " + currentClientRotation());
        deferredRotation = target;
        toastHostLocked();
    };

    /**
     * 使用者的擺放方向變了（{@link OrientationSync} 去抖後回報，對方指令也已送出）。
     *
     * <p><b>為什麼不讓本機畫面立刻跟著轉：</b>本機一轉、對方還沒轉的那段時間，
     * 就是「橫螢幕裡放一個豎畫面」—— 左右兩大片黑邊，等對方跟上才又跳一次填滿。
     * 兩次跳變、中間還夾著黑邊，看起來就是「不絲滑」。
     *
     * <p>所以改成：<b>本機畫面壓住不動，先讓對方轉</b>；等對方的新畫面到了，
     * 才把本機轉過去 —— 這樣畫面只翻一次，而且翻的當下影片已經是新方向，
     * 從頭到尾都不會出現黑邊。
     *
     * <p><b>對方若根本轉不動</b>（App 鎖方向，例如 launcher），最多等
     * {@link #HOST_WAIT_MS} 就放棄 —— 而且本機畫面<b>也不轉</b>。
     * 本機單方面轉過去，換來的是「橫屏容器裡放一條豎畫面」：畫面變小、
     * 兩側兩片大黑邊、觸控只剩中間一條能用，比不轉更糟。
     * 方向一律以對方實際畫面為準，看到什麼就是什麼。
     */
    private void onIntent(int rotation) {
        ui.removeCallbacks(hostWaitTimeout);
        // 有新的目標了，上一個「等不到對方轉」的待辦就作廢
        deferredRotation = null;

        ScrcpySession s = session;
        if (s == null || !s.isRunning() || s.videoWidth() <= 0 || s.videoHeight() <= 0) {
            pendingRotation = null;
            rotateClient(rotation);
            return;
        }

        boolean wantLand = OrientationSync.isLandscapeRotation(rotation);
        boolean hostLand = s.videoWidth() > s.videoHeight();
        pendingRotation = rotation;
        if (wantLand == hostLand) {
            // 比例不變（左右橫互換）：沒有黑邊風險，只要避開「對方還沒轉」那一小段
            DiagLog.i(TAG, "等對方換到方位 " + rotation + "（比例不變）");
            ui.postDelayed(hostWaitTimeout, SAME_ASPECT_WAIT_MS);
        } else {
            DiagLog.i(TAG, "先請對方轉向，本機畫面壓住等它");
            ui.postDelayed(hostWaitTimeout, HOST_WAIT_MS);
        }
    }

    /**
     * 把本機畫面轉成指定方位。
     *
     * @return true ＝ 真的發起了轉向（之後會收到 onConfigurationChanged）
     */
    private boolean rotateClient(int rotation) {
        int o;
        switch (rotation) {
            case 1:  o = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; break;
            case 2:  o = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT; break;
            case 3:  o = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE; break;
            default: o = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; break;
        }
        if (getRequestedOrientation() == o) return false;
        DiagLog.i(TAG, "本機畫面轉向：方位 " + rotation);
        lastClientRotateAt = SystemClock.uptimeMillis();
        setRequestedOrientation(o);
        return true;
    }

    /**
     * 提示「對方 App 把方向鎖住了，本機維持跟它一致」。
     *
     * <p>不提示的話，使用者會以為壞了：明明把手機轉橫了，畫面卻不動。
     * 這是整個設計裡最容易被誤解成 bug 的地方 —— 但畫面不動才是對的。
     *
     * <p>5 秒內只說一次：使用者翻來翻去時，每翻一次彈一次會很吵。
     */
    private void toastHostLocked() {
        long now = SystemClock.uptimeMillis();
        if (now - lastHostLockedToastAt < 5_000) return;
        lastHostLockedToastAt = now;
        Toast.makeText(this, R.string.mirror_host_locked, Toast.LENGTH_SHORT).show();
    }

    /** 本機畫面現在的方位（0/1/2/3），直接讀我們自己設定的方向。 */
    private int currentClientRotation() {
        switch (getRequestedOrientation()) {
            case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE: return 1;
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE: return 3;
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT: return 2;
            case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT: return 0;
            default:
                // 還沒由我們指定（剛進場、或方向同步關著）—— 這時視窗方向是系統給的，
                // 只能讀顯示器。一律回 0 會讓「開場前先鎖對方」把橫著拿的手機鎖成豎的，
                // 300ms 後再被感測器糾正回來：白白多翻一次。
                return displayRotation();
        }
    }

    /** 顯示器現在的旋轉 → {@code Surface.ROTATION_*} 的 0/1/2/3。 */
    @SuppressWarnings("deprecation")
    private int displayRotation() {
        Display d = getWindowManager().getDefaultDisplay();
        switch (d.getRotation()) {
            case Surface.ROTATION_90: return 1;
            case Surface.ROTATION_180: return 2;
            case Surface.ROTATION_270: return 3;
            default: return 0;
        }
    }

    /**
     * 開場（或對方自己轉了）時，把本機現在的方向再送一次給對方。
     *
     * <p>手機<b>平放</b>時感測器回 {@code ORIENTATION_UNKNOWN}，不會有任何
     * 「方向變了」的事件 —— 這時候如果本機和對方本來就不一致，就永遠沒人處理。
     */
    private void syncNow() {
        if (orientationSync == null || !orientationSync.isEnabled()) return;
        if (pendingRotation != null) return;             // 已經在等對方，別插手
        // 剛轉完本機畫面時 onVideoSize 可能還會再來一次（編碼器重啟會重送 config），
        // 那時本機的 config 還沒更新，急著對齊會送出反方向的指令，畫面會彈回去。
        if (SystemClock.uptimeMillis() - lastClientRotateAt < 1_500) return;
        orientationSync.requestHost(currentClientRotation());
    }

    /** 對方的新畫面到了嗎？到了就把本機轉過去。 */
    private void onHostSizeMaybeReady(int width, int height) {
        Integer target = pendingRotation;
        if (target == null) return;
        // 只有「比例會變」那種才靠尺寸判斷；左右橫互換尺寸不變，交給計時器
        if (OrientationSync.isLandscapeRotation(target) == (width > height)) return;
        ui.removeCallbacks(hostWaitTimeout);
        pendingRotation = null;
        rotateClient(target);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen();
        DiagLog.i(TAG, "視窗焦點：" + hasFocus + "（接收器 hasFocus="
                + (imeSink != null && imeSink.hasFocus()) + "）");
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        if (orientationSync != null) orientationSync.start();
    }

    @Override
    protected void onStop() {
        // 完全看不到畫面時就別再佔著對方的一條 ADB 連線（還有它的 scrcpy-server 進程）。
        // 只 detach Surface 不夠：實測反覆切換前景會累積出好幾個 session，
        // 每個都在對方機器上留一個 app_process。
        if (orientationSync != null) orientationSync.stop();

        // 鍵盤跟著收起來：留著的話回來時它還掛在畫面上，但輸入框已經沒連線了
        if (imeSink != null) {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(imeSink.getWindowToken(), 0);
            imeSink.clearFocus();
            keyboardOn = false;
            updateKeyboardButton();
        }

        // ★ 例外：子母畫面。那時 Activity 仍然可見（只是變小），
        // 關掉連線就等於「一縮小就斷」，整個功能白做。
        if (isInPictureInPictureMode()) {
            DiagLog.i(TAG, "子母畫面中（畫面仍可見）—— 保留連線");
            super.onStop();
            return;
        }

        closeSession();
        stopped = true;
        super.onStop();
    }

    /**
     * 按下 Home／切到別的 App 時，把鏡像縮成子母畫面繼續看。
     *
     * <p>這是「邊看邊用」最省事的做法：Activity 沒有被 stop（仍可見），
     * 連線、解碼器、音訊都不用重建；使用者點一下小窗就回到全螢幕。
     * 真正的背景執行（螢幕熄掉還要有聲音）需要前景服務，那是另一件事。
     *
     * <p>刻意<b>不</b>在使用者按返回時進子母畫面 —— 那是「我要離開」，
     * 縮成小窗會讓人關不掉。
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (session == null || !session.isRunning()) return;
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return;
        try {
            PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
            int w = session.videoWidth();
            int h = session.videoHeight();
            if (w > 0 && h > 0) b.setAspectRatio(new Rational(w, h));
            if (enterPictureInPictureMode(b.build())) {
                DiagLog.i(TAG, "已進入子母畫面");
            }
        } catch (Exception e) {
            // 進不去就照舊（onStop 會把連線收掉），不要讓離開 App 變成閃退
            DiagLog.w(TAG, "進不了子母畫面", e);
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration config) {
        super.onPictureInPictureModeChanged(inPip, config);
        if (inPip) {
            hideBarNow();
        } else {
            // 從子母畫面點回來：工具列先亮一下，讓使用者知道回到了全螢幕
            showBar();
        }
    }

    @Override
    protected void onDestroy() {
        hideBarNow();
        closeSession();
        // 收掉錄影執行緒。close() 會等它把 trailer 寫完（最多 1.5 秒）——
        // 不等也行，但那個檔案就會缺 moov、播放器打不開。
        if (recorder != null) recorder.close();
        if (inputBg != null) inputBg.shutdownNow();
        super.onDestroy();
    }

    /** 關掉目前的連線（背景執行緒，close() 會關 socket 可能阻塞）。 */
    private void closeSession() {
        // 錄影跟著連線走：影像串流沒了，錄下去只會得到一個停格的檔。
        // stop() 不阻塞，收檔（muxer trailer、相簿 IS_PENDING）在錄影執行緒上做完才回呼。
        if (recorder != null) recorder.stop();
        if (clipboardSync != null) clipboardSync.detach();
        final ScrcpySession s = session;
        session = null;
        if (s == null) return;
        // 對方螢幕由 ScrcpySession.close() 統一開回來：它才確切知道這次連線有沒有
        // 把對方關黑，而且每一條關閉路徑（退出、錯誤、重連）都會經過那裡。
        Thread t = new Thread(s::close, "omniadb-mirror-close");
        t.setDaemon(true);
        t.start();
    }

    // ================================================================== UI

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        surfaceView = new AspectSurfaceView(this);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        slp.gravity = Gravity.CENTER;
        surfaceView.setLayoutParams(slp);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                // Surface 又回來了（多半只是轉向），取消剛才排的「放掉解碼器」
                ui.removeCallbacks(detachTask);
                ScrcpySession s = session;
                if (s != null) {
                    // 回到前景：SurfaceView 拿到的是全新的 Surface，重新掛給解碼器
                    s.attachSurface(holder.getSurface());
                } else {
                    startSession(holder.getSurface());
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                // 尺寸變更不影響解碼器（它自己會縮放到 Surface），不需要重建
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                // 不要立刻放掉解碼器。轉向、下拉狀態列、短暫遮擋都會讓 Surface
                // 被銷毀又馬上重建，一銷毀就 releaseCodec() 等於白黑 100ms 再重建，
                // 看起來就是「翻屏時閃一下」。延後一點，新 Surface 來了就取消。
                ui.removeCallbacks(detachTask);
                ui.postDelayed(detachTask, SURFACE_DETACH_GRACE_MS);
            }
        });
        surfaceView.setOnTouchListener(this::onSurfaceTouch);
        root.addView(surfaceView);

        // 隱形輸入框要蓋在最上層（它只有 1dp，看不到，但必須能取得焦點）
        root.addView(buildImeSink());

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setTextColor(0xFFDDDDDD);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(24), dp(12), dp(24), dp(12));
        status.setText(R.string.mirror_connecting);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER;
        root.addView(status, tlp);

        // 錄影中的紅點。刻意放在左上角、跟著畫面（不跟工具列一起收起來）——
        // 錄影是「一直在背景發生的事」，沒有任何提示的話很容易錄了半小時才想起來。
        recordDot = new TextView(this);
        recordDot.setText(R.string.record_dot);
        recordDot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        recordDot.setTextColor(0xFFFF453A);
        recordDot.setShadowLayer(dp(2), 0, dp(1), 0x80000000);
        recordDot.setPadding(dp(12), dp(6), dp(12), dp(6));
        recordDot.setVisibility(View.GONE);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.gravity = Gravity.TOP | Gravity.START;
        root.addView(recordDot, rlp);

        root.addView(buildHandle());
        root.addView(buildBar());

        // 工具列與把手要讓開狀態列與挖孔。全螢幕（immersive）下狀態列平常是隱藏的，
        // 但手指點到畫面上緣時系統會把它拉出來蓋住工具列 —— 實測「返回」會跟時鐘
        // 疊在一起。所以這裡不問狀態列現在可不可見，直接保留它的高度。
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) top = Math.max(top, cutout.getSafeInsetTop());
            }
            if (top <= 0) {
                int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) top = getResources().getDimensionPixelSize(id);
            }
            setTopMargin(bar, top + dp(4));
            setTopMargin(handle, top + dp(4));
            setTopMargin(recordDot, top + dp(4));
            return insets;
        });
        return root;
    }

    /**
     * 建一個「只為了接本機輸入法」的隱形輸入框。
     *
     * <p><b>為什麼需要一個 EditText：</b>本機的輸入法（注音、拼音、Gboard）不是靠
     * KeyEvent 打字的 —— 它透過 {@code InputConnection} 送出「組字中」與「commit」兩種事件。
     * 沒有可編輯的 View 取得焦點，輸入法連開都不會開。所以放一個 1dp、全透明的
     * EditText 當接收端，把 commit 出來的字轉送給對方。
     *
     * <p><b>為什麼組字中不送：</b>打「你好」時輸入法會先送 {@code nihao} 五次組字事件。
     * 照送的話對方螢幕上會出現一堆拼音字母，選字之後還得倒回去刪掉 —— 那不是「像本地」，
     * 那是把本機的輸入法狀態洩漏到對方畫面上。所以只送 commit 的最終文字。
     */
    private View buildImeSink() {
        imeSink = new EditText(this) {
            @Override
            public boolean onCheckIsTextEditor() {
                return true;
            }

            @Override
            public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
                // 不要「擷取模式」：橫屏時不少輸入法會把整個畫面換成一個大輸入框，
                // 那樣就看不到對方螢幕了，等於失去鏡像的意義。
                outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        | EditorInfo.IME_FLAG_NO_FULLSCREEN
                        | EditorInfo.IME_ACTION_NONE;
                outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
                // 給初始游標位置。不給（預設 -1）的話，部分輸入法會判斷
                // 「這個編輯區沒有游標、大概不可用」，改用自家的浮動輸入框接字。
                outAttrs.initialSelStart = 0;
                outAttrs.initialSelEnd = 0;

                // ★ 這裡有兩個連續的坑，都是實測踩出來的，改動前先讀完：
                //
                // 坑一：輸入法<b>不是</b>每按一個鍵就 commit。三星／Gboard 打英文時，
                //   每個字母送的是 setComposingText（組字中），按空白才 commit ——
                //   而且 commit 的內容<b>只有那個空白</b>，不是整個單字。因為它假設
                //   「kbd」這段組字文字早就已經躺在輸入框裡了。
                //   （只送 commitText 的參數 ⇒ 對方只收到一個空格，整個單字消失。）
                //
                // 坑二：想靠 super 讓 Editable 自己變成正確內容也<b>不行</b> ——
                //   BaseInputConnection.getEditable() 在這個匿名子類裡回 null，
                //   super 全部靜默失敗而且還回 true（log 顯示 editable 長度永遠 0）。
                //
                // 所以自己維護一份緩衝（{@link #imeWord}），語意照著真正的 IC 走：
                //   組字中（setComposingText）→ 覆蓋「組字區」
                //   組字已結束（finishComposingText 之後）才來的 commit → 接在後面
                // 這樣三星那種「先結束組字、再 commit 一個空白」的流程才會得到 "kbd "。
                return new BaseInputConnection(this, true) {
                    @Override
                    public boolean setComposingText(CharSequence text, int newCursorPosition) {
                        cancelPendingFlush();
                        String t = text == null ? "" : text.toString();
                        if (!imeComposing()) imeCompStart = imeWord.length();
                        imeWord.setLength(imeCompStart);
                        imeWord.append(t);
                        DiagLog.d(TAG, "輸入法：組字中「" + t + "」" + sinkState());
                        return true;
                    }

                    @Override
                    public boolean finishComposingText() {
                        if (imeComposing()) {
                            imeCompStart = -1;      // 組字區變成正式內容，留在緩衝裡等 commit
                        }
                        DiagLog.d(TAG, "輸入法：組字結束" + sinkState());
                        // 有些輸入法組完字不會馬上 commit（例如使用者直接收起鍵盤）。
                        // 給 400ms 的寬限：這段時間內沒有 commit 就把緩衝送出去，
                        // 免得使用者打了字卻一個字都沒過去。
                        scheduleFlush(400L);
                        return true;
                    }

                    @Override
                    public boolean commitText(CharSequence text, int newCursorPosition) {
                        cancelPendingFlush();
                        String t = text == null ? "" : text.toString();
                        if (imeComposing()) {
                            // 還在組字 ⇒ 這段是來「取代組字區」的（注音選字就是這種）
                            imeWord.setLength(imeCompStart);
                            imeWord.append(t);
                            imeCompStart = -1;
                        } else {
                            // 組字已結束 ⇒ 這段是接在後面的（三星打英文按空白就是這種）
                            imeWord.append(t);
                        }
                        DiagLog.i(TAG, "輸入法：commit「" + t + "」" + sinkState());
                        flushSink();
                        return true;
                    }

                    @Override
                    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                        // 組字中的字<b>還沒送到對方</b>，所以退格只能改本機緩衝，
                        // 不能真的送 DEL 過去 —— 否則會把對方輸入框裡更早的字刪掉。
                        int local = imeWord.length();
                        if (local > 0) {
                            int cut = Math.min(beforeLength, local);
                            imeWord.setLength(local - cut);
                            if (imeCompStart > imeWord.length()) imeCompStart = imeWord.length();
                            DiagLog.d(TAG, "輸入法：退格（本機緩衝）" + sinkState());
                            return true;
                        }
                        ScrcpySession s = session;
                        if (s != null && s.isRunning()) {
                            for (int i = 0; i < beforeLength; i++) s.sendKey(KeyEvent.KEYCODE_DEL);
                            for (int i = 0; i < afterLength; i++) s.sendKey(KeyEvent.KEYCODE_FORWARD_DEL);
                        }
                        return true;
                    }

                    @Override
                    public boolean sendKeyEvent(KeyEvent event) {
                        // 輸入法自己的 Enter／空白／刪除會走這裡，不會走 commitText
                        ScrcpySession s = session;
                        if (s != null && s.isRunning() && event.getAction() == KeyEvent.ACTION_DOWN) {
                            DiagLog.d(TAG, "輸入法：按鍵 " + event.getKeyCode());
                            s.sendHardwareKey(KeyEvent.ACTION_DOWN, event.getKeyCode(),
                                    0, event.getMetaState());
                            s.sendHardwareKey(KeyEvent.ACTION_UP, event.getKeyCode(),
                                    0, event.getMetaState());
                        }
                        return true;
                    }

                    @Override
                    public boolean performEditorAction(int actionCode) {
                        ScrcpySession s = session;
                        if (s != null && s.isRunning()) s.sendKey(KeyEvent.KEYCODE_ENTER);
                        return true;
                    }
                };
            }
        };

        // 第二條路：<b>實體鍵盤</b>（藍牙鍵盤、USB 鍵盤、adb 注入的按鍵）。
        // 這些按鍵不經過 InputConnection，是 EditText 自己把字元插進 Editable，
        // 所以要在這裡補一次轉送，否則接了鍵盤打字等於打在空氣上。
        // 因為上面完全不碰 Editable，這裡讀到的內容<b>只會</b>是實體鍵盤打的字。
        imeSink.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                if (e.length() == 0) return;
                String typed = e.toString();
                e.clear();          // 會再觸發一次本方法，但那時長度是 0，直接返回
                cancelPendingFlush();
                DiagLog.i(TAG, "實體鍵盤：" + typed.length() + " 字");
                imeWord.append(typed);
                flushSink();
            }
        });
        imeSink.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        imeSink.setTextColor(android.graphics.Color.TRANSPARENT);
        imeSink.setCursorVisible(false);
        imeSink.setFocusable(true);
        imeSink.setFocusableInTouchMode(true);
        // 這個一定要關。EditText 預設「一拿到焦點就自己叫鍵盤」，而它在版面裡是唯一
        // 可聚焦的 View，進場就會自動取得焦點 —— 於是「點鏡像」等於「鍵盤自動彈出來」。
        // 關掉之後，鍵盤只由工具列那顆「鍵盤」叫。
        imeSink.setShowSoftInputOnFocus(false);
        // 「看不見」用透明的前景／背景來做，不用 setAlpha(0)：alpha=0 的 View 在某些
        // 輸入法眼裡等於不存在，會改用自家的浮動輸入框接字（保險，不是已證實的坑）。
        // 尺寸用 2dp 而不是 1dp：太小容易被判成「退化的編輯區」。
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(sinkSizeDp()), dp(sinkSizeDp()));
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        imeSink.setLayoutParams(lp);
        // 焦點一丟，輸入法就改用「自己的浮動輸入框」接字，我們的 InputConnection
        // 從此一個字都收不到（而且畫面上完全看不出來）。這行日誌是為了抓現行。
        imeSink.setOnFocusChangeListener((v, has) -> DiagLog.i(TAG, "接收器焦點：" + has));
        return imeSink;
    }

    /**
     * 接收器的邊長（dp）。
     *
     * <p>2dp 的透明小方塊：夠小不會擋到觸控，又不會像 1dp 那樣在某些輸入法裡
     * 被判成「尺寸退化」。留了 {@code -e sinksize 40} 這個測試入口，因為不同輸入法
     * 對「這個編輯區算不算數」的判斷不一樣，用同一個 APK 掃幾個尺寸比重編快得多。
     */
    private int sinkSizeDp() {
        String v = getIntent().getStringExtra("sinksize");
        if (v != null) {
            try {
                int n = Integer.parseInt(v.trim());
                if (n > 0) return n;
            } catch (Exception ignored) {
                // 參數壞掉就用預設值
            }
        }
        return 2;
    }

    /**
     * 把緩衝裡現在的字整段送給對方，然後清空。
     *
     * <p>這是<b>唯一</b>的出口。不管是輸入法 commit、實體鍵盤打字、還是組字結束後的
     * 寬限補送，都走這裡 —— 這樣就不會有某一條路漏送或重送。
     */
    private void flushSink() {
        if (imeWord.length() == 0) {
            DiagLog.d(TAG, "flushSink：沒有東西可送" + sinkState());
            return;
        }
        String s = imeWord.toString();
        imeWord.setLength(0);
        imeCompStart = -1;
        DiagLog.i(TAG, "輸入法：送出「" + s + "」");
        sendTextToRemote(s);
    }

    /** 現在有沒有在組字。 */
    private boolean imeComposing() {
        return imeCompStart >= 0;
    }

    /** 診斷用：緩衝裡現在有什麼。 */
    private String sinkState() {
        return "（緩衝 " + imeWord.length() + "「" + imeWord + "」，組字起點 " + imeCompStart + "）";
    }

    /** 排一個「等一下還沒有人送，就自己送」的補送。 */
    private void scheduleFlush(long delayMs) {
        cancelPendingFlush();
        pendingFlush = () -> {
            pendingFlush = null;
            if (imeWord.length() == 0) return;
            DiagLog.i(TAG, "輸入法：組字結束後沒有 commit，補送");
            flushSink();
        };
        ui.postDelayed(pendingFlush, delayMs);
    }

    private void cancelPendingFlush() {
        if (pendingFlush != null) {
            ui.removeCallbacks(pendingFlush);
            pendingFlush = null;
        }
    }

    /** 只改上緣間距，其餘（例如把手的右邊界）保持原樣。 */
    private void setTopMargin(View v, int top) {        if (v == null) return;
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (!(p instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) p;
        if (lp.topMargin == top) return;
        lp.topMargin = top;
        v.setLayoutParams(lp);
    }

    /** 工具列收起來之後留在右上角的小把手，點一下把工具列叫回來。 */
    private View buildHandle() {
        handle = new TextView(this);
        handle.setText("≡");
        handle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        handle.setTextColor(0x99FFFFFF);
        handle.setGravity(Gravity.CENTER);
        handle.setBackgroundColor(0x66000000);
        handle.setPadding(dp(8), dp(2), dp(8), dp(4));
        handle.setOnClickListener(v -> {
            if (bar.getVisibility() == View.VISIBLE) hideBarNow();
            else showBar();
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.setMargins(0, dp(4), dp(4), 0);
        handle.setLayoutParams(lp);
        return handle;
    }

    private View buildBar() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xCC000000);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));

        bar.addView(barButton(R.string.mirror_btn_back, v -> onBarButton(s -> {
            s.sendBackOrScreenOn(0);         // ACTION_DOWN
            s.sendBackOrScreenOn(1);         // ACTION_UP
        })));
        bar.addView(barButton(R.string.mirror_btn_home, v -> onBarButton(s -> s.sendKey(3))));          // KEYCODE_HOME
        bar.addView(barButton(R.string.mirror_btn_recents, v -> onBarButton(s -> s.sendKey(187))));     // KEYCODE_APP_SWITCH

        soundButton = barButton(R.string.mirror_btn_sound_on, v -> toggleSound());
        bar.addView(soundButton);
        updateSoundButton();

        // 「鍵盤」：叫出本機輸入法，直接打到對方有焦點的輸入框（見 buildImeSink）
        keyboardButton = barButton(R.string.mirror_btn_keyboard, v -> toggleKeyboard());
        bar.addView(keyboardButton);
        // 「設置」：旋轉／跟隨／音量／電源這些「一次調好就不動」的都收進這裡
        // （見 showMoreMenu）。留在工具列上只會讓每顆按鈕變窄、字被擠成兩行。
        bar.addView(barButton(R.string.mirror_btn_more, this::showMoreMenu));
        bar.addView(barButton(R.string.mirror_btn_close, v -> finish()));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        bar.setLayoutParams(lp);
        return bar;
    }

    /**
     * 工具列按鈕的共用入口。
     *
     * <p>連線還沒建立完成時 {@link #session} 是 null（伺服器要 1~2 秒才起得來），
     * 這段期間按任何鍵都不該炸掉 —— 使用者看到的是「按了沒反應」，
     * 而不是閃退。
     */
    private void onBarButton(java.util.function.Consumer<ScrcpySession> action) {
        showBar();
        ScrcpySession s = session;
        if (s == null || !s.isRunning()) return;
        action.accept(s);
    }

    /**
     * 把對方轉成「另一個方向」（豎 ↔ 橫）。
     *
     * <p>用 shell 直接<b>鎖</b>方向。scrcpy 的 {@code rotateDevice} 是 freeze 完
     * 立刻 thaw，在自動旋轉開著的機器上等於什麼都沒做（實測 POCO / Android 15）。
     *
     * <p>對方轉好之後，本機畫面才跟著轉 —— 否則橫的影片留在豎的螢幕裡就是左右
     * 兩片黑邊。走跟自動同步同一條路（等對方真的變了才轉）。對方轉不動
     * （App 鎖方向）時兩邊都不動，並提示一次原因。
     *
     * <p>以前這是工具列上一顆常駐按鈕，現在收進「設置」選單：一整天可能只按一次。
     */
    private void rotateHost() {
        onBarButton(s -> {
            final int target = s.videoWidth() > s.videoHeight() ? 0 : 1;   // 0=豎 1=橫
            Thread t = new Thread(() -> s.setHostRotation(target), "omniadb-rotate-btn");
            t.setDaemon(true);
            t.start();
            ui.removeCallbacks(hostWaitTimeout);
            deferredRotation = null;
            pendingRotation = target;
            ui.postDelayed(hostWaitTimeout, HOST_WAIT_MS);
            // 對方轉完（編碼器重啟約 1~2 秒）才把「想要的方向」改成它現在的方向，
            // 否則下一次感測器事件會立刻把它轉回來。
            if (orientationSync != null) {
                ui.postDelayed(orientationSync::adoptHost, 2_200);
            }
        });
    }

    /**
     * 切換「跟隨本機方向」。
     *
     * <p>關掉之後就不再自動送旋轉指令，只剩選單裡的「旋轉」可以手動轉 ——
     * 給「我要它一直保持橫屏」這種需求留一條路。
     *
     * <p>狀態不再畫在工具列上（按鈕已收進選單），改由選單項的勾選表示；
     * 切換時仍舊彈一句提示，因為按完選單就消失了，看不到勾。
     */
    private void toggleFollow() {
        showBar();
        if (orientationSync == null) return;
        boolean on = orientationSync.toggle();
        Toast.makeText(this, on ? R.string.mirror_follow_on : R.string.mirror_follow_off,
                Toast.LENGTH_SHORT).show();
    }

    /** 「跟隨本機方向」現在是開著的嗎（給選單畫勾用）。 */
    private boolean followEnabled() {
        return orientationSync == null || orientationSync.isEnabled();
    }

    /**
     * 把<b>對方的實體螢幕</b>關掉或開回來，影像串流照常。
     *
     * <p>為什麼要做這件事：手機放在架上給人遙控時，螢幕整晚亮著既耗電又會烙印，
     * 而且別人走過去就看到內容。scrcpy 的 {@code --turn-screen-off} 就是同一個機制
     * （{@code TYPE_SET_DISPLAY_POWER}）—— 面板不亮，但 SurfaceFlinger 仍在合成，
     * 所以我們這邊的畫面完全不受影響，觸控也照樣送得進去。
     *
     * <p>不跟「電源鍵」共用：電源鍵是真的把裝置送進休眠（鎖屏、斷網路都可能），
     * 那會讓遠端連線自己斷掉。這裡只動顯示器的電源狀態。
     *
     * <p>參數是<b>目標狀態</b>而不是切換：重複送同一個值沒有副作用，
     * 所以不需要（也不該）用本機的欄位去猜對方現在是亮是暗 —— 猜錯就會出現
     * 「按了『關閉』結果更亮」這種事。
     */
    private void setHostScreen(boolean on) {
        ScrcpySession s = session;
        if (s == null || !s.isRunning()) {
            Toast.makeText(this, R.string.mirror_connecting, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!s.setScreenPowerMode(on)) {
            // 連線剛好斷了／佇列滿了：訊息根本沒送出去
            Toast.makeText(this, R.string.msg_need_reconnect, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, on ? R.string.msg_host_screen_on
                : R.string.msg_host_screen_off, Toast.LENGTH_SHORT).show();
        DiagLog.i(TAG, "對方螢幕：" + (on ? "開啟" : "關閉（鏡像繼續）"));
        showBar();
    }

    /**
     * 切換<b>對方喇叭</b>要不要出聲。
     *
     * <p><b>這顆按鈕管的是遠端，不是本機。</b>
     * <ul>
     *   <li>「有聲」＝ 對方喇叭照樣放（{@code audio_dup=true}），兩邊都聽得到</li>
     *   <li>「靜音」＝ 對方喇叭安靜（{@code audio_dup=false}），<b>我這邊照樣聽得到</b></li>
     * </ul>
     * 本機要靜音請按自己的音量鍵 —— 這也是為什麼這裡<b>不</b>去動本機的
     * {@code AudioTrack}：以前那樣做的結果是「按了靜音，對方卻還在放」，
     * 而且看不出來按鈕到底控制了誰。
     *
     * <p>實作上「靜音」不能靠調對方的音量：播放擷取是<b>音量之後</b>的訊號，
     * 對方調 0 我們這邊也會全零（實測 RMS 601 → 0）。只能換 {@code audio_dup}
     * 重開音訊 session（約 1 秒，畫面不受影響，見 {@link AudioSession}）。
     *
     * <p><b>第三種狀態（選單裡的「不要聲音」）</b>：那時根本沒有音訊 session，
     * 這顆按鈕就變成「接回聲音」——按下去把 session 補起來，<b>畫面完全不動</b>。
     * 以前這裡是直接彈「對方沒有送出音訊」然後什麼都不做，結果就是使用者選了
     * 「不要聲音」之後，對方的喇叭從此誰都管不到（回報：「選不要聲音會影響
     * 控制遠端 host 聲音」）。現在三種狀態都接得回來。
     */
    private void toggleSound() {
        showBar();
        ScrcpySession s = session;
        if (s == null) {
            Toast.makeText(this, R.string.mirror_no_audio, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!s.isAudioOn()) {
            // 「不要聲音」狀態 → 接回來（只補音訊那條 session，影像不動）
            applyAudioMode(Settings.AUDIO_BOTH);
            Toast.makeText(this, R.string.mirror_audio_back, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!s.isAudioActive()) {
            // 對方 < Android 13、或擷取被拒絕時就是這個狀態。按了沒反應最難查，
            // 所以這裡明講。
            Toast.makeText(this, R.string.mirror_no_audio, Toast.LENGTH_SHORT).show();
            return;
        }
        hostAudible = !hostAudible;
        s.setHostAudible(hostAudible);
        Settings.setAudioMode(this, hostAudible ? Settings.AUDIO_BOTH : Settings.AUDIO_CLIENT_ONLY);
        updateSoundButton();
        Toast.makeText(this, hostAudible ? R.string.mirror_host_audible
                : R.string.mirror_host_muted, Toast.LENGTH_SHORT).show();
    }

    private void updateSoundButton() {
        if (soundButton == null) return;
        ScrcpySession s = session;
        if (s != null && !s.isAudioOn()) {
            // 第三種狀態：這條連線沒有音訊。按下去是「接回聲音」，不是切換靜音。
            soundButton.setText(R.string.mirror_btn_sound_off_all);
            soundButton.setTextColor(0x66FFFFFF);
            return;
        }
        boolean active = s != null && s.isAudioActive();
        soundButton.setText(hostAudible ? R.string.mirror_btn_sound_on : R.string.mirror_btn_sound_off);
        // 沒有音訊串流時整顆變暗 —— 讓「按了會彈提示」這件事看得出來
        soundButton.setTextColor(!active ? 0x55FFFFFF : (hostAudible ? 0xFF7FD4A0 : 0xFFAAAAAA));
    }

    /**
     * 套用音訊模式（選單、工具列按鈕、測試入口都走這裡）。
     *
     * <p><b>不會重連影像。</b>音訊本來就是獨立的一條 session，開／關／換
     * {@code audio_dup} 都只動那一條 —— 以前「不要聲音」走的是
     * {@code restartSession()}，畫面會黑一下再回來，而且斷線期間對方播放器
     * 還會被路由切換打成暫停。
     */
    private void applyAudioMode(int mode) {
        Settings.setAudioMode(this, mode);
        hostAudible = mode == Settings.AUDIO_BOTH;
        ScrcpySession s = session;
        if (s != null && s.isRunning()) {
            if (mode == Settings.AUDIO_OFF) {
                s.setAudioOn(false, true);
            } else {
                s.setAudioOn(true, hostAudible);
            }
        }
        updateSoundButton();
        DiagLog.i(TAG, "音訊模式 → " + mode + "（畫面 session 未重建）");
    }

    // ================================================================== 選單

    /**
     * 工具列最右邊的「設置」。
     *
     * <p>為什麼不把這些都做成按鈕：工具列以前塞到十二顆，在 1080px 上每顆只剩
     * 90px，字被擠成兩行、工具列變高還蓋住畫面。這裡面全部都是「偶爾用一次」
     * 的東西，收進選單才不會每天擋在眼前。
     *
     * <p>選單分兩段（API 28 以上會畫分隔線）：
     * <ol>
     *   <li><b>對對方動手</b>：旋轉／跟隨本機方向／音量／電源／關閉螢幕省電／
     *       截圖／錄影 —— 以前是五顆常駐按鈕</li>
     *   <li><b>本機這端的設定</b>：貼上剪貼簿／畫質／常亮／音訊／重連</li>
     * </ol>
     *
     * <p>注意「送文字」那顆已經拿掉了：它要使用者在一個小框裡打字再按送出，
     * 打完還得看著字跑過去 —— 既然都是在本機打，直接讓本機輸入法打到對方就好
     * （工具列的「鍵盤」），要貼東西就走「貼上本機剪貼簿」。
     */
    private void showMoreMenu(View anchor) {
        showBar();
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(GROUP_REMOTE, MENU_ROTATE, 0, R.string.mirror_btn_rotate);
        menu.getMenu().add(GROUP_REMOTE, MENU_FOLLOW, 1, R.string.menu_follow)
                .setCheckable(true)
                .setChecked(followEnabled());
        menu.getMenu().add(GROUP_REMOTE, MENU_VOLUME, 2, R.string.menu_volume);
        menu.getMenu().add(GROUP_REMOTE, MENU_POWER, 4, R.string.mirror_btn_power);
        // 一個勾選項，勾＝把對方的面板關掉（省電，鏡像照常）。
        // 以前是「關閉／開啟」兩項並列，理由是怕本機狀態和對方不同步時，
        // 單一顆切換鈕會「按了更暗」。但這裡送的一律是<b>絕對值</b>
        // （勾→送關、取消→送開），根本不會有「切換錯方向」這回事；
        // 兩項並列反而讓人每次都要先讀一遍才知道該按哪個。
        menu.getMenu().add(GROUP_REMOTE, MENU_SCREEN_OFF, 5, R.string.menu_screen_off)
                .setCheckable(true)
                .setChecked(Settings.hostScreenOff(this));
        // 截圖放在「對對方動手」那一段：它抓的是<b>對方</b>的畫面（原生解析度、
        // 不含我們的工具列），不是「我這邊看到什麼」。
        menu.getMenu().add(GROUP_REMOTE, MENU_SCREENSHOT, 7, R.string.menu_screenshot);
        // 錄影跟截圖同一段、同一個語意：錄的是對方那塊螢幕，不是本機視窗。
        // 標題會隨狀態換（錄影中 → 停止錄影），不然錄完不知道要按哪裡停。
        boolean recording = recorder != null && recorder.isRecording();
        menu.getMenu().add(GROUP_REMOTE, MENU_RECORD, 8,
                recording ? R.string.menu_record_stop : R.string.menu_record);

        menu.getMenu().add(GROUP_SETTINGS, MENU_PASTE, 8, R.string.menu_paste);
        menu.getMenu().add(GROUP_SETTINGS, MENU_QUALITY, 9, R.string.menu_quality);
        menu.getMenu().add(GROUP_SETTINGS, MENU_AWAKE, 10, R.string.menu_stay_awake)
                .setCheckable(true).setChecked(Settings.stayAwake(this));
        menu.getMenu().add(GROUP_SETTINGS, MENU_AUDIO, 11, R.string.menu_audio);
        menu.getMenu().add(GROUP_SETTINGS, MENU_RECONNECT, 12, R.string.menu_reconnect);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 兩段之間畫一條線，免得「電源」和「貼上」黏在一起看不出分界。
            menu.getMenu().setGroupDividerEnabled(true);
        }

        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_ROTATE:
                    rotateHost();
                    return true;
                case MENU_FOLLOW:
                    // 先切換，再把勾回填成「實際狀態」—— 不依賴點擊自動翻轉，
                    // 這樣就算 orientationSync 還是 null（連線中）也不會顯示錯的勾。
                    toggleFollow();
                    item.setChecked(followEnabled());
                    return true;
                case MENU_VOLUME:
                    showVolumeDialog();
                    return true;
                case MENU_POWER:
                    // 這裡刻意用 KEYCODE_POWER（跟實體電源鍵一樣，會休眠、鎖屏），
                    // 和上面「關閉螢幕省電」不同：需要真的把對方叫醒／鎖屏時用這個。
                    onBarButton(s -> s.sendKey(26));       // KEYCODE_POWER
                    return true;
                case MENU_SCREEN_OFF: {
                    // 勾＝關閉省電，而且<b>記住</b>（下次連上自動套用）。
                    // 送出去的永遠是絕對值：勾→關、取消→開，所以就算上一次
                    // 來不及開回來，取消勾選一定能把對方救回亮著的狀態。
                    boolean want = !Settings.hostScreenOff(this);
                    Settings.setHostScreenOff(this, want);
                    setHostScreen(!want);
                    item.setChecked(want);
                    return true;
                }
                case MENU_SCREENSHOT:
                    takeScreenshot();
                    return true;
                case MENU_RECORD:
                    toggleRecord();
                    return true;
                case MENU_PASTE:
                    pasteLocalClipboard();
                    return true;
                case MENU_QUALITY:
                    showQualityDialog();
                    return true;
                case MENU_AWAKE:
                    boolean awake = !Settings.stayAwake(this);
                    Settings.setStayAwake(this, awake);
                    item.setChecked(awake);
                    Toast.makeText(this, R.string.msg_need_reconnect, Toast.LENGTH_SHORT).show();
                    return true;
                case MENU_AUDIO:
                    showAudioDialog();
                    return true;
                case MENU_RECONNECT:
                    reconnectAttempts = 0;
                    restartSession("使用者要求重連");
                    return true;
                default:
                    return false;
            }
        });
        // 選單開著時別讓工具列按時收起來：PopupMenu 掛在工具列按鈕上，
        // 按鈕一變成 GONE，選單也會跟著消失 —— 使用者還在讀就被抽走，
        // 而且手指抬起來時會變成「點到下面的畫面」（實測開著選單 5 秒後就點不到）。
        ui.removeCallbacks(hideBar);
        menu.setOnDismissListener(m -> showBar());
        menu.show();
    }

    /** 選單第一段：對對方動手。 */
    private static final int GROUP_REMOTE = 1;
    /** 選單第二段：本機這端的設定。 */
    private static final int GROUP_SETTINGS = 2;

    private static final int MENU_ROTATE = 1;
    private static final int MENU_FOLLOW = 2;
    private static final int MENU_VOLUME = 3;
    private static final int MENU_POWER = 5;
    private static final int MENU_PASTE = 6;
    private static final int MENU_QUALITY = 7;
    private static final int MENU_AWAKE = 8;
    private static final int MENU_AUDIO = 9;
    private static final int MENU_RECONNECT = 10;
    private static final int MENU_SCREEN_OFF = 11;
    private static final int MENU_SCREEN_ON = 12;
    private static final int MENU_SCREENSHOT = 13;
    private static final int MENU_RECORD = 14;

    // ================================================================== 音量

    /**
     * 對方音量的調整面板。
     *
     * <p><b>為什麼不是選單裡的兩顆「音量＋／音量－」</b>：PopupMenu 點一下就會關掉，
     * 所以調 5 格要把選單開 5 次。音量這種東西本來就是「按住連續調」的操作，
     * 一次一格根本不能用。
     *
     * <p>所以做成一個不會自己關掉的面板：點一下走一格，<b>按住就連續走</b>
     * （400 ms 後開始，每 120 ms 一格），放手就停。手指離開按鈕之後面板還在，
     * 可以再按、也可以直接按旁邊的「關閉」。
     *
     * <p>不顯示絕對音量：要拿到對方真實音量得去讀 {@code dumpsys audio}，
     * 每個 ROM 的格式都不一樣，而且我們送的是相對的按鍵事件（{@code KEYCODE_VOLUME_UP}），
     * 兩邊對不上反而更 confusing。看到畫面／聽到聲音的變化就是回饋。
     */
    private void showVolumeDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        // 兩個按鈕共用同一個「正在連發的那個任務」：換手按另一顆時，
        // 舊的那條要停掉，不然兩邊會同時連發。
        final Runnable[] active = new Runnable[1];

        Button down = volumeButton("−", 25, active);     // KEYCODE_VOLUME_DOWN
        Button up = volumeButton("＋", 24, active);      // KEYCODE_VOLUME_UP

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(6), 0, dp(6), 0);
        box.addView(down, lp);
        box.addView(up, new LinearLayout.LayoutParams(lp));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.menu_volume)
                .setView(box)
                .setMessage(R.string.volume_hint)
                .setPositiveButton(R.string.close, null)
                .create();
        // 面板關掉時一定要把連發停掉 —— 否則那個 Runnable 會一直往對方送音量鍵，
        // 使用者已經離開畫面了還在調音量（這種事只有自己踩過才會記得加）。
        dlg.setOnDismissListener(d -> {
            if (active[0] != null) {
                ui.removeCallbacks(active[0]);
                active[0] = null;
            }
        });
        dlg.show();
    }

    /**
     * 一顆會連發的音量鈕。
     *
     * <p>用 {@link View.OnTouchListener} 而不是 {@code setOnClickListener}：
     * 點擊事件要等手指抬起來才來，而這裡要的是「按下去就送第一格」。
     *
     * @param active 目前正在連發的任務（兩顆按鈕共用，換手時要停掉舊的）
     */
    private Button volumeButton(String label, int keyCode, Runnable[] active) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(0, dp(10), 0, dp(10));

        // 連發的節奏：先送一格（手指一碰就有反應），停 400 ms 還沒放開才開始連發，
        // 之後每 120 ms 一格。400 ms 是「刻意按一下」和「我要一直調」的分界。
        final Runnable[] tick = new Runnable[1];
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (active[0] != null) {
                        ui.removeCallbacks(active[0]);
                        active[0] = null;
                    }
                    sendVolume(keyCode);
                    tick[0] = new Runnable() {
                        @Override
                        public void run() {
                            sendVolume(keyCode);
                            ui.postDelayed(this, REPEAT_INTERVAL_MS);
                        }
                    };
                    active[0] = tick[0];
                    ui.postDelayed(tick[0], REPEAT_DELAY_MS);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (tick[0] != null) {
                        ui.removeCallbacks(tick[0]);
                        if (active[0] == tick[0]) active[0] = null;
                        tick[0] = null;
                    }
                    v.performClick();       // 無障礙服務要知道這裡「被按了」
                    return true;
                default:
                    return false;
            }
        });
        return b;
    }

    private void sendVolume(int keyCode) {
        ScrcpySession s = session;
        if (s == null || !s.isRunning()) {
            Toast.makeText(this, R.string.mirror_connecting, Toast.LENGTH_SHORT).show();
            return;
        }
        s.sendKey(keyCode);
        // 刻意不在這裡 showBar()：連發時每一格都叫一次工具列，等於在畫面
        // 正在解碼的時候插一堆 layout，實測會把連發的節奏拖慢一倍。
        // 面板本身就是現在該看的東西，工具列等面板關掉再說。
    }

    /** 按住多久之後開始連發。 */
    private static final long REPEAT_DELAY_MS = 400;
    /**
     * 連發的間隔。
     *
     * <p>100 ms 而不是更短：主執行緒同時在解 60fps 的鏡像，postDelayed 會跟著延遲，
     * 實測 120 ms 排出來大約 250 ms 才真的跑一次。再往下調只是堆積任務，
     * 送出去的速度不會變快，放手之後還會多送好幾格。
     */
    private static final long REPEAT_INTERVAL_MS = 100;

    // ================================================================== 打字

    /**
     * 開／關本機輸入法。
     *
     * <p>用法就是「像本地」：先在對方螢幕上點一下輸入框（把焦點放到對方那邊），
     * 再按這顆鍵盤，接著就用本機的注音／拼音／Gboard 打字 —— 打完的字直接出現在
     * 對方那個輸入框裡，不必再按「送出」。
     */
    private void toggleKeyboard() {
        showBar();
        if (imeSink == null) return;
        InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return;

        if (keyboardOn) {
            // 收鍵盤前先把還沒送出的字送掉 —— 使用者很可能打完就直接收鍵盤，
            // 那時輸入法不一定會 commit。
            cancelPendingFlush();
            flushSink();
            imm.hideSoftInputFromWindow(imeSink.getWindowToken(), 0);
            imeSink.clearFocus();
            keyboardOn = false;
            DiagLog.i(TAG, "鍵盤：關");
            updateKeyboardButton();
            return;
        }

        imeSink.setText("");
        imeWord.setLength(0);
        imeCompStart = -1;
        imeSink.requestFocus();
        keyboardOn = true;
        updateKeyboardButton();

        // 關鍵：requestFocus() 之後<b>不能在同一個 message 裡</b>就叫輸入法。
        // 這一刻 View 的 InputConnection 還沒建立、window 的焦點也還沒生效，
        // showSoftInput() 會直接回 false（而且畫面上什麼都不會發生）。
        // 排到下一個 frame 再叫。
        imeSink.postDelayed(() -> tryShowIme(imm, 0), 60L);
    }

    /**
     * 叫出本機輸入法，失敗就重試 —— 因為 {@code showSoftInput()} 的回傳值不可靠。
     *
     * <p>它回 false 有兩種完全相反的意思：「沒叫出來」和「本來就已經開著」。
     * 所以這裡不看回傳值，看<b>畫面實際被吃掉多少高度</b>（輸入法一開，
     * window 的可見區域一定會從底部縮上去）。只有真的沒開才繼續重試。
     */
    private void tryShowIme(InputMethodManager imm, int attempt) {
        if (!keyboardOn || imeSink == null) return;

        boolean shown = imm.showSoftInput(imeSink, InputMethodManager.SHOW_IMPLICIT);
        boolean visible = imeVisible();
        DiagLog.i(TAG, "鍵盤：第 " + (attempt + 1) + " 次 showSoftInput=" + shown
                + "，hasFocus=" + imeSink.hasFocus() + "，實際可見=" + visible);
        if (visible) {
            // 第一次開鍵盤時提一句：很多人會以為「按了鍵盤就能打字」，
            // 但對方的焦點其實還沒放在任何輸入框上，打了字也不知道去哪。
            if (!keyboardHintShown) {
                keyboardHintShown = true;
                Toast.makeText(this, R.string.msg_keyboard_hint, Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (attempt == 0) {
            // 給輸入法一點動畫時間再確認一次
            imeSink.postDelayed(() -> tryShowIme(imm, 1), 200L);
        } else if (attempt == 1) {
            // 有些機型（Samsung 部分版本）對 SHOW_IMPLICIT 完全不理，只能強制顯示。
            // 這裡已經確認「真的沒開」，所以 toggle 不會誤把開著的收起來。
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            DiagLog.i(TAG, "鍵盤：退回 toggleSoftInput(SHOW_FORCED)");
            imeSink.postDelayed(() -> tryShowIme(imm, 2), 300L);
        }
        // attempt >= 2 就放棄，不再騷擾輸入法
    }

    /**
     * 輸入法是不是真的顯示了：看 window 可見區域被底部吃掉多少。
     *
     * <p>注意<b>不能</b>用 {@code rootView.getHeight()} 當基準 —— 我們用的是
     * {@code SOFT_INPUT_ADJUST_RESIZE}，輸入法一開 DecorView 自己也會被縮，
     * 兩者相減永遠是 0，會誤判成「沒開」。所以要拿螢幕的<b>真實</b>高度來比。
     */
    private boolean imeVisible() {
        View root = getWindow().getDecorView();
        android.graphics.Rect r = new android.graphics.Rect();
        root.getWindowVisibleDisplayFrame(r);

        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);

        int covered = dm.heightPixels - r.bottom;
        // 導航列（手勢或三鍵）最多吃掉 ~50dp，門檻設 120dp 不會誤判
        boolean visible = covered > dp(120);
        DiagLog.d(TAG, "鍵盤：螢幕 " + dm.heightPixels + "，可見底 " + r.bottom
                + "，被吃 " + covered + "px → " + visible);
        return visible;
    }

    private void updateKeyboardButton() {
        if (keyboardButton == null) return;
        keyboardButton.setTextColor(keyboardOn ? 0xFF7FD4A0 : 0xFFFFFFFF);
    }

    /**
     * 把一段文字送到對方 —— <b>依內容選路徑</b>。
     *
     * <ul>
     *   <li>純 ASCII：走 {@code TYPE_INJECT_TEXT}，伺服器逐字轉成按鍵事件。
     *       這條不會動到剪貼簿，也不會打斷對方正在複製的東西。</li>
     *   <li>中文／emoji：按鍵事件表達不了（那是輸入法的產物），只能把字放進對方
     *       剪貼簿再請對方貼上。</li>
     * </ul>
     */
    private void sendTextToRemote(String text) {
        if (text == null || text.isEmpty()) return;
        ScrcpySession s = session;
        if (s == null || !s.isRunning()) {
            DiagLog.w(TAG, "送字：session 沒跑，丟棄 " + text.length() + " 字");
            return;
        }

        boolean ascii = isPlainAscii(text);
        DiagLog.i(TAG, "送字：" + text.length() + " 字，ascii=" + ascii);
        if (ascii) {
            s.sendText(text);
        } else {
            pasteToRemote(text);
        }
    }

    /** 全部是可列印 ASCII 或換行才算「可以用按鍵打出來」。 */
    private static boolean isPlainAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\t') continue;
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    /** 把文字塞進對方剪貼簿並請對方貼上（阻塞等 ACK，所以丟到單執行緒背景）。 */
    private void pasteToRemote(String text) {
        if (text == null || text.isEmpty()) return;
        final String payload = text;
        if (inputBg == null) return;
        inputBg.execute(() -> {
            ScrcpySession s = session;
            if (s == null || !s.isRunning()) return;
            DiagLog.i(TAG, "貼上到對方：" + payload.length() + " 字");
            boolean ok = s.sendClipboard(payload, true);
            ui.post(() -> {
                if (!ok) {
                    Toast.makeText(this, R.string.msg_paste_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /**
     * 「貼上本機剪貼簿」。
     *
     * <p>剪貼簿本來就雙向同步（{@link ClipboardSync}），所以多數時候對方已經有內容、
     * 長按選「貼上」就行。這顆按鈕是給「懶得長按」用的：直接把本機內容送過去並貼上。
     */
    private void pasteLocalClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            Toast.makeText(this, R.string.msg_clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(this, R.string.msg_clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence cs = clip.getItemAt(0).coerceToText(this);
        String text = cs == null ? "" : cs.toString();
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.msg_clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        pasteToRemote(text);
        Toast.makeText(this, getString(R.string.msg_paste_sent, text.length()),
                Toast.LENGTH_SHORT).show();
    }

    /**
     * 截圖：把對方<b>原生解析度</b>的畫面存到本機相簿。
     *
     * <p><b>為什麼另開一條連線</b>：{@link AdbClient#screenshot(File)} 會建立 SYNC 通道，
     * 而 SYNC 通道在大量寫入後可能把整條 {@code AdbConnection} 弄成半死狀態。
     * 鏡像那條連線正扛著 60fps 的影像與控制訊息，不能拿它來賭。
     * 多一次 TLS 握手（區網約 200~400 ms）換「鏡像絕對不會因為截圖而斷」是划算的。
     *
     * <p><b>為什麼在背景做</b>：screencap 本身 1~2 秒，加上 3~4 MB 的下載，
     * 放主執行緒就是整個畫面凍住。
     */
    private void takeScreenshot() {
        Toast.makeText(this, R.string.shot_started, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            AdbClient client = null;
            File png = null;
            try {
                png = new File(getCacheDir(), "shot.png");
                client = new AdbClient(this, host, port);
                client.screenshot(png);
                final String where = saveToPictures(png);
                ui.post(() -> Toast.makeText(this, getString(R.string.shot_saved, where),
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                DiagLog.w(TAG, "截圖失敗", e);
                final String msg = AdbClient.describeError(e);
                ui.post(() -> Toast.makeText(this, getString(R.string.shot_failed, msg),
                        Toast.LENGTH_LONG).show());
            } finally {
                if (client != null) client.close();
                //noinspection ResultOfMethodCallIgnored
                if (png != null) png.delete();
            }
        }, "omniadb-screenshot").start();
    }

    /**
     * 存到系統相簿（{@code Pictures/OmniADB/}）。
     *
     * <p>Android 10 以後走 MediaStore，<b>不需要任何儲存權限</b> ——
     * 這是「存一張圖」最正確的做法。10 以前沒有 {@code RELATIVE_PATH}，
     * 要寫到相簿就得要 {@code WRITE_EXTERNAL_STORAGE}，為了截圖去要這個權限
     * 並不划算，所以退回 App 專屬目錄（免權限，但不在相簿裡）。
     *
     * @return 給使用者看的位置字串
     */
    private String saveToPictures(File png) throws IOException {
        String name = "OmniADB_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/OmniADB");
            // IS_PENDING：寫完才讓相簿看到，否則會先出現一張殘缺的圖
            v.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("無法在相簿建立項目");
            try (InputStream is = new FileInputStream(png);
                 OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IOException("無法寫入相簿");
                copy(is, os);
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, v, null, null);
            return "Pictures/OmniADB/" + name;
        }

        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "OmniADB");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("無法建立目錄 " + dir);
        File out = new File(dir, name);
        try (InputStream is = new FileInputStream(png);
             OutputStream os = new FileOutputStream(out)) {
            copy(is, os);
        }
        return out.getAbsolutePath();
    }

    private static void copy(InputStream is, OutputStream os) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
    }

    // ================================================================== 錄影

    /**
     * 開／停錄影。
     *
     * <p><b>為什麼要主動送一次 {@code TYPE_RESET_VIDEO}：</b>MP4 的第一個樣本必須是
     * 關鍵幀（IDR），而 scrcpy 的編碼器 GOP 是 10 秒 —— 按下錄影的當下多半在某個
     * GOP 的中間。不催它的話，畫面要嘛開頭花屏，要嘛得空等最多 10 秒才真的開始錄。
     * 送了之後對方會立刻重吐 config + 一個 IDR，通常 200ms 內就進入錄影狀態。
     *
     * <p>跟截圖不同，錄影<b>不另開連線</b>：它吃的是已經在解碼的那條碼流，
     * 開新連線反而拿不到同一份畫面。
     */
    private void toggleRecord() {
        if (recorder == null) return;

        if (recorder.isRecording()) {
            recorder.stop();        // 收檔在背景，完成後 onRecordStopped 會回報
            return;
        }

        ScrcpySession s = session;
        if (s == null || !s.isRunning()) {
            Toast.makeText(this, R.string.record_need_connection, Toast.LENGTH_SHORT).show();
            return;
        }
        recorder.start();
        if (!recorder.isRecording()) {
            // 上一段還在收檔（按停止後立刻又按錄影）：佇列裡還有東西要寫完，
            // 這時硬開新檔會把那一段的結尾吃掉。老實說一聲，使用者再按一次就好。
            Toast.makeText(this, R.string.record_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        s.requestVideoReset();
        setRecordingIndicator(true);
        Toast.makeText(this, R.string.record_started, Toast.LENGTH_SHORT).show();
    }

    /**
     * 錄影結束（正常停止、斷線、或出錯）。在<b>主執行緒</b>被呼叫。
     *
     * @param where   存到哪裡；完全沒錄到東西時是 {@code null}
     * @param message 錯誤訊息；正常結束時是 {@code null}
     */
    private void onRecordStopped(String where, String message) {
        setRecordingIndicator(false);
        if (isFinishing() || isDestroyed()) return;
        if (message != null) {
            Toast.makeText(this, getString(R.string.record_failed, message),
                    Toast.LENGTH_LONG).show();
        } else if (where != null) {
            Toast.makeText(this, getString(R.string.record_stopped, where),
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, R.string.record_empty, Toast.LENGTH_SHORT).show();
        }
    }

    private void setRecordingIndicator(boolean on) {
        if (recordDot == null) return;
        recordDot.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /** 畫質三檔。改完直接重連（伺服器參數只能在啟動時給）。 */
    private void showQualityDialog() {
        final int[] values = {Settings.QUALITY_LOW, Settings.QUALITY_STANDARD, Settings.QUALITY_HIGH};
        int current = Settings.quality(this);
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_quality_title)
                .setSingleChoiceItems(R.array.quality_items, checked, (d, which) -> {
                    d.dismiss();
                    if (values[which] == Settings.quality(this)) return;
                    Settings.setQuality(this, values[which]);
                    restartSession("畫質切換");
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 音訊三種模式。
     *
     * <p>為什麼是「三選一」而不是一個「不要聲音」的勾選項：勾選項只有兩種狀態，
     * 但這裡實際上有三種結果（見 {@link Settings#AUDIO_BOTH} 的表）。以前做成勾選項時，
     * 勾起來就等於「沒有音訊 session」，於是「對方喇叭要不要出聲」這件事連帶失去控制 ——
     * 使用者回報的正是這個：「點選不要聲音會影響控制遠端 host 聲音」。
     *
     * <p>換模式<b>不重連影像</b>（{@link #applyAudioMode}），所以調聲音不會黑畫面。
     */
    private void showAudioDialog() {
        final int[] values = {Settings.AUDIO_BOTH, Settings.AUDIO_CLIENT_ONLY, Settings.AUDIO_OFF};
        int current = Settings.audioMode(this);
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_audio_title)
                .setSingleChoiceItems(R.array.audio_items, checked, (d, which) -> {
                    d.dismiss();
                    if (values[which] == Settings.audioMode(this)) return;
                    applyAudioMode(values[which]);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ================================================================== 重連
    /** 自動重連次數上限。三次之後就別再試 —— 通常代表對方關了無線偵錯或不在同一個網段。 */
    private static final int MAX_RECONNECT = 3;

    private int reconnectAttempts;

    /**
     * 重建連線（換畫質、換音訊設定、手動重連都走這裡）。
     *
     * <p>為什麼要延遲：scrcpy 伺服器的 abstract socket 名字是固定的
     * （{@code localabstract:scrcpy}），舊的 {@code app_process} 從 SIGHUP 到真的退出
     * 有幾百毫秒。立刻重開會撞上還沒死透的舊名字 —— 症狀是「連上了但沒有資料」。
     * 音訊 session 那邊踩過同一個坑（{@code AUDIO_RESTART_GAP_MS}）。
     */
    private void restartSession(String reason) {
        if (stopped || isFinishing()) return;
        DiagLog.i(TAG, "重建連線：" + reason);
        status.setText(R.string.mirror_restarting);
        status.setVisibility(View.VISIBLE);
        closeSession();
        // 舊連線的旋轉等待已經沒有意義。留著的話 pendingRotation 不為 null，
        // 新連線的 syncNow() 會被「已經在等對方，別插手」擋掉，方向就永遠不修正。
        ui.removeCallbacks(hostWaitTimeout);
        pendingRotation = null;
        deferredRotation = null;
        sessionStarting = false;
        ui.postDelayed(() -> {
            if (stopped || isFinishing()) return;
            Surface s = surfaceView.getHolder().getSurface();
            if (s == null || !s.isValid()) {
                showError(getString(R.string.mirror_failed, "Surface 已失效"));
                return;
            }
            startSession(s);
        }, 1200);
    }

    /**
     * 斷線後自動重連（退避 1s／2s／3s）。
     *
     * <p>為什麼需要：Wi-Fi 換手、對方 adbd 重啟、訊號抖一下都會讓影像串流斷掉。
     * 以前只會顯示「連線已結束」，使用者得退出再進來（還要重新掃描一次）。
     * 手機放在架上用的時候，這個「重進」的動作很煩。
     */
    private void scheduleReconnect() {
        if (stopped || isFinishing()) return;
        if (reconnectAttempts >= MAX_RECONNECT) {
            showError(getString(R.string.mirror_reconnect_failed));
            return;
        }
        final int attempt = ++reconnectAttempts;
        DiagLog.i(TAG, "連線中斷，第 " + attempt + " 次重連");
        status.setText(getString(R.string.mirror_reconnecting, attempt));
        status.setVisibility(View.VISIBLE);
        if (bar != null) bar.setVisibility(View.VISIBLE);

        closeSession();
        // 舊連線的旋轉等待已經沒有意義。留著的話 pendingRotation 不為 null，
        // 新連線的 syncNow() 會被「已經在等對方，別插手」擋掉，方向就永遠不修正。
        ui.removeCallbacks(hostWaitTimeout);
        pendingRotation = null;
        deferredRotation = null;
        sessionStarting = false;
        ui.postDelayed(() -> {
            if (stopped || isFinishing()) return;
            Surface s = surfaceView.getHolder().getSurface();
            if (s == null || !s.isValid()) {
                showError(getString(R.string.mirror_reconnect_failed));
                return;
            }
            startSession(s);
        }, 1000L * attempt);
    }

    // ================================================================== 實體鍵盤

    /**
     * 實體鍵盤直通：本機接的鍵盤／遙控器按鍵直接送到對方。
     *
     * <p>KeyEvent 的 keycode 在兩台 Android 上是同一套常數，所以不需要對照表。
     * 但<b>系統鍵不能轉發</b>：BACK 是使用者離開鏡像的方式、HOME／多工／電源／音量
     * 是這台手機自己的功能，轉出去會變成「按了兩件事」。
     *
     * <p>沒有這條路的話，對方沒有輸入法時完全打不了字（點輸入框連鍵盤都不會跳）。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        ScrcpySession s = session;
        if (s == null || !s.isRunning()) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        if (isLocalOnlyKey(code)) return super.dispatchKeyEvent(event);
        s.sendHardwareKey(event.getAction(), code, event.getRepeatCount(), event.getMetaState());
        return true;
    }

    private static boolean isLocalOnlyKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_UNKNOWN:
                return true;
            default:
                return false;
        }
    }

    private Button barButton(int textRes, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(textRes);
        b.setAllCaps(false);
        // 七顆按鈕平分 1080px ＝ 每顆 154px，10sp 一定放得下；留著 10sp 是為了
        // 窄螢幕（720px）與英文介面的長字（Settings）也不會折行。
        // 以前十二顆時每顆只剩 90px，11sp 的「音量+」在三星上會折成兩行（截圖驗證過），
        // 折行後工具列變高、蓋住更多畫面 —— 那也是後來把不常用的收進選單的原因。
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        b.setSingleLine(true);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(0x00000000);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(1), dp(8), dp(1), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private void showBar() {
        if (bar == null) return;
        bar.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideBar);
        ui.postDelayed(hideBar, BAR_AUTO_HIDE_MS);
    }

    private final Runnable hideBar = () -> {
        if (bar != null) bar.setVisibility(View.GONE);
    };

    private void hideBarNow() {
        ui.removeCallbacks(hideBar);
        if (bar != null) bar.setVisibility(View.GONE);
    }

    private void goFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ================================================================== 連線

    private void startSession(Surface surface) {
        if (sessionStarting || session != null) return;
        sessionStarting = true;
        showBar();

        // 新連線＝新的方向起點，必須在 session 開跑「之前」清（見 OrientationSync#reset）。
        // 不能挪到 onReady 才清：ScrcpySession.start() 是「先 onVideoSize、後 onReady」，
        // 而 onVideoSize 才是 syncNow 的觸發點 —— 兩者都只是 post 到主執行緒，順序固定。
        // 在 onReady 才清，等於「第一次對齊已經拿著上一輪的記錄跑完、而且被擋掉了」，
        // 清完也沒人再送，症狀就是重連後非得手動翻一次手機。
        if (orientationSync != null) orientationSync.reset();

        // 開場前就把對方鎖到本機現在的方向。伺服器一啟動就照當下的顯示器方向編碼、
        // 尺寸也寫進 session meta —— 晚一步（連上才送指令）就會看到「先一條橫的、
        // 1~2 秒後才轉正」。關掉方向同步時傳 -1：尊重對方的自動旋轉。
        final int lockRotation = (orientationSync != null && orientationSync.isEnabled())
                ? currentClientRotation() : -1;

        starter = new Thread(() -> {
            ScrcpySession s = new ScrcpySession(getApplicationContext(), host, port,
                    new ScrcpySession.Listener() {
                        @Override
                        public void onVideoSize(int width, int height) {
                            ui.post(() -> {
                                if (height <= 0) return;
                                float aspect = (float) width / height;

                                // 是不是「我剛才請對方轉、它現在轉好了」？
                                Integer target = pendingRotation;
                                boolean ready = target != null
                                        && OrientationSync.isLandscapeRotation(target)
                                        == (width > height);

                                if (ready) {
                                    ui.removeCallbacks(hostWaitTimeout);
                                    pendingRotation = null;
                                    if (rotateClient(target)) {
                                        // 視窗先轉，比例等 onConfigurationChanged 再套。
                                        // 反過來做的話，中間會有一幀「新比例的畫面
                                        // 塞在舊方向的視窗裡」—— 看起來就是閃一下。
                                        pendingAspect = aspect;
                                    } else if (surfaceView != null) {
                                        surfaceView.setAspect(aspect);
                                    }
                                } else {
                                    // 之前「等不到對方轉」而壓著沒轉的，現在它真的轉了 —— 補上。
                                    // 沒有這一段，轉得慢的 ROM（實測 1.5 秒）會被誤判成
                                    // 「轉不動」，畫面就一直卡在舊方向，直到下次擺動才修好。
                                    Integer deferred = deferredRotation;
                                    boolean nowReady = deferred != null
                                            && OrientationSync.isLandscapeRotation(deferred)
                                            == (width > height);
                                    if (nowReady) {
                                        deferredRotation = null;
                                        DiagLog.i(TAG, "對方後來轉好了，本機補轉 " + deferred);
                                        if (rotateClient(deferred)) {
                                            pendingAspect = aspect;
                                        } else if (surfaceView != null) {
                                            surfaceView.setAspect(aspect);
                                        }
                                    } else if (surfaceView != null) {
                                        surfaceView.setAspect(aspect);
                                    }
                                }

                                // 先看「現在該不該一致」（開場／對方自己轉了），
                                // 再處理「是不是我剛才請對方轉、它已經轉好了」。
                                syncNow();
                                onHostSizeMaybeReady(width, height);
                            });
                        }

                        @Override
                        public void onReady() {
                            ui.post(() -> {
                                reconnectAttempts = 0;
                                status.setVisibility(View.GONE);
                                setTitle(getString(R.string.mirror_title,
                                        session == null ? host : session.deviceName()));
                                // 勾過「關閉螢幕省電」的人，每次連上都自動套用。
                                // 放在 onReady 之後：控制通道要通了才送得出去。
                                if (Settings.hostScreenOff(MirrorActivity.this)) {
                                    setHostScreen(false);
                                }
                                runPendingTestInput();
                            });
                        }

                        @Override
                        public void onError(String message) {
                            ui.post(() -> showError(message));
                        }

                        @Override
                        public void onEnded() {
                            ui.post(() -> {
                                if (isFinishing() || stopped) return;
                                // 影像串流斷了（Wi-Fi 換手、對方 adbd 重啟、訊號抖動）。
                                // 先試著自己接回來，三次都失敗才把錯誤丟給使用者。
                                scheduleReconnect();
                            });
                        }

                        @Override
                        public void onHostClipboard(String text) {
                            // 這裡是控制通道的讀取執行緒，ClipboardSync 內部會轉到主執行緒
                            if (clipboardSync != null) clipboardSync.onHostText(text);
                        }

                        @Override
                        public void onAudioState(boolean active, boolean hostAudible) {
                            // 音訊 session 是獨立的一條，連上／重建完成都比畫面晚。
                            // 這裡只重畫按鈕，不把 hostAudible 寫回來：事實來源是
                            // Settings，回呼只是「結果通知」，兩邊互相覆蓋會打架。
                            ui.post(MirrorActivity.this::updateSoundButton);
                        }
                    });
            // 音訊的起始狀態由 ScrcpySession 自己從 Settings 讀（見 start()），
            // 這裡不再傳 —— 兩邊各記一份遲早會不一致。
            try {
                s.setRecorder(recorder);
                s.start(surface, lockRotation);
                if (stopped || isFinishing()) {
                    // 啟動期間使用者已經離開（例如按下返回），別把連線留下來
                    s.close();
                    return;
                }
                session = s;
                DiagLog.i(TAG, "鏡像已就緒 " + host + ":" + port);

                // 啟動期間（約 2 秒）Surface 可能已經被換掉了，補一次掛接
                ui.post(() -> {
                    SurfaceHolder h = surfaceView.getHolder();
                    if (h.getSurface() != null && h.getSurface().isValid()) {
                        s.attachSurface(h.getSurface());
                    }
                    // 再對齊一次方向。上面那行之後 session 一定已經指派好 ——
                    // onVideoSize 那次可能跑在指派之前（session 還是 null，syncNow 靜默跳過），
                    // 而 onVideoSize 只在尺寸變化時才來，錯過就再也沒有第二次。
                    // 重複呼叫是幂等的（見 OrientationSync#requestHost）。
                    syncNow();
                    // 音訊是獨立 session，還在背景起（約 1~1.5 秒）——
                    // 先按目前狀態把按鈕畫出來，好了再由 onAudioState 更新
                    updateSoundButton();
                });

                // 剪貼簿：先請對方回報一次、再決定誰蓋誰。
                // 這一步會等對方 ACK（最多 1.5 秒），所以放在上面那段之後 ——
                // 不能讓「畫面/靜音」跟著一起等。
                if (clipboardSync != null) clipboardSync.attach(s);
                if (stopped) {
                    // 談判期間使用者已經離開 —— 把剛註冊的監聽收掉，別留下來亂送
                    if (clipboardSync != null) clipboardSync.detach();
                    closeSession();
                    return;
                }
            } catch (Exception e) {
                s.close();
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                DiagLog.e(TAG, "鏡像啟動失敗", e);
                ui.post(() -> showError(getString(R.string.mirror_failed, msg)));
            } finally {
                sessionStarting = false;
            }
        }, "omniadb-mirror-start");
        starter.setDaemon(true);
        starter.start();
    }

    private void showError(String message) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
        if (bar != null) bar.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideBar);
    }

    // ================================================================== 觸控

    private boolean onSurfaceTouch(View v, MotionEvent e) {
        final ScrcpySession s = session;
        if (s == null || !s.isRunning()) return true;

        int vw = v.getWidth();
        int vh = v.getHeight();
        if (vw <= 0 || vh <= 0) return true;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                sendTouch(s, 0 /* ACTION_DOWN */, e, i, vw, vh, BTN_PRIMARY, BTN_PRIMARY);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    sendTouch(s, 2 /* ACTION_MOVE */, e, i, vw, vh, 0, BTN_PRIMARY);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                sendTouch(s, 1 /* ACTION_UP */, e, i, vw, vh, 0, 0);
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    sendTouch(s, 1 /* ACTION_UP */, e, i, vw, vh, 0, 0);
                }
                break;
            }
            default:
                break;
        }
        return true;
    }

    private void sendTouch(ScrcpySession s, int action, MotionEvent e, int index,
                           int viewW, int viewH, int actionButton, int buttons) {
        // 座標換算：SurfaceView 已經被量成「影像的長寬比」，所以兩個方向同一個比例
        int x = Math.round(e.getX(index) * s.videoWidth() / viewW);
        int y = Math.round(e.getY(index) * s.videoHeight() / viewH);
        x = Math.max(0, Math.min(s.videoWidth() - 1, x));
        y = Math.max(0, Math.min(s.videoHeight() - 1, y));
        float pressure = action == 1 ? 0f : 1f;   // ACTION_UP 放掉壓力
        s.sendTouch(action, e.getPointerId(index), x, y, pressure, actionButton, buttons);
    }

    // ================================================================== 等比例 Surface

    /**
     * 依影像長寬比量出自己的大小，置中於父容器。
     *
     * <p>為什麼不直接鋪滿：鋪滿會把畫面拉變形，而且<b>觸控座標會對不上</b>
     * （手指在畫面上看到的位置 ≠ 送出的座標）。等比縮放之後，
     * 一個像素的位移在任何方向都代表同樣的位移量，換算只剩一個乘數。
     */
    private static final class AspectSurfaceView extends SurfaceView {

        private float aspect;

        AspectSurfaceView(android.content.Context context) {
            super(context);
        }

        void setAspect(float a) {
            if (a <= 0 || Math.abs(a - aspect) < 0.001f) return;
            aspect = a;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            int h = MeasureSpec.getSize(heightSpec);
            if (aspect > 0 && w > 0 && h > 0) {
                if (w / aspect <= h) {
                    h = Math.round(w / aspect);
                } else {
                    w = Math.round(h * aspect);
                }
            }
            setMeasuredDimension(w, h);
        }
    }

    // ================================================================== 小工具

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
