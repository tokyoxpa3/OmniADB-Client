package com.omniadb.client;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 把 scrcpy 的 H.264 串流解到一個 {@link Surface} 上。
 *
 * <p>四個設計決定：
 *
 * <ol>
 *   <li><b>用 Surface 模式而不是拿 ByteBuffer 自己畫。</b>MediaCodec 直接輸出到
 *       SurfaceView 的 Surface，解碼後的幀由 compositor 顯示，完全不經過 CPU ——
 *       這是 60fps 鏡像能成立的前提。自己畫則要多一次 YUV→RGB 轉換，
 *       在手機上會直接吃掉兩三個核心。</li>
 *   <li><b>csd 從 config 包拿，不用 codec meta。</b>codec meta 只有開頭那份，
 *       旋轉之後就過期了；config 包則是每次編碼器重啟都會送。</li>
 *   <li><b>遇到新尺寸就重建解碼器。</b>MediaCodec 對「同一條串流中途換解析度」
 *       的支援各家實作不一致（有的直接丟錯、有的繼續吐舊尺寸的幀），
 *       重建雖然多花 ~100ms，但行為確定。旋轉是低頻事件，值得。</li>
 *   <li><b>Surface 可以被抽換。</b>本機螢幕一關、通知欄一拉、切到別的 App，
 *       SurfaceView 的 Surface 就會被系統銷毀。解碼器如果還握著那個死掉的
 *       Surface，MediaCodec 會直接報 {@code NO_INIT} 並永久失效
 *       （實測 logcat：{@code BufferQueue has been abandoned} →
 *       {@code Codec reported err 0xffffffed/NO_INIT}）。
 *       所以 Surface 的掛上／抽掉都當成<b>指令</b>排進同一條佇列，
 *       由解碼執行緒單獨處理，避免兩個執行緒同時動 MediaCodec。</li>
 * </ol>
 */
final class VideoDecoder {

    private static final String TAG = "OmniADB-Client";
    private static final String MIME = "video/avc";

    /**
     * 待解封包佇列長度。
     *
     * <p>刻意做小：這是<b>背壓</b>的來源。解碼跟不上時讀取執行緒會停在
     * {@link #submit}，資料積在 ADB socket 的接收緩衝裡，再滿就讓 adbd 那端阻塞
     * —— 整條鏈路自然降速，而不是把幾百 MB 的幀堆進我們的記憶體裡然後 OOM。
     */
    private static final int QUEUE_CAPACITY = 12;

    interface Callback {
        /** 畫面尺寸變了（含第一次拿到尺寸）。呼叫在解碼執行緒上，要自己 post 到 UI。 */
        void onVideoSize(int width, int height);

        /** 解碼器掛了（重建失敗等）。 */
        void onDecoderError(String message);
    }

    /** 佇列裡的一個項目：影像封包，或「換 Surface」指令。 */
    static final class Packet {
        final byte[] data;
        final long ptsUs;
        final boolean config;
        /** 非 null 表示這是一個「掛上這個 Surface」的指令。 */
        final Surface attachSurface;
        /** true 表示「Surface 沒了，放掉解碼器」。 */
        final boolean detach;

        private Packet(byte[] data, long ptsUs, boolean config, Surface attachSurface, boolean detach) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.config = config;
            this.attachSurface = attachSurface;
            this.detach = detach;
        }

        static Packet frame(byte[] data, long ptsUs, boolean config) {
            return new Packet(data, ptsUs, config, null, false);
        }

        static Packet attach(Surface surface) {
            return new Packet(null, 0, false, surface, false);
        }

        static Packet detach() {
            return new Packet(null, 0, false, null, true);
        }
    }

    private final Callback callback;
    private final LinkedBlockingQueue<Packet> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private volatile Surface surface;

    private MediaCodec codec;
    /** 目前解碼器是為哪個尺寸建的。0 表示還沒建。 */
    private int codecWidth;
    private int codecHeight;
    /** 最近一次 config 包給的 csd。Surface 換掉之後要靠它重建解碼器。 */
    private byte[] sps;
    private byte[] pps;

    /**
     * 等「新尺寸的第一幀」真的上螢幕之後才通知 View 改長寬比。
     *
     * <p>0 表示沒有待通知的尺寸。見 {@link #handleConfig} 的說明。
     */
    private int pendingNotifyWidth;
    private int pendingNotifyHeight;

    private Thread thread;
    private volatile boolean running = true;
    private int width;
    private int height;

    VideoDecoder(Surface surface, Callback callback) {
        this.surface = surface;
        this.callback = callback;
    }

    void start() {
        thread = new Thread(this::loop, "omniadb-video-decoder");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 送入一個封包，必要時阻塞。
     *
     * <p>由讀取執行緒呼叫。佇列滿了就等 —— 見 {@link #QUEUE_CAPACITY}。
     */
    void submit(Packet p) {
        while (running) {
            try {
                if (queue.offer(p, 50, TimeUnit.MILLISECONDS)) return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 換一個 Surface（Activity 回到前景、螢幕重新亮起）。 */
    void attachSurface(Surface s) {
        // 同一個 Surface 且解碼器還活著 → 不用動，否則會白白重建一次（畫面會閃）
        if (s == surface && codec != null) return;
        surface = s;
        submit(Packet.attach(s));
    }

    /** Surface 被系統銷毀（切到背景、螢幕熄滅）。解碼器必須放掉它。 */
    void detachSurface() {
        surface = null;
        submit(Packet.detach());
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ 執行緒

    private void loop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            Packet p = null;
            try {
                p = queue.poll(5, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (p != null) {
                if (p.detach) {
                    releaseCodec();
                } else if (p.attachSurface != null) {
                    // 換 Surface 時先試「只改輸出目標」：setOutputSurface() 不用停機、
                    // 不用重建，畫面完全不中斷。轉向、下拉狀態列都會走到這裡，
                    // 用舊做法（放掉再重建）每次都要黑掉約 100ms。
                    if (!retarget(p.attachSurface)) {
                        releaseCodec();      // 舊的解碼器綁著死掉的 Surface，直接丟
                        ensureCodec();
                    }
                } else if (p.config) {
                    handleConfig(p.data);
                } else {
                    feed(p);
                }
            }
            drain(info);
        }
        releaseCodec();
    }

    /**
     * 處理 config 包：拆出 SPS/PPS、必要時（重）建解碼器。
     *
     * <p>config 包<b>不會</b>再丟給解碼器 —— 它的內容已經當 csd 設進去了，
     * 重複送進去只是浪費一次 input buffer。
     */
    private void handleConfig(byte[] payload) {
        H264.Csd csd = H264.splitCsd(payload);
        if (!csd.isValid()) {
            DiagLog.w(TAG, "config 包裡找不到 SPS/PPS，略過");
            return;
        }
        sps = csd.sps;
        pps = csd.pps;

        int[] size = H264.spsSize(csd.sps);
        boolean sizeChanged = false;
        if (size == null) {
            DiagLog.w(TAG, "SPS 解析失敗，沿用舊尺寸 " + width + "x" + height);
        } else if (size[0] != width || size[1] != height) {
            width = size[0];
            height = size[1];
            sizeChanged = true;
            DiagLog.i(TAG, "畫面尺寸 " + width + "x" + height);
        }

        // 尺寸變了就重建：舊解碼器的輸出格式跟新的對不上
        boolean rebuild = codec != null && (codecWidth != width || codecHeight != height);
        if (rebuild) {
            DiagLog.i(TAG, "尺寸變更，重建解碼器（原本 " + codecWidth + "x" + codecHeight + "）");
            releaseCodec();
            // ★ 這裡刻意**不**通知 View 改長寬比。
            // Surface 上現在還留著「舊尺寸的最後一幀」，一旦把 View 改成新比例，
            // compositor 會把那一幀硬拉進新比例裡 —— 翻屏時看到畫面被扯一下就是這個。
            // 改成等新解碼器真的吐出第一幀再通知（見 drain）。
            pendingNotifyWidth = width;
            pendingNotifyHeight = height;
        } else if (sizeChanged && callback != null) {
            callback.onVideoSize(width, height);
        }
        ensureCodec();
    }

    /**
     * 把現有解碼器的輸出目標換成新的 Surface，不重建。
     *
     * <p>{@code MediaCodec.setOutputSurface()} 是 API 23 才有的，而且要求解碼器
     * 處於執行中狀態。成功的話畫面完全不中斷 —— 這是轉向時「不閃一下」的關鍵。
     *
     * @return false ＝ 換不了，呼叫端要走「放掉再重建」那條路
     */
    private boolean retarget(Surface s) {
        MediaCodec c = codec;
        if (c == null || s == null || !s.isValid()) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        try {
            c.setOutputSurface(s);
            DiagLog.i(TAG, "解碼器輸出目標已切換（沒有重建）");
            return true;
        } catch (Exception e) {
            DiagLog.w(TAG, "切換輸出 Surface 失敗，改為重建：" + e.getMessage());
            return false;
        }
    }

    /** 有 Surface 也有 csd 才建得起來；缺一個就等下一次。 */
    private void ensureCodec() {
        if (!running || codec != null) return;
        Surface s = surface;
        if (s == null || !s.isValid() || sps == null || pps == null) return;
        if (width <= 0 || height <= 0) return;

        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));

            codec = MediaCodec.createDecoderByType(MIME);
            codec.configure(format, s, null, 0);
            codec.start();
            codecWidth = width;
            codecHeight = height;
            DiagLog.i(TAG, "解碼器已啟動 " + width + "x" + height);
        } catch (Exception e) {
            DiagLog.e(TAG, "解碼器建立失敗", e);
            releaseCodec();
            if (callback != null) {
                callback.onDecoderError(e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
    }

    private void feed(Packet p) {
        MediaCodec c = codec;
        if (c == null) return;   // 還沒收到 csd 或沒有 Surface，丟掉（開頭幾幀本來就沒有畫面）

        // 正常情況一次就拿到；拿不到代表解碼器塞住，等一下再試。
        // 直接丟掉這一幀會讓畫面從這裡開始花屏，直到下一個 I 幀才恢復。
        int idx = -1;
        long deadline = System.currentTimeMillis() + 500;
        while (running && idx < 0 && System.currentTimeMillis() < deadline) {
            try {
                idx = c.dequeueInputBuffer(10_000);
            } catch (IllegalStateException e) {
                // 解碼器已經失效（多半是 Surface 被收走）。放掉它，
                // 等 Surface 重新掛上時 ensureCodec() 會重建。
                DiagLog.w(TAG, "解碼器失效，等待重新掛接：" + e.getMessage());
                releaseCodec();
                return;
            }
        }
        if (idx < 0) {
            DiagLog.w(TAG, "輸入緩衝一直拿不到，丟棄 " + p.data.length + " bytes");
            return;
        }
        try {
            ByteBuffer buf = c.getInputBuffer(idx);
            if (buf == null) return;
            buf.clear();
            buf.put(p.data);
            c.queueInputBuffer(idx, 0, p.data.length, p.ptsUs, 0);
        } catch (IllegalStateException e) {
            DiagLog.w(TAG, "queueInputBuffer 失敗：" + e.getMessage());
            releaseCodec();
        }
    }

    private void drain(MediaCodec.BufferInfo info) {
        MediaCodec c = codec;
        if (c == null) return;
        while (true) {
            int idx;
            try {
                idx = c.dequeueOutputBuffer(info, 0);
            } catch (IllegalStateException e) {
                releaseCodec();
                return;
            }
            if (idx >= 0) {
                // 新尺寸的第一幀要上螢幕了：先讓 View 把長寬比改過來，
                // 這樣改比例的動作跟「新畫面出現」是同一個瞬間，
                // 不會出現「舊幀被拉進新比例」的怪畫面。
                if (pendingNotifyWidth > 0) {
                    int w = pendingNotifyWidth;
                    int h = pendingNotifyHeight;
                    pendingNotifyWidth = 0;
                    pendingNotifyHeight = 0;
                    if (callback != null) callback.onVideoSize(w, h);
                }
                // true = 把這一幀丟給 Surface 顯示
                try {
                    c.releaseOutputBuffer(idx, true);
                } catch (IllegalStateException e) {
                    releaseCodec();
                    return;
                }
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                DiagLog.i(TAG, "解碼器輸出格式：" + c.getOutputFormat());
            } else {
                // INFO_TRY_AGAIN_LATER / INFO_OUTPUT_BUFFERS_CHANGED
                return;
            }
        }
    }

    private void releaseCodec() {
        MediaCodec c = codec;
        codec = null;
        codecWidth = 0;
        codecHeight = 0;
        // 待通知的尺寸跟著作廢：沒有新解碼器就不會有「新尺寸的第一幀」，
        // 留著只會在下次重建時誤觸發一次比例變更。
        pendingNotifyWidth = 0;
        pendingNotifyHeight = 0;
        if (c == null) return;
        try {
            c.stop();
        } catch (Exception ignored) {
            // 已經停了
        }
        try {
            c.release();
        } catch (Exception ignored) {
            // 沒有補救手段
        }
    }
}
