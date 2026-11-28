import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Shown version; CI reads it for the APK name and the release tag.
val appVersion = "1.3.0"
// CI run number becomes the version code, so each new APK installs over the previous one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// ---------------------------------------------------------------- offline TTS (sherpa-onnx + piper voice)
// Large binaries are downloaded at build time (checksum-pinned) instead of being committed.
val ttsCache = rootProject.file(".cache")
val sherpaVersion = "1.13.8"
val sherpaAar = File(ttsCache, "sherpa-onnx-$sherpaVersion.aar")
val voiceArchive = File(ttsCache, "vits-piper-zh_CN-huayan-medium.tar.bz2")
val ttsAssets = layout.buildDirectory.dir("generated/ttsAssets").get().asFile

fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

fun fetch(url: String, dest: File, sha: String) {
    if (dest.exists() && sha256(dest) == sha) return
    dest.parentFile.mkdirs()
    logger.lifecycle("Downloading $url")
    URI(url).toURL().openStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
    check(sha256(dest) == sha) { "Checksum mismatch for ${dest.name}" }
}

fetch(
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar",
    sherpaAar, "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96",
)
fetch(
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-huayan-medium.tar.bz2",
    voiceArchive, "dbdfec42b91d9cee31cce9ff4b3e9c305eb6fbf60546d071f7e46273554cce6b",
)
// Keep only what Chinese + English need: the model, tokens and espeak-ng data without other languages' dictionaries.
val voiceDir = File(ttsAssets, "tts")
if (!File(voiceDir, "model.onnx").exists()) {
    copy {
        from(tarTree(resources.bzip2(voiceArchive)))
        include("*/zh_CN-huayan-medium.onnx", "*/tokens.txt", "*/espeak-ng-data/**")
        exclude { it.name.endsWith("_dict") && it.name != "cmn_dict" && it.name != "en_dict" }
        eachFile {
            path = path.substringAfter('/')
            if (name == "zh_CN-huayan-medium.onnx") name = "model.onnx"
        }
        includeEmptyDirs = false
        into(voiceDir)
    }
}

android {
    namespace = "com.orangeflower.pet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.orangeflower.pet"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = appVersion
        ndk {
            // practically every phone in use is 64-bit ARM; keeps the speech engine small
            abiFilters += "arm64-v8a"
        }
    }

    sourceSets["main"].assets.srcDir(ttsAssets)

    packaging {
        // the JNI library only needs onnxruntime; the C/C++ API libraries are for other bindings
        jniLibs.excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
    }

    androidResources {
        noCompress += listOf("onnx")
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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(files(sherpaAar))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
