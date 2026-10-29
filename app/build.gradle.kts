plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.lite.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.lite.launcher"
        minSdk = 29            // Android 10+
        targetSdk = 34         // на 35 включается принудительный edge-to-edge, код под него не адаптирован
        versionCode = 1
        versionName = "0.1"
    }

    // Свой ключ берётся из переменных окружения (в CI они приходят из GitHub Secrets)
    val keystorePath: String? = System.getenv("KEYSTORE_FILE")

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")   // запасной вариант, чтобы APK всегда можно было установить
            }
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

// Зависимостей нет: ни AndroidX, ни Material, ни Compose.
