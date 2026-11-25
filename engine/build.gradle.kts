// Sideload-only engine for Watcher: it needs an Android Context to list and close other apps, which a
// Light SDK tool may not touch. Light's plugin doesn't police project dependencies, and Light's builder
// never sees this module.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.sevgjan.watcher.engine"
    compileSdk = rootProject.ext["compileSdk"] as Int
    defaultConfig { minSdk = rootProject.ext["minSdk"] as Int }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
        targetCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(rootProject.ext["jvmTarget"] as String))
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines)
    // The SDK's glyphs (phone, camera, map, …) for the clock shortcuts.
    implementation(project(":sdk:ui"))
    // Talks to the phone's own debug bridge (adbd) over loopback, as `adb shell` does from a Mac.
    implementation("dev.mobile:dadb:1.2.10")
}
