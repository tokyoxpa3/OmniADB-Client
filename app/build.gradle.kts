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
        // 0.5.1：設置選單「畫質」與「音訊」改成相鄰（保持常亮往上挪一格）；
        //        README 補上六張截圖、主圖縮小到 240px
        // 0.5.0：首個 GitHub 公開發布版本。連線時不再自動「補 play」（暫停中的
        //        播放器不會自己開播）；靜音↔有聲的切換照舊補。
        //        省電模式下「保持常亮」灰掉並改標題
        // 0.4.7：（內部版本，併入 0.5.0）
        // 0.4.6：錄影（碼流直封裝成 MP4，含 AAC 音軌）＋開場前先鎖對方方位（不再「先橫後豎」）
        // 0.4.5：遠端指令不再猜秒數（心跳 + 閒置逾時）、進度框顯示已等待秒數
        // 0.4.4：剪貼簿（複製／剪下／貼上，含同側搬移）＋ 撞名確認（覆蓋／兩份都留）
        // 0.4.3：列表縮圖（本機問系統、遠端抓回來解，兩層快取，只在可見時做）
        // 0.4.2：檔案總管易用性（點＝開啟、長按＝多選、搜尋、可點麵包屑、類型圖示、目的地）
        // 0.4.0：檔案總管（SYNC 協定：列目錄／雙向傳檔）＋ 對方畫面截圖
        // 0.3.4：對方實體螢幕可關／開（設置選單），離線前自動開回
        // 0.3.3：工具列收成七顆（旋轉／跟隨／音量／電源進「設置」）、換新圖示
        versionCode = 19
        versionName = "0.5.1"

        // 實測用逃生門：`./gradlew assembleDebug -PdevSuffix` 會把包名改成
        // com.omniadb.client.dev，可以和手機上已經裝好的正式包並存。
        //
        // 為什麼需要：debug 簽章是「每台開發機一份」的（~/.android/debug.keystore）。
        // 手機上那顆若是別的帳號／別台機器裝的，`adb install -r` 一律
        // INSTALL_FAILED_UPDATE_INCOMPATIBLE，`pm uninstall -k` 也救不了
        // （保留的套件記錄連簽章一起留著）。改包名是唯一不動使用者資料的辦法。
        if (project.hasProperty("devSuffix")) {
            applicationIdSuffix = ".dev"
        }
    }

    // 固定的 release 簽章金鑰（keystore 放 repo 外的 keys/，路徑與密碼由
    // 根目錄的 keystore.properties 指定；該檔與 *.jks 都已 gitignore）。
    //
    // 為什麼不能沿用 debug 金鑰：`~/.android/debug.keystore` 是「每個 Windows 帳號一份」，
    // 換帳號／換機器建出來的 APK 簽章就不同。手機上那顆若是別的帳號裝的，之後
    // `adb install -r` 一律 INSTALL_FAILED_UPDATE_INCOMPATIBLE；就算使用者手動按 APK，
    // 小米也會擋成「應用程式套件與現有套件衝突」（178 上真的踩過）。
    //
    // keystore.properties 不存在時（別人 clone、CI 沒塞 secret）退回 debug 金鑰，
    // 保證還是建得起來 —— 但那種產物**不要發佈**。
    val keystorePropsFile = rootProject.file("keystore.properties")
    val hasReleaseKey = keystorePropsFile.exists()
    if (hasReleaseKey) {
        // 手寫解析，不用 java.util.Properties：在 build script 裡 `java` 會被
        // JavaPluginExtension 遮蔽，`java.util.Properties()` 編不過。
        val props = keystorePropsFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(props.getValue("storeFile"))
                storePassword = props.getValue("storePassword")
                keyAlias = props.getValue("keyAlias")
                keyPassword = props.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有固定金鑰就用它（正式發佈走這條）；沒有才退回 debug。
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
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
