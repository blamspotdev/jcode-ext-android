plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The virtual device: the container that runs a built APK inside JCode.
 *
 * An `.apk`, unlike its two neighbours: the status bar, the quick-settings icons and the permission
 * prompt are real drawables, the ids its views carry are what `uiautomator dump` reports and an
 * agent addresses, and the device's own system apps ride along as assets. All of that needs a
 * resource table, and a table needs an archive for `addAssetPath` to attach.
 *
 * `minSdk 33` is real here and only here: the container is built on hidden members that are
 * greylisted at 33 and would be denied above it.
 */
android {
    // Deliberately the pack's old namespace, not `….vdevice`: this module owns the resources, so it
    // owns `R`, and its views import `dev.jcode.ext.android.R` today. Renaming it would be a rename
    // of every drawable reference for nothing -- the two resource-free modules have no R to collide
    // with, so there is no clash to resolve.
    namespace = "dev.jcode.ext.android"
    defaultConfig {
        minSdk = 33
        applicationId = "dev.jcode.ext.android.vdevice"
    }
    sourceSets {
        getByName("main") {
            java.srcDirs("java")
            aidl.srcDirs("aidl")
            res.srcDirs("res")
            assets.srcDirs("assets")
        }
    }
    // The wire between the pack's IDE half and its `:guest` half. Both ends are this module's, so the
    // interface lives here rather than in JCode -- the app's stub passes an IBinder through and never
    // looks inside it.
    buildFeatures.aidl = true
}

dependencies {
    compileOnly("androidx.appcompat:appcompat:1.7.0")
    compileOnly("androidx.activity:activity-compose:1.10.0")
    compileOnly("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
}
