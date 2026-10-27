package com.omniadb.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 裝置列表 —— 這支 App 的主畫面。
 *
 * <p>流程對應電腦版 {@code python -m omniadb} 的四件事，但全部在手機上完成：
 *
 * <pre>
 *   掃描 / 新增  →  配對（一次性）  →  安裝 Host  →  列出資訊  →  連線
 * </pre>
 *
 * <p>UI 刻意用程式碼堆出來（跟 OmniADB-Host 一致），不寫 layout XML：
 * 這個畫面結構很單純，寫 XML 只是多一份要同步維護的檔案。
 */
public class MainActivity extends Activity {

    private static final String TAG = "OmniADB-Client";

    private static final int COLOR_OK = 0xFF2E7D32;
    private static final int COLOR_WARN = 0xFFC62828;
    private static final int COLOR_AUTH = 0xFFEF6C00;
    private static final int COLOR_DIM = 0xFF757575;

    /** 請求授權最多等多久。留餘裕給人「走過去拿起手機 + 解鎖」。 */
    private static final int AUTH_TIMEOUT_S = 45;

    /** 選 APK 的 requestCode。 */
    private static final int REQ_PICK_APK = 1001;

    /**
     * 匯出日誌用的 provider authority。
     *
     * <p>必須跟 {@code AndroidManifest.xml} 裡 {@code <provider>} 的 authorities 一字不差
     * —— 這種字串重複兩份是典型的「改一個忘一個」，改了這裡記得改那裡。
     */
    private static final String LOG_AUTHORITY = "com.omniadb.client.fileprovider";

    /**
     * 單次探測的硬上限（含「換 5555 再試一次」那一輪）。
     *
     * <p>正常情況 1~3 秒就有結果：連線階段 2 秒逾時、埠預檢 2.5 秒、探測批次讀取 8 秒，
     * 全部都在 {@link AdbClient} 裡各自有上限。這裡只是最後一道保險 ——
     * 真的卡住就把連線關掉，卡片絕不留在「檢查中」。
     *
     * <p>（以前這道保險會<b>在 UI 執行緒</b>呼叫 {@code close()}，而 {@code close()}
     * 要 join libadb 的連線執行緒，遇到慢關閉就會凍住畫面；現在改丟背景執行緒。）
     */
    private static final long PROBE_TIMEOUT_MS = 20_000;

    /**
     * 判定「未授權」之後，在背景繼續等對方按下「允許」的視窗。
     *
     * <p>這個數字要跟系統那個 RSA 對話框的壽命對齊（約 30 秒）再多留一點餘裕，
     * 因為使用者可能過一會才走過去拿手機。
     */
    private static final int AUTH_WATCH_S = 60;

    /** 列表上的一列。{@code info} 在探測完成前是 null。 */
    private static class Row {
        String host;
        int port;
        String model = "";
        /**
         * 這台手機的硬體序號（{@link DeviceInfo#hwid}）。
         *
         * <p>連上之前一定是空的 —— 序號只有連上才問得到。清單要去重就是靠它：
         * 位址會變（換埠、換 IP），序號不會。
         */
        String serial = "";
        DeviceInfo info;
        boolean busy;
        /** 這一列是不是掃描臨時發現的（尚未配對）。 */
        boolean discovered;
        /** 需要授權才能連（金鑰沒被對方接受）。UI 據此顯示「請求授權」。 */
        boolean unauthorized;
        /** 背景正在等對方按「允許」。同一個列只跑一個。 */
        boolean authWatching;
        /**
         * 這一輪探測是「授權剛完成」的補抓。
         *
         * <p>失敗時<b>不要</b>把卡片打回紅字 —— 對方明明已經按了「允許」，
         * 只是 adbd 還要幾秒才恢復正常回應，打回紅字等於告訴使用者「失敗了」。
         */
        boolean postAuthProbe;
        /** 探測世代號。舊的探測回來時靠它判斷「這一輪已經被看門狗收掉了」。 */
        int probeGen;
        /** 這一輪已經自動重試過幾次（逾時常常只是對方重啟 adbd，重試一次就正常）。 */
        int probeRetry;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newFixedThreadPool(3);
    /**
     * 探測專用的執行緒池，<b>刻意跟 {@link #bg} 分開</b>。
     *
     * <p>以前探測也丟在 {@code bg}（固定三條）。只要有一條探測卡住（libadb 的
     * {@code AdbStream.read()} 真的會永久阻塞，見 {@link AdbClient#shell(String, String, long)}），
     * 三條都被佔滿之後<b>所有</b>探測與「請求授權」就只是排隊 —— 卡片永遠「檢查中」、
     * 對方也不會彈授權框，只有「鏡像」（自己開執行緒）還活著。
     *
     * <p>用 cached pool：一條卡住不影響其他列，而且探測本來就短命。
     */
    private final ExecutorService probeBg = Executors.newCachedThreadPool();
    /**
     * 等待授權專用的執行緒池。
     *
     * <p>刻意跟 {@link #bg} 分開：一次等待最長 60 秒，丟進共用的池子會把
     * 「探測」「安裝」這些真正需要即時回應的工作卡在佇列裡。
     */
    private final ExecutorService authBg = Executors.newCachedThreadPool();
    private final List<Row> rows = new ArrayList<>();
    /** 掃描到的「可連線埠」：host → port。配對完要用它來連。 */
    private final Map<String, Integer> connectPorts = new HashMap<>();

    private DeviceStore store;
    private Scanner scanner;
    private LinearLayout listContainer;
    private TextView statusView;

    private String pendingPairHost;
    private int pendingPairPort;

    /** 使用者選完 APK 之後要裝到哪一列。 */
    private Row pendingInstall;

    // ================================================================== 生命週期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiagLog.init(this);
        DiagLog.i(TAG, "語系：" + getResources().getConfiguration().getLocales().toLanguageTags());
        store = new DeviceStore(this);
        setContentView(buildUi());
        loadStored();
    }

    @Override
    protected void onDestroy() {
        if (scanner != null) scanner.stop();
        bg.shutdownNow();
        probeBg.shutdownNow();
        authBg.shutdownNow();
        super.onDestroy();
    }

    // ================================================================== UI 組裝

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(8));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF111111);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.app_subtitle);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setTextColor(COLOR_DIM);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle);

        root.addView(buildActionRow());

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scroll.setLayoutParams(scrollLp);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer);
        root.addView(scroll);

        // ---- 底部：狀態文字 + 日誌 ----
        // 日誌按鈕刻意放在這裡而不是上面那排：它是「出事才會用」的功能，
        // 擠進「掃描／新增／配對」那一排只會讓四個按鈕都變窄。
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(4), 0, 0);

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusView.setTextColor(COLOR_DIM);
        statusView.setText(R.string.status_idle);
        statusView.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        statusRow.addView(statusView);

        Button logs = new Button(this);
        logs.setText(R.string.btn_logs);
        logs.setAllCaps(false);
        logs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logs.setMinHeight(0);
        logs.setMinimumHeight(0);
        logs.setPadding(dp(10), dp(4), dp(10), dp(4));
        logs.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        logs.setOnClickListener(v -> showLogs());
        statusRow.addView(logs);

        root.addView(statusRow);

        return root;
    }

    private View buildActionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(12));

        row.addView(actionButton(R.string.btn_scan, v -> startScan()));
        row.addView(actionButton(R.string.btn_add, v -> showAddDialog()));
        row.addView(actionButton(R.string.btn_pair, v -> openPairDialog()));
        return row;
    }

    private Button actionButton(int textRes, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(textRes);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    // ================================================================== 列表

    private void loadStored() {
        rows.clear();
        // 同一台手機只留一列。存檔裡可能同時有「隨機埠」和「5555」兩筆，
        // 那其實是同一支手機 —— 序號一樣就合併，優先留 5555（Host 釘住的埠，
        // 重開機也不會變）。序號是空的（舊存檔）才退回比位址。
        for (DeviceStore.Device d : store.all()) {
            Row same = null;
            for (Row r : rows) {
                if (sameMachine(r, d)) {
                    same = r;
                    break;
                }
            }
            if (same != null) {
                DiagLog.i(TAG, "存檔裡同一台手機有兩筆（" + d.key() + "），合併成一列");
                if (same.port != 5555 && d.port == 5555) same.port = 5555;
                if (same.model == null || same.model.isEmpty()) same.model = d.model;
                if (same.serial.isEmpty()) same.serial = d.serial;
                continue;
            }
            Row r = new Row();
            r.host = d.host;
            r.port = d.port;
            r.model = d.model;
            r.serial = d.serial;
            rows.add(r);
        }
        render();
        probeAll();
    }

    /** 這一列跟存檔裡的一筆是不是同一台手機。序號優先，沒有序號才比位址。 */
    private static boolean sameMachine(Row r, DeviceStore.Device d) {
        if (!r.serial.isEmpty() && !d.serial.isEmpty()) return r.serial.equals(d.serial);
        return r.host.equals(d.host);
    }

    /**
     * 學到序號之後，把清單上「同一台」的其他列併掉。
     *
     * <p>為什麼需要：換 IP 之後第一次連上時，清單上同時有舊位址那張（連不上）
     * 和新位址這張 —— 兩張都是同一支手機。只有連上才知道序號，
     * 所以只能等到這一刻才併得起來。
     */
    private void mergeDuplicateRows(Row keep) {
        if (keep.serial == null || keep.serial.isEmpty()) return;
        boolean changed = false;
        for (int i = rows.size() - 1; i >= 0; i--) {
            Row other = rows.get(i);
            if (other == keep || !keep.serial.equals(other.serial)) continue;
            // 5555 那張才是「以後要用的位址」，別讓舊的隨機埠蓋掉它
            if (keep.port != 5555 && other.port == 5555) keep.port = 5555;
            if (keep.model == null || keep.model.isEmpty()) keep.model = other.model;
            DiagLog.i(TAG, "序號 " + keep.serial + " 重複，合併 " + other.host + ":" + other.port
                    + " → " + keep.host + ":" + keep.port);
            rows.remove(i);
            changed = true;
        }
        if (changed) render();
    }

    private void probeAll() {
        for (Row r : rows) probe(r);
    }

    /** 背景連線 + 抓快照。失敗就把這一列標成離線，不會擋住其他列。 */
    private void probe(Row r) {
        if (r.busy) return;
        // 已經有一條連線撐著在等對方按「允許」。
        // 這時候再探一次，只會讓對方多彈一個框，而新連線一開，
        // 舊框就沒人讀了（變孤兒，按不動）。等 authWatch 結束它自己會重探。
        if (r.authWatching) return;
        r.busy = true;
        render();

        final String host = r.host;
        final int port = r.port;
        final int gen = ++r.probeGen;
        final AdbClient client = new AdbClient(getApplicationContext(), host, port);
        // 回退到 5555 時會換一條連線，看門狗要關「目前那一條」，
        // 所以用一個可換的參照，而不是直接捕捉 client。
        final AtomicReference<AdbClient> live = new AtomicReference<>(client);

        // 看門狗：卡住時把連線關掉，UI 才不會一直「檢查中」
        ui.postDelayed(() -> {
            if (r.probeGen != gen || !r.busy) return;
            DiagLog.w(TAG, "探測逾時 " + host + ":" + port + "，主動關閉連線");
            // close() 會 join libadb 的連線執行緒，可能阻塞 —— 絕對不能在 UI 執行緒做
            final AdbClient c = live.get();
            probeBg.execute(() -> closeQuietly(c));
            if (r.postAuthProbe) {
                // 授權後的補抓逾時：對方明明已經按了「允許」，
                // 只是 adbd 還沒恢復。卡片維持「已連上」，不要打回紅字，
                // 也不要再重試一輪（那只會讓使用者多等 12 秒）。
                r.postAuthProbe = false;
                r.busy = false;
                DiagLog.d(TAG, "授權後補抓逾時，維持已連上 " + host + ":" + port);
                render();
                return;
            }
            if (r.probeRetry < 1) {
                // 逾時常常是「對方剛重啟 adbd」造成的（例如 Host 裝好後把埠切到 5555），
                // 那種情況重試一次就正常。不重試的話，明明安裝成功，
                // 卡片卻立刻變成紅字「連線逾時」，看起來像失敗。
                r.probeRetry++;
                r.busy = false;
                r.probeGen++;   // 讓還在跑的那次結果作廢
                DiagLog.i(TAG, "探測重試一次 " + host + ":" + port);
                ui.postDelayed(() -> probe(r), 1500);
                return;
            }
            if (r.postAuthProbe) {
                // 授權後的補抓逾時：卡片維持「已連上」，不要打回紅字
                r.postAuthProbe = false;
                r.busy = false;
                DiagLog.d(TAG, "授權後補抓逾時，維持已連上 " + host + ":" + port);
                render();
                return;
            }
            DeviceInfo info = new DeviceInfo();
            info.serial = host + ":" + port;
            info.port = port;
            info.error = getString(R.string.msg_probe_timeout);
            r.busy = false;
            r.info = info;
            render();
        }, PROBE_TIMEOUT_MS);

        probeBg.execute(() -> {
            DeviceInfo info = DeviceInfo.probe(client, host + ":" + port, port);
            AdbClient used = client;
            int usedPort = port;
            // 「待授權」不是失敗 —— 這條連線還活著，對方手機上正掛著授權框。
            // 關掉它，那個框就變成孤兒（畫著但按不動），而且會把
            // system_server 的 AdbDebuggingManager 佔死，之後連框都彈不出來。
            // 所以留著，把連線所有權交給 startAuthWatch 繼續等。
            boolean holding = info.unauthorized;

            // ★ 這個埠連不上就退回 5555 再試一次。
            // mDNS 掃到的隨機 connect 埠走的是 TLS：憑證不被信任時在握手階段就被打回，
            // 對方根本不會彈授權框。而 5555 是明文埠，會走 A_AUTH —— 只有它彈得出
            // 「允許 USB 偵錯嗎？」。所以「掃到的埠連不上」幾乎都該退回 5555。
            if (!info.reachable && !info.unauthorized && port != 5555
                    && AdbClient.shouldTryPlainPort(info.cause)) {
                DiagLog.i(TAG, "埠 " + port + " 連不上（" + info.error + "），改試 5555 " + host);
                closeQuietly(client);
                AdbClient plain = new AdbClient(getApplicationContext(), host, 5555);
                live.set(plain);
                DeviceInfo via5555 = DeviceInfo.probe(plain, host + ":5555", 5555);
                if (via5555.reachable || via5555.unauthorized) {
                    used = plain;
                    usedPort = 5555;
                    info = via5555;
                    holding = via5555.unauthorized;
                    DiagLog.i(TAG, "5555 可行（" + (holding ? "待授權" : "已連上") + "）" + host);
                } else {
                    // 兩條路都不通。保留原本那個比較有指引性的錯誤（例如
                    // 「憑證不被信任 → 用配對碼」），但補一句「5555 也試過了」，
                    // 免得使用者以為我們沒試。
                    closeQuietly(plain);
                    live.set(null);
                    DiagLog.i(TAG, "5555 也不行（" + via5555.error + "）" + host);
                    info.error = info.error + "\n"
                            + getString(R.string.msg_tried_5555, via5555.error);
                }
            }

            final DeviceInfo result = info;
            final boolean keep = holding;
            final AdbClient keepClient = used;
            final int adopted = usedPort;
            ui.post(() -> {
                if (r.probeGen != gen) {          // 已經被看門狗收掉了
                    if (keep) closeQuietly(keepClient);
                    return;
                }
                r.busy = false;
                r.probeRetry = 0;                // 這一輪有結果了，下次又可以重試
                if (!result.reachable && r.postAuthProbe) {
                    // 授權剛完成時的補抓失敗是預期內的（adbd 還在收尾）。
                    // 卡片維持「已連上」，等使用者下次刷新或點「詳情」再補。
                    r.postAuthProbe = false;
                    DiagLog.d(TAG, "授權後補抓詳情未成功，維持已連上 " + host + ":" + port);
                    render();
                    return;
                }
                r.postAuthProbe = false;
                if (adopted != r.port) {
                    // 只有 5555 這條路走得通（或只有它彈得出授權框），就把這一列改記成 5555。
                    // 之後的探測、鏡像、安裝都會直接用它，不必再繞一圈。
                    DiagLog.i(TAG, "把 " + host + " 的埠從 " + r.port + " 改成 " + adopted);
                    r.port = adopted;
                }
                r.info = result;
                r.unauthorized = result.unauthorized;
                if (result.reachable && result.model != null && !result.model.isEmpty()) {
                    r.model = result.model;
                    r.discovered = false;
                    r.unauthorized = false;
                    // 序號只有連上才問得到。記下來之後，這台手機換 IP 也不會變成第二張卡片。
                    if (result.hwid != null && !result.hwid.isEmpty()) r.serial = result.hwid;
                    store.put(new DeviceStore.Device(host, r.port, result.model, r.serial));
                    mergeDuplicateRows(r);
                }
                render();
                // 只要這條連線還撐著（= 對方手機上正掛著授權框），就一定要等。
                // 這裡不能有「使用者沒主動要求就不等」的分支 —— 框已經彈出去了，
                // 我們把連線一關，那個框就沒人收尾，反而會把對方的授權流程搞壞。
                if (keep) startAuthWatch(r, keepClient);
            });
        });
    }

    /**
     * 探測判定「待授權」之後，<b>用同一條連線</b>繼續等對方按下「允許」。
     *
     * <p><b>為什麼一定要用同一條連線：</b>那個授權框綁在「發起請求的 transport」上。
     * 我們一斷線，框就變成沒有收件人的孤兒 —— 還畫在螢幕上，但按「允許」完全沒反應，
     * 而且它會把 {@code system_server} 的 {@code AdbDebuggingManager} 佔住，
     * 之後任何連線都彈不出框，只能重開機。這是實測出來的：
     * 舊版探測用 12 秒逾時後主動 {@code close()}，反覆刷新幾次就會把對方搞死，
     * 症狀正是「刪掉憑證後再也連不上、要重開機才恢復」。
     *
     * <p><b>為什麼光等不用重連：</b>對方按下「允許」後，{@code adbd} 會把我們的公鑰
     * 寫進 {@code /data/misc/adb/adb_keys}，並<b>在同一條 socket 上</b>送出
     * {@code A_CNXN}。libadb 的連線執行緒收到後會把 {@code mConnectionEstablished}
     * 設成 true，{@code isConnected()} 立刻變 true —— 不需要重連一次去確認。
     * （舊版註解說「一定要重連」，那是錯的，錯因是我們自己每 5 秒關一次連線。）
     */
    private void startAuthWatch(Row r, AdbClient client) {
        if (r.authWatching) return;
        r.authWatching = true;
        final String host = r.host;
        final int port = r.port;
        render();
        DiagLog.i(TAG, "等待對方按「允許」 " + host + ":" + port + "，最多 " + AUTH_WATCH_S + " 秒");

        authBg.execute(() -> {
            boolean ok = false;
            try {
                ok = client.awaitAuth(AUTH_WATCH_S * 1000L);
            } catch (Exception e) {
                DiagLog.d(TAG, "等待授權中斷 " + host + ":" + port + "：" + e.getMessage());
            } finally {
                // 這條連線走過完整的 A_AUTH 握手，之後會「半死」（能送不能收），
                // 一定丟掉，不要拿它去開 shell。
                closeQuietly(client);
            }

            final boolean success = ok;
            if (!success) {
                ui.post(() -> {
                    r.authWatching = false;
                    render();
                });
                return;
            }

            // ① 先讓卡片立刻變「已連上」。
            // 對方按下「允許」的瞬間 adbd 會 marking framework writable，
            // 接下來幾秒 dumpsys 會變慢甚至丟包 —— 探測批次有一半是 dumpsys，
            // 實測授權後立刻探測會卡到 12 秒逾時。不能讓使用者盯著
            // 「等待對方允許…」看十幾秒，所以先給結論，詳情在背景補。
            ui.post(() -> {
                r.authWatching = false;
                r.unauthorized = false;
                if (r.info == null) {
                    r.info = new DeviceInfo();
                    r.info.serial = host + ":" + port;
                    r.info.port = port;
                }
                r.info.reachable = true;
                render();
            });

            // ② 過幾秒再用「正常探測路徑」補詳情。
            // 這裡刻意**不**自己搭執行緒／FutureTask：實測那種寫法怎麼試都失敗，
            // 換成跟一般刷新同一條 probe() 路徑就正常（同一個進程、同一個 adbd）。
            ui.postDelayed(() -> {
                if (!rows.contains(r)) return;
                if (r.port != port) return;
                r.postAuthProbe = true;
                probe(r);
            }, 3_000);
        });
    }


    private static void closeQuietly(AdbClient c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
            // 關閉失敗沒有補救手段
        }
    }

    private void render() {
        listContainer.removeAllViews();

        if (rows.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.empty_hint);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setTextColor(COLOR_DIM);
            empty.setPadding(dp(4), dp(24), dp(4), dp(4));
            listContainer.addView(empty);
            return;
        }

        for (Row r : rows) {
            listContainer.addView(buildCard(r));
        }
    }

    private View buildCard(Row r) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(0xFFF5F6F8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);

        // ---- 第一行：型號 + 狀態 ----
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        String display = r.model == null || r.model.isEmpty() ? r.host : r.model;
        name.setText(display);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(0xFF111111);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(name);

        TextView state = new TextView(this);
        state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        if (r.busy) {
            state.setText(R.string.state_probing);
            state.setTextColor(COLOR_DIM);
        } else if (r.info != null && r.info.reachable) {
            state.setText(R.string.state_online);
            state.setTextColor(COLOR_OK);
        } else if (r.authWatching) {
            state.setText(R.string.state_waiting_auth);
            state.setTextColor(COLOR_AUTH);
        } else if (r.unauthorized) {
            state.setText(R.string.state_need_auth);
            state.setTextColor(COLOR_AUTH);
        } else if (r.info == null) {
            state.setText(R.string.state_unknown);
            state.setTextColor(COLOR_DIM);
        } else {
            state.setText(R.string.state_offline);
            state.setTextColor(COLOR_WARN);
        }
        titleRow.addView(state);
        card.addView(titleRow);

        // ---- 第二行：IP:埠 ----
        TextView addr = new TextView(this);
        addr.setText(r.host + ":" + r.port);
        addr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        addr.setTextColor(COLOR_DIM);
        addr.setPadding(0, dp(2), 0, dp(2));
        card.addView(addr);

        // ---- 第三行：資訊摘要 ----
        if (r.info != null && r.info.reachable) {
            TextView meta = new TextView(this);
            meta.setText(getString(R.string.meta_line,
                    r.info.androidText(), r.info.batteryText(), r.info.screenText()));
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            meta.setTextColor(0xFF444444);
            meta.setPadding(0, dp(2), 0, 0);
            card.addView(meta);
        } else if (r.unauthorized && !r.busy) {
            // 探測中就不要還掛著上一輪的「待授權」提示，那會讓人以為沒在動
            TextView hint = new TextView(this);
            hint.setText(r.authWatching ? R.string.auth_watching : R.string.auth_row_hint);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            hint.setTextColor(COLOR_AUTH);
            hint.setPadding(0, dp(2), 0, 0);
            card.addView(hint);
        } else if (r.info != null && r.info.error != null && !r.info.error.isEmpty()) {
            TextView err = new TextView(this);
            err.setText(r.info.error);
            err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            err.setTextColor(COLOR_WARN);
            card.addView(err);
        }

        // ---- 需要授權時，給一顆橫跨整張卡的主要按鈕 ----
        // 放在小按鈕列「上面」，因為這是這一列唯一該做的事；
        // 其餘（詳情／鏡像）在授權前都做不了。
        if (r.unauthorized && !r.busy && !r.authWatching) {
            Button auth = new Button(this);
            auth.setText(R.string.btn_authorize);
            auth.setAllCaps(false);
            auth.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams authLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            authLp.setMargins(0, dp(8), 0, 0);
            auth.setLayoutParams(authLp);
            auth.setOnClickListener(v -> showAuthorize(r));
            card.addView(auth);
        }

        // ---- 按鈕列 ----
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(8), 0, 0);

        buttons.addView(smallButton(R.string.btn_detail, v -> showDetail(r)));
        buttons.addView(smallButton(R.string.btn_install, v -> showInstallChooser(r)));
        buttons.addView(smallButton(R.string.btn_mirror, v -> openMirror(r)));
        buttons.addView(smallButton(R.string.btn_files, v -> openFiles(r)));
        buttons.addView(smallButton(R.string.btn_remove, v -> confirmRemove(r)));

        card.addView(buttons);
        return card;
    }

    private Button smallButton(int textRes, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(textRes);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(4), dp(6), dp(4), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }
    // ================================================================== 掃描

    private void startScan() {
        if (scanner != null) scanner.stop();
        scanner = new Scanner(this);
        setStatus(getString(R.string.status_scanning));
        connectPorts.clear();

        scanner.start(new Scanner.Listener() {
            @Override
            public void onFound(Scanner.Found f) {
                ui.post(() -> mergeFound(f));
            }

            @Override
            public void onDone() {
                ui.post(() -> setStatus(getString(R.string.status_scan_done, rows.size())));
            }

            @Override
            public void onError(String message) {
                ui.post(() -> setStatus(message));
            }
        }, 8000);
    }

    private void mergeFound(Scanner.Found f) {
        if (f.pairing) {
            // 配對埠只活到對話框關掉為止，先記下來，使用者按「配對」時自動填
            pendingPairHost = f.host;
            pendingPairPort = f.port;
            setStatus(getString(R.string.status_pairing_port_found, f.host, f.port));
            return;
        }

        connectPorts.put(f.host, f.port);

        for (Row r : rows) {
            if (r.host.equals(f.host)) {
                if (r.port == 5555) {
                    // 已經記成 5555 就別被 mDNS 的隨機埠蓋掉。
                    // 那個埠是 TLS：沒配對過連不上，而且連不上時對方也不會彈授權框
                    // （實測：TLS 握手被拒，adbd 直接踢掉連線，一個框都沒有）。
                    // 舊版在這裡無條件覆蓋，結果「本來連得上的卡片，一掃描就壞掉」。
                    DiagLog.i(TAG, "掃到 " + f.host + ":" + f.port + "，但這一列已釘住 5555，不覆蓋");
                    return;
                }
                if (r.port != f.port) {
                    // 找到的是「現在」的埠，比記住的舊值可信
                    r.port = f.port;
                    r.info = null;
                    probe(r);
                }
                return;
            }
        }

        Row r = new Row();
        r.host = f.host;
        r.port = f.port;
        // 刻意不用 f.name 當標題：那是 mDNS 實例名（adb-RFCN301WNQZ-AkxzG3），
        // 對人沒有意義。型號要等連上之後才有，在那之前先顯示 IP。
        r.model = "";
        r.discovered = true;
        // ★ 這裡刻意**不自動 probe**。
        // 探測就等於發起一次連線，而對方 adbd 對陌生金鑰會彈「允許 USB 偵錯嗎？」，
        // 那個對話框只活約 30 秒 —— 掃描時自動彈，等於在別人正在用的手機上亂跳視窗，
        // 而且沒人看就白白過期。所以掃到只列出來，等使用者明確按「請求授權」才連。
        r.unauthorized = true;
        rows.add(r);
        render();
    }

    // ================================================================== 新增 / 配對

    private void showAddDialog() {
        LinearLayout box = dialogBox();
        EditText host = input(getString(R.string.hint_host), "", InputType.TYPE_CLASS_TEXT);
        EditText port = input(getString(R.string.hint_port), "5555",
                InputType.TYPE_CLASS_NUMBER);
        box.addView(host);
        box.addView(port);

        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_add_title)
                .setView(box)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String h = text(host);
                    int p = parsePort(text(port), 5555);
                    if (h.isEmpty()) return;

                    // 同一台手機只留一列。無線偵錯的埠每次都不一樣，
                    // 所以先用 host 比對；換埠就就地更新，不要多疊一列。
                    Row exist = findRow(h, p);
                    if (exist == null) {
                        for (Row row : rows) {
                            if (row.host.equals(h)) {
                                exist = row;
                                break;
                            }
                        }
                    }
                    if (exist != null) {
                        exist.port = p;
                        exist.info = null;
                        render();
                        probe(exist);
                        return;
                    }

                    Row r = new Row();
                    r.host = h;
                    r.port = p;
                    rows.add(r);
                    render();
                    probe(r);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 開配對框。優先用掃到的配對埠，其次用清單裡第一個待授權的裝置。 */
    private void openPairDialog() {
        String host = pendingPairHost;
        if (host == null) {
            for (Row r : rows) {
                if (r.unauthorized) {
                    host = r.host;
                    break;
                }
            }
        }
        showPairDialog(host, pendingPairPort);
    }

    private void showPairDialog(String prefillHost, int prefillPairPort) {
        LinearLayout box = dialogBox();
        EditText host = input(getString(R.string.hint_host),
                prefillHost == null ? "" : prefillHost, InputType.TYPE_CLASS_TEXT);
        EditText port = input(getString(R.string.hint_pair_port),
                prefillPairPort > 0 ? String.valueOf(prefillPairPort) : "",
                InputType.TYPE_CLASS_NUMBER);
        EditText code = input(getString(R.string.hint_code), "",
                InputType.TYPE_CLASS_NUMBER);

        TextView hint = new TextView(this);
        hint.setText(R.string.pair_hint);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTextColor(COLOR_DIM);
        hint.setPadding(0, dp(8), 0, 0);

        box.addView(host);
        box.addView(port);
        box.addView(code);
        box.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_pair_title)
                .setView(box)
                .setPositiveButton(R.string.ok, (d, w) ->
                        doPair(text(host), parsePort(text(port), 0), text(code)))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doPair(String host, int pairPort, String code) {
        if (host.isEmpty() || pairPort <= 0 || code.isEmpty()) {
            Toast.makeText(this, R.string.msg_pair_need_input, Toast.LENGTH_SHORT).show();
            return;
        }
        setStatus(getString(R.string.status_pairing));
        bg.execute(() -> {
            boolean ok = AdbClient.pair(getApplicationContext(), host, pairPort, code);
            ui.post(() -> {
                if (!ok) {
                    setStatus(getString(R.string.msg_pair_fail));
                    Toast.makeText(this, R.string.msg_pair_fail, Toast.LENGTH_LONG).show();
                    return;
                }
                setStatus(getString(R.string.msg_pair_ok));
                // 配對成功就代表金鑰已被對方接受，直接把這一列接上並抓資訊。
                // 不順手幫使用者裝 Host —— 那是另一件事，讓他自己決定。
                Integer cp = connectPorts.get(host);
                int connectPort = cp != null ? cp : 5555;
                Row r = findRow(host, connectPort);
                if (r == null) {
                    r = new Row();
                    r.host = host;
                    r.port = connectPort;
                    rows.add(r);
                }
                r.unauthorized = false;
                render();
                probe(r);
            });
        });
    }

    // ================================================================== 安裝

    /**
     * 「安裝」按鈕 —— 先問要裝哪一種。
     *
     * <p>兩件事都叫「安裝」，但用途完全不同：Host 是為了讓對方能被控制
     * （順便把 ADB 釘在 5555）；選 APK 則是一般的遠端安裝。
     * 擠成兩顆按鈕會讓卡片變成五顆、在 1080px 上必然換行，所以用一層選單。
     */
    private void showInstallChooser(Row r) {
        if (r.info == null || !r.info.reachable) {
            Toast.makeText(this, R.string.msg_need_connect, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.install_chooser_title, r.host))
                .setItems(new CharSequence[]{
                        getString(R.string.install_choice_host),
                        getString(R.string.install_choice_apk)
                }, (d, which) -> {
                    if (which == 0) runInstall(r);
                    else pickApk(r);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 開系統的檔案選擇器挑 APK。
     *
     * <p>用 {@code ACTION_OPEN_DOCUMENT} 而不是 {@code GET_CONTENT}：前者回傳的
     * URI 在這一次授權內一定讀得到，而且不依賴對方 App 是否還活著。
     *
     * <p>MIME 故意設 {@code *}{@code /*} 再用 {@code EXTRA_MIME_TYPES} 限縮 ——
     * 不少檔案管理器（含三星「我的檔案」）把 APK 標成
     * {@code application/octet-stream}，只設 apk 那個 MIME 會讓檔案整片變灰。
     */
    private void pickApk(Row r) {
        pendingInstall = r;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive",
                "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_PICK_APK);
        } catch (Exception e) {
            pendingInstall = null;
            Toast.makeText(this, R.string.msg_no_file_picker, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_APK || resultCode != RESULT_OK) return;

        Uri uri = data == null ? null : data.getData();
        final Row r = pendingInstall;
        pendingInstall = null;
        if (uri == null || r == null) return;

        final String name = displayName(uri);
        setStatus(getString(R.string.status_copying_apk, name));

        // 先複製到自家 cache 再處理：content:// 的權限只在這次回呼有效，
        // 而 push 是在背景執行緒慢慢跑的，不能拿著 URI 過去。
        bg.execute(() -> {
            File apk = null;
            String err = null;
            try {
                apk = copyToCache(uri, name);
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final File file = apk;
            final String fail = err;
            ui.post(() -> {
                if (file == null) {
                    setStatus(getString(R.string.msg_apk_read_failed, fail == null ? "" : fail));
                    return;
                }
                installApkTo(r, file);
            });
        });
    }

    /** 把選到的 content:// 複製成 cache 目錄下的實體檔。 */
    private File copyToCache(Uri uri, String name) throws IOException {
        File out = new File(getCacheDir(), name.endsWith(".apk") ? name : name + ".apk");
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException(getString(R.string.msg_apk_read_failed, ""));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        if (out.length() == 0) throw new IOException(getString(R.string.msg_apk_empty));
        return out;
    }

    /** 取檔案顯示名稱；拿不到就退回用路徑尾巴。 */
    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String s = c.getString(0);
                if (s != null && !s.isEmpty()) return s;
            }
        } catch (Exception ignored) {
            // 查不到名字不影響流程
        }
        String p = uri.getLastPathSegment();
        return p == null || p.isEmpty() ? "picked.apk" : p;
    }

    /**
     * 把挑好的 APK 裝到遠端，過程像 Host 安裝那樣逐行輸出。
     *
     * <p>沒有 {@code adb install} 可用（那是 adb server 的功能），
     * 所以走「shell 串流傳檔 → 同一條串流裡 {@code pm install} → 清理」，
     * 細節見 {@link AdbClient#installApk(File, AdbClient.Progress)}。
     */
    private void installApkTo(Row r, File apk) {
        LinearLayout box = dialogBox();
        TextView pct = new TextView(this);
        ProgressBar bar = progressBar(box, pct);
        pct.setText(getString(R.string.progress_preparing, apk.length() / (1024 * 1024)));

        TextView log = new TextView(this);
        log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        log.setTextColor(0xFF333333);
        log.setText(getString(R.string.msg_apk_pushing, apk.length() / (1024 * 1024)));
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(160)));
        scroll.addView(log);
        box.addView(scroll);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.msg_apk_install_title, apk.getName()))
                .setView(box)
                .setPositiveButton(R.string.close, null)
                .create();
        dialog.show();

        final AdbClient.Progress progress = progressCallback(bar, pct);
        final String host = r.host;
        final int port = r.port;
        bg.execute(() -> {
            AdbClient client = new AdbClient(getApplicationContext(), host, port);
            String result = null;
            String err = null;
            try {
                result = client.installApk(apk, progress);
            } catch (Exception e) {
                err = AdbClient.describeError(e);
            } finally {
                client.close();
                // 這個檔只是為了推一次，裝完就別留著佔空間（11 MB 起跳）
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
            }

            final String res = result;
            final String fail = err;
            // 注意順序：上面的 finally 已經把 client 關掉了，才輪到這裡 probe()。
            // probe() 會另開一條連線，兩條 session 同時活著時新的那條會卡住
            // —— 症狀就是安裝明明成功，卡片卻變紅字「連線逾時」。
            ui.post(() -> {
                if (fail != null) {
                    pct.setText(R.string.progress_failed);
                    log.append("\n\n❌ " + fail);
                    setStatus(getString(R.string.msg_apk_install_failed, fail));
                    return;
                }
                String out = res == null ? "" : res.trim();
                boolean ok = out.contains("Success");
                if (!ok) pct.setText(R.string.progress_failed);
                log.append("\n\n" + (ok ? "✅ " : "❌ ") + out);
                if (!ok) {
                    // pm install 只回一行 Failure [CODE: 人話]，CODE 才是可操作的資訊。
                    // USER_RESTRICTED 是小米/HyperOS 的 USB 安裝鎖，光看英文沒人知道要改什麼。
                    if (out.contains("USER_RESTRICTED")) {
                        log.append("\n\n" + getString(R.string.msg_install_user_restricted));
                    }
                }
                setStatus(getString(ok
                        ? R.string.msg_apk_install_done
                        : R.string.msg_apk_install_failed, host));
                // 裝完順手重探一次，讓卡片上的資訊保持新鮮
                probe(r);
            });
        });
    }
    // ================================================================== 安裝 Host

    private void runInstall(Row r) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(getString(R.string.msg_install_title, r.host));

        LinearLayout box = dialogBox();
        TextView pct = new TextView(this);
        ProgressBar bar = progressBar(box, pct);
        pct.setText(R.string.msg_installing);

        TextView log = new TextView(this);
        log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        log.setTextColor(0xFF333333);
        log.setText(R.string.msg_installing);
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)));
        scroll.addView(log);
        box.addView(scroll);
        b.setView(box);
        b.setPositiveButton(R.string.close, null);
        AlertDialog dialog = b.create();
        dialog.show();

        final AdbClient.Progress progress = progressCallback(bar, pct);
        final String host = r.host;
        final int port = r.port;
        bg.execute(() -> {
            AdbClient client = new AdbClient(getApplicationContext(), host, port);
            HostInstaller.Result res = null;
            String err = null;
            try {
                res = HostInstaller.install(
                        getApplicationContext(), client,
                        step -> ui.post(() -> log.append("\n"
                                + (step.ok ? "✅ " : "❌ ") + step.title
                                + (step.detail.isEmpty() ? "" : "\n     " + step.detail))),
                        progress);
            } catch (Exception e) {
                err = e.getMessage();
            } finally {
                // ★ 一定要在丟出 UI 工作之前關掉。probe() 會另開一條連線，
                // 兩條 session 同時活著時，新的那條 stream 會一直等不到回應
                // —— 症狀就是安裝明明成功，卡片卻變紅字「連線逾時」。
                client.close();
            }

            final HostInstaller.Result done = res;
            final String failure = err;
            ui.post(() -> {
                if (done == null) {
                    pct.setText(R.string.progress_failed);
                    log.append("\n\n❌ " + failure);
                    return;
                }
                if (!done.ok) pct.setText(R.string.progress_failed);
                log.append("\n\n" + getString(done.ok
                        ? R.string.msg_install_done : R.string.msg_install_failed));
                // 裝好 Host 之後埠會固定在 5555，改用它重探一次
                if (done.ok && r.port != 5555) {
                    r.port = 5555;
                    r.info = null;
                }
                probe(r);
            });
        });
    }

    // ================================================================== 請求授權

    /**
     * 請求授權 —— 按下去就開始等，中間不再問「要不要開始」。
     *
     * <p>對方會彈「允許 USB 偵錯嗎？」，那個框只活約 30 秒，所以這裡顯示倒數
     * 讓使用者知道還剩多少時間（這是狀態，不是詢問）。
     *
     * <p>走的是「撐住同一條連線 + 輪詢」：開一條連線後就<b>不再重連</b>，
     * 對方按下「允許」時 {@code A_CNXN} 會在同一條 socket 上送達，
     * {@code isConnected()} 立刻變 true。詳見 {@link AdbClient#authorize(long)}。
     * 授權成功後才把這條連線關掉，交給 {@link #probe(Row)} 重新探測
     * —— 走過完整 {@code A_AUTH} 握手的連線再開 shell 容易卡住。
     *
     * <p><b>這一列記的是 mDNS 隨機埠就先試它、快速失敗再換 5555。</b>
     * 隨機埠是 TLS，憑證不被信任時連握手都過不去，對方根本不會彈框；
     * 只有明文的 5555 會走 {@code A_AUTH}，也就只有它彈得出那個對話框。
     */
    private void showAuthorize(Row r) {
        final String host = r.host;
        final int port = r.port;

        TextView progress = new TextView(this);
        progress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        progress.setPadding(dp(20), dp(8), dp(20), dp(8));
        progress.setText(getString(R.string.auth_waiting, AUTH_TIMEOUT_S));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.auth_title)
                .setView(progress)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();

        final int[] left = {AUTH_TIMEOUT_S};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!dialog.isShowing()) return;
                left[0]--;
                if (left[0] <= 0) return;
                progress.setText(getString(R.string.auth_waiting, left[0]));
                ui.postDelayed(this, 1000L);
            }
        };
        ui.postDelayed(tick, 1000L);

        // 刻意<b>不</b>設 busy：那會讓卡片顯示「檢查中」，把「等待對方允許」蓋掉。
        // 這裡要的狀態是 authWatching（橘色「等待對方允許…」）。
        r.authWatching = true;
        render();

        authBg.execute(() -> {
            AuthAttempt a = tryAuthorize(host, port, AUTH_TIMEOUT_S * 1000L);
            int usedPort = port;

            // ★ 這個埠彈不出授權框就換 5555。
            //
            // mDNS 隨機埠是 TLS：憑證不被信任時握手就被打回，libadb 把那個 SSL 例外
            // 吞在自己的執行緒裡、對外只丟 IOException("Connection failed")，
            // 所以這裡<b>不能只看「回 false」</b>，要看例外型別（見 shouldTryPlainPort）。
            // 而且必須是「快速失敗」：慢慢失敗代表框已經彈出來、對方正在考慮，
            // 這時候換埠只會讓對方多一個框，原本那個還會變孤兒。
            if (!a.ok && port != 5555 && a.cause != null && a.spentMs < 8_000
                    && AdbClient.shouldTryPlainPort(a.cause)) {
                DiagLog.i(TAG, "授權在埠 " + port + " 上失敗（" + a.error + "），改試 5555 " + host);
                a = tryAuthorize(host, 5555, AUTH_TIMEOUT_S * 1000L);
                usedPort = 5555;
            }

            final boolean success = a.ok;
            final String message = a.error;
            final int adopted = usedPort;
            ui.post(() -> {
                if (dialog.isShowing()) dialog.dismiss();
                r.authWatching = false;
                if (success) {
                    r.unauthorized = false;
                    if (adopted != r.port) {
                        DiagLog.i(TAG, "授權成功於 5555，把 " + host + " 的埠從 " + r.port + " 改成 " + adopted);
                        r.port = adopted;
                    }
                    setStatus(getString(R.string.auth_ok, host));
                    render();
                    probe(r);            // 用新連線抓一次裝置資訊（有逾時保護）
                    return;
                }
                r.unauthorized = true;
                setStatus(getString(R.string.auth_failed,
                        message.isEmpty() ? getString(R.string.auth_timeout) : message));
                render();
            });
        });
    }

    /** 一次授權嘗試的結果。{@code spentMs} 用來判斷「這次是快速失敗，還是對方真的在考慮」。 */
    private static class AuthAttempt {
        boolean ok;
        String error = "";
        long spentMs;
        Throwable cause;
    }

    /**
     * 在指定埠上請求授權，用完就把連線丟掉。
     *
     * <p><b>一定不能復用這條連線</b>：它走過完整的 {@code A_AUTH} 握手，
     * 在那之上再開 shell 會卡住不返回（實測卡片就永遠停在「檢查中」）。
     */
    private AuthAttempt tryAuthorize(String host, int port, long budgetMs) {
        AuthAttempt r = new AuthAttempt();
        long t0 = System.currentTimeMillis();
        AdbClient client = new AdbClient(getApplicationContext(), host, port);
        try {
            r.ok = client.authorize(budgetMs);
        } catch (Exception e) {
            r.cause = e;
            r.error = AdbClient.describeError(e);
        } finally {
            closeQuietly(client);
        }
        r.spentMs = System.currentTimeMillis() - t0;
        DiagLog.i(TAG, "授權嘗試 " + host + ":" + port + " → " + (r.ok ? "成功" : "失敗")
                + "（" + r.spentMs + " ms）" + (r.error.isEmpty() ? "" : " " + r.error));
        return r;
    }

    // ================================================================== 鏡像

    /**
     * 開鏡像畫面。
     *
     * <p>這裡刻意<b>不</b>先確認裝置能不能連：鏡像自己有完整的啟動流程，
     * 再多一次探測只是讓使用者多等 3 秒，而且探測連線和鏡像連線同時活著
     * 會互相卡（同一個 adbd 對第二條 session 的容忍度很差，安裝 APK 那輪踩過）。
     */
    private void openMirror(Row r) {
        Intent i = new Intent(this, MirrorActivity.class);
        i.putExtra(MirrorActivity.EXTRA_HOST, r.host);
        i.putExtra(MirrorActivity.EXTRA_PORT, r.port);
        startActivity(i);
    }

    /**
     * 開檔案總管。
     *
     * <p>刻意<b>不在這裡</b>先探測或建立連線：檔案總管自己有一條專用連線
     * （大檔會把連線弄成半死，不能跟鏡像共用），讓它自己建比較乾淨。
     * 這裡只負責把位址交過去。
     */
    private void openFiles(Row r) {
        Intent i = new Intent(this, FilesActivity.class);
        i.putExtra(FilesActivity.EXTRA_HOST, r.host);
        i.putExtra(FilesActivity.EXTRA_PORT, r.port);
        startActivity(i);
    }

    // ================================================================== 詳情 / 移除

    private void showDetail(Row r) {
        if (r.info == null || !r.info.reachable) {
            Toast.makeText(this, R.string.msg_need_connect, Toast.LENGTH_SHORT).show();
            return;
        }
        // 剛授權完的那一小段時間裡，卡片已經變「已連上」但詳情還是空的
        // （adbd 那時 shell 不回應，見 probeAfterAuth）。點進來就順手再抓一次。
        if (r.info.model == null || r.info.model.isEmpty()) {
            Toast.makeText(this, R.string.msg_reading_info, Toast.LENGTH_SHORT).show();
            probe(r);
            return;
        }
        DeviceInfo i = r.info;
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.lbl_ip)).append("：").append(r.host).append(':').append(r.port).append('\n');
        sb.append(getString(R.string.lbl_model)).append("：").append(i.model)
                .append(i.brand.isEmpty() ? "" : "（" + i.brand + "）").append('\n');
        sb.append(getString(R.string.lbl_android)).append("：").append(i.androidText()).append('\n');
        sb.append(getString(R.string.lbl_screen)).append("：").append(i.screenText()).append('\n');
        sb.append(getString(R.string.lbl_battery)).append("：").append(i.batteryText()).append('\n');
        sb.append(getString(R.string.lbl_storage)).append("：").append(i.storageText()).append('\n');
        sb.append(getString(R.string.lbl_foreground)).append("：")
                .append(i.foreground.isEmpty() ? "—" : i.foreground);

        new AlertDialog.Builder(this)
                .setTitle(i.displayName())
                .setMessage(sb.toString())
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void confirmRemove(Row r) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.msg_remove_title)
                .setMessage(getString(R.string.msg_remove_msg, r.host + ":" + r.port))
                .setPositiveButton(R.string.ok, (d, w) -> {
                    store.remove(r.host, r.port, r.serial);
                    rows.remove(r);
                    render();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ================================================================== 進度條

    /**
     * 在對話框裡放一條進度條 + 一行百分比文字。
     *
     * @return 進度條本體，交給 {@link #progressCallback(ProgressBar, TextView)}
     */
    private ProgressBar progressBar(LinearLayout box, TextView pct) {
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        box.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(10)));

        pct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pct.setTextColor(0xFF444444);
        pct.setPadding(0, dp(6), 0, dp(8));
        box.addView(pct);
        return bar;
    }

    /**
     * 把 {@link AdbClient.Progress} 接到剛剛那條進度條。
     *
     * <p>回呼是從背景執行緒來的（傳檔那條），所以要自己丟回主執行緒。
     * 另外<b>同一個百分比只更新一次</b>：60 MB 的 APK 會回呼上百次，
     * 每次都 {@code setText} 會讓畫面一直重排，看起來反而像卡住。
     *
     * <p><b>「對方安裝中」那一段刻意不用不確定動畫。</b>實測（Samsung、
     * {@code Theme.DeviceDefault.Light}）{@code setIndeterminate(true)} 之後
     * 那條橫向 ProgressBar 整段是空的 —— 畫面上會變成「進度條不見了」，
     * 而那段正好是等最久的一段。改成停在 100% 並在文字上數秒數：
     * 進度條代表「傳輸」這件事已經完成，秒數代表「對方還在忙」。
     */
    private AdbClient.Progress progressCallback(ProgressBar bar, TextView pct) {
        final int[] lastPct = {-1};
        final long[] installStart = {0};
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (installStart[0] == 0) return;
            long s = (System.currentTimeMillis() - installStart[0]) / 1000;
            pct.setText(getString(R.string.progress_installing, s));
            ui.postDelayed(tick[0], 1000L);
        };

        return (sent, total, phase) -> ui.post(() -> {
            switch (phase) {
                case AdbClient.Progress.PHASE_PUSH: {
                    int p = total <= 0 ? 0 : (int) Math.min(100, sent * 100 / total);
                    if (p == lastPct[0]) return;
                    lastPct[0] = p;
                    bar.setIndeterminate(false);
                    bar.setProgress(p);
                    pct.setText(getString(R.string.progress_push, p,
                            sent / (1024 * 1024),
                            Math.max(1, total / (1024 * 1024))));
                    break;
                }
                case AdbClient.Progress.PHASE_INSTALL:
                    // 位元組送完了，接下來是對方的 pm install —— 沒有任何進度可問
                    installStart[0] = System.currentTimeMillis();
                    bar.setIndeterminate(false);
                    bar.setProgress(100);
                    pct.setText(getString(R.string.progress_installing, 0L));
                    ui.postDelayed(tick[0], 1000L);
                    break;
                default:
                    installStart[0] = 0;
                    ui.removeCallbacks(tick[0]);
                    bar.setIndeterminate(false);
                    bar.setProgress(100);
                    pct.setText(R.string.progress_done);
                    break;
            }
        });
    }

    // ================================================================== 日誌

    /** 看最近的日誌，順便匯出。 */
    private void showLogs() {
        String text = DiagLog.recent();
        boolean empty = text.isEmpty();

        TextView view = new TextView(this);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextColor(0xFF333333);
        view.setTextIsSelectable(true);
        view.setText(empty ? getString(R.string.log_empty) : text);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(320)));
        scroll.setPadding(dp(12), dp(8), dp(12), dp(8));
        scroll.addView(view);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_logs_title)
                .setView(scroll)
                .setPositiveButton(R.string.log_export, (d, w) -> exportLogs())
                .setNeutralButton(R.string.log_clear, (d, w) -> {
                    DiagLog.clear();
                    setStatus(getString(R.string.log_cleared));
                })
                .setNegativeButton(R.string.close, null)
                .create();
        dialog.show();

        // 最新的一行在最後面，進來就捲到底，不然看到的永遠是最舊的
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * 匯出日誌：打包成一個檔案，交給系統的分享面板。
     *
     * <p>為什麼是「分享」而不是「存到下載資料夾」：Android 10 之後寫共用儲存空間
     * 要 MediaStore 或 SAF，而使用者真正想做的事幾乎都是「把這個傳給某人／傳到電腦」。
     * 分享面板一次解決，也不必申請任何儲存權限。
     *
     * <p>分享意圖只帶得動 {@code content://}（見 manifest 的 FileProvider），
     * 拿不到 URI 時退回剪貼簿 —— 至少還能把最後幾百行貼出來。
     */
    private void exportLogs() {
        File snapshot;
        try {
            snapshot = DiagLog.export(this, logHeader());
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.log_export_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
            return;
        }

        Uri uri;
        try {
            uri = FileProvider.getUriForFile(this, LOG_AUTHORITY, snapshot);
        } catch (Exception e) {
            DiagLog.e(TAG, "取得日誌 URI 失敗", e);
            copyLogs();
            return;
        }

        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_export_subject))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.log_share_title)));
            setStatus(getString(R.string.log_exported, snapshot.getName()));
        } catch (Exception e) {
            DiagLog.e(TAG, "沒有可分享的 App", e);
            copyLogs();
        }
    }

    private void copyLogs() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.dlg_logs_title), logHeader() + "\n" + DiagLog.recent()));
            Toast.makeText(this, R.string.log_copied, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.log_export_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 日誌開頭補上的環境資訊。
     *
     * <p>收到日誌的人第一件事一定是問「哪支手機、哪個版本」。
     * 先寫在檔案裡，省掉一輪來回。
     */
    private String logHeader() {
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // 取不到版本不影響匯出
        }
        StringBuilder sb = new StringBuilder();
        sb.append("OmniADB Client ").append(version).append('\n');
        sb.append("手機：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("（").append(Build.DEVICE).append("）\n");
        sb.append("Android：").append(Build.VERSION.RELEASE)
                .append("（SDK ").append(Build.VERSION.SDK_INT).append("）\n");
        sb.append("時間：").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.US).format(new java.util.Date())).append('\n');
        sb.append("清單：").append(rows.size()).append(" 台");
        for (Row row : rows) {
            sb.append('\n').append("  - ").append(row.host).append(':').append(row.port)
                    .append(' ').append(row.model == null || row.model.isEmpty() ? "?" : row.model);
            if (row.serial != null && !row.serial.isEmpty()) {
                sb.append(" sn=").append(row.serial);
            }
            sb.append(row.info != null && row.info.reachable ? " 已連上" : " 未連上");
        }
        sb.append('\n');
        return sb.toString();
    }

    // ================================================================== 小工具

    private void setStatus(String text) {
        statusView.setText(text);
    }

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        return box;
    }

    private EditText input(String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(type);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        return e;
    }

    private static String text(EditText e) {
        return e.getText().toString().trim();
    }

    /** 找出清單裡同一個 host:port 的那一列。 */
    private Row findRow(String host, int port) {
        for (Row r : rows) {
            if (r.host.equals(host) && r.port == port) return r;
        }
        return null;
    }

    private static int parsePort(String s, int fallback) {
        try {
            int v = Integer.parseInt(s);
            return (v > 0 && v <= 65535) ? v : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
