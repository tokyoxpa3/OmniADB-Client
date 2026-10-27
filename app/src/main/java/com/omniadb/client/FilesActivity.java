package com.omniadb.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 檔案總管 —— 一邊是對方的手機，一邊是自己，中間直接搬檔案。
 *
 * <h3>為什麼是「分頁」而不是左右兩欄</h3>
 * 手機直向只有 1080px，兩欄各 540px，光是 {@code Camera/IMG_20261026_193012.jpg}
 * 這種檔名就會被截到看不出是哪一張。所以直向做成「本機／遠端」分頁，
 * 一次只看一邊，按下面那顆「傳到對方」把選中的東西搬過去。
 *
 * <h3>兩邊的「檔案系統」其實完全不一樣</h3>
 * <table border="1">
 *   <tr><th></th><th>遠端</th><th>本機</th></tr>
 *   <tr><td>怎麼列</td><td>ADB SYNC 的 {@code LIST}</td><td>SAF 的 {@code DocumentsContract}</td></tr>
 *   <tr><td>怎麼讀寫</td><td>{@code RECV} / {@code SEND}</td><td>{@code openInputStream} / {@code openOutputStream}</td></tr>
 *   <tr><td>路徑</td><td>{@code /sdcard/DCIM}</td><td>{@code content://…/document/primary:DCIM}</td></tr>
 * </table>
 *
 * <p>本機這一側<b>刻意不用 {@code File}</b>：Android 10 之後 scoped storage
 * 讓 App 根本看不到 {@code /sdcard}，硬要走那條路就得要
 * {@code MANAGE_EXTERNAL_STORAGE}（上架會被打回票，而且對使用者是過度授權）。
 * 改成讓使用者自己挑一個資料夾（SAF 樹），我們只碰得到他挑的那個 ——
 * 權限最小，而且不必求任何危險權限。
 *
 * <p><b>連線是獨立的</b>：檔案總管自己開一條 {@link AdbClient}，不跟鏡像共用。
 * 理由見 {@link AdbSync}：大檔會把連線弄成半死，而那條連線在鏡像那邊扛著影像。
 */
public class FilesActivity extends Activity {

    private static final String TAG = "OmniADB-Client";

    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";

    private static final String PREF = "omniadb_files";
    private static final String KEY_TREE = "local_tree";
    private static final String KEY_TREE_NAME = "local_tree_name";
    private static final String KEY_SORT = "sort_mode";

    /**
     * 遠端 shell 指令的結束記號。
     *
     * <p>理由跟 {@code AdbClient#shell} 一樣：libadb 的 {@code read()} 不能等 EOF。
     * 另外這裡還兼當「成功／失敗」的判準 —— {@code rm}/{@code mv}/{@code mkdir}
     * 成功時<b>一個字都不印</b>，所以記號之前只要有任何輸出就是錯誤訊息。
     */
    private static final String SHELL_END = "@@OMNIADB:END";

    /**
     * 跟 manifest 裡的 {@code android:authorities} 是同一個東西：兩邊都用
     * {@code <applicationId>.fileprovider}（manifest 走 {@code ${applicationId}} 佔位），
     * 所以 {@code -PdevSuffix} 的 {@code .dev} 包不會跟正式包撞 provider。
     */
    private String fileAuthority() {
        return getPackageName() + ".fileprovider";
    }

    /**
     * 超過這個大小才問「要先下載」。小檔（縮圖、txt）直接開，
     * 每次點一張照片都彈一個確認框只是煩人。
     */
    private static final long OPEN_CONFIRM_BYTES = 20L * 1024 * 1024;

    private static final int REQ_TREE = 4001;

    private static final int SIDE_LOCAL = 0;
    private static final int SIDE_REMOTE = 1;

    // 排序方式。目錄永遠排前面，這裡只決定同一群裡誰先。
    private static final int SORT_NAME_ASC = 0;
    private static final int SORT_NAME_DESC = 1;
    private static final int SORT_SIZE_DESC = 2;
    private static final int SORT_TIME_DESC = 3;

    /** 遠端開機預設位置。{@code /sdcard} 是 shell 使用者一定有權限的地方。 */
    private static final String REMOTE_ROOT = "/sdcard";

    private static final int COLOR_DIM = 0xFF777777;
    private static final int COLOR_TEXT = 0xFF111111;
    private static final int COLOR_SELECTED = 0xFFE8F0FE;
    private static final int COLOR_DIVIDER = 0xFFE4E4E4;
    /** 縮圖框的底色：縮圖還沒好時就是這個灰方塊，不是一片白。 */
    private static final int COLOR_THUMB_BG = 0xFFF1F3F4;

    /** 縮圖邊長（dp）。40 是一屏還看得到 15 列、又認得出照片的折衷。 */
    private static final int THUMB_DP = 40;

    /**
     * 超過這個大小就不做縮圖。
     *
     * <p>遠端縮圖是「整個檔案抓回來再解」，不是抓個預覽。一張 12 MB 的原圖要
     * 好幾秒，而使用者只是想看那是什麼 —— 划不來，留圖示就好。
     */
    private static final long THUMB_MAX_BYTES = 12L * 1024 * 1024;

    /** 解碼目標邊長（px）。40dp 在 4x 密度下是 160px。 */
    private static final int THUMB_PX = 160;

    private final Handler ui = new Handler(Looper.getMainLooper());

    /**
     * 傳輸用<b>單執行緒</b>。
     *
     * <p>不能開池：SYNC 通道是有狀態的（一條 stream 上連續做 RECV/SEND），
     * 兩個執行緒同時講話會把封包交錯在一起，整個協定就毀了。
     */
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /**
     * 縮圖用<b>另一條</b>單執行緒。
     *
     * <p>不能跟 {@link #io} 共用：那個池子是「使用者按了才動」的佇列。
     * 進到一個有 100 張照片的資料夾時，八張可見縮圖排進去，接下來點資料夾的
     * {@code LIST} 就得等這八張全部抓完 —— 遠端一張要 0.3 秒以上，等兩三秒
     * 才看到下一個目錄，這是不能接受的。
     *
     * <p>兩條執行緒同時講 SYNC 也不安全（協定是有狀態的），所以
     * {@link AdbSync} 的公開方法全部加了 {@code synchronized}。最壞情況是
     * 「多等一張圖」的時間，而不是整條流錯位。
     */
    private final ExecutorService thumbExec = Executors.newSingleThreadExecutor();

    private ThumbCache thumbs;

    /** 捲動容器。縮圖要判斷「這一列還在不在畫面上」，需要它的參照。 */
    private ScrollView scrollView;

    /** 目前畫面上看得到的縮圖 key。背景執行緒會讀，所以用併發集合。 */
    private final Set<String> visibleThumbKeys =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private String host;
    private int port;

    /** 檔案專用連線，第一次用到時才建。 */
    private AdbClient client;

    private int side = SIDE_REMOTE;

    // ---- 遠端狀態 ----
    private String remotePath = REMOTE_ROOT;
    private List<AdbSync.Entry> remoteItems = new ArrayList<>();

    // ---- 本機狀態 ----
    private Uri treeUri;
    /** 使用者挑的那個根目錄的顯示名稱（Pictures、Download…），拿不到就是 null。 */
    private String localRootName;
    private final List<LocalDir> localStack = new ArrayList<>();
    private List<LocalItem> localItems = new ArrayList<>();

    /** 目前選取的項目。key 是「遠端的完整路徑」或「本機的 document id」。 */
    private final Set<String> selected = new LinkedHashSet<>();

    // ---- 剪貼簿 ----
    // 為什麼要有它：在手機上「拖著檔案橫過螢幕」不是個手勢，而「選取 → 傳到對面」
    // 只解決了跨裝置搬檔。真正每天在用的是<b>同一個裝置內</b>把東西換個資料夾，
    // 以及「這份東西我要放兩三個地方」。複製／剪下／貼上是唯一同時涵蓋
    // 這四種組合（本機↔本機、本機→遠端、遠端→本機、遠端↔遠端）的說法。
    private static final int CLIP_NONE = 0;
    private static final int CLIP_COPY = 1;
    private static final int CLIP_CUT = 2;
    private int clipMode = CLIP_NONE;
    /** 剪下／複製時是哪一側 —— 貼上的那一側可能已經換了。 */
    private int clipSide = SIDE_REMOTE;
    private final List<Job> clipJobs = new ArrayList<>();

    /**
     * 要不要顯示點開頭的隱藏項目。預設<b>不要</b>。
     *
     * <p>實測 POCO 的 {@code /sdcard} 有 153 項，其中一大半是
     * {@code .7934039a}、{@code .gs_fs0}、{@code .UTSystemConfig} 這種系統雜物 ——
     * 不濾掉的話使用者得滑過十幾個看不懂的目錄才看到 {@code DCIM}。
     */
    private boolean showHidden;

    /** 目前排序方式，見 {@code SORT_*}。記在使用者偏好裡，下次打開照舊。 */
    private int sortMode = SORT_NAME_ASC;

    /**
     * 搜尋字串（只篩目前這個資料夾，不做全機搜尋）。
     *
     * <p>為什麼只篩目前的資料夾：全機搜尋要遞迴 {@code LIST} 整棵樹，在對方身上
     * 可能要跑幾十秒，而且沒有任何進度可言。使用者真正會抱怨的是
     * 「{@code /sdcard} 有 150 項，找一個檔要滑半天」—— 篩這一層就解決了。
     */
    private String filter = "";

    // ---- UI ----
    private TextView tabLocal;
    private TextView tabRemote;
    /** 麵包屑容器：每一段都是一顆可以點的 TextView，不是一行字串。 */
    private LinearLayout trailBox;
    private HorizontalScrollView trailScroll;
    private LinearLayout searchRow;
    private EditText searchBox;
    private TextView statusView;
    private ProgressBar loadingBar;
    private LinearLayout listBox;
    private Button upButton;
    private Button actionButton;
    private LinearLayout bottomBar;
    private TextView selectedView;
    private TextView destView;
    private TextView hiddenToggle;

    /** 有傳輸在跑時不讓使用者亂點（選單、換目錄都會打亂進度）。 */
    private volatile boolean transferring;

    // ================================================================== 資料結構

    /** 本機的一項（SAF 文件）。 */
    private static final class LocalItem {
        final String docId;
        final String name;
        final String mime;
        final long size;
        final long mtime;
        final boolean dir;

        LocalItem(String docId, String name, String mime, long size, long mtime, boolean dir) {
            this.docId = docId;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.mtime = mtime;
            this.dir = dir;
        }
    }

    /** 本機導覽堆疊的一層。 */
    private static final class LocalDir {
        final String docId;
        final String name;

        LocalDir(String docId, String name) {
            this.docId = docId;
            this.name = name;
        }
    }

    /** 一次傳輸要搬的一項。{@code key} 是遠端完整路徑或本機 document id。 */
    private static final class Job {
        final String key;
        final String name;
        final boolean dir;

        Job(String key, String name, boolean dir) {
            this.key = key;
            this.name = name;
            this.dir = dir;
        }
    }

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

        SharedPreferences p = getSharedPreferences(PREF, MODE_PRIVATE);
        sortMode = p.getInt(KEY_SORT, SORT_NAME_ASC);
        String saved = p.getString(KEY_TREE, null);
        if (saved != null) {
            treeUri = Uri.parse(saved);
            // 麵包屑第一段要顯示使用者挑的資料夾叫什麼，不然每個根目錄都只寫「本機」，
            // 挑過 Pictures 跟 Download 之後根本分不出現在在哪。
            localRootName = p.getString(KEY_TREE_NAME, null);
            if (localRootName == null) localRootName = queryRootName(treeUri);
            // 權限是「開機後仍然有效」的那種，但使用者可能在系統設定裡撤銷過，
            // 所以下面第一次列目錄時還是要接住 SecurityException。
            localStack.add(new LocalDir(DocumentsContract.getTreeDocumentId(treeUri), null));
        }

        setContentView(buildUi());
        thumbs = new ThumbCache(this);
        selectSide(SIDE_REMOTE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 離開畫面就別抱著一條連線：實測它會被對方單方面收掉（休眠／省電／系統凍結），
        // 留著只會在下次操作時撞到一個已經死掉的通道。回來時重連很快（區網 200~400 ms）。
        // 傳輸中例外 —— 那時候關連線等於把使用者的操作中斷掉。
        if (!transferring) closeClient();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        ui.removeCallbacks(thumbScan);
        thumbExec.shutdownNow();
        closeClient();
        super.onDestroy();
    }

    /**
     * 返回鍵：<b>取消選取 → 上一層 → 才離開</b>。
     *
     * <p>照順序做是因為這三個都是使用者「後悔一步」的自然順序。
     * 直接 finish() 的話，在 {@code /sdcard/DCIM/Camera} 按返回會整頁關掉，
     * 要回去得從頭點進來 —— 沒有一個檔案總管是這樣。
     */
    @Override
    public void onBackPressed() {
        if (transferring) return;
        if (!selected.isEmpty()) {
            selected.clear();
            updateBottomBar();
            renderList();
            return;
        }
        if (side == SIDE_LOCAL && localStack.size() > 1) {
            goUp();
            return;
        }
        if (side == SIDE_REMOTE && !REMOTE_ROOT.equals(remotePath)) {
            goUp();
            return;
        }
        super.onBackPressed();
    }

    /** 關掉檔案專用連線（換裝置、離開畫面、傳輸取消都走這裡）。 */
    private void closeClient() {
        final AdbClient c = client;
        client = null;
        if (c == null) return;
        // close() 會關 socket，可能阻塞；丟到背景，別卡 UI
        Thread t = new Thread(c::close, "omniadb-files-close");
        t.setDaemon(true);
        t.start();
    }

    // ================================================================== UI 組裝

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        root.addView(buildTitleBar());
        root.addView(buildTabs());
        root.addView(buildPathBar());
        root.addView(buildSearchRow());

        View divider = new View(this);
        divider.setBackgroundColor(COLOR_DIVIDER);
        root.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        scrollView = new ScrollView(this);
        ScrollView scroll = scrollView;
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scroll.setLayoutParams(scrollLp);
        // 停下來才去抓縮圖：快速滑過去的列不必下載。遠端一張要 0.3 秒以上，
        // 邊滑邊抓會讓整條佇列塞滿永遠看不到的圖。
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> scheduleThumbs());

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, dp(4), 0, dp(4));
        scroll.addView(listBox, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll);

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusView.setTextColor(COLOR_DIM);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        statusView.setLayoutParams(statusLp);

        // 讀目錄要幾百毫秒到幾秒（對方慢、目錄大）。只寫「載入中…」看起來像當掉，
        // 給一條會轉的東西，使用者才知道還活著。
        loadingBar = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        loadingBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams loadingLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        loadingLp.setMargins(0, 0, dp(6), 0);
        loadingBar.setLayoutParams(loadingLp);

        hiddenToggle = new TextView(this);
        hiddenToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hiddenToggle.setTextColor(0xFF1A73E8);
        hiddenToggle.setPadding(dp(8), 0, 0, 0);
        hiddenToggle.setOnClickListener(v -> {
            if (transferring) return;
            showHidden = !showHidden;
            updateHiddenToggle();
            reload();
        });
        updateHiddenToggle();

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(16), dp(4), dp(16), dp(4));
        statusRow.addView(loadingBar);
        statusRow.addView(statusView);
        statusRow.addView(hiddenToggle);
        root.addView(statusRow);

        root.addView(buildBottomBar());
        return root;
    }

    private View buildTitleBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(10), dp(16), dp(4));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        back.setTextColor(COLOR_TEXT);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(12), 0, dp(12), dp(2));
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText(getString(R.string.files_title, host));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT);
        bar.addView(title);
        return bar;
    }

    private View buildTabs() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(2), dp(16), dp(6));

        tabLocal = tabButton(R.string.files_tab_local, SIDE_LOCAL);
        tabRemote = tabButton(R.string.files_tab_remote, SIDE_REMOTE);
        row.addView(tabLocal);
        row.addView(tabRemote);
        return row;
    }

    private TextView tabButton(int textRes, int which) {
        TextView t = new TextView(this);
        t.setText(textRes);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(8), 0, dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> {
            if (!transferring) selectSide(which);
        });
        return t;
    }

    private View buildPathBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), 0, dp(8), dp(4));

        upButton = new Button(this);
        upButton.setText("↑");
        upButton.setAllCaps(false);
        upButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        upButton.setMinWidth(0);
        upButton.setMinimumWidth(0);
        upButton.setPadding(dp(10), 0, dp(10), 0);
        upButton.setOnClickListener(v -> goUp());
        row.addView(upButton);

        // 麵包屑：每一段分開做成 TextView，點了直接跳回那一層。
        // 以前是一行字串，想從 /sdcard/Android/data/xxx 回到 /sdcard
        // 只能連按三次 ↑，而且中間還要看清楚自己在哪。
        trailScroll = new HorizontalScrollView(this);
        trailScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams trailLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        trailLp.setMargins(dp(2), 0, dp(2), 0);
        trailScroll.setLayoutParams(trailLp);

        trailBox = new LinearLayout(this);
        trailBox.setOrientation(LinearLayout.HORIZONTAL);
        trailBox.setGravity(Gravity.CENTER_VERTICAL);
        trailScroll.addView(trailBox);
        row.addView(trailScroll);

        Button search = new Button(this);
        search.setText("🔍");
        search.setAllCaps(false);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        search.setMinWidth(0);
        search.setMinimumWidth(0);
        search.setPadding(dp(8), 0, dp(8), 0);
        search.setOnClickListener(v -> toggleSearch());
        row.addView(search);

        Button refresh = new Button(this);
        // 圖示而不是文字：路徑那一行是唯一會變長的地方，把字省下來給它。
        // 這顆按鈕每天按不到幾次，圖示＋下面那句「N 個項目」已經夠說明。
        refresh.setText("⟳");
        refresh.setAllCaps(false);
        refresh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        refresh.setMinWidth(0);
        refresh.setMinimumWidth(0);
        refresh.setPadding(dp(10), 0, dp(10), 0);
        refresh.setOnClickListener(v -> reload());
        row.addView(refresh);

        Button more = new Button(this);
        more.setText("⋮");
        more.setAllCaps(false);
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        more.setMinWidth(0);
        more.setMinimumWidth(0);
        more.setPadding(dp(10), 0, dp(10), 0);
        more.setOnClickListener(v -> showDirMenu());
        row.addView(more);
        return row;
    }

    /** 搜尋列。平常收起來，按 🔍 才出現 —— 目錄瀏覽九成時間不需要它。 */
    private View buildSearchRow() {
        searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(16), 0, dp(8), dp(4));
        searchRow.setVisibility(View.GONE);

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT);
        searchBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        searchBox.setHint(R.string.files_search_hint);
        searchBox.setBackgroundColor(0xFFF1F3F4);
        searchBox.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        searchBox.setLayoutParams(boxLp);
        // 邊打邊篩：清單本來就在記憶體裡，不必等按 Enter
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                filter = s.toString().trim();
                renderList();
                updateCount();
            }
        });
        searchRow.addView(searchBox);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        close.setTextColor(COLOR_DIM);
        close.setPadding(dp(12), dp(6), dp(12), dp(6));
        close.setOnClickListener(v -> toggleSearch());
        searchRow.addView(close);
        return searchRow;
    }

    private void toggleSearch() {
        boolean show = searchRow.getVisibility() != View.VISIBLE;
        searchRow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            searchBox.requestFocus();
            searchBox.post(() -> {
                android.view.inputmethod.InputMethodManager im =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(INPUT_METHOD_SERVICE);
                if (im != null) im.showSoftInput(searchBox,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            });
        } else {
            searchBox.setText("");
            filter = "";
            renderList();
            updateCount();
        }
    }

    private View buildBottomBar() {
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(0xFFF5F5F5);
        bottomBar.setPadding(dp(16), dp(8), dp(16), dp(8));
        bottomBar.setVisibility(View.GONE);

        // 兩行：上面「已選 N 項」、下面「→ /sdcard/DCIM」。
        // 目的地一定要寫出來 —— 「傳到對方」這句話沒說傳到對方<b>哪裡</b>，
        // 而對方那個目錄可能是五分鐘前逛到的某個資料夾。
        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textBox.setLayoutParams(textLp);

        selectedView = new TextView(this);
        selectedView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        selectedView.setTextColor(COLOR_TEXT);
        textBox.addView(selectedView);

        destView = new TextView(this);
        destView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        destView.setTextColor(COLOR_DIM);
        destView.setSingleLine(true);
        destView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        textBox.addView(destView);
        bottomBar.addView(textBox);

        actionButton = new Button(this);
        actionButton.setAllCaps(false);
        actionButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        // 動作不固定：有選取時是「傳到對面」，剪貼簿有東西時是「貼上」。
        // 按鈕只做一件事，所以文字每次都要跟著改（見 updateBottomBar）。
        bottomBar.addView(actionButton);

        Button more = new Button(this);
        more.setText("⋮");
        more.setAllCaps(false);
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        more.setMinWidth(0);
        more.setMinimumWidth(0);
        more.setPadding(dp(10), 0, dp(10), 0);
        more.setOnClickListener(v -> showSelectionMenu());
        bottomBar.addView(more);
        return bottomBar;
    }

    // ================================================================== 側別切換

    private void selectSide(int which) {
        side = which;
        selected.clear();
        resetFilter();
        updateTabs();
        updateBottomBar();
        reload();
    }

    private void updateTabs() {
        boolean local = side == SIDE_LOCAL;
        tabLocal.setTextColor(local ? Color.WHITE : COLOR_TEXT);
        tabLocal.setBackgroundColor(local ? 0xFF1A73E8 : 0x00000000);
        tabLocal.setTypeface(local ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

        tabRemote.setTextColor(local ? COLOR_TEXT : Color.WHITE);
        tabRemote.setBackgroundColor(local ? 0x00000000 : 0xFF1A73E8);
        tabRemote.setTypeface(local ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
    }

    // ================================================================== 讀取目錄

    private void reload() {
        if (transferring) return;
        if (side == SIDE_LOCAL) loadLocal();
        else loadRemote();
    }

    private void loadRemote() {
        renderTrail();
        upButton.setEnabled(!REMOTE_ROOT.equals(remotePath));
        setLoading(true);

        io.execute(() -> {
            try {
                List<AdbSync.Entry> raw = withSync(s -> s.list(remotePath));
                final List<AdbSync.Entry> items = hideIfNeeded(raw);
                // 沒有排序的話，順序就是 adbd 的 readdir 順序 —— 在對方眼裡
                // 就是「每次打開都不一樣」，找檔案會找到崩潰。
                Collections.sort(items, (a, b) -> compare(
                        a.isDir(), a.name, a.size, a.mtime,
                        b.isDir(), b.name, b.size, b.mtime));
                ui.post(() -> {
                    remoteItems = items;
                    setLoading(false);
                    renderList();
                    updateCount();
                });
            } catch (Exception e) {
                DiagLog.w(TAG, "列出遠端目錄失敗", e);
                String msg = AdbClient.describeError(e);
                ui.post(() -> {
                    remoteItems = new ArrayList<>();
                    setLoading(false);
                    renderList();
                    setStatus(getString(R.string.files_error, msg));
                });
            }
        });
    }

    private void loadLocal() {
        if (treeUri == null) {
            renderTrail();
            upButton.setEnabled(false);
            localItems = new ArrayList<>();
            renderList();
            setStatus("");
            return;
        }
        final String docId = localStack.get(localStack.size() - 1).docId;
        renderTrail();
        upButton.setEnabled(localStack.size() > 1);
        setLoading(true);

        io.execute(() -> {
            try {
                List<LocalItem> raw = listLocal(docId);
                final List<LocalItem> items = hideIfNeededLocal(raw);
                Collections.sort(items, (a, b) -> compare(
                        a.dir, a.name, a.size, a.mtime,
                        b.dir, b.name, b.size, b.mtime));
                ui.post(() -> {
                    localItems = items;
                    setLoading(false);
                    renderList();
                    updateCount();
                });
            } catch (Exception e) {
                DiagLog.w(TAG, "列出本機目錄失敗", e);
                ui.post(() -> {
                    localItems = new ArrayList<>();
                    setLoading(false);
                    renderList();
                    setStatus(getString(R.string.files_local_denied));
                });
            }
        });
    }

    private List<LocalItem> listLocal(String parentDocId) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        String[] proj = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
        List<LocalItem> out = new ArrayList<>();
        try (Cursor c = getContentResolver().query(children, proj, null, null, null)) {
            if (c == null) throw new IOException("query 回 null");
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.isNull(3) ? 0 : c.getLong(3);
                // SAF 給的是<b>毫秒</b>，而 SYNC 協定給的是秒。這裡一律換成秒，
                // 否則 formatTime() 會把毫秒當秒算 —— 畫面上就是一個亂七八糟的日期
                // （實測 2026-10-26 的檔案顯示成「05-10 15:24」）。
                long mtime = c.isNull(4) ? 0 : c.getLong(4) / 1000L;
                boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                if (id == null || name == null) continue;
                out.add(new LocalItem(id, name, mime, size, mtime, dir));
            }
        } catch (SecurityException e) {
            throw new IOException("權限已被撤銷", e);
        }
        return out;
    }

    private String localBreadcrumb() {
        StringBuilder sb = new StringBuilder(
                localRootName != null ? localRootName : getString(R.string.files_local_root));
        // 第一層是使用者挑的根目錄，它的名字要另外查（堆疊裡存的是 null）
        for (int i = 0; i < localStack.size(); i++) {
            String name = localStack.get(i).name;
            if (name == null) continue;
            sb.append(" / ").append(name);
        }
        return sb.toString();
    }

    /** 問系統這棵樹的根目錄叫什麼。查不到就回 null，麵包屑會退回泛稱。 */
    private String queryRootName(Uri tree) {
        try (Cursor c = getContentResolver().query(
                DocumentsContract.buildDocumentUriUsingTree(tree,
                        DocumentsContract.getTreeDocumentId(tree)),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception e) {
            DiagLog.w(TAG, "查不到本機根目錄的名稱", e);
        }
        return null;
    }

    // ================================================================== 麵包屑

    /**
     * 重畫麵包屑。
     *
     * <p>每一段都可以點 —— 從 {@code /sdcard/Android/data/com.xxx/cache} 回到
     * {@code /sdcard} 只要點一下，不必連按四次 ↑ 還要看清楚自己在哪。
     * 最後一段（目前所在的目錄）不加底線也不可點，因為點了也不會動。
     */
    private void renderTrail() {
        trailBox.removeAllViews();

        if (side == SIDE_LOCAL) {
            if (treeUri == null) {
                addTrail(getString(R.string.files_no_local_root), true, null);
                return;
            }
            String rootName = localRootName != null
                    ? localRootName : getString(R.string.files_local_root);
            final int rootKeep = 1;
            addTrail(rootName, localStack.size() <= 1, () -> truncateLocalTo(rootKeep));
            for (int i = 1; i < localStack.size(); i++) {
                String name = localStack.get(i).name;
                if (name == null) continue;
                final int keep = i + 1;
                addTrail(name, i == localStack.size() - 1, () -> truncateLocalTo(keep));
            }
        } else {
            addTrail("/", false, () -> goRemote("/"));
            StringBuilder acc = new StringBuilder();
            String[] parts = remotePath.split("/");
            int emitted = 0;
            for (String p : parts) {
                if (p.isEmpty()) continue;
                emitted++;
            }
            int seen = 0;
            for (String p : parts) {
                if (p.isEmpty()) continue;
                acc.append('/').append(p);
                seen++;
                final String target = acc.toString();
                addTrail(p, seen == emitted, () -> goRemote(target));
            }
        }

        // 路徑長的時後要把「目前在最後一段」露出來，不然永遠只看到 /sdcard
        trailScroll.post(() -> trailScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void addTrail(String label, boolean last, Runnable action) {
        if (trailBox.getChildCount() > 0) {
            TextView prev = (TextView) trailBox.getChildAt(trailBox.getChildCount() - 1);
            // 前一段是根 "/" 時不要再補一個分隔線，否則會變成 "//sdcard"
            if (!"/".equals(prev.getText().toString())) {
                TextView sep = new TextView(this);
                sep.setText("/");
                sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                sep.setTextColor(COLOR_DIM);
                sep.setPadding(dp(1), 0, dp(1), 0);
                trailBox.addView(sep);
            }
        }

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setSingleLine(true);
        t.setPadding(dp(5), dp(5), dp(5), dp(5));
        if (last) {
            t.setTextColor(COLOR_TEXT);
            t.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            t.setTextColor(0xFF1A73E8);
            t.setOnClickListener(v -> {
                if (!transferring) action.run();
            });
        }
        trailBox.addView(t);
    }

    private void truncateLocalTo(int keep) {
        while (localStack.size() > keep) localStack.remove(localStack.size() - 1);
        selected.clear();
        resetFilter();
        updateBottomBar();
        loadLocal();
    }

    private void goRemote(String path) {
        remotePath = path;
        selected.clear();
        resetFilter();
        updateBottomBar();
        loadRemote();
    }

    private void setLoading(boolean on) {
        loadingBar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) setStatus(getString(R.string.files_loading));
    }

    /** 狀態列那句「N 個項目」。有搜尋時順便說「顯示 M / N」。 */
    private void updateCount() {
        int total = side == SIDE_LOCAL ? localItems.size() : remoteItems.size();
        if (filter.isEmpty()) {
            setStatus(getString(R.string.files_count, total));
        } else {
            setStatus(getString(R.string.files_filtered, visibleCount(), total));
        }
    }

    private boolean matchesFilter(String name) {
        return filter.isEmpty() || name.toLowerCase(Locale.US)
                .contains(filter.toLowerCase(Locale.US));
    }

    private int visibleCount() {
        int n = 0;
        if (side == SIDE_LOCAL) {
            for (LocalItem it : localItems) if (matchesFilter(it.name)) n++;
        } else {
            for (AdbSync.Entry it : remoteItems) if (matchesFilter(it.name)) n++;
        }
        return n;
    }

    // ================================================================== 縮圖

    /**
     * 一列的縮圖狀態。
     *
     * <p>掛在 {@code row.setTag()} 上，捲動時就靠它反查「這一列對應哪個檔案」。
     * {@code loading} / {@code done} 會被背景執行緒讀寫，所以是 volatile。
     */
    private static final class ThumbSlot {
        final ImageView view;
        final TextView icon;
        /** 快取 key（含 mtime 與大小）。 */
        final String key;
        /** 真正的來源識別：遠端是完整路徑，本機是 document id。 */
        final String ref;
        final String name;
        final long size;
        final boolean dir;
        final boolean local;

        volatile boolean loading;
        volatile boolean done;

        ThumbSlot(ImageView view, TextView icon, String key, String ref,
                  String name, long size, boolean dir, boolean local) {
            this.view = view;
            this.icon = icon;
            this.key = key;
            this.ref = ref;
            this.name = name;
            this.size = size;
            this.dir = dir;
            this.local = local;
        }
    }

    /**
     * 快取 key：路徑 ＋ mtime ＋ 大小。
     *
     * <p>只拿路徑當 key 的話，同一個檔名換了內容還是會命中舊縮圖 —— 使用者
     * 覆蓋了一張照片，看到的還是舊的，而且怎麼重新整理都不會變。
     */
    private static String thumbKey(String ref, long mtime, long size) {
        return ref + "|" + mtime + "|" + size;
    }

    /** 只對這幾種副檔名做縮圖。{@code BitmapFactory} 解不開的（heic 在舊機）就自然失敗。 */
    private static boolean isImage(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".webp") || n.endsWith(".bmp") || n.endsWith(".gif");
    }

    /** 捲動時一直呼叫，所以要去抖 —— 每一格像素都排一次掃描沒有意義。 */
    private void scheduleThumbs() {
        if (thumbs == null || scrollView == null) return;
        ui.removeCallbacks(thumbScan);
        ui.postDelayed(thumbScan, 120);
    }

    private final Runnable thumbScan = this::loadVisibleThumbs;

    /** 掃一遍列表，只對「真的在畫面上」的列排縮圖。 */
    private void loadVisibleThumbs() {
        if (thumbs == null || listBox == null) return;
        visibleThumbKeys.clear();
        Rect r = new Rect();
        for (int i = 0; i < listBox.getChildCount(); i++) {
            View row = listBox.getChildAt(i);
            Object tag = row.getTag();
            if (!(tag instanceof ThumbSlot)) continue;
            ThumbSlot slot = (ThumbSlot) tag;
            if (slot.dir || slot.done) continue;
            if (!row.getGlobalVisibleRect(r)) continue;
            visibleThumbKeys.add(slot.key);
            startThumb(slot);
        }
    }

    private void startThumb(ThumbSlot slot) {
        if (slot.loading || slot.done) return;
        if (!isImage(slot.name) || slot.size > THUMB_MAX_BYTES) {
            slot.done = true;
            return;
        }
        // 正在搬檔案時不要插隊搶通道。不標 done，等這次搬完再捲一下就會重試。
        if (!slot.local && transferring) return;

        Bitmap cached = thumbs.peek(slot.key);
        if (cached != null) {
            applyThumb(slot, cached);
            return;
        }

        slot.loading = true;
        final String key = slot.key;
        final String ref = slot.ref;
        final boolean local = slot.local;
        final Uri tree = treeUri;

        thumbExec.execute(() -> {
            Bitmap b = thumbs.load(key);
            if (b == null) {
                // 排隊排到時可能已經滾出畫面了 —— 那就不必抓。
                // 遠端一張要 0.3 秒以上，捲過去的列全抓完會拖垮整個佇列。
                if (!visibleThumbKeys.contains(key)) {
                    ui.post(() -> slot.loading = false);
                    return;
                }
                b = local ? makeLocalThumb(ref, tree) : makeRemoteThumb(ref);
                if (b != null) thumbs.put(key, b);
            }
            final Bitmap result = b;
            ui.post(() -> {
                slot.loading = false;
                if (result != null) applyThumb(slot, result);
                // 失敗就別再試了：捲回來也一樣會失敗，只是白等一次逾時。
                else slot.done = true;
            });
        });
    }

    private void applyThumb(ThumbSlot slot, Bitmap b) {
        slot.done = true;
        slot.icon.setVisibility(View.GONE);
        slot.view.setImageBitmap(b);
        slot.view.setVisibility(View.VISIBLE);
    }

    /** 本機：API 29 以上直接問系統，26–28 自己解。 */
    private Bitmap makeLocalThumb(String docId, Uri tree) {
        if (tree == null) return null;
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // loadThumbnail 對圖片與影片都有效，而且由系統產生、有它自己的快取。
                Bitmap b = getContentResolver().loadThumbnail(
                        uri, new Size(THUMB_PX, THUMB_PX), null);
                return b == null ? null : scaleTo(b, THUMB_PX);
            }
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, probe);
            }
            if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sampleFor(probe.outWidth, probe.outHeight, THUMB_PX);
            Bitmap b;
            // 串流讀完就沒了，要再開一次
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                b = BitmapFactory.decodeStream(in, null, o);
            }
            return b == null ? null : scaleTo(b, THUMB_PX);
        } catch (Exception e) {
            DiagLog.w(TAG, "本機縮圖失敗：" + docId, e);
            return null;
        }
    }

    /**
     * 遠端：只能把整個檔案抓回來再解。
     *
     * <p>SYNC 協定沒有「給我縮圖」這種請求（只有 STAT/LIST/RECV/SEND），
     * 而遠端也不是我們的 App，沒辦法叫它自己產生預覽。所以這裡就是一次完整的
     * {@code RECV}。這也是為什麼要限 12 MB、要磁碟快取、要只在可見時才做。
     *
     * <p>刻意<b>不走</b> {@code withSync}：那個包裝在 IOException 時會把整個
     * client 關掉重建。一張縮圖失敗就把正在傳輸的連線踢掉，代價太大了。
     */
    private Bitmap makeRemoteThumb(String path) {
        File tmp = new File(getCacheDir(), "thumb.tmp");
        try {
            sync().recv(path, tmp, null);
            return decodeSquare(tmp, THUMB_PX);
        } catch (Exception e) {
            DiagLog.w(TAG, "遠端縮圖失敗：" + path, e);
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            // recv 是先寫 .part 再改名，失敗時 .part 會留在那裡
            //noinspection ResultOfMethodCallIgnored
            new File(tmp.getAbsolutePath() + ".part").delete();
        }
    }

    private static Bitmap decodeSquare(File f, int px) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), probe);
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sampleFor(probe.outWidth, probe.outHeight, px);
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        return b == null ? null : scaleTo(b, px);
    }

    /**
     * 等比縮到「短邊至少 px」，再從中間裁成正方形。
     *
     * <p>顯示端是 {@code CENTER_CROP}，本來就會裁；這裡先裁掉是為了存檔 ——
     * 存進去的就是正方形，之後每次讀出來都一樣大，不會因為某張是長條圖
     * 就讓快取裡混著 160×40 這種東西。
     */
    private static Bitmap scaleTo(Bitmap src, int px) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return null;
        if (w == px && h == px) return src;

        float scale = Math.max((float) px / w, (float) px / h);
        int nw = Math.max(px, Math.round(w * scale));
        int nh = Math.max(px, Math.round(h * scale));
        Bitmap scaled = (nw == w && nh == h) ? src : Bitmap.createScaledBitmap(src, nw, nh, true);
        return Bitmap.createBitmap(scaled, (nw - px) / 2, (nh - px) / 2, px, px);
    }

    /**
     * 取樣倍率。用<b>長邊</b>算，不是短邊。
     *
     * <p>用短邊算的話，一張 10000×100 的全景圖會算出 {@code inSampleSize=1}，
     * 直接解出 4 MB 的點陣圖 —— 為了做一個 40dp 的小方塊。
     */
    private static int sampleFor(int w, int h, int px) {
        int s = 1;
        while (Math.max(w, h) / (s * 2) >= px) s *= 2;
        return s;
    }

    // ================================================================== 畫列表

    private void renderList() {
        listBox.removeAllViews();
        visibleThumbKeys.clear();
        // 這裡不直接抓縮圖：view 才剛 add，還沒 layout，getGlobalVisibleRect 全是 0。
        // 排一個延遲任務，等這一輪 layout 過去再問「哪些真的看得到」。
        scheduleThumbs();

        if (side == SIDE_LOCAL && treeUri == null) {
            listBox.addView(buildEmpty(getString(R.string.files_pick_hint), R.string.files_pick_root,
                    v -> pickLocalRoot()));
            return;
        }

        if (side == SIDE_LOCAL) {
            boolean any = false;
            for (LocalItem it : localItems) {
                if (!matchesFilter(it.name)) continue;
                any = true;
                listBox.addView(buildRow(it.name, it.dir, it.size, it.mtime, it.docId,
                        it.dir ? () -> enterLocal(it) : () -> openLocal(it)));
            }
            if (!any) listBox.addView(buildEmpty(getString(R.string.files_empty), 0, null));
        } else {
            boolean any = false;
            for (AdbSync.Entry it : remoteItems) {
                if (!matchesFilter(it.name)) continue;
                any = true;
                listBox.addView(buildRow(it.name, it.isDir(), it.size, it.mtime, it.path,
                        it.isDir() ? () -> enterRemote(it) : () -> showRemoteFileActions(it)));
            }
            if (!any) listBox.addView(buildEmpty(getString(R.string.files_empty), 0, null));
        }
    }

    /**
     * 一列。
     *
     * <p><b>互動規則</b>（跟系統的檔案管理員一致，不要自己發明）：
     * <ul>
     *   <li>平常點一下＝開啟（目錄進去、本機檔案用系統的檢視器打開、遠端檔案先問）</li>
     *   <li>長按＝進入多選並選中這一項</li>
     *   <li>多選中點一下＝加選／減選</li>
     * </ul>
     *
     * <p>以前「點一下＝選取」是最被絆到的地方：使用者只是想看看那是什麼檔案，
     * 結果整個底部跳出「已選 1 項」，而且沒有任何方法真的把檔案打開。
     */
    private View buildRow(String name, boolean dir, long size, long mtime,
                          String key, Runnable open) {
        boolean sel = selected.contains(key);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(9), dp(14), dp(9));
        if (sel) row.setBackgroundColor(COLOR_SELECTED);

        // 縮圖框：40dp 方形。目錄也留框（裡面放 📁），這樣左緣才會對齊 ——
        // 有縮圖的行和沒縮圖的行左邊參差不齊，比全部都沒有縮圖還難看。
        FrameLayout thumbBox = new FrameLayout(this);
        int boxPx = dp(THUMB_DP);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(boxPx, boxPx);
        boxLp.setMargins(0, 0, dp(8), 0);
        thumbBox.setLayoutParams(boxLp);
        GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setColor(COLOR_THUMB_BG);
        thumbBg.setCornerRadius(dp(5));
        thumbBox.setBackground(thumbBg);
        // 讓裡面的 ImageView 被這個圓角輪廓裁掉（API 21+）
        thumbBox.setClipToOutline(true);

        // 類型圖示：150 個項目全是兩行字，靠檔名前幾個字找東西很累；
        // 一個圖示就能先把「資料夾／照片／影片／apk」分開。
        // 縮圖載入完成後它會被蓋掉，載入中與載入失敗時看到的就是它。
        //
        // 字級與留白：emoji 的字形本來就比行高小一圈，15sp 放在 40dp 的框裡
        // 四邊會空掉一半 —— 看起來像「一個灰方塊上貼了張小貼紙」。
        // 放大到 22sp 再關掉字型的上下留白（includeFontPadding），
        // 圖示才會撐滿框，行距卻不變。
        TextView icon = new TextView(this);
        icon.setText(iconFor(name, dir));
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        icon.setIncludeFontPadding(false);
        icon.setGravity(Gravity.CENTER);
        thumbBox.addView(icon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setVisibility(View.GONE);
        thumbBox.addView(thumb, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        row.addView(thumbBox);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        text.setLayoutParams(textLp);

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setTextColor(COLOR_TEXT);
        // 目錄加粗：一眼分得出「這是可以進去的」和「這是檔案」
        if (dir) title.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(title);

        TextView meta = new TextView(this);
        meta.setText(dir ? getString(R.string.files_kind_dir) : formatSize(size) + " · " + formatTime(mtime));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        meta.setTextColor(COLOR_DIM);
        meta.setPadding(0, dp(2), 0, 0);
        text.addView(meta);
        row.addView(text);

        // 右邊那一格：選取時是 ✓，平常目錄是 ›、檔案留空
        TextView tail = new TextView(this);
        tail.setText(sel ? "✓" : (dir ? "›" : ""));
        tail.setTextSize(TypedValue.COMPLEX_UNIT_SP, sel ? 16 : 20);
        tail.setTextColor(sel ? 0xFF1A73E8 : COLOR_DIM);
        tail.setGravity(Gravity.CENTER);
        tail.setWidth(dp(24));
        row.addView(tail);

        row.setOnClickListener(v -> {
            if (transferring) return;
            if (selectionMode()) toggle(key);
            else open.run();
        });
        // 長按＝選取這一項（包含目錄）。目錄的「點」被「進入」佔走了，
        // 沒有長按就永遠選不到資料夾，也就永遠刪不掉、傳不了整個資料夾。
        row.setOnLongClickListener(v -> {
            if (transferring) return true;
            if (selectionMode()) {
                toggle(key);
            } else {
                selected.clear();
                selected.add(key);
                updateBottomBar();
                renderList();
            }
            return true;
        });
        // 縮圖要用的東西全掛在這一列上：捲動時只掃「畫面上看得到的列」，
        // 沒有這個就無從知道某一列對應哪個檔案、該去哪裡抓。
        ThumbSlot slot = new ThumbSlot(thumb, icon, thumbKey(key, mtime, size), key,
                name, size, dir, side == SIDE_LOCAL);
        // 記憶體快取命中就當場畫上去。
        //
        // 這一條不是「最佳化」而是必須的：選取任何一項都會整個 renderList 重建，
        // 沒有它，每點一下就看著所有縮圖變回灰方塊、再一張一張長回來。
        Bitmap pre = thumbs == null ? null : thumbs.peek(slot.key);
        if (pre != null) {
            slot.done = true;
            icon.setVisibility(View.GONE);
            thumb.setImageBitmap(pre);
            thumb.setVisibility(View.VISIBLE);
        }
        row.setTag(slot);
        return row;
    }

    private boolean selectionMode() {
        return !selected.isEmpty();
    }

    /** 依副檔名給一個圖示。認不得的就當一般檔案。 */
    private static String iconFor(String name, boolean dir) {
        if (dir) return "📁";
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
                || n.endsWith(".gif") || n.endsWith(".bmp") || n.endsWith(".heic")
                || n.endsWith(".avif")) return "🖼";
        if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".mov")
                || n.endsWith(".webm") || n.endsWith(".3gp") || n.endsWith(".avi")) return "🎬";
        if (n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".flac")
                || n.endsWith(".wav") || n.endsWith(".ogg") || n.endsWith(".aac")) return "🎵";
        if (n.endsWith(".apk") || n.endsWith(".apks") || n.endsWith(".xapk")) return "📦";
        if (n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z")
                || n.endsWith(".tar") || n.endsWith(".gz")) return "🗜";
        if (n.endsWith(".pdf")) return "📕";
        if (n.endsWith(".txt") || n.endsWith(".log") || n.endsWith(".md")) return "📝";
        return "📄";
    }

    private View buildEmpty(String text, int buttonRes, View.OnClickListener l) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(40), dp(24), dp(24));

        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(COLOR_DIM);
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        if (buttonRes != 0) {
            Button b = new Button(this);
            b.setText(buttonRes);
            b.setAllCaps(false);
            b.setOnClickListener(l);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(12), 0, 0);
            b.setLayoutParams(lp);
            box.addView(b);
        }
        return box;
    }

    // ================================================================== 導覽

    /**
     * 換目錄時把搜尋清掉。
     *
     * <p>不清的話，使用者在 {@code /sdcard} 搜「IMG」、點進某個資料夾，
     * 會看到一個空畫面 —— 因為那個資料夾裡沒有叫 IMG 的東西。
     * 搜尋是「在這個資料夾裡找」，換了資料夾就該重來。
     */
    private void resetFilter() {
        if (filter.isEmpty()) return;
        filter = "";
        if (searchBox != null) searchBox.setText("");
    }

    private void enterRemote(AdbSync.Entry e) {
        remotePath = e.path;
        selected.clear();
        resetFilter();
        updateBottomBar();
        loadRemote();
    }

    private void enterLocal(LocalItem it) {
        localStack.add(new LocalDir(it.docId, it.name));
        selected.clear();
        resetFilter();
        updateBottomBar();
        loadLocal();
    }

    private void goUp() {
        if (side == SIDE_LOCAL) {
            if (localStack.size() <= 1) return;
            localStack.remove(localStack.size() - 1);
            selected.clear();
            resetFilter();
            updateBottomBar();
            loadLocal();
        } else {
            if (REMOTE_ROOT.equals(remotePath)) return;
            int slash = remotePath.lastIndexOf('/');
            remotePath = slash <= 0 ? "/" : remotePath.substring(0, slash);
            selected.clear();
            resetFilter();
            updateBottomBar();
            loadRemote();
        }
    }

    private void toggle(String key) {
        if (transferring) return;
        if (!selected.remove(key)) selected.add(key);
        updateBottomBar();
        renderList();
    }

    private void updateBottomBar() {
        // 這一條只在「有事可做」時出現。兩種事：
        //   1. 選了東西  → 傳到對面
        //   2. 剪貼簿有東西 → 貼到這裡
        // 兩個都成立時<b>以選取優先</b>：使用者剛勾了幾個檔，眼睛還在勾選框上。
        if (!selected.isEmpty()) {
            bottomBar.setVisibility(View.VISIBLE);
            selectedView.setText(getString(R.string.files_selected, selected.size()));
            // 按鈕文字寫的是「去哪」，不是「從哪」—— 使用者在選東西時想的是目的地
            actionButton.setText(side == SIDE_LOCAL
                    ? R.string.files_send_to_remote : R.string.files_send_to_local);
            actionButton.setOnClickListener(v -> transferSelection());
            // 目的地寫出來。對方那個目錄可能是五分鐘前逛到的，不講清楚就只能猜。
            destView.setText(getString(R.string.files_dest, destinationLabel()));
            return;
        }
        if (clipMode != CLIP_NONE && !clipJobs.isEmpty()) {
            bottomBar.setVisibility(View.VISIBLE);
            selectedView.setText(getString(clipMode == CLIP_CUT
                    ? R.string.files_clip_cut : R.string.files_clip_copy, clipJobs.size()));
            actionButton.setText(R.string.files_paste);
            actionButton.setOnClickListener(v -> paste());
            // 貼上的目的地跟「傳到對面」不一樣：貼上落在<b>現在這一側</b>的目前目錄，
            // 不是對面。用 destinationLabel() 會寫出一個完全無關的路徑。
            destView.setText(getString(R.string.files_dest, pasteTargetLabel()));
            return;
        }
        bottomBar.setVisibility(View.GONE);
    }

    /** 貼上會落在哪裡。 */
    private String pasteTargetLabel() {
        if (side == SIDE_LOCAL) {
            return localStack.isEmpty()
                    ? getString(R.string.files_no_local_root) : localBreadcrumb();
        }
        return remotePath;
    }

    /** 這次傳輸會落到哪裡（給底部那一行用）。 */
    private String destinationLabel() {
        if (side == SIDE_LOCAL) return remotePath;
        return localStack.isEmpty()
                ? getString(R.string.files_no_local_root) : localBreadcrumb();
    }

    private void setStatus(String s) {
        statusView.setText(s);
    }

    private void updateHiddenToggle() {
        hiddenToggle.setText(showHidden ? R.string.files_hide_hidden : R.string.files_show_hidden);
    }

    /**
     * 列表排序。<b>目錄永遠排在檔案前面</b>，排序方式只決定同一群裡面誰先。
     *
     * <p>為什麼不讓「大小」把目錄也排進去：目錄的 size 是 inode 大小（通常 4096），
     * 照大小排會讓一堆資料夾散在檔案中間 —— 那就不是檔案總管了。
     */
    private int compare(boolean aDir, String aName, long aSize, long aTime,
                        boolean bDir, String bName, long bSize, long bTime) {
        if (aDir != bDir) return aDir ? -1 : 1;
        switch (sortMode) {
            case SORT_NAME_DESC:
                return bName.compareToIgnoreCase(aName);
            case SORT_SIZE_DESC: {
                int c = Long.compare(bSize, aSize);
                return c != 0 ? c : aName.compareToIgnoreCase(bName);
            }
            case SORT_TIME_DESC: {
                int c = Long.compare(bTime, aTime);
                return c != 0 ? c : aName.compareToIgnoreCase(bName);
            }
            default:
                return aName.compareToIgnoreCase(bName);
        }
    }

    private void chooseSort() {
        final int[] modes = {SORT_NAME_ASC, SORT_NAME_DESC, SORT_SIZE_DESC, SORT_TIME_DESC};
        final int[] labels = {R.string.files_sort_name, R.string.files_sort_name_desc,
                R.string.files_sort_size, R.string.files_sort_time};
        String[] items = new String[labels.length];
        int checked = 0;
        for (int i = 0; i < labels.length; i++) {
            items[i] = getString(labels[i]);
            if (modes[i] == sortMode) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.files_menu_sort)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    sortMode = modes[which];
                    getSharedPreferences(PREF, MODE_PRIVATE).edit()
                            .putInt(KEY_SORT, sortMode).apply();
                    d.dismiss();
                    reload();
                })
                .setNegativeButton(R.string.files_cancel, null)
                .show();
    }

    /** 濾掉點開頭的項目（見 {@link #showHidden}）。 */
    private List<AdbSync.Entry> hideIfNeeded(List<AdbSync.Entry> in) {
        if (showHidden) return in;
        List<AdbSync.Entry> out = new ArrayList<>(in.size());
        for (AdbSync.Entry e : in) {
            if (!e.name.startsWith(".")) out.add(e);
        }
        return out;
    }

    private List<LocalItem> hideIfNeededLocal(List<LocalItem> in) {
        if (showHidden) return in;
        List<LocalItem> out = new ArrayList<>(in.size());
        for (LocalItem e : in) {
            if (!e.name.startsWith(".")) out.add(e);
        }
        return out;
    }

    // ================================================================== 本機根目錄

    private void pickLocalRoot() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            // 有些精簡 ROM 把文件選擇器拔掉了
            Toast.makeText(this, R.string.files_no_picker, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TREE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        try {
            // 取「重開 App 也還在」的權限。沒有這一步，App 一被殺掉
            // 下次回來就是 SecurityException —— 而且看不出為什麼。
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            DiagLog.w(TAG, "取得本機資料夾的持久權限失敗", e);
        }

        treeUri = uri;
        localRootName = queryRootName(uri);
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString(KEY_TREE, uri.toString())
                .putString(KEY_TREE_NAME, localRootName).apply();

        localStack.clear();
        localStack.add(new LocalDir(DocumentsContract.getTreeDocumentId(uri), null));
        selected.clear();
        updateBottomBar();
        selectSide(SIDE_LOCAL);
    }

    // ================================================================== 開啟檔案

    /**
     * 本機檔案：直接丟給系統的檢視器。
     *
     * <p>SAF 的 document URI 可以直接給別的 App，只要帶上
     * {@code FLAG_GRANT_READ_URI_PERMISSION} —— 權限只跟著這一次 Intent，
     * 不是把整個資料夾開放出去。
     */
    private void openLocal(LocalItem it) {
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, it.docId);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(doc, it.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.files_open_failed, Toast.LENGTH_SHORT).show();
            // 打不開就退回「這個檔案能做的事」，總比什麼都沒有好
            selected.clear();
            selected.add(it.docId);
            updateBottomBar();
            renderList();
        }
    }

    /**
     * 遠端檔案：點一下先問要做什麼，不直接下載。
     *
     * <p>為什麼跟本機不一樣：本機的檔案已經在手邊，打開是零成本；
     * 遠端的檔案要先把幾百 MB 搬過來，按一下就开始下載太粗暴。
     */
    private void showRemoteFileActions(final AdbSync.Entry e) {
        if (transferring) return;
        final List<String> items = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();

        items.add(getString(R.string.files_menu_download_open));
        acts.add(() -> askThenOpen(e));

        items.add(getString(R.string.files_send_to_local));
        acts.add(() -> {
            selected.clear();
            selected.add(e.path);
            updateBottomBar();
            renderList();
            transferSelection();
        });

        items.add(getString(R.string.files_info));
        acts.add(() -> {
            selected.clear();
            selected.add(e.path);
            updateBottomBar();
            renderList();
            showInfo();
        });

        items.add(getString(R.string.files_rename_title));
        acts.add(() -> {
            selected.clear();
            selected.add(e.path);
            updateBottomBar();
            renderList();
            renameSelection();
        });

        items.add(getString(R.string.files_delete_title));
        acts.add(() -> {
            selected.clear();
            selected.add(e.path);
            updateBottomBar();
            renderList();
            deleteSelection();
        });

        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(items.toArray(new String[0]), (d, i) -> acts.get(i).run())
                .setNegativeButton(R.string.files_cancel, null)
                .show();
    }

    /** 大檔先問一句。手機網路、對方電池、暫存空間都是成本。 */
    private void askThenOpen(final AdbSync.Entry e) {
        if (e.size > OPEN_CONFIRM_BYTES) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.files_open_confirm_title)
                    .setMessage(getString(R.string.files_open_confirm, e.name, formatSize(e.size)))
                    .setPositiveButton(android.R.string.ok, (d, w) -> downloadAndOpen(e))
                    .setNegativeButton(R.string.files_cancel, null)
                    .show();
        } else {
            downloadAndOpen(e);
        }
    }

    /**
     * 下載到快取再交給系統開啟。
     *
     * <p>放 {@code cacheDir/open/}：那是 {@link androidx.core.content.FileProvider}
     * 已經開放的路徑，而且系統空間不足時會自己清掉，不會愈積愈多。
     * 每次下載前先把舊的清空 —— 快取裡留著上一個看過的影片沒有任何好處。
     */
    private void downloadAndOpen(final AdbSync.Entry e) {
        final File dir = new File(getCacheDir(), "open");
        final File out = new File(dir, safeName(e.name));

        transferring = true;
        final ProgressUi p = new ProgressUi();
        p.show(getString(R.string.files_opening), true);

        io.execute(() -> {
            String err = null;
            try {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) {
                    for (File f : old) {
                        if (!f.getName().equals(out.getName())) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                }
                withSync(s -> {
                    s.recv(e.path, out, p);
                    return null;
                });
            } catch (Exception ex) {
                DiagLog.w(TAG, "下載後開啟失敗：" + e.name, ex);
                err = AdbClient.describeError(ex);
            }
            final String finalErr = err;
            ui.post(() -> {
                transferring = false;
                p.dismiss();
                if (finalErr != null) {
                    Toast.makeText(this, getString(R.string.files_op_failed, finalErr),
                            Toast.LENGTH_LONG).show();
                } else {
                    launchViewer(out, guessMime(e.name));
                }
            });
        });
    }

    private void launchViewer(File f, String mime) {
        Uri uri = FileProvider.getUriForFile(this, fileAuthority(), f);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.files_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** 檔名直接當快取檔名有風險（{@code ../}、{@code :}），換掉不安全的字元。 */
    private static String safeName(String name) {
        String s = name.replace('/', '_').replace('\\', '_').replace(':', '_');
        return s.isEmpty() ? "file" : s;
    }

    // ================================================================== 目錄操作

    /** 路徑列那顆 ⋮：對「目前這個目錄」做的事。 */
    private void showDirMenu() {
        if (transferring) return;
        final List<String> items = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();

        items.add(getString(R.string.files_menu_new_folder));
        acts.add(this::newFolder);

        if (side == SIDE_REMOTE) {
            items.add(getString(R.string.files_menu_jump));
            acts.add(this::jumpToPath);
            items.add(getString(R.string.files_menu_places));
            acts.add(this::showPlaces);
        } else {
            items.add(getString(R.string.files_menu_pick_root));
            acts.add(this::pickLocalRoot);
        }

        items.add(getString(R.string.files_menu_sort));
        acts.add(this::chooseSort);

        new AlertDialog.Builder(this)
                .setTitle(side == SIDE_REMOTE
                        ? R.string.files_tab_remote : R.string.files_tab_local)
                .setItems(items.toArray(new String[0]), (d, i) -> acts.get(i).run())
                .setNegativeButton(R.string.files_cancel, null)
                .show();
    }

    /** 底部動作列那顆 ⋮：對「選取的東西」做的事。 */
    private void showSelectionMenu() {
        if (transferring) return;
        // 沒有選取時這顆 ⋮ 屬於剪貼簿（見 updateBottomBar），不是選取菜單
        if (selected.isEmpty()) {
            showClipMenu();
            return;
        }
        final List<String> items = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();

        // 複製／剪下排最前面：這是最常用的兩件事，不該滑到菜單底部才看到
        items.add(getString(R.string.files_copy));
        acts.add(() -> setClipboard(CLIP_COPY));
        items.add(getString(R.string.files_cut));
        acts.add(() -> setClipboard(CLIP_CUT));

        items.add(getString(R.string.files_select_all));
        acts.add(this::selectAll);

        items.add(getString(R.string.files_clear_selection));
        acts.add(() -> {
            selected.clear();
            updateBottomBar();
            renderList();
        });

        // 改名與詳細資訊只對「單一項」有意義，多選時乾脆不出現
        if (selected.size() == 1) {
            items.add(getString(R.string.files_rename_title));
            acts.add(this::renameSelection);
            items.add(getString(R.string.files_info));
            acts.add(this::showInfo);
        }

        items.add(getString(R.string.files_delete_title));
        acts.add(this::deleteSelection);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.files_selected, selected.size()))
                .setItems(items.toArray(new String[0]), (d, i) -> acts.get(i).run())
                .setNegativeButton(R.string.files_cancel, null)
                .show();
    }

    /** 剪貼簿那顆 ⋮。 */
    private void showClipMenu() {
        if (transferring || clipMode == CLIP_NONE) return;
        String[] items = {
                getString(R.string.files_paste),
                getString(R.string.files_clear_clip),
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(clipMode == CLIP_CUT
                        ? R.string.files_clip_cut : R.string.files_clip_copy, clipJobs.size()))
                .setItems(items, (d, i) -> {
                    if (i == 0) paste();
                    else {
                        clipMode = CLIP_NONE;
                        clipJobs.clear();
                        updateBottomBar();
                    }
                })
                .setNegativeButton(R.string.files_cancel, null)
                .show();
    }

    /**
     * 把目前的選取放進剪貼簿。
     *
     * <p>放完就<b>清掉選取</b>：留著的話底部那條會繼續顯示「已選 N 項」，
     * 使用者會以為剛才按的「複製」沒生效，接著再按一次。
     */
    private void setClipboard(int mode) {
        if (transferring || selected.isEmpty()) return;
        List<Job> jobs = jobsFromSelection();
        if (jobs.isEmpty()) return;
        clipMode = mode;
        clipSide = side;
        clipJobs.clear();
        clipJobs.addAll(jobs);
        selected.clear();
        updateBottomBar();
        renderList();
        Toast.makeText(this, getString(mode == CLIP_CUT
                ? R.string.files_cut_done : R.string.files_copied, jobs.size()),
                Toast.LENGTH_SHORT).show();
    }

    /** 選取 → 要搬的清單。名稱與「是不是資料夾」現在就要抓好，之後列表會變。 */
    private List<Job> jobsFromSelection() {
        List<Job> jobs = new ArrayList<>();
        if (side == SIDE_LOCAL) {
            for (LocalItem it : localItems) {
                if (selected.contains(it.docId)) jobs.add(new Job(it.docId, it.name, it.dir));
            }
        } else {
            for (AdbSync.Entry it : remoteItems) {
                if (selected.contains(it.path)) jobs.add(new Job(it.path, it.name, it.isDir()));
            }
        }
        return jobs;
    }

    private void selectAll() {
        selected.clear();
        // 只選「目前看得到的」：搜尋結果只有 3 項時按全選卻選了 150 項，
        // 接下來那一步（刪除／傳送）就是災難。
        if (side == SIDE_LOCAL) {
            for (LocalItem it : localItems) {
                if (matchesFilter(it.name)) selected.add(it.docId);
            }
        } else {
            for (AdbSync.Entry it : remoteItems) {
                if (matchesFilter(it.name)) selected.add(it.path);
            }
        }
        updateBottomBar();
        renderList();
    }

    /** 新增資料夾。 */
    private void newFolder() {
        if (transferring) return;
        if (side == SIDE_LOCAL && treeUri == null) {
            Toast.makeText(this, R.string.files_need_local_root, Toast.LENGTH_SHORT).show();
            pickLocalRoot();
            return;
        }
        final boolean local = side == SIDE_LOCAL;
        final String parentDocId = currentLocalDocId();
        final String parentRemote = remotePath;
        askName(R.string.files_menu_new_folder, "", name -> busy(
                getString(R.string.files_working),
                () -> {
                    if (local) createLocalDir(parentDocId, name);
                    else shellOp("mkdir -p " + q(parentRemote + "/" + name));
                },
                getString(R.string.files_created, name)));
    }

    private void renameSelection() {
        if (transferring || selected.size() != 1) return;
        final String key = selected.iterator().next();
        final boolean local = side == SIDE_LOCAL;
        final String oldName = nameOf(key);
        if (oldName == null) return;

        askName(R.string.files_rename_title, oldName, name -> {
            if (name.equals(oldName)) return;
            busy(getString(R.string.files_working), () -> {
                if (local) renameLocal(key, name);
                else renameRemote(key, name);
            }, getString(R.string.files_renamed, name));
        });
    }

    private void deleteSelection() {
        if (transferring || selected.isEmpty()) return;
        final List<String> keys = new ArrayList<>(selected);
        // 兩邊的「刪掉之後會怎樣」不一樣：本機走 SAF，遠端是 rm -rf。
        // 訊息要對得上，不然本機刪檔案卻跳出「遠端刪除不會進垃圾桶」只會讓人愣住。
        int msg = side == SIDE_LOCAL
                ? R.string.files_delete_confirm_local : R.string.files_delete_confirm;
        new AlertDialog.Builder(this)
                .setTitle(R.string.files_delete_title)
                .setMessage(getString(msg, keys.size()))
                .setPositiveButton(R.string.files_delete_ok, (d, w) -> doDelete(keys))
                .setNegativeButton(R.string.files_cancel, null)
                .show();
    }

    private void doDelete(List<String> keys) {
        final boolean local = side == SIDE_LOCAL;
        busy(getString(R.string.files_delete_title), () -> {
            if (local) {
                // 一個刪不掉不該讓其他的都不刪：記下來，最後一起講
                StringBuilder fail = new StringBuilder();
                for (String id : keys) {
                    try {
                        DocumentsContract.deleteDocument(getContentResolver(),
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, id));
                    } catch (Exception e) {
                        if (fail.length() > 0) fail.append("; ");
                        fail.append(e.getMessage() == null ? e.toString() : e.getMessage());
                    }
                }
                if (fail.length() > 0) throw new IOException(fail.toString());
            } else {
                StringBuilder cmd = new StringBuilder();
                for (String p : keys) {
                    if (cmd.length() > 0) cmd.append("; ");
                    cmd.append("rm -rf ").append(q(p));
                }
                shellOp(cmd.toString());
            }
        }, getString(R.string.files_deleted, keys.size()));
    }

    private void showInfo() {
        if (selected.size() != 1) return;
        String key = selected.iterator().next();
        String nl = "\n";
        StringBuilder sb = new StringBuilder();

        if (side == SIDE_LOCAL) {
            for (LocalItem it : localItems) {
                if (!it.docId.equals(key)) continue;
                sb.append(getString(R.string.files_info_name)).append("：").append(it.name).append(nl);
                sb.append(getString(R.string.files_info_path)).append("：")
                        .append(localBreadcrumb()).append(" / ").append(it.name).append(nl);
                sb.append(getString(R.string.files_info_kind)).append("：")
                        .append(it.dir ? getString(R.string.files_kind_dir)
                                : getString(R.string.files_kind_file)).append(nl);
                sb.append(getString(R.string.files_info_size)).append("：")
                        .append(it.dir ? "—" : formatSize(it.size)).append(nl);
                sb.append(getString(R.string.files_info_time)).append("：")
                        .append(it.mtime > 0 ? formatTime(it.mtime) : "—");
                break;
            }
        } else {
            for (AdbSync.Entry it : remoteItems) {
                if (!it.path.equals(key)) continue;
                sb.append(getString(R.string.files_info_name)).append("：").append(it.name).append(nl);
                sb.append(getString(R.string.files_info_path)).append("：").append(it.path).append(nl);
                sb.append(getString(R.string.files_info_kind)).append("：")
                        .append(it.isDir() ? getString(R.string.files_kind_dir)
                                : getString(R.string.files_kind_file)).append(nl);
                sb.append(getString(R.string.files_info_size)).append("：")
                        .append(it.isDir() ? "—" : formatSize(it.size)).append(nl);
                sb.append(getString(R.string.files_info_time)).append("：")
                        .append(it.mtime > 0 ? formatTime(it.mtime) : "—").append(nl);
                // 權限：看得到 x 位就知道這個資料夾能不能進去
                sb.append(getString(R.string.files_info_mode)).append("：")
                        .append(String.format(Locale.US, "%06o", it.mode));
                break;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.files_info)
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 常用位置。手打路徑太苦，這幾個是九成情況會去的地方。 */
    private void showPlaces() {
        final String[] places = {
                "/sdcard",
                "/sdcard/DCIM",
                "/sdcard/DCIM/Camera",
                "/sdcard/Pictures",
                "/sdcard/Download",
                "/sdcard/Movies",
                "/sdcard/Music",
                "/sdcard/Android/data",
                "/data/local/tmp",
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.files_menu_places)
                .setItems(places, (d, i) -> goRemote(places[i]))
                .setNegativeButton(R.string.files_cancel, null)
                .show();
    }

    /** 遠端才有的「直接跳到某個路徑」。一層一層點到 {@code /sdcard/Android/data} 太折磨人。 */
    private void jumpToPath() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        // 路徑是 ASCII，但用同一個 inputType 就好 —— 免得又踩到上面那個 NULL 的坑
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(remotePath);
        input.setSelection(remotePath.length());

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(R.string.files_jump_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.files_cancel, null)
                .create();
        d.setCanceledOnTouchOutside(false);
        // 按鈕預設按下去就關視窗；路徑不合法時要能留在原地重打，所以自己接管
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String p = input.getText().toString().trim();
                    if (!p.startsWith("/")) {
                        Toast.makeText(this, R.string.files_jump_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
                    remotePath = p;
                    selected.clear();
                    updateBottomBar();
                    d.dismiss();
                    loadRemote();
                }));
        d.show();
    }

    /**
     * 問一個名稱（新增資料夾／改名共用）。
     *
     * <p>名稱不合法時<b>留在原地</b>：關掉再重開等於把使用者剛打的字丟掉。
     */
    private void askName(int titleRes, String initial, NameSink sink) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        // 一定要指定 inputType：不指定的話 EditorInfo 是 inputType=0（NULL），
        // 有的輸入法就只顯示鍵盤、字一個都不進來（實測 Google 輸入法就是這樣）。
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.files_name_hint);
        input.setText(initial);
        input.setSelection(initial.length());

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.files_cancel, null)
                .create();
        // 點到外面就關掉，等於把剛打的字默默丟掉 —— 這種對話框寧可要求明確取消
        d.setCanceledOnTouchOutside(false);
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = input.getText().toString().trim();
                    if (!validName(name)) {
                        Toast.makeText(this, R.string.files_name_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    d.dismiss();
                    sink.run(name);
                }));
        d.show();
    }

    private interface NameSink {
        void run(String name);
    }

    private static boolean validName(String n) {
        return !n.isEmpty() && n.length() <= 255 && n.indexOf('/') < 0
                && !".".equals(n) && !"..".equals(n);
    }

    private String nameOf(String key) {
        if (side == SIDE_LOCAL) {
            for (LocalItem it : localItems) {
                if (it.docId.equals(key)) return it.name;
            }
        } else {
            for (AdbSync.Entry it : remoteItems) {
                if (it.path.equals(key)) return it.name;
            }
        }
        return null;
    }

    private String currentLocalDocId() {
        return localStack.isEmpty() ? null : localStack.get(localStack.size() - 1).docId;
    }

    private String createLocalDir(String parentDocId, String name) throws IOException {
        if (treeUri == null) throw new IOException(getString(R.string.files_need_local_root));
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId);
        Uri created = DocumentsContract.createDocument(getContentResolver(), parent,
                DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null) throw new IOException("本機建不了資料夾 " + name);
        return DocumentsContract.getDocumentId(created);
    }

    private void renameLocal(String docId, String name) throws IOException {
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        // 目標名稱已存在時 renameDocument 會丟 FileNotFoundException（不是默默覆蓋）
        Uri renamed = DocumentsContract.renameDocument(getContentResolver(), doc, name);
        if (renamed == null) throw new IOException("改名沒有回傳結果");
    }

    private void renameRemote(String oldPath, String name) throws Exception {
        String newPath = parentOf(oldPath) + "/" + name;
        try {
            shellOp("mv " + q(oldPath) + " " + q(newPath));
        } catch (IOException e) {
            // 通道有可能是在「mv 已經成功」之後才斷的，重試會找不到來源。
            // 用 STAT 問一次新的路徑，在就當成功。
            if (withSync(s -> s.stat(newPath)) == null) throw e;
        }
    }

    private static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    // ================================================================== 遠端 shell

    /**
     * mksh 的單引號轉義。
     *
     * <p>單引號裡什麼都不展開（空白、{@code $}、{@code *}、中文都安全），
     * 只有單引號自己要先收起來、跳脫、再開一個 —— 也就是 {@code '\''}。
     * 檔名裡有單引號的檔案真的存在（{@code it's.jpg}），不處理就是「刪錯檔案」。
     */
    private static String q(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    /**
     * 遠端檔案指令的<b>閒置</b>上限（不是總時長）。
     *
     * <p>心跳每秒一次，所以「15 秒沒有任何位元組」只可能是通道真的斷了 ——
     * 這比猜「這個操作大概要幾秒」可靠得多。
     */
    private static final long SHELL_IDLE_MS = 15_000;

    /** 遠端回的退出碼記號，見 {@link AdbClient#shellAlive}。 */
    private static final String RC_TAG = "@@OMNIADB:RC:";

    /** 跑一條遠端 shell 指令；通道死掉就重建再試一次。 */
    private void shellOp(String cmd) throws Exception {
        try {
            runShell(cmd);
        } catch (IOException first) {
            DiagLog.w(TAG, "遠端指令失敗，重建連線後重試一次：" + cmd, first);
            closeClientSync();
            runShell(cmd);
        }
    }

    /**
     * 執行遠端檔案指令，<b>不猜它要跑多久</b>。
     *
     * <p>以前這裡是一個固定的 20 秒，複製大檔另外硬塞 300 秒 —— 那都是猜的：
     * 200 MB 的相簿三分鐘、2 GB 的影片半小時，同一個數字不可能同時滿足。
     * 猜小了正常操作被砍，猜大了真的卡死要等半天。
     *
     * <p>現在改成心跳式（{@link AdbClient#shellAlive}）：遠端每秒吐一個位元組，
     * 只要還在動就一直等，連續 {@link #SHELL_IDLE_MS} 沒動靜才當成通道死了。
     */
    private void runShell(String cmd) throws Exception {
        if (client == null) client = new AdbClient(this, host, port);
        String out = client.shellAlive(cmd, SHELL_END, SHELL_IDLE_MS);

        // 心跳要剔掉，不然輸出裡每隔一秒就混一個 @@HB@
        out = out.replace(AdbClient.HEARTBEAT, "");
        int i = out.indexOf(SHELL_END);
        String body = (i >= 0 ? out.substring(0, i) : out).trim();

        // 退出碼比「有沒有輸出」準：cp 失敗時可能一句話都不印。
        // 有退出碼就優先信它，沒有才退回舊的「有輸出即失敗」。
        int rcAt = body.lastIndexOf(RC_TAG);
        if (rcAt >= 0) {
            String tail = body.substring(rcAt + RC_TAG.length()).trim();
            body = body.substring(0, rcAt).trim();
            int rc = 0;
            try {
                rc = Integer.parseInt(tail.split("\\s+")[0]);
            } catch (Exception ignored) {
                // 解析不到就當 0，不要因為讀不懂記號而報一個假錯誤
            }
            if (rc != 0 && body.isEmpty()) {
                throw new IOException("遠端指令回報錯誤碼 " + rc);
            }
        }
        if (!body.isEmpty()) throw new IOException(body);
    }

    /** 一個在背景跑、有「處理中」對話框的操作。 */
    private interface Op {
        void run() throws Exception;
    }

    /**
     * 跑一個短操作（新增資料夾／改名／刪除），全程蓋一個不能點的對話框。
     *
     * <p>為什麼要蓋：這些操作會用到<b>同一條</b>連線，而使用者在這段時間按「重新整理」
     * 或換目錄，就會有兩條 stream 同時講話 —— SYNC 是有狀態的協定，交錯就毀了。
     */
    private void busy(String title, Op op, String successMsg) {
        transferring = true;
        final ProgressUi dlg = new ProgressUi();
        dlg.show(title, false);

        io.execute(() -> {
            String err = null;
            try {
                op.run();
            } catch (Exception e) {
                DiagLog.w(TAG, "操作失敗：" + title, e);
                err = AdbClient.describeError(e);
            }
            final String finalErr = err;
            ui.post(() -> {
                transferring = false;
                dlg.dismiss();
                if (finalErr != null) {
                    Toast.makeText(this, getString(R.string.files_op_failed, finalErr),
                            Toast.LENGTH_LONG).show();
                } else if (successMsg != null) {
                    Toast.makeText(this, successMsg, Toast.LENGTH_SHORT).show();
                }
                reload();
            });
        });
    }

    // ================================================================== 傳輸

    /**
     * 把選中的東西搬到「對面」目前的目錄。
     *
     * <p>一次一個、序列做。理由跟單執行緒池一樣：SYNC 通道不支援並行。
     * 中途失敗就停在那裡 —— 繼續傳下去只會讓使用者以為全部成功。
     *
     * <p>選到資料夾就是<b>整個搬</b>（遞迴），跟檔案總管的直覺一致。
     */
    private void transferSelection() {
        if (transferring || selected.isEmpty()) return;
        List<Job> jobs = jobsFromSelection();
        if (jobs.isEmpty()) return;
        // 「傳到對面」就是一次不剪下的跨側貼上 —— 同一套流程，不另外寫一份
        runTransfer(jobs, side, 1 - side, false, false);
    }

    /** 貼上剪貼簿。目的地是<b>目前這一側</b>目前的目錄，可能跟來源同一側。 */
    private void paste() {
        if (transferring || clipMode == CLIP_NONE || clipJobs.isEmpty()) return;
        runTransfer(new ArrayList<>(clipJobs), clipSide, side, clipMode == CLIP_CUT, true);
    }

    // 撞名時怎麼辦
    private static final int ON_CONFLICT_RENAME = 1;
    private static final int ON_CONFLICT_OVERWRITE = 2;

    /**
     * 一次搬運的完整流程：檢查 →（有撞名就問）→ 執行。
     *
     * <p>四種組合（本機→本機、本機→遠端、遠端→本機、遠端→遠端）都走這裡。
     * 分開寫四份的話，撞名、進度、取消、失敗訊息都要複製四次，遲早不一致。
     */
    private void runTransfer(final List<Job> jobs, final int srcSide, final int dstSide,
                             final boolean cut, final boolean fromClip) {
        if (jobs.isEmpty()) return;

        if (dstSide == SIDE_LOCAL && treeUri == null) {
            Toast.makeText(this, R.string.files_need_local_root, Toast.LENGTH_SHORT).show();
            pickLocalRoot();
            return;
        }

        // 目的地座標先釘住：等一下的對話框可能讓使用者等幾秒，這中間目錄不能漂
        final String dstRemote = remotePath;
        final String dstLocal = currentLocalDocId();

        if (srcSide == dstSide) {
            String bad = checkSelf(srcSide, jobs, dstRemote, dstLocal, cut);
            if (bad != null) {
                Toast.makeText(this, bad, Toast.LENGTH_LONG).show();
                return;
            }
        }

        // 先問一次「目的地現在有什麼」——撞名要使用者決定，不能默默覆蓋
        transferring = true;
        final ProgressUi check = new ProgressUi();
        check.show(getString(R.string.files_checking), false);
        io.execute(() -> {
            List<Job> clash = null;
            String err = null;
            try {
                clash = clashes(dstSide, dstSide == SIDE_LOCAL ? dstLocal : dstRemote, jobs);
            } catch (Exception e) {
                DiagLog.w(TAG, "檢查目的地失敗", e);
                err = AdbClient.describeError(e);
            }
            final List<Job> finalClash = clash;
            final String finalErr = err;
            ui.post(() -> {
                transferring = false;
                check.dismiss();
                if (finalErr != null) {
                    Toast.makeText(this, getString(R.string.files_op_failed, finalErr),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (finalClash == null || finalClash.isEmpty()) {
                    executeTransfer(jobs, srcSide, dstSide, cut, fromClip,
                            ON_CONFLICT_RENAME, dstRemote, dstLocal);
                } else {
                    askConflict(finalClash, jobs, srcSide, dstSide, cut, fromClip,
                            dstRemote, dstLocal);
                }
            });
        });
    }

    /**
     * 擋掉三種「做了等於沒做，或做了會壞掉」的貼上。
     *
     * <p>前兩種是搬移到自己所在的資料夾（no-op，但會白跑一次），
     * 第三種最要命：把資料夾搬進它自己的子目錄，{@code cp -r} 會無限遞迴到塞爆儲存空間。
     *
     * @return 要顯示的訊息；{@code null} 表示可以繼續
     */
    private String checkSelf(int s, List<Job> jobs, String dstRemote, String dstLocal, boolean cut) {
        for (Job j : jobs) {
            if (s == SIDE_REMOTE) {
                String dst = dstRemote + "/" + j.name;
                if (cut && parentOf(j.key).equals(dstRemote)) {
                    return getString(R.string.files_same_dir);
                }
                if (j.dir && (dstRemote.equals(j.key) || dstRemote.startsWith(j.key + "/"))) {
                    return getString(R.string.files_into_self);
                }
            } else {
                if (cut && dstLocal != null && parentDocId(j.key).equals(dstLocal)) {
                    return getString(R.string.files_same_dir);
                }
                // SAF 的 document id 在外部儲存上是 primary:相對路徑，前綴比對就夠用。
                // 不支援這種格式的 provider 只是擋不住，後續操作會自己失敗，不會壞資料。
                if (j.dir && dstLocal != null && dstLocal.startsWith(j.key + "/")) {
                    return getString(R.string.files_into_self);
                }
            }
        }
        return null;
    }

    /** 目的地已經有同名的那些。 */
    private List<Job> clashes(int dstSide, String dirKey, List<Job> jobs) throws Exception {
        Set<String> names = namesIn(dstSide, dirKey);
        List<Job> out = new ArrayList<>();
        for (Job j : jobs) {
            if (names.contains(j.name)) out.add(j);
        }
        return out;
    }

    private Set<String> namesIn(int s, String dirKey) throws Exception {
        Set<String> names = new HashSet<>();
        if (s == SIDE_LOCAL) {
            if (dirKey == null) throw new IOException(getString(R.string.files_need_local_root));
            for (LocalItem it : listLocal(dirKey)) names.add(it.name);
        } else {
            for (AdbSync.Entry e : withSync(sy -> sy.list(dirKey))) names.add(e.name);
        }
        return names;
    }

    /** 「目標已有同名」的對話框。三個選擇都寫出來，不預設任何一個。 */
    private void askConflict(List<Job> clash, final List<Job> jobs, final int srcSide,
                             final int dstSide, final boolean cut, final boolean fromClip,
                             final String dstRemote, final String dstLocal) {
        StringBuilder sb = new StringBuilder();
        int show = Math.min(clash.size(), 8);
        for (int i = 0; i < show; i++) {
            if (i > 0) sb.append("\n");
            sb.append("· ").append(clash.get(i).name);
        }
        if (clash.size() > show) sb.append("\n…");
        String dest = dstSide == SIDE_LOCAL ? localBreadcrumb() : dstRemote;
        new AlertDialog.Builder(this)
                .setTitle(R.string.files_conflict_title)
                .setMessage(getString(R.string.files_conflict_msg, clash.size(), dest, sb.toString()))
                .setPositiveButton(R.string.files_conflict_overwrite, (d, w) ->
                        executeTransfer(jobs, srcSide, dstSide, cut, fromClip,
                                ON_CONFLICT_OVERWRITE, dstRemote, dstLocal))
                .setNeutralButton(R.string.files_conflict_keep, (d, w) ->
                        executeTransfer(jobs, srcSide, dstSide, cut, fromClip,
                                ON_CONFLICT_RENAME, dstRemote, dstLocal))
                .setNegativeButton(R.string.files_cancel, null)
                .show();
    }

    private void executeTransfer(final List<Job> jobs, final int srcSide, final int dstSide,
                                 final boolean cut, final boolean fromClip, final int onConflict,
                                 final String dstRemote, final String dstLocal) {
        transferring = true;
        final ProgressUi progress = new ProgressUi();
        progress.show(getString(cut ? R.string.files_moving : R.string.files_copying), true);

        io.execute(() -> {
            int done = 0;
            String error = null;
            List<Job> work = jobs;
            try {
                // 撞名要在<b>真正搬之前</b>處理完：搬的過程中目標一定不存在，
                // 就不會出現「mv 把檔案搬進已存在的目錄裡」這種怪結果。
                work = settleNames(dstSide, dstRemote, dstLocal, jobs, onConflict);
            } catch (Exception e) {
                DiagLog.w(TAG, "處理同名項目失敗", e);
                error = AdbClient.describeError(e);
            }
            if (error == null) {
                for (int i = 0; i < work.size(); i++) {
                    if (progress.cancelled) break;
                    final Job job = work.get(i);
                    final int index = i;
                    final int total = work.size();
                    ui.post(() -> progress.setFile(index + 1, total, job.name));
                    try {
                        moveOne(job, srcSide, dstSide, cut, dstRemote, dstLocal, progress);
                        done++;
                    } catch (Exception e) {
                        DiagLog.w(TAG, "搬運失敗：" + job.name, e);
                        error = job.name + "：" + AdbClient.describeError(e);
                        break;
                    }
                }
            }

            final int okCount = done;
            final String err = error;
            final boolean cancelled = progress.cancelled;
            ui.post(() -> {
                transferring = false;
                progress.dismiss();
                // 剪下的東西搬過去了，剪貼簿就該空 —— 留著再貼一次只會找不到來源
                if (fromClip && cut && (okCount > 0 || err == null)) {
                    clipMode = CLIP_NONE;
                    clipJobs.clear();
                }
                if (!fromClip) selected.clear();
                updateBottomBar();
                reload();
                if (err != null) {
                    Toast.makeText(this, getString(R.string.files_transfer_failed, err),
                            Toast.LENGTH_LONG).show();
                } else if (!cancelled) {
                    Toast.makeText(this, getString(R.string.files_transfer_done, okCount),
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /** 搬其中一項。四個方向、兩種語意，全部收在這裡。 */
    private void moveOne(Job j, int srcSide, int dstSide, boolean cut,
                         String dstRemote, String dstLocal, ProgressUi p) throws Exception {
        if (srcSide == SIDE_REMOTE && dstSide == SIDE_REMOTE) {
            String dst = dstRemote + "/" + j.name;
            if (j.key.equals(dst)) return;
            // mv 在同一分割區只是改個指標，瞬間；cp 是真的把位元組搬一遍，要給足時間
            shellOp((cut ? "mv " : "cp -r ") + q(j.key) + " " + q(dst));
        } else if (srcSide == SIDE_LOCAL && dstSide == SIDE_LOCAL) {
            localCopyOrMove(j, dstLocal, cut, p);
        } else if (srcSide == SIDE_LOCAL) {
            if (j.dir) uploadLocalTree(j.key, dstRemote, j.name, p);
            else uploadLocal(j.key, dstRemote + "/" + j.name, p);
        } else {
            if (j.dir) downloadRemoteTree(j.key, dstLocal, j.name, p);
            else downloadRemote(j.key, dstLocal, j.name, p);
        }
    }

    /**
     * 撞名的收尾：該刪的刪掉（覆蓋），或把名字改成不撞的（兩份都留）。
     *
     * @return 真正要用的清單（名字可能已經改過）
     */
    private List<Job> settleNames(int dstSide, String dstRemote, String dstLocal,
                                  List<Job> jobs, int onConflict) throws Exception {
        Set<String> taken = new HashSet<>();
        List<Job> out = new ArrayList<>();
        List<String> killRemote = new ArrayList<>();
        List<String> killLocal = new ArrayList<>();

        if (dstSide == SIDE_REMOTE) {
            Map<String, AdbSync.Entry> map = new HashMap<>();
            for (AdbSync.Entry e : withSync(sy -> sy.list(dstRemote))) {
                taken.add(e.name);
                map.put(e.name, e);
            }
            for (Job j : jobs) {
                if (!taken.contains(j.name)) {
                    out.add(j);
                } else if (onConflict == ON_CONFLICT_RENAME) {
                    String nn = uniqueName(j.name, taken);
                    taken.add(nn);
                    out.add(new Job(j.key, nn, j.dir));
                } else {
                    AdbSync.Entry old = map.get(j.name);
                    if (old != null) killRemote.add(old.path);
                    out.add(j);
                }
            }
        } else {
            if (dstLocal == null) throw new IOException(getString(R.string.files_need_local_root));
            Map<String, LocalItem> map = new HashMap<>();
            for (LocalItem it : listLocal(dstLocal)) {
                taken.add(it.name);
                map.put(it.name, it);
            }
            for (Job j : jobs) {
                if (!taken.contains(j.name)) {
                    out.add(j);
                } else if (onConflict == ON_CONFLICT_RENAME) {
                    String nn = uniqueName(j.name, taken);
                    taken.add(nn);
                    out.add(new Job(j.key, nn, j.dir));
                } else {
                    LocalItem old = map.get(j.name);
                    if (old != null) killLocal.add(old.docId);
                    out.add(j);
                }
            }
        }

        for (String p : killRemote) shellOp("rm -rf " + q(p));
        for (String id : killLocal) {
            DocumentsContract.deleteDocument(getContentResolver(),
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id));
        }
        return out;
    }

    /** {@code a.jpg} → {@code a (1).jpg}，一路加到不撞為止。 */
    private static String uniqueName(String name, Set<String> taken) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            String cand = base + " (" + i + ")" + ext;
            if (!taken.contains(cand)) return cand;
        }
        return base + " (" + System.currentTimeMillis() + ")" + ext;
    }

    private static String parentDocId(String docId) {
        int i = docId.lastIndexOf('/');
        return i < 0 ? docId : docId.substring(0, i);
    }

    /**
     * 本機同側的複製／搬移。
     *
     * <p>先用系統的 {@code moveDocument}/{@code copyDocument}（快，provider 內部
     * 可能只是改指標，不用把位元組搬一遍）；不支援再退回自己串流複製。
     * 各家 provider（雲端硬碟、USB 隨身碟）支援程度不一，不支援時會丟
     * {@code UnsupportedOperationException} 或 {@code IllegalArgumentException}。
     */
    private void localCopyOrMove(Job j, String dstParentDocId, boolean cut, ProgressUi p)
            throws Exception {
        if (dstParentDocId == null) throw new IOException(getString(R.string.files_need_local_root));
        Uri src = DocumentsContract.buildDocumentUriUsingTree(treeUri, j.key);
        Uri dstParent = DocumentsContract.buildDocumentUriUsingTree(treeUri, dstParentDocId);

        if (cut) {
            try {
                Uri moved = DocumentsContract.moveDocument(getContentResolver(), src,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId(j.key)),
                        dstParent);
                ensureName(moved, j.name);
                return;
            } catch (Exception e) {
                DiagLog.w(TAG, "moveDocument 不支援，改成複製後刪除：" + j.name, e);
            }
        } else if (Build.VERSION.SDK_INT >= 31) {
            try {
                ensureName(DocumentsContract.copyDocument(getContentResolver(), src, dstParent),
                        j.name);
                return;
            } catch (Exception e) {
                DiagLog.w(TAG, "copyDocument 不支援，改成串流複製：" + j.name, e);
            }
        }

        if (j.dir) localCopyTree(j.key, dstParentDocId, j.name, p);
        else localCopyFile(j.key, dstParentDocId, j.name, p);
        if (cut) DocumentsContract.deleteDocument(getContentResolver(), src);
    }

    private void localCopyTree(String srcDocId, String dstParentDocId, String name, ProgressUi p)
            throws Exception {
        String newId = createLocalDir(dstParentDocId, name);
        for (LocalItem it : listLocal(srcDocId)) {
            if (p.cancelled) return;
            if (it.dir) {
                localCopyTree(it.docId, newId, it.name, p);
            } else {
                p.setName(it.name);
                localCopyFile(it.docId, newId, it.name, p);
            }
        }
    }

    private void localCopyFile(String srcDocId, String dstParentDocId, String name, ProgressUi p)
            throws Exception {
        Uri src = DocumentsContract.buildDocumentUriUsingTree(treeUri, srcDocId);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, dstParentDocId);
        String mime = "application/octet-stream";
        long size = -1;
        try (Cursor c = getContentResolver().query(src, new String[]{
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                if (!c.isNull(0)) mime = c.getString(0);
                if (!c.isNull(1)) size = c.getLong(1);
            }
        }
        Uri created = DocumentsContract.createDocument(getContentResolver(), parent, mime, name);
        if (created == null) throw new IOException("本機建不了 " + name);
        try (InputStream in = getContentResolver().openInputStream(src);
             OutputStream out = getContentResolver().openOutputStream(created)) {
            if (in == null || out == null) throw new IOException("本機讀寫失敗：" + name);
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (p.cancelled) break;
                out.write(buf, 0, n);
                done += n;
                p.onBytes(done, size);
            }
        } catch (Exception e) {
            // 半個檔案比沒有檔案更糟
            try {
                DocumentsContract.deleteDocument(getContentResolver(), created);
            } catch (Exception ignored) {
                // 刪不掉也只能這樣
            }
            throw e;
        }
    }

    /** 系統 API 搬完／複製完可能還叫舊名字（改名模式），補一次 rename。失敗不算錯。 */
    private void ensureName(Uri doc, String want) {
        if (doc == null) return;
        String have = null;
        try (Cursor c = getContentResolver().query(doc,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) have = c.getString(0);
        } catch (Exception ignored) {
            // 問不到名字就不用改
        }
        if (have == null || have.equals(want)) return;
        try {
            DocumentsContract.renameDocument(getContentResolver(), doc, want);
        } catch (Exception e) {
            DiagLog.w(TAG, "改名失敗：" + want, e);
        }
    }

    /**
     * 遠端一整個目錄搬過來：先在本機建同名資料夾，再逐項遞迴。
     *
     * <p><b>不套用「顯示隱藏」的過濾</b> —— 使用者選的是一個資料夾，意思就是整個資料夾；
     * 偷偷少搬幾個檔才是真的坑。
     */
    private void downloadRemoteTree(String remoteDir, String localParentDocId, String dirName,
                                    ProgressUi p) throws Exception {
        String docId = createLocalDir(localParentDocId, dirName);
        List<AdbSync.Entry> items = withSync(s -> s.list(remoteDir));
        for (AdbSync.Entry e : items) {
            if (p.cancelled) return;
            if (e.isDir()) {
                downloadRemoteTree(e.path, docId, e.name, p);
            } else {
                p.setName(e.name);
                downloadRemote(e.path, docId, e.name, p);
            }
        }
    }

    /** 本機一整個目錄搬過去：先 {@code mkdir -p}，再逐項遞迴。 */
    private void uploadLocalTree(String localDocId, String remoteParentDir, String dirName,
                                 ProgressUi p) throws Exception {
        String dir = remoteParentDir + "/" + dirName;
        shellOp("mkdir -p " + q(dir));
        for (LocalItem it : listLocal(localDocId)) {
            if (p.cancelled) return;
            if (it.dir) {
                uploadLocalTree(it.docId, dir, it.name, p);
            } else {
                p.setName(it.name);
                uploadLocal(it.docId, dir + "/" + it.name, p);
            }
        }
    }

    private void uploadLocal(String docId, String remoteFile, AdbSync.Progress p) throws Exception {
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        long size = -1;
        try (Cursor c = getContentResolver().query(doc,
                new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) size = c.getLong(0);
        }
        final long total = size;
        // 串流要在 lambda 裡面開：重試時需要一條新的（舊的已經讀到一半或關了）
        withSync(s -> {
            try (InputStream is = getContentResolver().openInputStream(doc)) {
                if (is == null) throw new IOException("讀不到這個檔案");
                s.send(is, total, remoteFile, p);
            }
            return null;
        });
    }

    private void downloadRemote(String remoteFile, String localDocId, String name,
                                AdbSync.Progress p) throws Exception {
        if (localDocId == null) throw new IOException("本機還沒有選資料夾");
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, localDocId);
        final String mime = guessMime(name);
        withSync(s -> {
            Uri created = DocumentsContract.createDocument(getContentResolver(), parent, mime, name);
            if (created == null) throw new IOException("本機建不了這個檔案");
            try (OutputStream os = getContentResolver().openOutputStream(created)) {
                if (os == null) throw new IOException("本機寫不進去");
                s.recv(remoteFile, os, p);
            } catch (Exception e) {
                // 半個檔案比沒有檔案更糟（使用者會以為抓完了），失敗就刪掉
                try {
                    DocumentsContract.deleteDocument(getContentResolver(), created);
                } catch (Exception ignored) {
                    // 刪不掉也只能這樣
                }
                throw e;
            }
            return null;
        });
    }

    /** 取得（必要時建立）檔案通道。通道死了會自動重建，見 {@link AdbSync}。 */
    private AdbSync sync() throws Exception {
        if (client == null) client = new AdbClient(this, host, port);
        return client.sync();
    }

    /** 一個會用到 SYNC 通道的操作。 */
    private interface SyncOp<T> {
        T run(AdbSync sync) throws Exception;
    }

    /**
     * 執行一個 SYNC 操作，<b>失敗就重建連線再試一次</b>。
     *
     * <p>為什麼一定要有這層：實測這條通道<b>會被對方單方面收掉</b> ——
     * 手機休眠、Wi-Fi 省電、系統凍結背景 App 都可能讓它默默死掉。
     * 死掉的當下不會有任何通知，使用者看到的是「按了傳輸，馬上說失敗」。
     *
     * <p>重試是安全的：{@code SEND} 會把遠端檔案截斷重寫，{@code RECV}
     * 的失敗路徑已經把半成品刪掉了，{@code LIST} 本來就沒有副作用。
     * 重試時<b>連 {@link AdbClient} 一起換掉</b> —— 只換 sync 通道不夠，
     * 底層的 TLS 連線可能也已經半死（那個坑見 {@code AdbClient#installApk}）。
     */
    private <T> T withSync(SyncOp<T> op) throws Exception {
        try {
            return op.run(sync());
        } catch (IOException first) {
            DiagLog.w(TAG, "檔案通道失效，重建連線後重試一次", first);
            closeClientSync();
            return op.run(sync());
        }
    }

    /** 同步關掉連線。只在背景執行緒用（{@code close()} 可能阻塞）。 */
    private void closeClientSync() {
        final AdbClient c = client;
        client = null;
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
            // 已經死了
        }
    }

    // ================================================================== 進度對話框

    /**
     * 傳輸進度。
     *
     * <p>用 {@link AlertDialog} 而不是自製 View：這一段使用者只會看兩秒到兩分鐘，
     * 不需要花俏，需要的是「看得懂現在在做什麼」＋「不對勁可以停」。
     */
    private final class ProgressUi implements AdbSync.Progress {
        AlertDialog dialog;
        ProgressBar bar;
        TextView text;
        volatile boolean cancelled;
        /** 已經切成「不確定」動畫了，別每一塊資料都重設一次（會讓動畫一直重來）。 */
        private boolean indeterminate;
        /** 現在在搬哪一項（給下面那個計時器用）。 */
        private String label = "";
        private long startedAt;

        /**
         * 每秒把「已 N 秒」寫進訊息。
         *
         * <p>為什麼需要：{@code cp -r} 一個兩千檔的目錄要三十幾秒，這期間
         * <b>沒有任何位元組回報</b>（進度條只能是不確定動畫）。畫面完全不動，
         * 使用者唯一能判斷「還活著」的線索就是秒數在跳。
         */
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (dialog == null || !dialog.isShowing()) return;
                long s = (System.currentTimeMillis() - startedAt) / 1000;
                // 前兩秒不顯示：一閃而過的操作多一個數字只是噪音
                if (s >= 2) {
                    text.setText(getString(R.string.files_elapsed,
                            label.isEmpty() ? getString(R.string.files_working) : label, s));
                }
                ui.postDelayed(this, 1000);
            }
        };

        void show(String title, boolean cancellable) {
            LinearLayout box = new LinearLayout(FilesActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(24), dp(8), dp(24), dp(8));

            text = new TextView(FilesActivity.this);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            text.setTextColor(COLOR_TEXT);
            text.setText(getString(R.string.files_preparing));
            box.addView(text);

            bar = new ProgressBar(FilesActivity.this, null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgress(0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
            lp.setMargins(0, dp(12), 0, 0);
            bar.setLayoutParams(lp);
            box.addView(bar);

            AlertDialog.Builder b = new AlertDialog.Builder(FilesActivity.this)
                    .setTitle(title)
                    .setView(box)
                    .setCancelable(false);
            if (cancellable) {
                b.setNegativeButton(R.string.files_cancel, (d, w) -> {
                    cancelled = true;
                    // 關掉連線是最乾脆的中斷方式：SYNC 通道上的讀取沒有
                    // 中斷機制（見 AdbSync 的說明），關 stream 才會讓它拋例外。
                    closeClient();
                });
            } else {
                // 新增資料夾／刪除這種一秒鐘的事，畫一個「取消」只會讓人以為按了有效。
                // 也不能讓它變成不確定動畫：那看起來像卡住了。
                bar.setIndeterminate(true);
            }
            dialog = b.create();
            dialog.show();
            startedAt = System.currentTimeMillis();
            ui.postDelayed(tick, 1000);
        }

        void setFile(int index, int total, String name) {
            if (dialog == null || !dialog.isShowing()) return;
            label = getString(R.string.files_progress, index, total, name);
            text.setText(label);
        }

        /** 遞迴傳輸時只更新「現在搬到哪個檔」，沒有第幾個／共幾個。 */
        void setName(String name) {
            ui.post(() -> {
                if (dialog == null || !dialog.isShowing()) return;
                label = name;
                text.setText(name);
            });
        }

        /** 收到位元組。{@code total < 0} 表示協定沒先講大小（RECV 就是這樣）。 */
        @Override
        public void onBytes(long done, long total) {
            ui.post(() -> {
                if (dialog == null || !dialog.isShowing()) return;
                if (total > 0) {
                    bar.setProgress((int) Math.min(1000, done * 1000 / total));
                } else if (!indeterminate) {
                    // 不知道總量就別畫一個亂跳的百分比，改成跑馬燈式的不確定動畫
                    indeterminate = true;
                    bar.setIndeterminate(true);
                }
            });
        }

        void dismiss() {
            ui.removeCallbacks(tick);
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        }
    }

    // ================================================================== 小工具

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private static String formatTime(long seconds) {
        if (seconds <= 0) return "";
        // 協定給的是秒，Java 要毫秒
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(seconds * 1000L));
    }

    /** 依副檔名猜 MIME。SAF 建立文件時要這個，猜錯只影響系統之後怎麼開它。 */
    private static String guessMime(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".txt") || n.endsWith(".log")) return "text/plain";
        return "application/octet-stream";
    }
}
