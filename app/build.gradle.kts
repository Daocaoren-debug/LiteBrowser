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
        versionCode = 1
        versionName = "1.0.0"

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

    // 仅保留 zh 资源，进一步压缩包体（低端机省空间）
    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module")
        }
    }
}

// 刻意不引入 AndroidX / Material / 任何第三方库。
// 全部使用 android.* 系统框架，这是低端机适配的关键：APK 小、方法数少、启动快、无冲突。
dependencies {
    // no dependencies by design
}
