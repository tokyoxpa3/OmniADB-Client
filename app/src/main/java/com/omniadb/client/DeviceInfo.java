package com.omniadb.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一台遠端裝置的狀態快照。
 *
 * <p>設計照抄電腦版 {@code omniadb/api.py}：<b>所有探測併成一次 shell 批次</b>，
 * 用 {@code echo @@OMNIADB:xxx} 分段，本機再切開解析。
 *
 * <p>為什麼要併成一次：每個 {@code adb shell} 都是一次 stream 開啟 + 關閉，
 * 實測 100~500 ms。型號、Android、電量、解析度、儲存、前景 App 一項一項問
 * 就是十幾次往返、兩三秒的延遲。併成一次大約 300 ms。
 *
 * <p>分段標記<b>不能用 {@code #} 開頭</b> —— shell 會把 {@code #} 當註解，
 * 而且註解吃掉的是「整行」，這串指令全在同一行，第一個 {@code #} 之後的東西
 * （含結尾的 {@code }}）會全部變成註解，shell 只看到 {@code { echo} 然後語法錯誤。
 * 電腦版踩過這個坑，這裡沿用 {@code @@}。
 */
public class DeviceInfo {

    private static final String MARK = "@@OMNIADB:";

    /**
     * 遠端批次最後一定會印出的結束記號。
     *
     * <p><b>探測靠它判斷「收完了」，不靠 EOF。</b>libadb 的 {@code AdbStream.read()}
     * 在某些情況下永遠等不到結束通知（見 {@link AdbClient#shell(String, String, long)}），
     * 以前用「讀到 EOF」的寫法會讓整條探測執行緒永久卡住，連帶把共用執行緒池佔滿
     * —— 症狀就是卡片永遠停在「檢查中」。
     */
    public static final String END_MARK = MARK + "END";

    /** 一次探測最多等多久。裡面有四條 {@code dumpsys}，慢機器 2~3 秒很正常。 */
    private static final long PROBE_READ_TIMEOUT_MS = 8_000;

    /**
     * 整包丟給裝置端跑一次。
     *
     * <p>最外層的 {@code { … } 2>/dev/null} 是必要的：{@code grep … | head -1}
     * 會提早關閉管線，讓 dumpsys 噴 "Failed to write while dumping service"。
     */
    public static final String PROBE_CMD =
            "{ "
                    + "echo " + MARK + "model; getprop ro.product.model; "
                    + "echo " + MARK + "brand; getprop ro.product.brand; "
                    + "echo " + MARK + "android; getprop ro.build.version.release; "
                    + "echo " + MARK + "sdk; getprop ro.build.version.sdk; "
                    + "echo " + MARK + "ip; ip -f inet addr show wlan0 2>/dev/null "
                    + "| grep -o 'inet [0-9.]*' | head -1; "
                    + "echo " + MARK + "wm; wm size 2>/dev/null | tail -1; "
                    + "echo " + MARK + "batt; dumpsys battery 2>/dev/null; "
                    + "echo " + MARK + "fs; stat -f -c '%S %b %a' /data 2>/dev/null; "
                    + "echo " + MARK + "wake; dumpsys power 2>/dev/null "
                    + "| grep mWakefulness | head -1; "
                    + "echo " + MARK + "fg; dumpsys activity activities 2>/dev/null "
                    + "| grep -E 'topResumedActivity|mResumedActivity' | head -1; "
                    + "echo " + MARK + "focus; dumpsys window 2>/dev/null "
                    + "| grep mCurrentFocus | head -1; "
                    // 硬體身分：用來認出「同一台手機換了 IP」。
                    // ro.serialno 在部分 ROM 上會被抹成 unknown（Android 10+ 的隱私限制），
                    // 這時退回 android_id（factory reset 才會變）。
                    + "echo " + MARK + "hwid; (getprop ro.serialno; "
                    + "settings get secure android_id) 2>/dev/null "
                    + "| grep -v '^unknown$' | grep -v '^$' | head -1; "
                    + "echo " + MARK + "END; "
                    + "} 2>/dev/null";

    // 全部寫成靜態 Pattern：這個解析每次刷新都會跑，重複 compile 沒意義。
    private static final Pattern BATT_LEVEL = Pattern.compile("^\\s*level:\\s*(\\d+)", Pattern.MULTILINE);
    private static final Pattern BATT_TEMP = Pattern.compile("^\\s*temperature:\\s*(\\d+)", Pattern.MULTILINE);
    private static final Pattern BATT_POWERED = Pattern.compile("^\\s*(\\w+) powered:\\s*true", Pattern.MULTILINE);
    private static final Pattern WM_SIZE = Pattern.compile("(\\d+)\\s*x\\s*(\\d+)");
    private static final Pattern WAKE = Pattern.compile("mWakefulness=(\\w+)");
    private static final Pattern ACTIVITY =
            Pattern.compile("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/([A-Za-z0-9_.$]+)");
    private static final Pattern INET = Pattern.compile("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)");

    public String serial = "";
    /**
     * 硬體身分（{@code ro.serialno}，取不到時退回 {@code android_id}）。
     *
     * <p>跟 {@link #serial} 不是同一個東西：{@code serial} 是 ADB 的 transport id，
     * 走 Wi-Fi 時它就是 {@code IP:埠} —— 換個 IP 就變了。這個才是「同一台機器」的憑據，
     * {@link DeviceStore} 靠它避免「DHCP 換個位址就多出一張卡片」。
     *
     * <p>可能為空（ROM 限制），所以用的地方一律要有 fallback。
     */
    public String hwid = "";
    public String model = "";
    public String brand = "";
    public String android = "";
    public String sdk = "";
    public String ip = "";
    public int port;

    public int screenW;
    public int screenH;
    public int batteryLevel = -1;
    public float batteryTempC = -1f;
    public boolean charging;
    public float storageTotalGb = -1f;
    public float storageFreeGb = -1f;
    public boolean awake;
    public String foreground = "";

    public boolean reachable;
    /** 連得上但金鑰沒被授權 —— UI 要據此顯示「請求授權」，而不是單純的「離線」。 */
    public boolean unauthorized;
    public String error = "";

    /**
     * 失敗時的原始例外。
     *
     * <p>給呼叫端判斷「要不要換埠再試」用的 —— {@link #error} 是翻譯過的字串，
     * 拿字串比對會在改文案時默默失效。判斷一律看型別，見
     * {@link AdbClient#shouldTryPlainPort(Throwable)}。
     */
    public Throwable cause;

    /** 抓一次完整快照。失敗不會丟例外，而是回傳 {@code reachable=false} 並帶錯誤訊息。 */
    public static DeviceInfo probe(AdbClient client, String serial, int port) {
        DeviceInfo info = new DeviceInfo();
        info.serial = serial;
        info.port = port;
        try {
            String raw = client.shell(PROBE_CMD, END_MARK, PROBE_READ_TIMEOUT_MS);
            info.parse(raw);
            info.reachable = true;
        } catch (Exception e) {
            info.reachable = false;
            info.unauthorized = AdbClient.isUnauthorized(e);
            info.error = AdbClient.describeError(e);
            info.cause = e;
        }
        return info;
    }

    /** 解析探測批次輸出。公開給測試用 —— 這段是最容易因廠牌／版本而壞的地方。 */
    public void parse(String raw) {
        String[] sections = splitProbe(raw);

        model = nz(sections[0]);
        brand = nz(sections[1]);
        android = nz(sections[2]);
        sdk = nz(sections[3]);

        Matcher m = INET.matcher(nz(sections[4]));
        ip = m.find() ? m.group(1) : "";

        int[] wh = parseWmSize(nz(sections[5]));
        screenW = wh[0];
        screenH = wh[1];

        parseBattery(nz(sections[6]));
        parseStatFs(nz(sections[7]));

        awake = nz(sections[8]).contains("Awake");
        foreground = parseForeground(nz(sections[9]), nz(sections[10]));
        hwid = nz(sections[11]);
    }

    // ------------------------------------------------------------ 解析細節

    /**
     * 把分段輸出切成固定順序的 12 格
     * （model/brand/android/sdk/ip/wm/batt/fs/wake/fg/focus/hwid）。
     * 用固定長度陣列而不是 Map，是因為順序是協定的一部分，寫死比較不容易錯。
     */
    private static String[] splitProbe(String raw) {
        String[] out = new String[12];
        for (int i = 0; i < out.length; i++) out[i] = "";
        if (raw == null) return out;

        int idx = -1;
        StringBuilder buf = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            if (line.startsWith(MARK)) {
                if (idx >= 0 && idx < out.length) out[idx] = buf.toString().trim();
                buf.setLength(0);
                idx = sectionIndex(line.substring(MARK.length()).trim());
            } else if (idx >= 0) {
                buf.append(line).append('\n');
            }
        }
        if (idx >= 0 && idx < out.length) out[idx] = buf.toString().trim();
        return out;
    }

    private static int sectionIndex(String name) {
        switch (name) {
            case "model": return 0;
            case "brand": return 1;
            case "android": return 2;
            case "sdk": return 3;
            case "ip": return 4;
            case "wm": return 5;
            case "batt": return 6;
            case "fs": return 7;
            case "wake": return 8;
            case "fg": return 9;
            case "focus": return 10;
            case "hwid": return 11;
            default: return -1;
        }
    }

    /** {@code wm size} → (寬, 高)。有 Override size 時取<b>最後</b>一個（那才是生效值）。 */
    static int[] parseWmSize(String text) {
        int w = 0, h = 0;
        Matcher m = WM_SIZE.matcher(text);
        while (m.find()) {
            w = Integer.parseInt(m.group(1));
            h = Integer.parseInt(m.group(2));
        }
        return new int[]{w, h};
    }

    private void parseBattery(String text) {
        Matcher m = BATT_LEVEL.matcher(text);
        batteryLevel = m.find() ? Integer.parseInt(m.group(1)) : -1;

        m = BATT_TEMP.matcher(text);
        // temperature 的單位是十分之一度：325 = 32.5 °C
        batteryTempC = m.find() ? Integer.parseInt(m.group(1)) / 10f : -1f;

        charging = BATT_POWERED.matcher(text).find();
    }

    /**
     * {@code stat -f -c '%S %b %a' /data} → (總 GB, 可用 GB)。
     *
     * <p>為什麼不用 {@code df /data}：Android 用 bind mount 掛 APK，
     * {@code df} 對路徑的比對會挑錯項目 —— 實測 Samsung SM-G9810 上
     * {@code df /data} 的 "Mounted on" 竟然是某個 APK 的路徑。
     * {@code stat -f} 直接對 inode 問，不會挑錯。
     */
    private void parseStatFs(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length < 3) {
            storageTotalGb = -1f;
            storageFreeGb = -1f;
            return;
        }
        try {
            long bsize = Long.parseLong(parts[0]);
            long total = Long.parseLong(parts[1]);
            long avail = Long.parseLong(parts[2]);
            if (bsize <= 0 || total <= 0) throw new NumberFormatException();
            double gb = 1024.0 * 1024 * 1024;
            storageTotalGb = (float) (bsize * total / gb);
            storageFreeGb = (float) (bsize * avail / gb);
        } catch (NumberFormatException e) {
            storageTotalGb = -1f;
            storageFreeGb = -1f;
        }
    }

    /**
     * 從 dumpsys activity / window 的行裡抓前景 App。
     *
     * <p>格式各版本不一，所以傳兩個候選，取第一個能解析出 {@code 套件/Activity} 的。
     */
    static String parseForeground(String... candidates) {
        for (String text : candidates) {
            if (text == null || text.isEmpty()) continue;
            Matcher m = ACTIVITY.matcher(text);
            if (m.find()) return m.group(1) + "/" + m.group(2);
        }
        return "";
    }

    // ------------------------------------------------------------ 衍生值

    public String displayName() {
        if (model != null && !model.isEmpty()) return model;
        return serial.isEmpty() ? "?" : serial;
    }

    public String batteryText() {
        if (batteryLevel < 0) return "—";
        StringBuilder sb = new StringBuilder().append(batteryLevel).append('%');
        if (batteryTempC >= 0) sb.append(" · ").append(String.format("%.1f°C", batteryTempC));
        if (charging) sb.append(" · ⚡");
        return sb.toString();
    }

    public String screenText() {
        return screenW > 0 ? screenW + "×" + screenH : "—";
    }

    public String storageText() {
        if (storageTotalGb < 0) return "—";
        return String.format("%.1f / %.1f GB", storageFreeGb, storageTotalGb);
    }

    public String androidText() {
        if (android.isEmpty()) return "—";
        return sdk.isEmpty() ? android : android + "（SDK " + sdk + "）";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
