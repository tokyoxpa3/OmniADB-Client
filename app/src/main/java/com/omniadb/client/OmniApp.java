package com.omniadb.client;

import android.app.Application;

/**
 * 只做一件事：把 {@link DiagLog} 的目錄準備好。
 *
 * <p>為什麼不能等 Activity 起來再初始化：連線、鏡像、解碼那些 log 全部發生在
 * Activity 之後的背景執行緒，但<b>金鑰載入</b>（{@code AdbClient.SimpleAdbManager}）
 * 與 provider 初始化可能在很早的階段就出事，那正是最需要日誌的時候。
 * Application.onCreate 是唯一保證比所有程式碼都早的點。
 */
public class OmniApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        DiagLog.init(this);
        DiagLog.i("OmniADB-Client", "啟動 v" + versionName());
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
