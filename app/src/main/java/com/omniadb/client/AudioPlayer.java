package com.omniadb.client;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;

/**
 * 把 scrcpy 音訊串流裡的 PCM 直接丟給 {@link AudioTrack}。
 *
 * <p><b>為什麼是 PCM（{@code audio_codec=raw}）而不是 opus/AAC：</b>
 * scrcpy 伺服器用 {@code MediaCodec} 編音訊，而 POCO / Android 15 上那條路會死在
 * {@code MediaCodec.setCallback} → {@code DeviceConfig}：
 * <pre>
 * SecurityException: Given calling package android does not match caller's uid 2000
 * </pre>
 * 因為 scrcpy 以 shell（uid 2000）身分跑，卻拿著系統 context 去讀 DeviceConfig。
 * opus / aac / flac <b>三種編碼器全部一樣下場</b>（實測 2026-10-25），
 * 只有 {@code raw} 完全不碰 MediaCodec，所以兩台機器都通。
 *
 * <p>代價是 48 kHz × 立體聲 × 16-bit ＝ <b>1.54 Mbps</b>，但換來兩件事：
 * 不需要解碼器（Client 少一個會壞的環節），以及<b>零編碼延遲</b>
 * （opus/AAC 每幀 21 ms ＋ 編碼器 lookahead 約 20~40 ms 全部省掉）。
 *
 * <p><b>時鐘就是 {@code AudioTrack.write()}。</b>MODE_STREAM 下它是阻塞的：
 * 緩衝滿了就等，等多久剛好等於播放多久。所以這裡<b>不需要任何節拍器或計時執行緒</b> ——
 * 資料來多快就吞多快，播不完就自動把上游擋住（背壓一路傳回 socket）。
 * 這是小米盒子那套（MiBoxCastRx/LowLatPlayer）量出來的老結論，直接沿用。
 */
final class AudioPlayer {

    private static final String TAG = "OmniADB-Client";

    /** scrcpy 的音訊格式是固定的，見伺服器端 {@code AudioConfig}。 */
    static final int SAMPLE_RATE = 48_000;
    static final int CHANNELS = 2;
    static final int BYTES_PER_SEC = SAMPLE_RATE * CHANNELS * 2;

    /** 伺服器每包送 4096 bytes ≈ 21.3 ms。 */
    private static final int PACKET_BYTES = 4096;

    /**
     * 開播前先墊的量。
     *
     * <p>直接 {@code play()} 在空軌上，第一包還沒到就會先 underrun 一次 ——
     * 聽起來是開頭「喀」一聲。墊兩包（≈43 ms）就夠，再多只是白白增加延遲。
     */
    private static final int PREBUFFER_BYTES = PACKET_BYTES * 2;

    /**
     * AudioTrack 的緩衝。
     *
     * <p><b>這個數字就是延遲。</b>100 ms 是「不會天天 underrun」與「嘴型還沒歪掉」之間的折衷；
     * 實測（小米盒子）平台給的實際緩衝常比要求的小，而且{@code 已寫未播 ≈ 0}，
     * 所以真正落在緩衝裡的時間大約就是這個量級。
     */
    private static final int TRACK_BYTES = BYTES_PER_SEC / 10;

    private AudioTrack track;
    private volatile boolean running;
    private volatile boolean muted;
    private volatile boolean playing;

    private long writtenBytes;
    private long lastLogAt;
    private long lastLogBytes;
    private int writeErrors;

    /** 上一次統計區間的音量平方和與樣本數（算 RMS 用）。 */
    private long rmsAcc;
    private long rmsSamples;
    private long silentMs;

    /** 建立 AudioTrack 並進入可寫狀態。失敗會拋 {@link IllegalStateException}。 */
    void start() {
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) throw new IllegalStateException("getMinBufferSize 回 " + minBuf);

        int bufBytes = Math.max(minBuf, TRACK_BYTES);

        AudioAttributes attr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();

        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(attr)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            try {
                t.release();
            } catch (Throwable ignored) {
            }
            throw new IllegalStateException("AudioTrack 未初始化（state=" + t.getState() + "）");
        }

        track = t;
        running = true;
        lastLogAt = SystemClock.uptimeMillis();
        DiagLog.i(TAG, "音訊輸出就緒：" + SAMPLE_RATE + "Hz/" + CHANNELS + "ch，"
                + "要求緩衝 " + bufBytes + " bytes（平台最小 " + minBuf + "），"
                + "實際 " + t.getBufferSizeInFrames() + " 幀 ≈ "
                + (t.getBufferSizeInFrames() * 1000L / SAMPLE_RATE) + " ms");
    }

    /**
     * 寫入一段 PCM。<b>會阻塞</b>（那就是時鐘），必須在音訊讀取執行緒呼叫。
     *
     * <p>關閉期間或軌道已釋放時靜靜丟棄 —— 收尾時上游一定還有一兩包在飛。
     */
    void write(byte[] data, int len) {
        AudioTrack t = track;
        if (t == null || !running || len <= 0) return;
        try {
            int off = 0;
            while (off < len) {
                int n = t.write(data, off, len - off);
                if (n < 0) {
                    // 收尾時 release() 會讓阻塞中的 write 回錯誤碼，不算異常
                    if (running) DiagLog.w(TAG, "AudioTrack.write 回 " + n + "，停止寫入");
                    return;
                }
                off += n;
                writtenBytes += n;
            }
            measureLevel(data, len);
        } catch (Throwable e) {
            if (running) DiagLog.w(TAG, "音訊寫入失敗：" + e);
            writeErrors++;
            return;
        }

        if (!playing && writtenBytes >= PREBUFFER_BYTES) {
            playing = true;
            try {
                t.play();
                DiagLog.i(TAG, "音訊開播（已墊 " + writtenBytes + " bytes ≈ "
                        + (writtenBytes * 1000L / BYTES_PER_SEC) + " ms）");
            } catch (Throwable e) {
                DiagLog.w(TAG, "AudioTrack.play 失敗：" + e);
            }
        }
        logStatsIfDue();
    }

    /** 靜音。{@code AudioTrack.setVolume} 只影響輸出音量，不影響時鐘與緩衝。 */
    void setMuted(boolean m) {
        muted = m;
        AudioTrack t = track;
        if (t == null) return;
        try {
            t.setVolume(m ? 0f : 1f);
        } catch (Throwable e) {
            DiagLog.w(TAG, "設定音量失敗：" + e);
        }
    }

    boolean isMuted() {
        return muted;
    }

    /** 已經送進 AudioTrack 的音訊長度（毫秒）—— 排錯時看這個數字有沒有在長。 */
    long playedMs() {
        return writtenBytes * 1000L / BYTES_PER_SEC;
    }

    /** 收尾。任何執行緒都可以呼叫；阻塞中的 {@link #write} 會被 release 打斷。 */
    void stop() {
        running = false;
        AudioTrack t = track;
        track = null;
        if (t == null) return;
        try {
            if (playing) t.pause();
        } catch (Throwable ignored) {
        }
        try {
            t.flush();
        } catch (Throwable ignored) {
        }
        try {
            t.release();
        } catch (Throwable ignored) {
        }
        playing = false;
        DiagLog.i(TAG, "音訊輸出已關閉（共 " + playedMs() + " ms，寫入錯誤 " + writeErrors + " 次）");
    }

    /**
     * 累積音量平方和。
     *
     * <p><b>為什麼要量這個</b>：{@code AudioRecord} 在「擷取政策不符」時
     * <b>不會拋錯，只會一直回全零</b>（小米盒子那套量到的坑）。
     * 也就是說「連線正常、碼率正常、就是沒聲音」和「對方真的沒在播」
     * 從外面看起來一模一樣 —— 只有 RMS 分得出來。
     */
    private void measureLevel(byte[] data, int len) {
        long acc = 0;
        int n = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int v = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
            acc += (long) v * v;
            n++;
        }
        rmsAcc += acc;
        rmsSamples += n;
    }

    /**
     * 每 5 秒印一次。
     *
     * <p>只有「有沒有在長」是有意義的：數字停住代表上游斷了（或對方靜音，
     * 但靜音時 scrcpy 仍會送 3 bytes/包的空幀，所以<b>數字還是會長</b>）。
     */
    private void logStatsIfDue() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLogAt < 5_000) return;
        long delta = writtenBytes - lastLogBytes;
        long ms = now - lastLogAt;
        lastLogAt = now;
        lastLogBytes = writtenBytes;

        int rms = rmsSamples > 0 ? (int) Math.sqrt((double) rmsAcc / rmsSamples) : 0;
        rmsAcc = 0;
        rmsSamples = 0;
        if (rms < 1) {
            silentMs += ms;
            if (silentMs >= 5_000) {
                DiagLog.w(TAG, "音訊連續 " + (silentMs / 1000) + " 秒全零 —— "
                        + "對方沒在播，或那支 App 拒絕被擷取（allowAudioPlaybackCapture=false）");
            }
        } else {
            silentMs = 0;
        }

        DiagLog.i(TAG, "音訊 " + (delta * 8 / Math.max(1, ms)) + " kbps，RMS=" + rms
                + "，累計 " + playedMs() + " ms" + (muted ? "（已靜音）" : ""));
    }
}
