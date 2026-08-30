plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The Android SDK manager: a table over `sdkmanager --list`, drawn entirely from JCode's own design
 * system, so it owns no resources and ships as a bare `.dex`.
 *
 * The smallest of the three by a wide margin, and the one that made the single-archive arrangement
 * hardest to live with: editing 1.4k lines here re-dexed 26k.
 */
android {
    namespace = "dev.jcode.ext.android.sdkmanager"
    defaultConfig {
        minSdk = 26
        applicationId = "dev.jcode.ext.android.sdkmanager"
    }
    sourceSets {
        getByName("main") { java.srcDirs("java") }
    }
}
