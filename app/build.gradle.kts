plugins {
    id("com.android.application")
}

android {
    namespace = "com.omniadb.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omniadb.client"
        minSdk = 26
        targetSdk = 34
        // 0.4.5：遠端指令不再猜秒數（心跳 + 閒置逾時）、進度框顯示已等待秒數
        // 0.4.4：剪貼簿（複製／剪下／貼上，含同側搬移）＋ 撞名確認（覆蓋／兩份都留）
        // 0.4.3：列表縮圖（本機問系統、遠端抓回來解，兩層快取，只在可見時做）
        // 0.4.2：檔案總管易用性（點＝開啟、長按＝多選、搜尋、可點麵包屑、類型圖示、目的地）
        // 0.4.0：檔案總管（SYNC 協定：列目錄／雙向傳檔）＋ 對方畫面截圖
        // 0.3.4：對方實體螢幕可關／開（設置選單），離線前自動開回
        // 0.3.3：工具列收成七顆（旋轉／跟隨／音量／電源進「設置」）、換新圖示
        versionCode = 15
        versionName = "0.4.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 用 debug 金鑰簽章，方便直接 sideload 安裝
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    // 上游 libadb（JitPack 1.0.1，未改動）。授權框由它自己送公鑰觸發，
    // 不需要任何補丁 —— 這點用實機驗證過（Android 13 + 5555 明文埠）。
    implementation(libs.libadb)
    implementation(libs.conscrypt)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
}
