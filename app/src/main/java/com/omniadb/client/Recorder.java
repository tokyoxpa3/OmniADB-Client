package com.omniadb.client;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 把鏡像的 H.264 碼流<b>原樣</b>封裝成 MP4，存到本機相簿（{@code Movies/OmniADB/}）。
 *
 * <p><b>為什麼不是「錄本機畫面」</b>（MediaProjection + MediaRecorder）：
 * <ol>
 *   <li>每次錄影都要彈一次系統授權框，而且授權不跨行程 —— 螢幕一關就得重來。</li>
 *   <li>錄到的是「我這邊看到什麼」：帶著我們的工具列、黑邊、本機縮放後的解析度。
 *       使用者要的是<b>對方那塊螢幕</b>，跟截圖同一個語意。</li>
 *   <li>MediaProjection 是<b>重新編碼</b>一次，1080p60 對手機 SoC 是實打實的負擔，
 *       而且我們本來就已經有一條編碼好的碼流躺在記憶體裡。</li>
 * </ol>
 * 所以這裡只做封裝：拿到的每個封包（annex-B 的 H.264，帶 scrcpy 給的 pts）
 * 直接丟給 {@link MediaMuxer}。不解碼、不重編碼，CPU 幾乎零，畫質與檔案大小
 * 跟串流本身完全一致（6 Mbps 的檔就是 6 Mbps）。
 *
 * <p><b>為什麼只有影像、沒有聲音</b>：音訊走的是另一條 session，格式是<b>裸 PCM</b>
 * （見 {@link ScrcpyServer#AUDIO_CODEC}），要進 MP4 得先經過 MediaCodec 編成 AAC，
 * 還要把兩條流的時間戳對齊 —— 那是另一件事。scrcpy 自己的 {@code --record}
 * 預設也是只錄影像。
 *
 * <p><b>兩個非做不可的細節</b>：
 * <ul>
 *   <li><b>第一幀必須是關鍵幀。</b>MP4 的第一個樣本若不是 IDR，播放器開頭就是
 *       綠屏／花屏，而且有些播放器直接判定檔案損壞。scrcpy 的編碼器 GOP 是 10 秒，
 *       按下錄影的當下很可能剛好在中間 —— 所以 {@code MirrorActivity} 會先送
 *       {@code TYPE_RESET_VIDEO} 請對方立刻吐一個 IDR（見
 *       {@link ScrcpySession#requestVideoReset()}）。這裡則是「沒等到關鍵幀之前
 *       一律丟掉」，兩邊各守一道。</li>
 *   <li><b>尺寸變了就換檔。</b>{@link MediaMuxer} 的 track 尺寸在 {@code addTrack}
 *       之後就固定了，對方一旋轉（例如 574×1280 → 1280×574）就塞不進去。
 *       所以偵測到新的尺寸就收掉這一段、用同樣的檔名加序號開下一段。
 *       一段錄影中途翻手機 ⇒ 兩個檔，這是 MP4 的體質問題，不是我們的偷懶。</li>
 * </ul>
 *
 * <p><b>執行緒</b>：{@link #onPacket} 在影像讀取執行緒上被呼叫，<b>只做入列</b>；
 * 真正的封裝與寫檔在一條專屬執行緒上。寫檔（尤其慢速 SD 卡）若卡住，
 * 影像串流不會跟著停 —— 佇列滿了就丟新封包並記一筆，寧可掉幀也不要凍住鏡像。
 */
final class Recorder implements Closeable, AudioSession.PcmTap {

    private static final String TAG = "OmniADB-Client";

    /**
     * 佇列長度。60fps 下約等於 4 秒。
     *
     * <p>不能開太大：這是「碼流」，一個關鍵幀可能有 100 KB 以上，開到幾千筆就是幾十 MB。
     * 也不能太小：手機偶爾會有一次幾百毫秒的寫入抖動（相簿掃描、GC），
     * 一兩百筆的緩衝足以吸收，不會讓畫面卡一下。
     */
    private static final int QUEUE_CAP = 240;

    /** 停止用的哨兵。排在佇列尾端，前面已入列的封包會先寫完才收檔。 */
    private static final Pkt EOS = new Pkt(null, 0, false, false, 0, 0, true, false);

    /**
     * 兩幀間隔超過這個值就當成「畫面靜止」，用上一幀補滿（見 {@link #fill}）。
     *
     * <p>400ms ＝ 低於 2.5 fps 就開始補。正常動畫／滑動是 30~60 fps，不會誤觸。
     */
    private static final long FILL_AFTER_US = 400_000;

    /** 補幀的間隔：200ms（5 fps）。夠順、檔案也不會因為補幀而暴增。 */
    private static final long FILL_STEP_US = 200_000;

    /** 一段空檔最多補這麼多張。超過就把間隔拉大，時間軸照樣對得上。 */
    private static final int MAX_FILL_FRAMES = 600;

    // ---------------------------------------------------------------- 音訊

    /** 對方送來的就是這個格式（見 {@link ScrcpyServer#AUDIO_CODEC}：裸 PCM 48k 立體聲）。 */
    private static final int AUDIO_SAMPLE_RATE = 48_000;
    private static final int AUDIO_CHANNELS = 2;

    /** AAC 位元率。128 kbps 對「螢幕錄影的人聲／音樂」夠了，再高只是讓檔案變大。 */
    private static final int AUDIO_BIT_RATE = 128_000;

    /**
     * 逼出 AudioSpecificConfig 用的靜音長度：1024 個取樣 × 2 聲道 × 16 bit ＝ 4096 bytes。
     *
     * <p>MP4 的音軌一定要有 csd（AAC 的 AudioSpecificConfig），而它只有編碼器<b>真的收到
     * 資料</b>之後才會吐出來。開檔時先餵這一段靜音把它逼出來，才能 {@code addTrack}。
     */
    private static final int AUDIO_PRIME_BYTES = 4096;

    interface Callback {
        /**
         * 錄影結束了（使用者停止、連線斷了、或出錯）。
         *
         * @param where   存到哪裡（給使用者看的字串）；完全沒錄到東西時是 {@code null}
         * @param message {@code null} ＝ 正常結束；否則是要顯示的錯誤
         */
        void onStopped(String where, String message);
    }

    private final Context context;
    private final Callback callback;

    private final ArrayBlockingQueue<Pkt> queue = new ArrayBlockingQueue<>(QUEUE_CAP);

    /** 使用者現在是不是要錄影。{@code false} 之後佇列裡的殘料仍會寫完才收檔。 */
    private volatile boolean wanted;

    /** 已經喊過停。擋住重複的停止請求（第二次會對著收掉的 muxer 動手）。 */
    private boolean stopRequested;

    /** 這一輪的檔名主體（不含 {@code _2} 這種分段序號）。 */
    private String runName;

    private final List<String> saved = new ArrayList<>();

    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    private final Thread writer;

    /** 佇列滿了丟掉幾筆（只在第一次丟時記一筆 log，免得刷爆）。 */
    private boolean droppedOnce;

    /**
     * 現在有沒有音訊可以錄。
     *
     * <p>由 {@link ScrcpySession} 在音訊 session 起來／收掉時設定。false 就只錄影像
     * （跟 scrcpy 自己的 {@code --record} 預設行為一樣），不會留一條空音軌。
     */
    private volatile boolean audioOn;

    private volatile boolean closed;

    Recorder(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.writer = new Thread(this::writerLoop, "omniadb-record");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    // ------------------------------------------------------------ 對外

    boolean isRecording() {
        return wanted;
    }

    /**
     * 開始錄。真正開檔要等對方下一個 config 包（也就是下一個關鍵幀）。
     *
     * @return {@code false} ＝ 沒有開始（已經在錄、已經關掉、或上一段還在收檔）
     */
    boolean start() {
        if (wanted || closed) return false;
        // 上一段還在收檔（stop() 之後佇列裡還有東西要寫完）：
        // 這時清佇列會把 EOS 一起清掉，那一段就永遠收不了檔。
        if (stopRequested) return false;
        synchronized (saved) {
            saved.clear();
        }
        droppedOnce = false;
        runName = "OmniADB_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        // 上一輪萬一還有殘料（例如剛停就又按錄），先清掉，免得混進新檔
        queue.clear();
        wanted = true;
        DiagLog.i(TAG, "錄影開始：" + runName);
        return true;
    }

    /**
     * 停止錄影。<b>不會阻塞</b>：收檔（muxer trailer、相簿 IS_PENDING）在寫入執行緒上做，
     * 完成後才回呼 {@link Callback#onStopped}。
     */
    void stop() {
        // 只認第一次：連按兩次停止不該收兩次檔（第二次會對著已經收掉的 muxer 動手）
        if (!wanted || stopRequested) return;
        stopRequested = true;
        wanted = false;
        if (!queue.offer(EOS)) {
            // 佇列滿了：丟掉最後約 4 秒的畫面也要把 EOS 塞進去，否則收不了檔
            DiagLog.w(TAG, "錄影佇列已滿，停止時丟棄尾段");
            queue.clear();
            queue.offer(EOS);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        boolean active = wanted || stopRequested;
        stop();
        closed = true;
        // 只有「真的有東西要收」才等。沒在錄影時也 join 的話，離開鏡像會白卡 1.5 秒。
        if (active) {
            try {
                writer.join(1_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (writer.isAlive()) writer.interrupt();
    }

    // ------------------------------------------------------------ 影像端

    /**
     * 有沒有音訊可以錄（由 {@link ScrcpySession} 依音訊 session 的狀態呼叫）。
     *
     * <p>只影響<b>之後開的</b>那一段：已經在錄的檔不會突然多出／少掉一條音軌
     * （MediaMuxer 的 track 在 start() 之後就固定了）。
     */
    void setAudioOn(boolean on) {
        audioOn = on;
    }

    /**
     * 音訊旁路（在 {@link AudioSession} 的讀取執行緒上被呼叫）。
     *
     * <p>只做一件事：<b>複製</b>一份丟進佇列。<b>一定要複製</b> —— 呼叫端那塊
     * {@code buf} 是重複使用的，直接排隊進去，等寫入執行緒拿到時內容早就被下一包蓋掉了
     * （症狀是錄出來的聲音像快轉的雜訊）。
     */
    @Override
    public void onPcm(byte[] data, int len, long ptsUs) {
        if (!wanted || closed || !audioOn) return;
        if (len <= 0) return;
        byte[] copy = new byte[len];
        System.arraycopy(data, 0, copy, 0, len);
        if (!queue.offer(Pkt.audio(copy, ptsUs))) {
            if (!droppedOnce) {
                droppedOnce = true;
                DiagLog.w(TAG, "錄影跟不上（佇列已滿），開始丟棄封包");
            }
        }
    }

    /**
     * 影像讀取執行緒每收到一個封包就叫一次。<b>只入列，不碰磁碟。</b>
     *
     * @param data    annex-B 的 H.264 資料（可能含多個 NAL）
     * @param pts     微秒；scrcpy 的編碼器時間戳（<b>對方裝置的開機時間</b>，跟音訊同一套時鐘）
     * @param config  是不是 codec config 包（SPS/PPS，不是畫面）
     * @param key     是不是關鍵幀（IDR）
     * @param width   當下的影像寬（來自 session meta，權威來源）
     * @param height  當下的影像高
     */
    void onPacket(byte[] data, long pts, boolean config, boolean key, int width, int height) {
        if (!wanted || closed) return;
        if (width <= 0 || height <= 0) return;
        if (!queue.offer(new Pkt(data, pts, config, key, width, height, false, false))) {
            if (!droppedOnce) {
                droppedOnce = true;
                DiagLog.w(TAG, "錄影跟不上（佇列已滿），開始丟棄封包");
            }
        }
    }

    // ------------------------------------------------------------ 封裝

    private void writerLoop() {
        Segment seg = null;
        while (true) {
            Pkt p;
            try {
                p = queue.take();
            } catch (InterruptedException e) {
                // close() 打斷：把手上那段收乾淨再走（不用回呼，Activity 已經在收了）
                finish(seg, null);
                return;
            }
            try {
                if (p.eos) {
                    String where = finish(seg, null);
                    seg = null;
                    stopRequested = false;
                    notifyStopped(where, null);
                    continue;
                }
                if (p.audio) {
                    // 音訊封包可能比影像早到（錄影剛開始時）：seg 還沒有就把這幾包丟掉，
                    // 反正影片開頭 0.1 秒的空白沒人聽得出來
                    if (seg != null) writeAudio(seg, p);
                    continue;
                }
                if (p.config) {
                    // 新的 config：尺寸變了就是換檔（旋轉），沒變（例如我們自己請對方
                    // 重吐關鍵幀）就沿用同一個檔，不用重開
                    if (seg != null && (p.width != seg.width || p.height != seg.height)) {
                        DiagLog.i(TAG, "錄影：尺寸 " + seg.width + "x" + seg.height
                                + " → " + p.width + "x" + p.height + "，換下一段");
                        finish(seg, null);
                        seg = null;
                    }
                    if (seg == null && wanted) seg = openSegment(p);
                    continue;
                }
                if (seg == null) continue;      // 還沒拿到 csd，畫面包丟掉
                if (!seg.started) {
                    // 關鍵幀之前一律不寫：MP4 從 P 幀開始＝播放器開頭花屏
                    if (!p.key) continue;
                    seg.started = true;
                    seg.originPts = p.pts;
                    DiagLog.i(TAG, "錄影：收到關鍵幀，開始寫入 " + seg.where);
                }
                write(seg, p);
            } catch (Exception e) {
                DiagLog.e(TAG, "錄影寫入失敗", e);
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                String where = finish(seg, msg);
                seg = null;
                wanted = false;
                notifyStopped(where, msg);
            }
        }
    }

    private void write(Segment seg, Pkt p) throws IOException {
        long pts = p.pts - seg.originPts;
        // 時間戳必須嚴格遞增。scrcpy 送的是編碼器時間戳，正常單調，
        // 但換段／重置後可能撞在同一微秒 —— 撞了就往前推 1us，
        // 不值得為這種事讓 MediaMuxer 丟例外。
        if (pts <= seg.lastPts) pts = seg.lastPts + 1;

        if (seg.lastPts >= 0) fill(seg, pts);

        seg.lastPts = pts;
        seg.lastWallMs = SystemClock.uptimeMillis();
        info.set(0, p.data.length, pts, p.key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        seg.muxer.writeSampleData(seg.track, ByteBuffer.wrap(p.data), info);
        seg.lastSample = p.data;
        seg.samples++;
    }

    /**
     * 用<b>上一幀</b>補滿 {@code targetPts} 之前的空檔。
     *
     * <p><b>為什麼非補不可（實測 2026-10-27）：</b>scrcpy 的螢幕編碼器只在畫面
     * <b>有變化</b>時才產出幀。靜止的桌面 10 秒不給任何一張，於是「錄了 11.7 秒」
     * 的檔只有 1.1 秒（stts 裡 11 個樣本就結束）—— 使用者看到的是「錄影壞了，
     * 只錄到一瞬間」。實際上不是漏錄，是<b>沒有幀可以錄</b>。
     *
     * <p>補法是重複上一張壓縮過的畫面（不解碼、不重編碼）：同一筆資料再寫一次、
     * 換個時間戳。解碼器會照樣解出同一張圖，時間軸就對得上真實時間了。
     * 靜態畫面的 P 幀只有幾百 bytes，補 5 fps 一分鐘也才一兩 MB。
     *
     * <p>空檔很長時把間隔放大（最多 600 張），免得錄一個小時的靜止桌面塞進
     * 一萬八千筆重複樣本。
     */
    private void fill(Segment seg, long targetPts) throws IOException {
        long gap = targetPts - seg.lastPts;
        if (gap <= FILL_AFTER_US || seg.lastSample == null) return;
        long step = Math.max(FILL_STEP_US, gap / MAX_FILL_FRAMES);
        int n = (int) ((gap - 1) / step);
        for (int i = 1; i <= n; i++) {
            info.set(0, seg.lastSample.length, seg.lastPts + i * step, 0);
            seg.muxer.writeSampleData(seg.track, ByteBuffer.wrap(seg.lastSample), info);
            seg.samples++;
        }
        seg.lastPts += (long) n * step;
    }

    /**
     * 餵一包 PCM 給 AAC 編碼器，順便把編碼器已經吐出來的 AAC 寫進音軌。
     *
     * <p><b>為什麼要編碼而不是直接寫 PCM：</b>MP4 不認裸 PCM 當「音軌」
     * （就算包得進去，播放器十之八九不支援）。而 AAC 是 Android 每台機器都有的
     * 硬體／軟體編碼器，128 kbps 的體積也只有 PCM 的十二分之一。
     *
     * <p>時間戳直接用 scrcpy 送來的 pts（<b>對方裝置的開機時間</b>）減去影片的時間原點 ——
     * 影像與音訊是同一台機器上兩個伺服器進程，用的都是 {@code System.nanoTime}，
     * 所以<b>兩邊的時間軸天生就對齊</b>，不需要另外做 A/V 同步。
     * 早於影片起點的（負數）直接丟掉，Muxer 不收負時間戳。
     */
    private void writeAudio(Segment seg, Pkt p) throws IOException {
        MediaCodec enc = seg.audio;
        if (enc == null || seg.audioTrack < 0) return;

        long pts = p.pts - seg.originPts;
        if (pts < 0) return;
        if (pts <= seg.lastAudioPts) pts = seg.lastAudioPts + 1;
        seg.lastAudioPts = pts;

        int idx = enc.dequeueInputBuffer(10_000);
        if (idx < 0) {
            // 編碼器來不及（極少見）：丟掉這一包，總比卡住影像好
            return;
        }
        ByteBuffer in = enc.getInputBuffer(idx);
        if (in == null) return;
        in.clear();
        int n = Math.min(in.remaining(), p.data.length);
        in.put(p.data, 0, n);
        enc.queueInputBuffer(idx, 0, n, pts, 0);

        drainAudio(seg);
    }

    /** 把編碼器目前能給的 AAC 全部寫進音軌（不阻塞：timeout 0）。 */
    private void drainAudio(Segment seg) throws IOException {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        while (true) {
            int idx = seg.audio.dequeueOutputBuffer(bi, 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;   // 開檔時已經處理過
            if (idx < 0) continue;                                        // 其他 INFO_*
            try {
                ByteBuffer out = seg.audio.getOutputBuffer(idx);
                if (out != null && bi.size > 0
                        && (bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    out.position(bi.offset);
                    out.limit(bi.offset + bi.size);
                    if (bi.presentationTimeUs <= seg.lastAudioOutPts) {
                        bi.presentationTimeUs = seg.lastAudioOutPts + 1;
                    }
                    seg.lastAudioOutPts = bi.presentationTimeUs;
                    seg.muxer.writeSampleData(seg.audioTrack, out, bi);
                }
            } finally {
                seg.audio.releaseOutputBuffer(idx, false);
            }
        }
    }

    /**
     * 建立並啟動 AAC 編碼器，順便逼出 csd。
     *
     * @return 編碼器；拿不到 csd 或這台沒有 AAC 編碼器時是 {@code null}（退回純影像）
     */
    private MediaCodec openAudioEncoder() {
        MediaCodec enc = null;
        try {
            MediaFormat fmt = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();

            // 餵一段靜音，等 INFO_OUTPUT_FORMAT_CHANGED（裡面才有 csd-0）
            int idx = enc.dequeueInputBuffer(20_000);
            if (idx < 0) throw new IOException("AAC 編碼器沒有輸入緩衝區");
            ByteBuffer in = enc.getInputBuffer(idx);
            if (in == null) throw new IOException("AAC 編碼器輸入緩衝區是 null");
            in.clear();
            int prime = Math.min(in.remaining(), AUDIO_PRIME_BYTES);
            in.put(new byte[prime]);
            enc.queueInputBuffer(idx, 0, prime, 0, 0);

            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long deadline = SystemClock.uptimeMillis() + 500;
            while (SystemClock.uptimeMillis() < deadline) {
                int o = enc.dequeueOutputBuffer(bi, 20);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    DiagLog.i(TAG, "錄影：音軌就緒（AAC " + AUDIO_SAMPLE_RATE + "Hz/"
                            + AUDIO_CHANNELS + "ch " + AUDIO_BIT_RATE / 1000 + "kbps）");
                    return enc;
                }
                if (o >= 0) enc.releaseOutputBuffer(o, false);
            }
            throw new IOException("等不到 AudioSpecificConfig");
        } catch (Exception e) {
            DiagLog.w(TAG, "錄影：開不了 AAC 編碼器，這一段只錄影像", e);
            if (enc != null) {
                try {
                    enc.stop();
                } catch (Exception ignored) {
                    // 已經停了
                }
                enc.release();
            }
            return null;
        }
    }

    /**
     * 建立一段（一個檔）。
     *
     * <p>csd-0/csd-1 直接取自 config 包裡的 SPS/PPS（{@link H264#splitCsd}）——
     * 這跟 MediaCodec 餵給 MediaMuxer 的格式一模一樣（都是帶起始碼的 annex-B），
     * 所以後面的樣本可以原樣寫，Muxer 自己會轉成 MP4 要的長度前綴形式。
     */
    private Segment openSegment(Pkt config) throws IOException {
        H264.Csd csd = H264.splitCsd(config.data);
        if (!csd.isValid()) {
            DiagLog.w(TAG, "錄影：config 包裡找不到 SPS/PPS，跳過");
            return null;
        }

        MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", config.width, config.height);
        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd.sps));
        fmt.setByteBuffer("csd-1", ByteBuffer.wrap(csd.pps));

        Segment seg = new Segment();
        seg.width = config.width;
        seg.height = config.height;

        int seq = 1;
        synchronized (saved) {
            seq = saved.size() + 1;
        }
        String name = seq == 1 ? runName + ".mp4" : runName + "_" + seq + ".mp4";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/OmniADB");
            // IS_PENDING：還沒寫完不要讓相簿看到一個壞檔（跟截圖同一套做法）
            v.put(MediaStore.Video.Media.IS_PENDING, 1);

            ContentResolver cr = context.getContentResolver();
            Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("無法在相簿建立項目");
            ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "rw");
            if (pfd == null) {
                cr.delete(uri, null, null);
                throw new IOException("無法寫入相簿");
            }
            seg.uri = uri;
            seg.pfd = pfd;
            seg.where = "Movies/OmniADB/" + name;
        } else {
            // Android 10 以前沒有 RELATIVE_PATH，寫相簿要儲存權限 —— 為了錄影去要
            // 那個權限不划算，退回 App 專屬目錄（免權限，但不會出現在相簿裡）
            File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "OmniADB");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("無法建立目錄 " + dir);
            File out = new File(dir, name);
            seg.file = out;
            seg.pfd = ParcelFileDescriptor.open(out,
                    ParcelFileDescriptor.MODE_READ_WRITE
                            | ParcelFileDescriptor.MODE_CREATE
                            | ParcelFileDescriptor.MODE_TRUNCATE);
            seg.where = out.getAbsolutePath();
        }

        seg.muxer = new MediaMuxer(seg.pfd.getFileDescriptor(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        seg.track = seg.muxer.addTrack(fmt);

        // 音軌：只有「現在真的有音訊」才加。加了就回不去 —— MediaMuxer 的 track
        // 在 start() 之後固定，錄到一半切靜音也只能讓它從那裡開始沒有聲音。
        if (audioOn) {
            MediaCodec enc = openAudioEncoder();
            if (enc != null) {
                seg.audio = enc;
                seg.audioTrack = seg.muxer.addTrack(enc.getOutputFormat());
            }
        }

        seg.muxer.start();
        DiagLog.i(TAG, "錄影：已開檔 " + seg.where + "（" + seg.width + "x" + seg.height
                + (seg.audioTrack >= 0 ? " +AAC" : "（無音軌）") + "）");
        return seg;
    }

    /**
     * 收掉音訊編碼器。
     *
     * <p>先送 EOS 再排空一次，把編碼器手上那 20~40ms 的緩衝寫完 ——
     * 少了這一步，每次停止錄影都會把最後一個字吃掉。
     */
    private void closeAudioEncoder(Segment seg) {
        if (seg.audio == null) return;
        try {
            int idx = seg.audio.dequeueInputBuffer(5_000);
            if (idx >= 0) {
                long pts = seg.lastAudioPts < 0 ? 0 : seg.lastAudioPts + 21_000;
                seg.audio.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                drainAudio(seg);
            }
        } catch (Exception e) {
            DiagLog.w(TAG, "錄影：收音軌時出錯", e);
        }
        try {
            seg.audio.stop();
        } catch (Exception ignored) {
            // 已經停了
        }
        try {
            seg.audio.release();
        } catch (Exception ignored) {
            // 同上
        }
        seg.audio = null;
        seg.audioTrack = -1;
    }

    /**
     * 收掉一段。
     *
     * <p><b>什麼情況會保留、什麼情況會刪掉：</b>寫進去的幀數 &gt; 0 而且 muxer 正常收尾
     * 就保留（就算中途出錯也一樣 —— 前面錄到的東西是好的，沒道理一起丟）；
     * 一幀都沒寫進去，或 muxer 收尾時丟例外（檔案缺 moov，播放器打不開）就刪掉，
     * 不要在相簿裡留一個壞檔。
     *
     * @param message 非 null ＝ 因為錯誤而收（只影響回報的文字，不影響保留判斷）
     * @return 這一段的位置字串；沒有保留時是 {@code null}
     */
    private String finish(Segment seg, String message) {
        if (seg == null) return null;
        String where = seg.where;
        boolean ok = true;
        try {
            if (seg.started && seg.lastSample != null) {
                // 收檔前把「靜止的尾巴」補到現在。不補的話：錄 30 秒、最後 20 秒畫面
                // 沒動，檔案就只有 10 秒 —— 使用者只會得到「錄影壞了」這個結論。
                long idle = SystemClock.uptimeMillis() - seg.lastWallMs;
                if (idle > FILL_AFTER_US / 1000) {
                    fill(seg, seg.lastPts + idle * 1000);
                }
            }
            // 音訊要先收（它還會往 muxer 寫最後幾筆），muxer 才能停
            closeAudioEncoder(seg);
            if (seg.samples > 0) {
                // 一個樣本都沒寫過時 stop() 會丟 IllegalStateException（沒東西可收尾）
                seg.muxer.stop();
            }
        } catch (Exception e) {
            DiagLog.w(TAG, "錄影收檔失敗", e);
            ok = false;
            if (message == null) message = "收檔失敗";
        }
        try {
            seg.muxer.release();
        } catch (Exception ignored) {
            // release 失敗沒什麼能做的，檔案已經在關了
        }
        try {
            seg.pfd.close();
        } catch (Exception ignored) {
            // 同上
        }

        boolean keep = ok && seg.samples > 0;
        if (seg.uri != null) {
            ContentResolver cr = context.getContentResolver();
            if (keep) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.IS_PENDING, 0);
                cr.update(seg.uri, v, null, null);
            } else {
                // 一幀都沒寫進去，或收尾失敗：刪掉，別在相簿裡留一個打不開的檔
                cr.delete(seg.uri, null, null);
            }
        } else if (!keep && seg.file != null) {
            //noinspection ResultOfMethodCallIgnored
            seg.file.delete();
        }

        if (keep) {
            synchronized (saved) {
                saved.add(where);
            }
            DiagLog.i(TAG, "錄影：已收檔 " + where + "（" + seg.samples + " 幀）");
            return where;
        }
        DiagLog.w(TAG, "錄影：丟棄 " + where + "（" + seg.samples + " 幀，"
                + (message == null ? "沒有資料" : message) + "）");
        return null;
    }

    /** 回呼一次（收檔在背景執行緒，UI 那邊要自己 post）。 */
    private void notifyStopped(String where, String message) {
        String all;
        synchronized (saved) {
            all = saved.isEmpty() ? null : saved.get(0);
            if (saved.size() > 1) all = all + " 等 " + saved.size() + " 個檔案";
        }
        if (all == null) all = where;
        if (callback != null) callback.onStopped(all, message);
    }

    // ------------------------------------------------------------ 小工具

    /** 一個待處理的封包（影像或音訊）。欄位都是 final，跨執行緒傳遞不需要額外同步。 */
    private static final class Pkt {
        final byte[] data;
        final long pts;
        final boolean config;
        final boolean key;
        final int width;
        final int height;
        final boolean eos;
        /** true ＝ 裸 PCM（音訊），false ＝ H.264。 */
        final boolean audio;

        Pkt(byte[] data, long pts, boolean config, boolean key, int width, int height,
            boolean eos, boolean audio) {
            this.data = data;
            this.pts = pts;
            this.config = config;
            this.key = key;
            this.width = width;
            this.height = height;
            this.eos = eos;
            this.audio = audio;
        }

        static Pkt audio(byte[] pcm, long pts) {
            return new Pkt(pcm, pts, false, false, 0, 0, false, true);
        }
    }

    /** 一段錄影（一個 MP4 檔）。 */
    private static final class Segment {
        MediaMuxer muxer;
        ParcelFileDescriptor pfd;
        Uri uri;
        File file;
        String where;
        int track;
        int width;
        int height;
        boolean started;
        long originPts;
        long lastPts = -1;
        /** 最後寫進去的那筆資料。畫面靜止時拿它重複寫，把時間軸補滿（見 fill）。 */
        byte[] lastSample;
        /** 最後一次寫入的牆上時間，收檔時用它把「靜止的尾巴」補到現在。 */
        long lastWallMs;
        int samples;

        /** AAC 編碼器（這一段沒有音軌時是 null）。 */
        MediaCodec audio;
        /** 音軌索引；{@code -1} ＝ 這一段只有影像。 */
        int audioTrack = -1;
        /** 送進編碼器的最後一個 pts（微秒，相對影片起點）。 */
        long lastAudioPts = -1;
        /** 寫進 muxer 的最後一個 AAC pts，保證嚴格遞增。 */
        long lastAudioOutPts = -1;
    }
}
