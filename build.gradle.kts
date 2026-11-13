// GrabNetPrinter — root Gradle build (IDE convenience only).
//
// The real build is tools/build.sh, which uses the Android SDK build-tools
// directly and works completely offline. Do not make Gradle a build
// dependency: the POS/tooling environment may not have a Gradle wrapper
// distribution or network access.
plugins {
    id("com.android.application") version "8.5.2" apply false
}
