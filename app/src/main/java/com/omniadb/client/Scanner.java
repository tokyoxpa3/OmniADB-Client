package com.omniadb.client;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 用 mDNS 掃出同網段「開著無線偵錯」的手機。
 *
 * <p>兩種服務型別，用途完全不同：
 *
 * <ul>
 *   <li>{@code _adb-tls-connect._tcp} —— 已經開好無線偵錯、可以<b>直接連</b>的埠。
 *       這個埠每次開啟都隨機（例如 40589），所以只能掃、不能寫死。</li>
 *   <li>{@code _adb-tls-pairing._tcp} —— 「使用配對碼配對裝置」對話框開著時才廣播，
 *       只在<b>配對</b>那一次用得上。對話框一關，這個服務就消失。</li>
 * </ul>
 *
 * <p><b>踩過的坑：{@code resolveService} 同時只能跑一個。</b>
 * 在一個 resolve 還沒回來時再呼叫，會丟 {@code FAILURE_ALREADY_ACTIVE}，
 * 而且失敗是靜默的（只會走 onResolveFailed），很容易誤判成「掃不到東西」。
 * 所以這裡排一個佇列，一次只解一個。
 */
public class Scanner {

    private static final String TAG = "OmniADB-Client";

    private static final String SERVICE_CONNECT = "_adb-tls-connect._tcp";
    private static final String SERVICE_PAIRING = "_adb-tls-pairing._tcp";

    /** 掃到的一台裝置。 */
    public static class Found {
        public final String name;
        public final String host;
        public final int port;
        /** true = 這是配對埠，只能用來配對，不能拿來連線。 */
        public final boolean pairing;

        Found(String name, String host, int port, boolean pairing) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.pairing = pairing;
        }
    }

    public interface Listener {
        void onFound(Found found);

        /** 掃描時間到，兩個服務都已停止。 */
        void onDone();

        void onError(String message);
    }

    private final NsdManager nsd;
    private final android.net.wifi.WifiManager wifi;
    private final List<NsdManager.DiscoveryListener> listeners = new ArrayList<>();
    private final Deque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();

    private boolean resolving;
    private Listener listener;
    private android.net.wifi.WifiManager.MulticastLock multicastLock;

    public Scanner(Context context) {
        Context app = context.getApplicationContext();
        nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        wifi = (android.net.wifi.WifiManager) app.getSystemService(Context.WIFI_SERVICE);
    }

    /** 開始掃描。{@code timeoutMs} 之後自動停並回呼 {@link Listener#onDone()}。 */
    public void start(Listener l, long timeoutMs) {
        this.listener = l;
        if (nsd == null) {
            l.onError("這個系統沒有 mDNS 服務（NsdManager 為 null）");
            return;
        }

        // ★ 一定要拿 MulticastLock。
        // 沒有它，WiFi 驅動會把 multicast 封包濾掉，NsdManager 一個服務都收不到，
        // 而且**不會報錯** —— 症狀就只是「掃不到東西」，非常難查。
        // （第一次實機測試就是這樣：兩個 discovery 都 started，然後 8 秒內 0 筆。）
        acquireMulticastLock();

        discover(SERVICE_CONNECT, false);
        discover(SERVICE_PAIRING, true);

        // 用 postDelayed 收尾：Android 沒有「掃描完成」的回呼，只能自己計時
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::stop, timeoutMs);
    }

    /** 停止掃描。可以重複呼叫。 */
    public void stop() {
        for (NsdManager.DiscoveryListener dl : listeners) {
            try {
                nsd.stopServiceDiscovery(dl);
            } catch (Exception ignored) {
                // 已經停了
            }
        }
        listeners.clear();
        synchronized (resolveQueue) {
            resolveQueue.clear();
        }
        releaseMulticastLock();
        if (listener != null) {
            listener.onDone();
            listener = null;
        }
    }

    private void acquireMulticastLock() {
        if (wifi == null || multicastLock != null) return;
        try {
            multicastLock = wifi.createMulticastLock("omniadb-mdns");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            DiagLog.i(TAG, "已取得 MulticastLock");
        } catch (Exception e) {
            DiagLog.e(TAG, "取得 MulticastLock 失敗", e);
            multicastLock = null;
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock == null) return;
        try {
            if (multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {
            // 釋放失敗沒有補救手段
        }
        multicastLock = null;
    }

    // ------------------------------------------------------------------ 內部

    private void discover(String serviceType, boolean pairing) {
        NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String type) {
                DiagLog.i(TAG, "開始掃描 " + type);
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                DiagLog.i(TAG, "發現服務 " + info.getServiceName() + " / " + info.getServiceType());
                enqueue(info, pairing);
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
                // 不處理：清單由 MainActivity 自己決定要不要保留
            }

            @Override
            public void onDiscoveryStopped(String type) {
                DiagLog.i(TAG, "停止掃描 " + type);
            }

            @Override
            public void onStartDiscoveryFailed(String type, int errorCode) {
                DiagLog.e(TAG, "掃描啟動失敗 " + type + "，錯誤碼 " + errorCode);
                if (listener != null) listener.onError("掃描啟動失敗（錯誤碼 " + errorCode + "）");
            }

            @Override
            public void onStopDiscoveryFailed(String type, int errorCode) {
                DiagLog.e(TAG, "掃描停止失敗 " + type + "，錯誤碼 " + errorCode);
            }
        };
        listeners.add(dl);
        try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, dl);
        } catch (Exception e) {
            DiagLog.e(TAG, "無法啟動掃描 " + serviceType, e);
        }
    }

    private void enqueue(NsdServiceInfo info, boolean pairing) {
        synchronized (resolveQueue) {
            resolveQueue.add(info);
        }
        pairingFlags.put(info.getServiceName() + "/" + info.getServiceType(), pairing);
        resolveNext();
    }

    /** 記住每個服務是「連線埠」還是「配對埠」。 */
    private final java.util.Map<String, Boolean> pairingFlags = new java.util.HashMap<>();

    private void resolveNext() {
        NsdServiceInfo next;
        synchronized (resolveQueue) {
            if (resolving) return;
            next = resolveQueue.poll();
            if (next == null) return;
            resolving = true;
        }

        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    DiagLog.w(TAG, "解析失敗 " + info.getServiceName() + "，錯誤碼 " + errorCode);
                    done();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo info) {
                    emit(info);
                    done();
                }

                private void done() {
                    synchronized (resolveQueue) {
                        resolving = false;
                    }
                    resolveNext();
                }
            });
        } catch (Exception e) {
            DiagLog.e(TAG, "resolveService 失敗", e);
            synchronized (resolveQueue) {
                resolving = false;
            }
        }
    }

    private void emit(NsdServiceInfo info) {
        String host = info.getHost() != null ? info.getHost().getHostAddress() : null;
        int port = info.getPort();
        if (host == null || port <= 0) return;

        // Android 9 之前會回 IPv6 link-local，帶 %wlan0 尾巴，這裡統一去掉
        int pct = host.indexOf('%');
        if (pct > 0) host = host.substring(0, pct);
        // IPv6 位址暫時不支援（ADB TLS 走 IPv4 最穩）
        if (host.contains(":")) return;

        Boolean pairing = pairingFlags.get(info.getServiceName() + "/" + info.getServiceType());
        Found f = new Found(info.getServiceName(), host, port, pairing != null && pairing);
        DiagLog.i(TAG, "解析成功 " + host + ":" + port + (f.pairing ? "（配對埠）" : "（連線埠）"));

        Listener l = listener;
        if (l != null) l.onFound(f);
    }
}
