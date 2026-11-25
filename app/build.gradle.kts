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
        versionCode = 9
        versionName = "1.4.2"

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
        jniLibs {
            // FFmpeg 的 so 体积大，压缩存放、安装时解压，避免 APK 直接涨到 35MB。
            // 32/64 位都保留，老机型仍能安装（32 位机上转换功能会给出提示）。
            useLegacyPackaging = true
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

    // 本地 M3U8 / MP4 扫描模块：列表 + SAF 目录访问
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.core:core-ktx:1.13.1")

    // m3u8 -> mp4 无损封装转换（不重编码，画质不变、体积不增）。
    // 原 com.arthenica 包已下架，用社区维护的 fork；min 版只保留系统编解码器，
    // 但 hls/mpegts 解复用、mp4 复用、faststart、file/http(s) 协议齐全，够 remux 用。
    // LGPL-3.0，见仓库根目录 THIRD_PARTY_NOTICES.md。
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-min:8.1.7")
    // min 版 AAR 未内嵌 smart-exception，缺它会在加载 FFmpegKit 时报 NoClassDefFoundError
    implementation("com.arthenica:smart-exception-java:0.2.1")
}
