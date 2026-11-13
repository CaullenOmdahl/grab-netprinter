// GrabNetPrinter — Gradle settings.
//
// Gradle is OPTIONAL and only here for IDE convenience (Android Studio).
// The authoritative, fully-offline build is tools/build.sh (aapt2 + javac +
// d8 + zipalign + apksigner); it needs no Gradle, no network and no wrapper.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GrabNetPrinter"
include(":app")
