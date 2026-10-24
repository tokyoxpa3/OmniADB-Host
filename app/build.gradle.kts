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
    implementation(libs.libadb)
    implementation(libs.conscrypt)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.androidx.annotation)
}
