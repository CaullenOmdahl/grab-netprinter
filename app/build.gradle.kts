// GrabNetPrinter — Xposed/LSPosed module APK (IDE convenience only).
//
// tools/build.sh is the authoritative build. This file exists so Android
// Studio can open/index the project; it requires network access to download
// the Android Gradle Plugin, which tools/build.sh does not.
//
// Notes:
//  * The Xposed API v82 jar is vendored at libs/xposed-api-82.jar and is
//    compileOnly — it is NOT packaged into the APK (LSPosed provides it at
//    runtime). tools/fetch-api.sh re-downloads/verifies it if missing.
//  * app/src/main/AndroidManifest.xml keeps the historical `package=`
//    attribute because aapt2 (manual build) takes the APK package name from
//    it; modern AGP may warn about it, which is harmless here.
plugins {
    id("com.android.application")
}

android {
    namespace = "dev.blacksheep.grabnetprinter"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.blacksheep.grabnetprinter"
        minSdk = 27
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        // No launcher activity: this is a headless Xposed module.
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // assets/xposed_init must end up at assets/xposed_init inside the APK.
    sourceSets["main"].assets.srcDirs("src/main/assets")
}

dependencies {
    // compileOnly: provided by LSPosed at runtime, never packaged.
    compileOnly(files("libs/xposed-api-82.jar"))
}
