package com.omniadb.client;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client ↔ Host 剪貼簿同步的<b>政策</b>；socket 與封包格式在 {@link ScrcpySession}。
 *
 * <p>要讓兩種「順序」都能用，這是整個設計的重點：
 *
 * <ol>
 *   <li><b>先在 Client 複製好，才連上 Host。</b>連線建立時會把本機剪貼簿推給對方，
 *       之後在鏡像畫面上長按 → 貼上，貼出來的就是剛才複製的東西。</li>
 *   <li><b>在 Host 複製，想貼回 Client 的 App。</b>連線前複製的也算 —— 連線時會先
 *       讀一次對方的剪貼簿，之後持續輪詢，一變就寫進本機剪貼簿，切到別的 App 貼上即可。</li>
 * </ol>
 *
 * <p><b>連線當下的衝突</b>：兩邊可能都有內容（而且都可能是舊的），但剪貼簿只有一份，
 * 一定得挑一邊蓋掉另一邊。判斷方式看本機那筆「什麼時候複製的」：
 * 剛複製的（{@link #FRESH_MS} 內）當成「我要貼到對方」，推過去；
 * 放很久的當成殘留，讓對方的內容蓋回來。對方根本沒內容時，還是推本機的過去。
 *
 * <p><b>回音（echo）</b>：把對方的內容寫進本機剪貼簿，會觸發本機的變更監聽；
 * 若不處理就會「Host → Client → Host → …」互相蓋。所以兩邊都留了最後一次的字串：
 * 收到等於自己剛送出的就直接丟掉（{@link #applied} / {@link #pushed}）。
 */
final class ClipboardSync {

    private static final String TAG = "OmniADB-Client";

    /** 寫進剪貼簿時標記的來源，方便其他 App（或 debug）看出這筆是哪來的。 */
    private static final String LABEL = "OmniADB";

    /**
     * 「本機這筆是使用者剛剛才複製的」的門檻。
     *
     * <p>這個數字決定的其實是<b>「複製完到連上線」可以拖多久</b>：超過就當成殘留，
     * 讓對方的內容蓋回本機。
     *
     * <p>原本給 3 分鐘，但實測「複製 → 開 App → 掃描 → 對方按允許 → 連上」在
     * 需要重新授權時很容易拖過 3 分鐘（授權框只活 30 秒，錯過就要重來），
     * 那時使用者的複製就會被對方的舊內容吃掉 —— 正是最不能出錯的那一步。
     * 放寬到 10 分鐘仍然擋得住「昨天複製的一段文字」。
     */
    private static final long FRESH_MS = 10 * 60 * 1000;

    /**
     * 連線後等對方第一筆剪貼簿的上限。
     *
     * <p>對方剪貼簿是空的時候，{@code GET_CLIPBOARD} 什麼都不會回（不是錯誤），
     * 所以不能無限期等。
     */
    private static final long FIRST_WAIT_MS = 1_200;

    /**
     * 送出本機內容之後，這段時間內收到的對方內容一律當成「送之前的舊資料」丟掉。
     *
     * <p>為什麼需要：我們請對方讀剪貼簿的請求，可能在我們送出新內容<b>之前</b>就已經在路上了。
     * 實測（POCO / Android 15）送完 12 毫秒後就收到這筆舊資料，會把本機剛複製的內容蓋掉 ——
     * 而它其實是對方<i>被我們蓋掉之前</i>的東西。對方那邊已經是我們的新內容，這裡絕不能套用。
     */
    private static final long PUSH_SETTLE_MS = 900;

    private final ClipboardManager clipboard;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /**
     * 推內容給對方會阻塞（等 ACK），一律排在這條執行緒 ——
     * 剪貼簿的變更回呼是在主執行緒，直接寫 socket 會踩
     * {@code NetworkOnMainThreadException}。
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "omniadb-clipboard-sync");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean active = new AtomicBoolean();

    /** 對方第一筆剪貼簿到了沒。每次 {@link #attach} 換一個新的。 */
    private volatile CountDownLatch firstHost = new CountDownLatch(1);

    private volatile ScrcpySession session;

    /** 這條連線談好了沒（談好之前不把本機的變動往外送，免得跟談判打架）。 */
    private volatile boolean live;

    /** 正在把本機內容推給對方；這段時間對方回報的是「蓋掉之前」的舊內容，不能套用。 */
    private volatile boolean pushing;

    /** 最後一次由<b>對方</b>寫進本機剪貼簿的內容（擋回音用）。 */
    private volatile String applied;

    /** 最後一次送給對方的內容（擋回音用）。 */
    private volatile String pushed;

    /** 這個時間點之前收到的對方內容都不算數（見 {@link #PUSH_SETTLE_MS}）。 */
    private volatile long ignoreHostUntil;

    private final ClipboardManager.OnPrimaryClipChangedListener clientListener =
            this::onClientClipboardChanged;

    ClipboardSync(Context context) {
        this.clipboard = (ClipboardManager) context.getApplicationContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
    }

    // ================================================================ 連線

    /**
     * 連線就緒時呼叫。<b>會阻塞</b>（最多約 1.5 秒，等對方 ACK），
     * 必須在背景執行緒呼叫。
     */
    void attach(ScrcpySession s) {
        detach();
        if (clipboard == null) return;      // 這台沒有剪貼簿服務，直接放棄同步

        session = s;
        live = false;
        pushing = false;
        applied = null;
        pushed = null;
        ignoreHostUntil = 0;
        firstHost = new CountDownLatch(1);
        active.set(true);

        clipboard.addPrimaryClipChangedListener(clientListener);
        // 請對方立刻回報一次 —— 這是「連線前就複製好」的內容唯一拿得到的地方
        s.startClipboardSync();
        negotiate(s);
    }

    /** 連線結束時呼叫（主執行緒，很快）。 */
    void detach() {
        if (!active.getAndSet(false)) return;
        live = false;
        session = null;
        applied = null;
        pushed = null;
        if (clipboard != null) {
            try {
                clipboard.removePrimaryClipChangedListener(clientListener);
            } catch (Throwable ignored) {
                // 移除失敗沒救，下次 attach 會再加一個（重複回呼由 active 擋掉）
            }
        }
    }

    /**
     * 連線當下決定「誰蓋誰」（背景執行緒）。
     *
     * <p>本機剛複製過 → 直接推；否則先等對方那一筆，等不到（對方是空的）才推本機的。
     */
    private void negotiate(ScrcpySession s) {
        String mine = readClientClipboard();
        boolean hasMine = mine != null && !mine.isEmpty();
        long age = clipAgeMs();
        // 本機這筆如果是「上一輪同步從對方抄過來的」，時間戳一定是新的，
        // 但它代表的是<b>對方舊的</b>內容。若照「新就推」處理，重新連線時
        // 就會把對方在這段時間內新複製的東西蓋掉。所以標記是我們寫的一律不算新。
        boolean syncedByUs = isOurs(clipLabel());
        // age < 0 ＝ 這台拿不到複製時間（少數 ROM／服務實作）。判斷不了新舊時，
        // 寧可相信「使用者自己複製的東西」—— 主動連上對方，通常就是為了把它貼過去。
        boolean fresh = hasMine && !syncedByUs && (age < 0 || age < FRESH_MS);

        if (hasMine && syncedByUs) {
            DiagLog.i(TAG, "剪貼簿：本機這筆是上次同步從對方抄來的 → 不往回推，等對方的");
        }
        if (fresh) {
            DiagLog.i(TAG, "剪貼簿：本機有使用者複製的內容（"
                    + (age >= 0 ? (age / 1000) + " 秒前" : "時間未知") + "）→ 推給對方");
            pushToHost(s, mine);
        } else {
            boolean got = awaitFirstHost();
            if (!active.get()) return;
            if (got) {
                DiagLog.i(TAG, "剪貼簿：對方有內容 → 套用到本機");
            } else if (hasMine) {
                DiagLog.i(TAG, "剪貼簿：對方沒有內容 → 改推本機的過去");
                pushToHost(s, mine);
            }
        }
        live = true;
    }

    private boolean awaitFirstHost() {
        try {
            return firstHost.await(FIRST_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void pushToHost(ScrcpySession s, String text) {
        pushing = true;
        pushed = text;
        try {
            if (!s.sendClipboard(text)) {
                DiagLog.w(TAG, "剪貼簿：對方沒有確認收到（可能已斷線）");
            }
        } finally {
            pushing = false;
            ignoreHostUntil = android.os.SystemClock.uptimeMillis() + PUSH_SETTLE_MS;
        }
    }

    // ================================================================ 對方 → 本機

    /**
     * 對方的剪貼簿有<b>新</b>內容（由 {@link ScrcpySession} 去重後回報）。
     *
     * <p>在控制通道的讀取執行緒上被呼叫，這裡自己轉到主執行緒再碰剪貼簿。
     */
    void onHostText(String text) {
        if (text == null) return;
        String trimmed = text;
        ui.post(() -> applyIfUseful(trimmed));
    }

    private void applyIfUseful(String text) {
        if (!active.get()) return;
        firstHost.countDown();
        if (pushing) return;                            // 我們正在蓋過去，這是蓋之前的舊內容
        if (text.isEmpty()) return;                      // 對方清空剪貼簿不用跟著清
        if (text.equals(pushed) || text.equals(applied)) return;   // 我們自己造成的，丟掉
        if (SystemClock.uptimeMillis() < ignoreHostUntil) {
            // 送出去之後的一小段時間內，對方回報的都還是「被我們蓋掉之前」的內容
            DiagLog.i(TAG, "剪貼簿：對方回報的是送出前的舊內容（" + text.length() + " 字）→ 忽略");
            return;
        }

        applied = text;
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, text));
            DiagLog.i(TAG, "剪貼簿：對方 → 本機（" + text.length() + " 字）");
        } catch (Throwable e) {
            // 少數機型在「App 沒有焦點」時會拒絕寫入，不該讓它拖垮鏡像
            DiagLog.w(TAG, "剪貼簿：寫不進本機剪貼簿", e);
        }
    }

    // ================================================================ 本機 → 對方

    /** 本機剪貼簿變了（主執行緒）。 */
    private void onClientClipboardChanged() {
        if (!active.get()) return;
        String text = readClientClipboard();
        if (text == null || text.isEmpty()) return;

        if (text.equals(applied)) {
            // 這次是我們剛才把對方的內容寫進來的，不要再送回對方。
            // 這裡<b>不能</b>把 applied 清掉：有些機型（實測 Samsung A13）寫一次剪貼簿
            // 會連續回呼兩次，清掉之後第二次就會被當成使用者的新複製而回送對方。
            return;
        }
        if (!live) return;                  // 還在談判，別插隊
        if (text.equals(pushed)) return;    // 同一筆已經送過

        ScrcpySession s = session;
        if (s == null) return;

        pushed = text;
        DiagLog.i(TAG, "剪貼簿：本機 → 對方（" + text.length() + " 字）");
        worker.execute(() -> {
            if (!active.get()) return;
            s.sendClipboard(text);
            // 送完之後，對方在路上的舊資料也不能套用（見 PUSH_SETTLE_MS）
            ignoreHostUntil = SystemClock.uptimeMillis() + PUSH_SETTLE_MS;
        });
    }

    // ================================================================ 工具

    private String readClientClipboard() {
        try {
            if (!clipboard.hasPrimaryClip()) return null;
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence cs = clip.getItemAt(0).getText();
            return cs == null ? null : cs.toString();
        } catch (Throwable e) {
            // Android 10+ 背景讀剪貼簿會被拒；鏡像是前景，正常不該走到這裡
            return null;
        }
    }

    /** 本機剪貼簿是多久以前複製的（毫秒）。判斷不出來回 -1。 */
    private long clipAgeMs() {
        try {
            ClipDescription d = clipboard.getPrimaryClipDescription();
            if (d == null) return -1;
            long t = d.getTimestamp();
            if (t <= 0) return -1;
            return Math.max(0, System.currentTimeMillis() - t);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 本機剪貼簿的標籤（就是複製它的 App 名字）。拿不到回 null。 */
    private CharSequence clipLabel() {
        try {
            ClipDescription d = clipboard.getPrimaryClipDescription();
            return d == null ? null : d.getLabel();
        } catch (Throwable e) {
            return null;
        }
    }

    /** 這筆是不是同步機制自己寫進去的（見 {@link #LABEL}）。 */
    private static boolean isOurs(CharSequence label) {
        return label != null && LABEL.contentEquals(label);
    }
}
