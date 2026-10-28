plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.darkknightsoul.tvlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.darkknightsoul.tvlauncher"
        // На телевизоре Android 7.0 (API 24)
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Сжимаем код и ресурсы: на ТВ всего ~800 МБ памяти, чем меньше — тем лучше
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Подписываем отладочным ключом — приложение только для своего телевизора
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
