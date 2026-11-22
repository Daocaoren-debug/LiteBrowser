plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.litebrowser.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.litebrowser.app"
        // 最低支持 Android 8.0 (API 26)，覆盖绝大多数仍在服役的低端机
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.1"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module")
        }
    }
}

// 除播放内核外不引入任何依赖：界面、数据库、下载、嗅探全部用 android.* 系统框架。
// Media3(ExoPlayer) 是唯一例外 —— 系统 MediaPlayer 对 m3u8/fMP4 支持太差，
// 而"嗅探网页视频"场景里 m3u8 占大多数，只能请出 ExoPlayer。
dependencies {
    val media3 = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}
