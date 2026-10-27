package com.omniadb.client;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@code OmniADB-Host} 裝到遠端手機上 —— 這是電腦版 {@code omniadb setup} 的手機移植。
 *
 * <p><b>為什麼不是「push + pm install 就結束」</b>：Host 裝好之後什麼都不會做。
 * 它需要三樣東西才會開始釘埠：
 *
 * <ol>
 *   <li>{@code WRITE_SECURE_SETTINGS} —— 沒有它，重開機後無線偵錯是關的，
 *       沒人會去開。這個權限是 signature|privileged，<b>一般安裝拿不到</b>，
 *       只能由 shell（uid 2000）用 {@code pm grant} 給。</li>
 *   <li>被啟動一次 —— 服務是 {@code exported=false}，電腦端／控制端都沒辦法
 *       直接 start service，只有 {@code am start} 開 Activity 才拉得起來
 *       （Host 的 {@code MainActivity.onCreate} 有 {@code ensureServiceRunning()}）。</li>
 *   <li>ADB 金鑰不要一週就過期 —— {@code adb_allowed_connection_time=0} 關掉效期。</li>
 * </ol>
 *
 * <p>三件事都只要 shell 權限，而我們連上 B 之後拿到的正是 shell，
 * 所以整個流程可以在手機 A 上一次跑完，不需要電腦。
 */
public class HostInstaller {

    private static final String TAG = "OmniADB-Client";

    public static final String HOST_PACKAGE = "com.omniadb.host";
    public static final String HOST_ACTIVITY = HOST_PACKAGE + "/.MainActivity";
    private static final String ASSET_NAME = "OmniADB-Host.apk";

    /** 每一步的結果，讓 UI 可以像電腦版一樣逐步顯示勾勾。 */
    public static class Step {
        public final String title;
        public final boolean ok;
        public final String detail;

        Step(String title, boolean ok, String detail) {
            this.title = title;
            this.ok = ok;
            this.detail = detail == null ? "" : detail;
        }
    }

    public static class Result {
        public boolean ok;
        public final List<Step> steps = new ArrayList<>();
    }

    /**
     * 執行完整安裝。
     *
     * @param onStep     每完成一步就回呼一次（可能不在主執行緒）
     * @param onProgress 傳檔／對方安裝的進度（可能不在主執行緒）；可為 {@code null}
     */
    public static Result install(Context context, AdbClient client,
                                 java.util.function.Consumer<Step> onStep,
                                 AdbClient.Progress onProgress) {
        Result result = new Result();

        // ---- 1. 把內建的 Host APK 解到快取 ----
        File apk;
        try {
            apk = extractAsset(context);
            add(result, onStep, "準備 APK", true, apk.length() / 1024 / 1024 + " MB");
        } catch (Exception e) {
            add(result, onStep, "準備 APK", false, e.getMessage());
            result.ok = false;
            return result;
        }

        // ---- 2. 安裝 ----
        try {
            String out = client.installApk(apk, onProgress);
            boolean ok = out.contains("Success");
            add(result, onStep, "安裝 App", ok, firstLine(out));
            if (!ok) {
                result.ok = false;
                return result;
            }
        } catch (Exception e) {
            add(result, onStep, "安裝 App", false, e.getMessage());
            result.ok = false;
            return result;
        }

        // ---- 3. 授予 WRITE_SECURE_SETTINGS（重開機自恢復的關鍵）----
        try {
            String out = client.shell("pm grant " + HOST_PACKAGE
                    + " android.permission.WRITE_SECURE_SETTINGS");
            boolean ok = !out.toLowerCase().contains("error")
                    && !out.toLowerCase().contains("exception");
            add(result, onStep, "授予系統設定權限", ok,
                    ok ? "重開機後能自己開回無線偵錯" : firstLine(out));
        } catch (Exception e) {
            add(result, onStep, "授予系統設定權限", false, e.getMessage());
        }

        // ---- 4. 關掉 ADB 金鑰效期 ----
        try {
            client.shell("settings put global adb_allowed_connection_time 0");
            add(result, onStep, "延長 ADB 金鑰效期", true, "不再一週過期");
        } catch (Exception e) {
            add(result, onStep, "延長 ADB 金鑰效期", false, e.getMessage());
        }

        // ---- 5. 啟動 App（服務是 exported=false，只能這樣拉起來）----
        try {
            String out = client.shell("am start -n " + HOST_ACTIVITY);
            boolean ok = !out.contains("Error") && !out.contains("Exception");
            add(result, onStep, "啟動常駐服務", ok, ok ? "" : firstLine(out));
        } catch (Exception e) {
            add(result, onStep, "啟動常駐服務", false, e.getMessage());
        }

        // ---- 6. 驗證：App 是否真的裝上了 ----
        try {
            String out = client.shell("pm path " + HOST_PACKAGE);
            boolean ok = out.contains("package:");
            add(result, onStep, "驗證安裝結果", ok, ok ? "" : "找不到套件");
            result.ok = ok;
        } catch (Exception e) {
            add(result, onStep, "驗證安裝結果", false, e.getMessage());
            result.ok = false;
        }

        return result;
    }

    // ------------------------------------------------------------------ 內部

    private static void add(Result r, java.util.function.Consumer<Step> onStep,
                            String title, boolean ok, String detail) {
        Step s = new Step(title, ok, detail);
        r.steps.add(s);
        DiagLog.i(TAG, (ok ? "✅ " : "❌ ") + title + " —— " + s.detail);
        if (onStep != null) onStep.accept(s);
    }

    /** 把 assets 裡的 Host APK 解到 cache，回傳檔案。 */
    private static File extractAsset(Context context) throws Exception {
        File out = new File(context.getCacheDir(), ASSET_NAME);
        try (InputStream in = context.getAssets().open(ASSET_NAME);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
        return out;
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) return t.length() > 120 ? t.substring(0, 120) : t;
        }
        return "";
    }
}
