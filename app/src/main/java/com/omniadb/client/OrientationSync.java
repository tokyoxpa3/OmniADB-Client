package com.omniadb.client;

import android.content.Context;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.OrientationEventListener;

import java.util.function.Supplier;

/**
 * 把本機（Client）的擺放方向同步給對方（Host）。
 *
 * <p><b>規則：完全以 Client 為主。</b>Client 怎麼擺，Host 就怎麼轉。
 * 不去猜 Host 的 App 有沒有鎖方向 —— 送了它不轉就是不轉，畫面會自己說明一切。
 *
 * <p><b>四個方位都要分辨。</b>{@code wm user-rotation lock N} 的 N 就是
 * {@code Surface.ROTATION_*}（0/1/2/3）。只分「橫／豎」是不夠的：
 * 橫著拿有「左翻」和「右翻」兩種，送錯那一種，畫面上就是<b>整整差 180°</b>。
 *
 * <p><b>為什麼只信感測器、不看畫面旋轉：</b>
 * 這個 Activity 的螢幕方向<b>不是</b>系統決定的（見 {@code MirrorActivity} 的
 * {@code setRequestedOrientation}），而是我們在「對方轉好之後」才自己轉的。
 * 所以畫面旋轉是<b>結果</b>，不能拿來當輸入，否則會自己餵自己。
 * 唯一的輸入是 {@link OrientationEventListener} 讀到的物理擺放 ——
 * 它跟系統的自動旋轉設定無關，關掉自動旋轉也照樣有效。
 */
final class OrientationSync {

    /** 使用者把本機擺成某個方向了（去抖後），而且 Host 那邊已經處理過。 */
    interface Callback {
        /**
         * @param rotation 目標 {@code Surface.ROTATION_*}（0/1/2/3）
         */
        void onIntent(int rotation);
    }

    private static final String TAG = "OmniADB-Client";

    /**
     * 方向要穩定這麼久才動作。轉動過程中會連續報值，太靈敏會把對方轉來轉去。
     *
     * <p>刻意比系統的旋轉判定（約 400ms）短一點：指令越早送到對方，對方就越早開始轉，
     * 本機畫面要「壓住等它」的時間就越短。
     */
    private static final long SETTLE_MS = 300;

    /**
     * 送出旋轉後，短時間內不再送第二個指令。
     *
     * <p>這裡送的是 {@code wm user-rotation lock N} —— <b>絕對值</b>，不是
     * scrcpy 那種「橫豎互切」的 toggle，所以重複送同一個值是幂等的、不會轉兩下。
     * 冷卻的目的是擋住抖動時的連續送出。試過降到 400ms，反而讓「路過的方向」
     * 更容易被送出去（冷卻一結束就補送），畫面多翻一次；700ms 比較穩。
     */
    private static final long SETTLE_AFTER_ROTATE_MS = 700;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Supplier<ScrcpySession> session;
    private final Callback callback;
    private final OrientationEventListener sensor;

    private boolean enabled = true;

    /** 最近一次「想要」的方位。{@code -1} 表示還沒決定過。 */
    private int wantRotation = -1;

    /** 最近一次送給對方的方位。{@code -1} 表示這次連線還沒送過。 */
    private int requestedRotation = -1;

    /** 感測器去抖：候選方位 + 它是從什麼時候開始的。 */
    private boolean pendingValid;
    private int pendingRotation;
    private long pendingSince;

    /** 最近一次感測器報的方位（去抖前）。給 {@link #adoptHost()} 用。 */
    private int lastRotation = -1;

    /** 已送出旋轉、還在等對方重啟編碼器。 */
    private boolean waiting;

    OrientationSync(Context context, Supplier<ScrcpySession> session, Callback callback) {
        this.session = session;
        this.callback = callback;

        OrientationEventListener l = null;
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sm != null) {
            l = new OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
                @Override
                public void onOrientationChanged(int orientation) {
                    if (orientation == ORIENTATION_UNKNOWN) return;
                    onDeviceOrientation(orientation);
                }
            };
        }
        this.sensor = l;
    }

    /** 這台裝置有沒有方向感測器（桌面／模擬器通常沒有）。 */
    boolean hasSensor() {
        return sensor != null && sensor.canDetectOrientation();
    }

    void start() {
        if (hasSensor()) {
            try {
                sensor.enable();
                DiagLog.i(TAG, "方向同步已啟動（感測器）");
            } catch (Exception e) {
                DiagLog.w(TAG, "方向感測器啟用失敗，本機畫面不會自動轉向", e);
            }
        } else {
            // 沒有感測器就沒有方向輸入源。畫面方向由 setRequestedOrientation 控制，
            // 沒有感測器時就維持開場那個方向不動。
            DiagLog.i(TAG, "沒有方向感測器，本機畫面固定不動");
        }
    }

    void stop() {
        if (sensor != null) {
            try {
                sensor.disable();
            } catch (Exception ignored) {
                // 已經停用過就沒事了
            }
        }
        ui.removeCallbacksAndMessages(null);
        waiting = false;
    }

    boolean isEnabled() {
        return enabled;
    }

    /** 切換「跟隨本機方向」。回傳切換後是否啟用。 */
    boolean toggle() {
        enabled = !enabled;
        DiagLog.i(TAG, "方向同步：" + (enabled ? "開" : "關"));
        if (enabled) {
            // 關掉期間可能已經擺到別的方向了，重新依現在的擺放決定
            wantRotation = -1;
            pendingValid = false;
        }
        return enabled;
    }

    /**
     * 手動按了工具列的「旋轉」之後呼叫。
     *
     * <p>把「想要的方位」改成感測器現在報的值，這樣下一次感測器事件就不會
     * 立刻把它轉回去 —— 使用者會覺得那顆按鈕壞了。
     */
    void adoptHost() {
        if (lastRotation >= 0) {
            wantRotation = lastRotation;
            DiagLog.i(TAG, "手動旋轉：接受目前擺放方位 " + lastRotation);
        }
    }

    /**
     * 主動把「本機現在的方向」送給對方，不需要感測器。
     *
     * <p>用在開場對齊：手機<b>平放</b>時 {@link OrientationEventListener} 回
     * {@code ORIENTATION_UNKNOWN}，永遠不會有「方向變了」的事件，
     * 但這時候本機和對方的方向可能本來就不一致（例如對方停在橫屏）。
     * 沒有這個入口，那個不一致就會一直留著 —— 畫面上就是兩片黑邊。
     *
     * <p>刻意<b>不</b>回呼 {@link Callback#onIntent}：本機畫面本來就已經是這個方向了。
     */
    void requestHost(int rotation) {
        if (!enabled) return;
        if (wantRotation == rotation && requestedRotation == rotation) return;
        if (!apply(rotation)) return;
        wantRotation = rotation;
    }

    // ------------------------------------------------------------ 方向來源

    private void onDeviceOrientation(int orientation) {
        int rotation = toRotation(orientation);
        lastRotation = rotation;
        if (!enabled) return;

        // 去抖之後才送指令。
        //
        // 曾經試過「方位一變就送、不等去抖」，想說早送 300ms 對方就早 300ms 轉。
        // 結果反而變差：轉動過程中方位會經過中間值，提早送等於叫對方往一個
        // 「使用者只是路過」的方向轉，接著又要修正，看起來就是多翻一次。
        // 去抖在這裡不只是防手抖，也是「等使用者真的決定方向」。
        long now = SystemClock.uptimeMillis();
        if (!pendingValid || rotation != pendingRotation) {
            // 換方向了（或第一次），重新計時
            pendingValid = true;
            pendingRotation = rotation;
            pendingSince = now;
            return;
        }
        if (now - pendingSince < SETTLE_MS) return;
        if (wantRotation == rotation) return;

        // 連線還沒就緒時 apply 會回 false，這時**不要**記下 want ——
        // 否則之後同方向就再也不會觸發，鏡像起來了卻永遠不同步。
        if (!apply(rotation)) return;
        wantRotation = rotation;
        // 交給 UI 決定什麼時候轉本機畫面（要等對方跟上，見 MirrorActivity）
        if (callback != null) callback.onIntent(rotation);
    }

    // ------------------------------------------------------------ 同步

    /**
     * @return false ＝ 現在還不能處理（沒連上／沒有畫面尺寸），呼叫端先別記下目標
     */
    private boolean apply(int rotation) {
        if (!enabled) return false;

        ScrcpySession s = session.get();
        if (s == null || !s.isRunning()) return false;
        if (s.videoWidth() <= 0 || s.videoHeight() <= 0) return false;

        // 上一個指令還在冷卻：目標照記，等窗口過去再補
        if (waiting) return true;

        // 已經送過同一個方位就不用再送。
        // 這裡**不能**改成「比對對方現在是橫還是豎」—— 橫著拿有左翻右翻兩種，
        // 兩者的影像尺寸完全一樣，靠尺寸分不出來，只會把 180° 的錯留在畫面上。
        if (requestedRotation == rotation) return true;

        DiagLog.i(TAG, "方向同步：本機方位 " + rotation + " → 鎖定對方 " + rotation);
        requestedRotation = rotation;
        final ScrcpySession target = s;
        // 走 shell 會阻塞（開一條串流 + 等指令回來），不能在感測器回呼（主執行緒）裡做
        Thread t = new Thread(() -> target.setHostRotation(rotation), "omniadb-rotate");
        t.setDaemon(true);
        t.start();
        waiting = true;
        ui.postDelayed(() -> {
            waiting = false;
            // 只在「冷卻期間目標又變了」時補送。不能無條件補送 ——
            // 對方要是根本轉不動（App 鎖方向／ROM 擋住），就會變成每 700ms
            // 送一次指令的無限循環，logcat 被灌滿而且毫無意義。
            if (enabled && wantRotation >= 0 && wantRotation != requestedRotation) {
                apply(wantRotation);
            }
        }, SETTLE_AFTER_ROTATE_MS);
        return true;
    }

    // ------------------------------------------------------------ 角度換算

    /**
     * 感測器角度 → {@code Surface.ROTATION_*}。
     *
     * <p>{@link OrientationEventListener} 報的是<b>裝置被轉了多少</b>：
     * 0＝自然方向、90＝左側朝上、180＝上下顛倒、270＝右側朝上。
     * 而 {@code Surface.ROTATION_*} 是<b>畫面內容要轉多少</b>，方向剛好相反。
     * 所以 90 對應 {@code ROTATION_270}、270 對應 {@code ROTATION_90} ——
     * 這一格弄反，橫著拿就會整整差 180°。
     */
    static int toRotation(int orientation) {
        int o = ((orientation % 360) + 360) % 360;
        if (o >= 315 || o < 45) return 0;      // 自然方向
        if (o < 135) return 3;                 // 左側朝上 → 畫面轉 270°
        if (o < 225) return 2;                 // 上下顛倒
        return 1;                              // 右側朝上 → 畫面轉 90°
    }

    static boolean isLandscapeRotation(int rotation) {
        return rotation == 1 || rotation == 3;
    }
}
