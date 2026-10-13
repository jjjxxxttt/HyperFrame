plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.padlink"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "dev.padlink"
        minSdk = 29
        targetSdk = 35
        versionCode = 112
        versionName = "1.1.2"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 零第三方依赖：插帧已改为纯 GLES shader 自研（GSFG 风格，v0.29.0），
    // v0.27.0 的 ONNX Runtime AI 插帧原型（NNAPI 在 Android 15/新 Adreno 挂起卡死）已移除
}