plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The New Android Project gallery: pick a template by looking at it, configure it, scaffold it.
 *
 * Its own library for the same reason the others are: this is the surface somebody touches once per
 * project and never again, and it has no business being re-dexed when the SDK manager changes.
 */
android {
    namespace = "dev.jcode.ext.android.newproject"
    defaultConfig {
        minSdk = 26
        applicationId = "dev.jcode.ext.android.newproject"
    }
    sourceSets {
        getByName("main") {
            java.srcDirs("java")
            res.srcDirs("res")
            assets.srcDirs("assets")
        }
    }
}
