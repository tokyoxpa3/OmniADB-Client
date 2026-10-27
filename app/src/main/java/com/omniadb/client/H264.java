package com.omniadb.client;

/**
 * 一點點 H.264 位元流處理 —— 只做兩件事：拆出 SPS/PPS，從 SPS 讀出畫面尺寸。
 *
 * <p>為什麼需要它：scrcpy 的 codec meta 只在連線開始時送一次（12 bytes：codec id +
 * width + height），之後就沒有尺寸資訊了。<b>但對方一旋轉，編碼器會重啟、
 * 解析度跟著變</b>（例如 1024×464 ↔ 464×1024），只會再送一個新的 config 包。
 * 我們要把觸控座標送到正確的座標系，就必須知道「現在的」尺寸 ——
 * 唯一可靠的來源是 SPS。
 *
 * <p>用 SPS 而不是 codec meta 還有一個好處：尺寸是<b>解碼器真正要解的</b>那個尺寸，
 * 不含任何猜測。
 */
final class H264 {

    /** 一個 config 包拆出來的 SPS 與 PPS，兩者都<b>帶著 Annex-B 起始碼</b>。 */
    static final class Csd {
        final byte[] sps;
        final byte[] pps;

        Csd(byte[] sps, byte[] pps) {
            this.sps = sps;
            this.pps = pps;
        }

        boolean isValid() {
            return sps != null && pps != null;
        }
    }

    private H264() {
    }

    /**
     * 把 scrcpy 送來的 config 包拆成 SPS / PPS。
     *
     * <p>實測內容是 {@code 00 00 00 01 67 <sps> 00 00 00 01 68 <pps>}，
     * 但起始碼可能是 3 bytes（{@code 00 00 01}）也可能有前導 0，
     * 所以照 Annex-B 的規則掃，不要假設固定位移。
     */
    static Csd splitCsd(byte[] data) {
        byte[] sps = null;
        byte[] pps = null;

        int i = 0;
        int n = data.length;
        while (i + 3 < n) {
            int start = -1;
            int startLen = 0;
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                start = i;
                startLen = 3;
            } else if (i + 4 < n && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0
                    && data[i + 3] == 1) {
                start = i;
                startLen = 4;
            }
            if (start < 0) {
                i++;
                continue;
            }

            int nalStart = start + startLen;
            if (nalStart >= n) break;
            int type = data[nalStart] & 0x1F;

            // 找下一個起始碼，決定這個 NAL 的結尾
            int next = n;
            for (int j = nalStart + 1; j + 2 < n; j++) {
                if (data[j] == 0 && data[j + 1] == 0 && (data[j + 2] == 1)) {
                    next = j;
                    break;
                }
            }
            // 結尾可能殘留 0（下一個起始碼的前導 0），要削掉
            int end = next;
            while (end > nalStart && data[end - 1] == 0) end--;

            if (type == 7) {
                sps = slice(data, start, end);
            } else if (type == 8) {
                pps = slice(data, start, end);
            }
            i = next;
        }
        return new Csd(sps, pps);
    }

    /**
     * 從 SPS 讀出 {@code [width, height]}，讀不出來回傳 {@code null}。
     *
     * <p>依 ITU-T H.264 7.3.2.1.1 的語法順序走，只走到 frame cropping 為止 ——
     * 再後面的欄位跟尺寸無關，不需要解。
     */
    static int[] spsSize(byte[] sps) {
        if (sps == null || sps.length < 5) return null;
        try {
            // 跳過起始碼，找到 NAL header（0x67 = SPS）
            int off = 0;
            while (off + 3 < sps.length && !(sps[off] == 0 && sps[off + 1] == 0 && sps[off + 2] == 1)) {
                off++;
            }
            off += 3;
            BitReader r = new BitReader(sps, off + 1, sps.length);   // +1 跳過 NAL header

            int profileIdc = r.u(8);
            r.u(8);                     // constraint_set flags + reserved
            r.u(8);                     // level_idc
            r.ue();                     // seq_parameter_set_id

            int chromaFormatIdc = 1;
            boolean separateColourPlane = false;
            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244
                    || profileIdc == 44 || profileIdc == 83 || profileIdc == 86
                    || profileIdc == 118 || profileIdc == 128 || profileIdc == 138
                    || profileIdc == 139 || profileIdc == 134 || profileIdc == 135) {
                chromaFormatIdc = r.ue();
                if (chromaFormatIdc == 3) separateColourPlane = r.u(1) == 1;
                r.ue();                 // bit_depth_luma_minus8
                r.ue();                 // bit_depth_chroma_minus8
                r.u(1);                 // qpprime_y_zero_transform_bypass_flag
                if (r.u(1) == 1) {      // seq_scaling_matrix_present_flag
                    int lists = chromaFormatIdc != 3 ? 8 : 12;
                    for (int i = 0; i < lists; i++) {
                        if (r.u(1) == 1) skipScalingList(r, i < 6 ? 16 : 64);
                    }
                }
            }

            r.ue();                     // log2_max_frame_num_minus4
            int pocType = r.ue();
            if (pocType == 0) {
                r.ue();                 // log2_max_pic_order_cnt_lsb_minus4
            } else if (pocType == 1) {
                r.u(1);                 // delta_pic_order_always_zero_flag
                r.se();                 // offset_for_non_ref_pic
                r.se();                 // offset_for_top_to_bottom_field
                int cycle = r.ue();
                for (int i = 0; i < cycle; i++) r.se();
            }
            r.ue();                     // max_num_ref_frames
            r.u(1);                     // gaps_in_frame_num_value_allowed_flag

            int widthMbs = r.ue() + 1;
            int heightMapUnits = r.ue() + 1;
            boolean frameMbsOnly = r.u(1) == 1;
            if (!frameMbsOnly) r.u(1);   // mb_adaptive_frame_field_flag
            r.u(1);                      // direct_8x8_inference_flag

            int cropLeft = 0;
            int cropRight = 0;
            int cropTop = 0;
            int cropBottom = 0;
            if (r.u(1) == 1) {           // frame_cropping_flag
                cropLeft = r.ue();
                cropRight = r.ue();
                cropTop = r.ue();
                cropBottom = r.ue();
            }

            int chromaArrayType = separateColourPlane ? 0 : chromaFormatIdc;
            int cropUnitX;
            int cropUnitY;
            if (chromaArrayType == 0) {
                cropUnitX = 1;
                cropUnitY = 2 - (frameMbsOnly ? 1 : 0);
            } else {
                int subWidthC = chromaArrayType == 3 ? 1 : 2;
                int subHeightC = chromaArrayType == 1 ? 2 : 1;
                cropUnitX = subWidthC;
                cropUnitY = subHeightC * (2 - (frameMbsOnly ? 1 : 0));
            }

            int width = widthMbs * 16 - cropUnitX * (cropLeft + cropRight);
            int height = (2 - (frameMbsOnly ? 1 : 0)) * heightMapUnits * 16
                    - cropUnitY * (cropTop + cropBottom);
            if (width <= 0 || height <= 0) return null;
            return new int[]{width, height};
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void skipScalingList(BitReader r, int size) {
        int lastScale = 8;
        int nextScale = 8;
        for (int j = 0; j < size; j++) {
            if (nextScale != 0) {
                int delta = r.se();
                nextScale = (lastScale + delta + 256) % 256;
            }
            lastScale = nextScale == 0 ? lastScale : nextScale;
        }
    }

    private static byte[] slice(byte[] src, int from, int to) {
        int len = to - from;
        if (len <= 0) return null;
        byte[] out = new byte[len];
        System.arraycopy(src, from, out, 0, len);
        return out;
    }

    /** H.264 的位元讀取器：指數哥倫布碼 + 有號哥倫布碼。 */
    private static final class BitReader {
        private final byte[] data;
        private int bit;

        BitReader(byte[] data, int byteOffset, int limit) {
            this.data = data;
            this.bit = byteOffset * 8;
            this.limit = limit * 8;
        }

        private final int limit;

        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | nextBit();
            }
            return v;
        }

        /** 無號指數哥倫布碼。 */
        int ue() {
            int zeros = 0;
            while (nextBit() == 0) {
                zeros++;
                if (zeros > 31) throw new IllegalStateException("bad exp-golomb");
            }
            if (zeros == 0) return 0;
            return (1 << zeros) - 1 + u(zeros);
        }

        /** 有號指數哥倫布碼。 */
        int se() {
            int v = ue();
            return (v & 1) == 1 ? (v + 1) / 2 : -(v / 2);
        }

        private int nextBit() {
            if (bit >= limit) throw new IllegalStateException("sps overflow");
            int b = (data[bit >> 3] >> (7 - (bit & 7))) & 1;
            bit++;
            return b;
        }
    }
}
