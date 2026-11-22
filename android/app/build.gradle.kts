plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI run number becomes the version code, so each new APK installs over the previous one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.orangeflower.pet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.orangeflower.pet"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // A fixed, non-secret debug-style key (password "android") so CI builds can update each other.
    signingConfigs {
        create("shared") {
            storeFile = file("pet-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
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
