package com.omniadb.client;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * scrcpy-server 的資產與啟動參數。
 *
 * <p>螢幕鏡像不走「{@code screencap} 輪詢」—— 那個只有 1~3 fps，控制手感全無。
 * 走 scrcpy 的路線：把 {@code scrcpy-server.jar} 推到對方手機，用 {@code app_process}
 * 跑起來，它會開一個 {@code localabstract:scrcpy} 的本機 socket，吐出 H.264。
 *
 * <p><b>不需要 {@code adb forward}。</b>{@code forward} 是 adb <b>server</b> 的功能，
 * libadb 只做到協定層；但我們可以直接對 {@code localabstract:scrcpy} 開 stream，
 * 效果完全一樣（電腦版 scrcpy 就是 {@code adb forward tcp:x localabstract:scrcpy}
 * 之後連本機 TCP，繞了一圈而已）。
 *
 * <p><b>版本必須與 assets 裡的 jar 一致。</b>scrcpy 的伺服器會在啟動時比對版本字串，
 * 而且協定（標頭長度、控制訊息欄位）跟著版本走，混用會出現極難查的偏移錯誤。
 */
final class ScrcpyServer {

    private static final String TAG = "OmniADB-Client";

    /**
     * 對應 assets/scrcpy-server-4.1.jar（scrcpy v4.1 release 的 {@code scrcpy-server}，未修改）。
     *
     * <p><b>影像／控制與音訊用同一顆 jar、同一個版本（2026-10-26 統一）。</b>
     * 之前是影像 2.7、音訊 4.1 兩顆並存，原因是 4.1 的控制協定「聽說」有動過，
     * 不想為了音訊去冒險。實際讀 v4.1 原始碼＋實機探針（{@code .probe/v41_probe.py}）之後，
     * 差別只有三處，而且都已經在 {@link ScrcpySession} 裡處理：
     *
     * <ol>
     *   <li><b>影像標頭多了一段。</b>2.7 是 {@code dummy(1) + name(64) + codec meta(12)}；
     *       4.1 是 {@code dummy(1) + name(64) + codec id(4) + session meta(12)}。
     *       session meta 的格式是 {@code flags(4) + width(4) + height(4)}，
     *       其中 flags 的最高位固定為 1（跟封包標頭共用 12 bytes 的位置）。</li>
     *   <li><b>frame flag 位移。</b>2.7 是 CONFIG={@code 1<<63}、KEY={@code 1<<62}；
     *       4.1 把 bit63 讓給 SESSION，變成 CONFIG={@code 1<<62}、KEY={@code 1<<61}。
     *       照舊寫法讀，第一個 config 包就會被當成一般封包（畫面全黑但碼率正常）。</li>
     *   <li><b>session meta 會夾在封包流中間。</b>對方旋轉／resize 時，
     *       同一條影像 socket 上會再插一筆 12 bytes 的 session meta
     *       （實測 574x1280 → 1280x574）。不認得它就會把 width 當成 pts。</li>
     * </ol>
     *
     * <p>控制訊息（觸控 32 bytes、按鍵 14 bytes、剪貼簿、{@code TYPE_ROTATE_DEVICE}）
     * 與 2.7 <b>完全相同</b>，實測可直接沿用；唯一變的是
     * {@code TYPE_SET_DISPLAY_POWER} 從「mode 位元組」改成 boolean（見
     * {@link ScrcpySession#setScreenPowerMode(boolean)}）。
     *
     * <p>音訊仍然需要 4.1 才有的行為：只有 {@code audio_source=playback} ＋
     * {@code audio_dup=true} 能讓對方喇叭同時出聲（2.7 的 dup 走
     * {@code ROUTE_FLAG_LOOP_BACK_RENDER}，實測 SPEAKER 一直是 0）。
     */
    static final String VERSION = "4.1";

    private static final String ASSET_NAME = "scrcpy-server-4.1.jar";

    /**
     * 遠端路徑。
     *
     * <p>放 {@code /data/local/tmp} 是因為 shell 使用者（uid 2000）對它可讀可寫，
     * 不需要 root，也不需要動對方的 App 目錄。
     */
    static final String REMOTE_JAR = "/data/local/tmp/omniadb-scrcpy.jar";

    /**
     * 影像長邊上限、位元率、幀率上限。
     *
     * <p>不再寫死在這裡 —— 使用者可以在鏡像的工具列選單裡切三檔
     * （見 {@link Settings#video(Context)}），實際值由呼叫端傳進來。
     * 對方螢幕可能是 1440p，原尺寸編碼在 WiFi 上會吃掉 20+ Mbps 而且解碼也累。
     */

    /**
     * 音訊編碼。<b>刻意選 {@code raw}（裸 PCM），不是 opus/AAC。</b>
     *
     * <p>scrcpy 伺服器是用 {@code MediaCodec} 編音訊的，而 POCO / Android 15 上
     * 那條路會死在 {@code MediaCodec.setCallback} → {@code DeviceConfig}：
     * <pre>
     * SecurityException: Given calling package android does not match caller's uid 2000
     * </pre>
     * （scrcpy 以 shell 身分跑，卻拿系統 context 去讀 DeviceConfig。）
     * 實測 opus / aac / flac <b>三種全部一樣下場</b>，伺服器只會往音訊 socket
     * 寫 4 個 0 表示「停用」；只有 {@code raw} 完全不碰 MediaCodec。
     *
     * <p>代價：48 kHz 立體聲 16-bit ＝ <b>1.54 Mbps</b>（影像 6 Mbps 之外再加這個，
     * 5GHz 沒感覺，2.4GHz 才需要考慮）。好處：Client 不必解碼，
     * 而且省掉每幀 21 ms ＋ 編碼器 lookahead 的延遲。
     */
    private static final String AUDIO_CODEC = "raw";

    /**
     * 音訊來源：{@code playback}。
     *
     * <p><b>★ 只有 {@code playback} + {@code audio_dup=true} 的組合能讓對方喇叭同時出聲</b>
     * （見 {@link #VERSION} 的說明）。{@code output} 不管 dup 開不開，對方都變安靜。
     *
     * <p>兩者的差別在於 AudioPolicy 的接法：{@code output} 是「把播放重新導向到
     * REMOTE_SUBMIX」，聲音離開原本的路徑；{@code playback} 是「在原本的 render
     * 路徑上旁聽」，所以只要 dup 開著，喇叭照樣響、我們也拿得到同一份訊號。
     *
     * <p><b>好消息：{@code playback} 不會建立 MediaProjection</b>（實測
     * {@code dumpsys media_projection} 仍是 {@code null}），所以<b>不會</b>觸發
     * Android 15 那個「螢幕正在被分享 → 受保護頁面變占位圖」的問題。
     *
     * <p>⚠ 要求 Android 13（API 33）以上，12 以下伺服器會寫 4 個 0，我們就只跑影像。
     * 只抓得到 {@code USAGE_MEDIA}／{@code GAME} 這類媒體音；通知音、通話、鈴聲抓不到，
     * App 也可以宣告 {@code allowAudioPlaybackCapture=false} 拒絕被擷取。
     */
    private static final String AUDIO_SOURCE = "playback";

    /**
     * 純音訊 session 的 socket 名。
     *
     * <p>主 session 不帶 {@code scid}（socket 就叫 {@code scrcpy}），音訊 session 一定要帶 ——
     * 兩個 {@code app_process} 同時活著，不給名字會撞在同一個 abstract socket 上
     * （第二個直接 {@code Address already in use}）。名稱會變成 {@code scrcpy_<scid>}。
     *
     * <p><b>每次重開都換一個 scid</b>：切「靜音／有聲」是「關舊的、開新的」，
     * 舊伺服器從 SIGHUP 到真的退出有一小段時間。用同一個名字時，新伺服器會撞上
     * 還沒死透的舊名字，然後我們去連到的其實是正在收尾的舊進程（症狀：連上了但沒資料）。
     * 換名字就沒這個競態，舊的那條自己消失。
     *
     * <p>格式必須是<b>8 位小寫十六進位</b>，否則 scrcpy 伺服器直接拒絕啟動。
     */
    static String audioScid() {
        return String.format(java.util.Locale.US, "%08x", AUDIO_SCID_SEQ.incrementAndGet());
    }

    private static final java.util.concurrent.atomic.AtomicInteger AUDIO_SCID_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(
                    new java.util.Random().nextInt(0xFFFF) + 1);

    /** 純音訊 session 的 socket 名（{@code localabstract:scrcpy_<scid>}）。 */
    static String audioSocket(String scid) {
        return "localabstract:scrcpy_" + scid;
    }

    private ScrcpyServer() {
    }

    /**
     * 組出啟動 scrcpy-server 的完整 shell 指令（<b>主 session：影像 ＋ 控制</b>）。
     *
     * <p>參數一個一個說明（全部都是 {@code key=value}，中間不能有空格）：
     *
     * <ul>
     *   <li>{@code tunnel_forward=true} —— <b>這個最關鍵</b>。設 false 時伺服器會反過來
     *       去連本機的埠（等於要求先做 {@code adb reverse}），直接噴
     *       {@code Connection refused}；設 true 才是「伺服器開 abstract socket 等我們連」。</li>
     *   <li>{@code audio=false} —— <b>音訊不在這裡跑，另開一個純音訊 session。</b>
     *       理由見類別說明與 {@link #audioCommandLine(boolean)}：{@code audio_dup}
     *       只能在啟動時決定，而它一換就得重啟整個伺服器 —— 綁在影像 session 上，
     *       切一次「對方靜音」畫面就要黑掉重連。分開之後切換只重啟音訊那條。</li>
     *   <li>{@code control=true} —— 要觸控，這條控制 socket 必須開。</li>
     *   <li>{@code clipboard_autosync=false} —— <b>這個違反 scrcpy 的預設，但非這樣不可。</b>
     *       預設 {@code true} 時，伺服器只會「對方剪貼簿一變就推給我們」，
     *       而 {@code GET_CLIPBOARD} 會被它當成 no-op 直接丟掉 —— 結果就是
     *       <b>連線前對方就複製好的內容永遠拿不到</b>。關掉之後對方不再主動推，
     *       但我們隨時可以讀到現況，連線前後的內容就一視同仁了
     *       （代價是要輪詢，見 {@link ScrcpySession#CLIPBOARD_POLL_MS}）。</li>
     *   <li>{@code cleanup=false} —— <b>預設是 true，會在伺服器結束時把 jar 刪掉</b>。
     *       我們希望 jar 留在對方機器上重複使用，所以關掉。踩過這個坑：
     *       第一次連線失敗後 jar 就不見了，第二次啟動變成
     *       {@code ClassNotFoundException} → {@code app_process} 直接 SIGABRT。
     *       <b>音訊 session 也一定要關</b>：它比影像 session 晚結束，一旦它把 jar 刪了，
     *       下一次連線就找不到 jar。</li>
     *   <li>{@code stay_awake=true} —— 讓<b>對方</b>螢幕在鏡像期間不睡
     *       （伺服器會加 {@code FLAG_KEEP_SCREEN_ON}）。沒有它的話，對方螢幕逾時
     *       就變一片黑，只能伸手按電源鍵。使用者可以在選單裡關掉。</li>
     * </ul>
     */
    static String commandLine(Settings.Video video, boolean stayAwake) {
        return "CLASSPATH=" + REMOTE_JAR + " app_process / com.genymobile.scrcpy.Server "
                + VERSION
                + " log_level=info"
                + " max_size=" + video.maxSize
                + " video_bit_rate=" + video.bitRate
                + " max_fps=" + video.maxFps
                + " tunnel_forward=true"
                + " audio=false"
                + " control=true"
                + " stay_awake=" + stayAwake
                + " clipboard_autosync=false"
                + " cleanup=false";
    }

    /**
     * 純音訊 session 的啟動指令（{@code video=false}）。
     *
     * <p><b>為什麼要拆成兩條 session：</b>scrcpy 的 {@code audio_dup} 決定
     * 「擷取的聲音要不要同時從對方喇叭放出來」，它在伺服器<b>啟動時就固定</b>
     * （要換只能重啟）。綁在影像 session 上就代表「切一次靜音＝整個鏡像重連」
     * （畫面黑 1~2 秒）；拆出來之後切換只重啟這一條，畫面完全不動，
     * 代價只有約 1 秒的聲音空窗。
     *
     * <p><b>★ 純音訊時的握手順序不一樣</b>：只有一條 socket，而且
     * {@code dummy(1) + 裝置名(64)} 會寫在<b>這條音訊 socket</b> 上（有影像時才寫在影像
     * socket），之後才是 4 bytes codec id（音訊沒有 session meta —— 那是影像專屬的）。
     * 照影像那套讀就會把裝置名當成 codec id。
     *
     * @param keepHostPlaying {@code audio_dup}：true ＝ 對方喇叭照樣出聲（兩邊都聽得到）；
     *                        false ＝ 只擷取、對方喇叭安靜（client 仍聽得到）。
     * @param scid            abstract socket 的後綴，每次重開都換（見 {@link #audioScid()}）。
     */
    static String audioCommandLine(boolean keepHostPlaying, String scid) {
        return "CLASSPATH=" + REMOTE_JAR + " app_process / com.genymobile.scrcpy.Server "
                + VERSION
                + " log_level=info"
                + " tunnel_forward=true"
                + " video=false"
                + " audio=true"
                + " audio_source=" + AUDIO_SOURCE
                + " audio_codec=" + AUDIO_CODEC
                + " audio_dup=" + keepHostPlaying
                + " control=false"
                + " clipboard_autosync=false"
                + " cleanup=false"
                + " scid=" + scid;
    }

    /**
     * 把 assets 裡的 jar 解到 cache，回傳那個檔案。
     *
     * <p>不解到 {@code filesDir}：這是可重建的暫存物，清 cache 就該清掉，
     * 不該跟 ADB 金鑰那種不能掉的东西混在一起。
     */
    static File extract(Context context) throws IOException {
        return extract(context, ASSET_NAME);
    }

    private static File extract(Context context, String assetName) throws IOException {
        File out = new File(context.getCacheDir(), assetName);
        // 無條件重寫：AssetManager 的 available() 對壓縮過的資產不可信，
        // 用長度比對會偶發「檔案明明存在卻被當成壞的」或反過來。直接覆蓋最省事。
        try (InputStream in = context.getAssets().open(assetName)) {
            try (OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        }
        DiagLog.i(TAG, "scrcpy-server 已解出：" + out + "（" + out.length() + " bytes）");
        return out;
    }
}
