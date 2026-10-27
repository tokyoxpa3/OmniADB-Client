package com.omniadb.client;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 連線參數（畫質、音訊、對方螢幕常亮）。
 *
 * <p>為什麼要獨立一個類別：這些值<b>同時</b>被 {@link ScrcpyServer}（組指令）、
 * {@link ScrcpySession}（決定要不要開音訊那條 session）與 {@link MirrorActivity}
 * （畫勾選狀態）讀寫。放在 Activity 的欄位裡會變成「換頁就忘」，而它們本來就該
 * 記住使用者的選擇。
 *
 * <p>全部存在 {@code SharedPreferences}，不引入任何依賴 —— 只有三個值。
 */
final class Settings {

    private static final String PREF = "omniadb_client_settings";
    private static final String KEY_QUALITY = "quality";
    private static final String KEY_AUDIO_MODE = "audio_mode";
    private static final String KEY_STAY_AWAKE = "stay_awake";
    private static final String KEY_HOST_SCREEN_OFF = "host_screen_off";

    /** 畫質三檔。數字只是索引，實際參數見 {@link #video(Context)}。 */
    static final int QUALITY_LOW = 0;
    static final int QUALITY_STANDARD = 1;
    static final int QUALITY_HIGH = 2;

    /**
     * 音訊三種模式。
     *
     * <p><b>為什麼是三種而不是「有聲／不要聲音」兩種：</b>
     * 「對方喇叭要不要出聲」只能靠 {@code audio_dup} 控制，而它只在音訊 session
     * 啟動時決定 —— 所以「不要聲音」如果只是「不開 session」，那顆
     * 「有聲／靜音」按鈕就等於被廢掉，對方喇叭變成誰都管不到。
     * 三種模式各自對應一個明確的結果：
     *
     * <table border="1">
     *   <tr><th>模式</th><th>音訊 session</th><th>對方喇叭</th><th>我這邊</th></tr>
     *   <tr><td>{@link #AUDIO_BOTH}</td><td>開，dup=true</td><td>出聲</td><td>聽得到</td></tr>
     *   <tr><td>{@link #AUDIO_CLIENT_ONLY}</td><td>開，dup=false</td><td>安靜</td><td>聽得到</td></tr>
     *   <tr><td>{@link #AUDIO_OFF}</td><td><b>不開</b></td><td>照常出聲（不受我們影響）</td><td>聽不到</td></tr>
     * </table>
     */
    static final int AUDIO_BOTH = 0;
    static final int AUDIO_CLIENT_ONLY = 1;
    static final int AUDIO_OFF = 2;

    /** 一組影像參數。 */
    static final class Video {
        final int maxSize;
        final int bitRate;
        final int maxFps;

        Video(int maxSize, int bitRate, int maxFps) {
            this.maxSize = maxSize;
            this.bitRate = bitRate;
            this.maxFps = maxFps;
        }
    }

    private Settings() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    static int quality(Context c) {
        return prefs(c).getInt(KEY_QUALITY, QUALITY_STANDARD);
    }

    static void setQuality(Context c, int q) {
        prefs(c).edit().putInt(KEY_QUALITY, q).apply();
    }

    /**
     * 畫質 → 實際編碼參數。
     *
     * <p>為什麼不是「愈高愈好」：{@code max_size} 是<b>長邊</b>上限，對方 1220x2712 的機器
     * 在 1280 時實際只有 574x1280；拉到 1600 會變成 720x1600，位元率也要跟著上去，
     * 2.4 GHz WiFi 就開始抖。反過來 720 在訊號差的時候至少還能操作。
     */
    static Video video(Context c) {
        switch (quality(c)) {
            case QUALITY_LOW:
                return new Video(720, 2_000_000, 30);
            case QUALITY_HIGH:
                return new Video(1600, 12_000_000, 60);
            default:
                return new Video(1280, 6_000_000, 60);
        }
    }

    /** 音訊模式（見 {@link #AUDIO_BOTH} 的表）。 */
    static int audioMode(Context c) {
        return prefs(c).getInt(KEY_AUDIO_MODE, AUDIO_BOTH);
    }

    static void setAudioMode(Context c, int mode) {
        prefs(c).edit().putInt(KEY_AUDIO_MODE, mode).apply();
    }

    /** 這個模式需不需要開音訊 session。 */
    static boolean audioSessionNeeded(Context c) {
        return audioMode(c) != AUDIO_OFF;
    }

    /**
     * 鏡像時要不要讓<b>對方</b>螢幕保持常亮（scrcpy 的 {@code stay_awake}）。
     *
     * <p>預設 true：鏡像的重點就是「看著對方」，對方自己睡著只會得到一片黑，
     * 還得伸手按電源鍵。代價是對方耗電 —— 不想要的人可以在選單裡關掉。
     */
    static boolean stayAwake(Context c) {
        return prefs(c).getBoolean(KEY_STAY_AWAKE, true);
    }

    static void setStayAwake(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_STAY_AWAKE, on).apply();
    }

    /**
     * 連上之後要不要<b>自動把對方的面板關掉</b>（省電，鏡像照常）。
     *
     * <p>預設 false：這是「對別人的手機動手」，不該預設就做。但一旦勾過就記住 ——
     * 會勾這個的人，理由通常是固定的（例如把舊手機掛著當監看螢幕），
     * 每次連上都要再點一次只是折磨。
     *
     * <p>注意這是<b>偏好</b>不是「現在的狀態」：對方此刻是亮是暗仍然由
     * {@link ScrcpySession#isDisplayPowerOff()} 決定。兩者分開才不會出現
     * 「上次沒開回來，這次一連上就以為已經關著」。
     */
    static boolean hostScreenOff(Context c) {
        return prefs(c).getBoolean(KEY_HOST_SCREEN_OFF, false);
    }

    static void setHostScreenOff(Context c, boolean off) {
        prefs(c).edit().putBoolean(KEY_HOST_SCREEN_OFF, off).apply();
    }
}
