plugins {
    id("com.android.application")
}

android {
    namespace = "com.omniadb.host"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omniadb.host"
        minSdk = 26
        targetSdk = 34
        // 1.1.0：中英雙語（依系統語系自動切換，中文含繁簡）。狀態文字改成
        //        語系無關的代碼，顯示時才翻譯，切換語言不用重啟服務。
        // 1.0.1：修掉桌面圖示右上角永遠掛著「1」的角標（常駐通知改用新頻道
        //        adb_always_on_v2，showBadge=false）。舊頻道行為鎖死改不掉，
        //        只能換 ID，所以 versionCode 也一起往上，方便確認裝到的是新版。
        versionCode = 3
        versionName = "1.1.0"
    }

    // 固定的 release 簽章金鑰（keystore 放 repo 外的 keys/，路徑與密碼由
    // 根目錄的 keystore.properties 指定；該檔與 *.jks 都已 gitignore）。
    //
    // 為什麼不能沿用 debug 金鑰：`~/.android/debug.keystore` 是「每個 Windows 帳號一份」，
    // 換帳號／換機器建出來的 APK 簽章就不同，舊版裝機上之後 `install -r` 一律
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE。已經發過 1.1.0，這條路不能再走。
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
    implementation(libs.libadb)
    implementation(libs.conscrypt)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.androidx.annotation)
}
