package com.omniadb.client;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 記住「連過哪些手機」。
 *
 * <p>為什麼一定要有這個：手機 B 的無線偵錯埠號每次開啟都隨機，但裝了
 * {@code OmniADB-Host} 之後會把埠釘在 5555 並在重開機後自己爬回來。
 * 所以只要記住 {@code IP:5555}，下次開 App 就能直接連，不必再掃一次。
 *
 * <p>用 SharedPreferences 存 JSON，不引入 Room —— 這裡的資料量是「幾台手機」，
 * 一個資料庫換不到任何東西。
 *
 * <p><b>去重一律用序號（{@link Device#serial}），不是位址。</b>
 * 位址有兩種會變的情況：換埠（隨機埠 → Host 釘住的 5555）、換 IP（DHCP 重新配發）。
 * 用位址當鍵的話，這兩件事都會在清單上多出一張「同一支手機」的卡片。
 */
public class DeviceStore {

    private static final String PREF = "omniadb_client";
    private static final String KEY_DEVICES = "devices";

    /** 一台「記得的手機」。 */
    public static class Device {
        public String host = "";
        public int port = 5555;
        public String model = "";
        /**
         * 硬體序號 —— 這才是「同一台機器」的憑據。
         *
         * <p>來源是 {@link DeviceInfo#hwid}：{@code ro.serialno}，被 ROM 抹掉時退回
         * {@code android_id}（只有 factory reset 才會變）。
         * <b>可能為空</b>：少數 ROM 兩個都拿不到，那時只能退回位址。
         */
        public String serial = "";
        public long lastSeen;

        public Device() {
        }

        public Device(String host, int port, String model) {
            this.host = host;
            this.port = port;
            this.model = model;
            this.lastSeen = System.currentTimeMillis();
        }

        public Device(String host, int port, String model, String serial) {
            this(host, port, model);
            this.serial = serial == null ? "" : serial.trim();
        }

        public String id() {
            return host + ":" + port;
        }

        /**
         * 去重用的鍵。
         *
         * <p>有序號就用序號 —— 換埠、換 IP 都認得出來是同一台。
         * 沒有序號才退回位址（舊資料，或 ROM 不給序號）。
         */
        public String key() {
            return serial.isEmpty() ? "addr:" + id() : "sn:" + serial;
        }

        public String label() {
            String name = (model == null || model.isEmpty()) ? host : model;
            return name + "  ·  " + id();
        }
    }

    private final SharedPreferences prefs;

    public DeviceStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public List<Device> all() {
        List<Device> out = new ArrayList<>();
        String raw = prefs.getString(KEY_DEVICES, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Device d = new Device();
                d.host = o.optString("host", "");
                d.port = o.optInt("port", 5555);
                d.model = o.optString("model", "");
                // 舊版的欄位叫 hwid，而且從來沒被寫進檔案；兩個都讀，舊存檔才不會斷掉
                d.serial = o.optString("serial", o.optString("hwid", "")).trim();
                d.lastSeen = o.optLong("lastSeen", 0);
                if (!d.host.isEmpty()) out.add(d);
            }
        } catch (Exception ignored) {
            // 存檔壞掉就當作空的，不要讓 App 起不來
        }
        return out;
    }

    /**
     * 新增或更新，<b>同一台手機只留一筆</b>。
     *
     * <p>判準見 {@link #sameDevice(Device, Device)}：兩邊都有序號就只認序號，
     * 序號不同＝不同手機（即使 IP 一樣 —— 那是 DHCP 把舊位址配給了別台）。
     * 只有「其中一邊還沒有序號」時才退回位址比對，那是舊資料升級上來的那一輪。
     *
     * <p>合併時刻意保留三個東西：型號（舊的可能是對的）、序號（新補上的）、
     * 5555 埠（Host 釘住的埠比隨機埠可信）。
     */
    public void put(Device device) {
        if (device == null || device.host == null || device.host.isEmpty()) return;
        if (device.serial == null) device.serial = "";

        List<Device> kept = new ArrayList<>();
        for (Device old : all()) {
            if (!sameDevice(old, device)) {
                kept.add(old);
                continue;
            }
            if (device.model == null || device.model.isEmpty()) device.model = old.model;
            if (device.serial.isEmpty()) device.serial = old.serial;
            // 埠：5555 是 Host 釘住的，任何情況下都比隨機埠可信
            if (old.port == 5555 && device.port != 5555) device.port = 5555;
            // 時間戳沿用舊的，卡片才不會看起來像剛加入
            device.lastSeen = Math.max(device.lastSeen, old.lastSeen);
        }
        kept.add(device);
        save(kept);
    }

    /**
     * 兩筆資料是不是同一台手機。
     *
     * <p><b>有序號就只認序號</b>：這是最重要的一條。用「同 IP 就算同一台」的話，
     * 路由器把 {@code .178} 配給另一支手機時，兩支手機會被併成一張卡片，
     * 而使用者看到的是「我明明有兩台，清單只有一台」。
     *
     * <p>沒有序號（舊資料，或 ROM 不給）才退回位址 —— 而且只比 host，
     * 不比 {@code host:port}：換埠是常態（隨機埠 → 5555），那還是同一台。
     */
    private static boolean sameDevice(Device a, Device b) {
        if (!a.serial.isEmpty() && !b.serial.isEmpty()) {
            return a.serial.equals(b.serial);
        }
        return a.host.equals(b.host);
    }

    /** 移除。有序號就照序號清掉所有殘留（換過 IP 的舊紀錄也一起帶走）。 */
    public void remove(String host, int port, String serial) {
        List<Device> kept = new ArrayList<>();
        for (Device d : all()) {
            boolean hit;
            if (serial != null && !serial.isEmpty() && !d.serial.isEmpty()) {
                hit = d.serial.equals(serial);
            } else {
                hit = d.host.equals(host) && d.port == port;
            }
            if (!hit) kept.add(d);
        }
        save(kept);
    }

    private void save(List<Device> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Device d : list) {
                JSONObject o = new JSONObject();
                o.put("host", d.host);
                o.put("port", d.port);
                o.put("model", d.model == null ? "" : d.model);
                o.put("serial", d.serial == null ? "" : d.serial);
                o.put("lastSeen", d.lastSeen);
                arr.put(o);
            }
        } catch (Exception ignored) {
            return;
        }
        prefs.edit().putString(KEY_DEVICES, arr.toString()).apply();
    }
}
