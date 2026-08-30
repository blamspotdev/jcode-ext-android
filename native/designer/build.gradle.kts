plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The layout designer, shipped as a bare `.dex`.
 *
 * It resolves no resources -- it parses layout XML itself and builds views in code -- so an archive
 * around its dex would carry an empty resource table and nothing else.
 *
 * The dex that ships is the one inside this module's APK, not the one under `intermediates/`: AGP
 * keeps a bundled library's classes in a dex of their own until packaging, and ConstraintLayout is
 * bundled here. Taking the intermediate would drop it silently. See build.mjs.
 *
 * `minSdk` is the pack's floor rather than the container's 33: nothing here touches a greylisted
 * member, and inheriting 33 from a module it does not depend on was one of the costs of shipping
 * these three together.
 */
android {
    namespace = "dev.jcode.ext.android.designer"
    defaultConfig {
        minSdk = 26
        applicationId = "dev.jcode.ext.android.designer"
    }
    sourceSets {
        getByName("main") {
            java.srcDirs("java")
            res.srcDirs("res")
            assets.srcDirs("assets")
        }
        getByName("test") { java.srcDirs("test/java") }
    }
}

dependencies {
    // NOT shipped by JCode (it uses Compose Material3, not the View libraries), so this is bundled
    // and is what lets the canvas inflate a real ConstraintLayout instead of drawing a box that
    // looks like one. Bundled HERE only: the container and the SDK manager never inflate a View, and
    // carrying this for them was one of the costs of a single archive. androidx.core / appcompat are
    // excluded deliberately: they ARE in JCode, and a second copy is the duplicate-R hazard.
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") {
        exclude(group = "androidx.appcompat")
        exclude(group = "androidx.core")
    }
    compileOnly("androidx.appcompat:appcompat:1.7.0")

    // The parsers are plain Kotlin and are the part most likely to be wrong on input nobody
    // anticipated, so they are tested on the JVM rather than by deploying to a device and looking.
    //
    // `compileOnly` does not reach the test compile classpath, and the Compose compiler plugin
    // refuses to run without the runtime in front of it -- so the same set is repeated here. These
    // are test-only and never enter the archive, so the ABI rule is not weakened by them.
    testImplementation(kotlin("test"))
    testImplementation(rootProject.files("build-libs/jcode-ext-api-abi3.jar"))
    testImplementation("androidx.compose.runtime:runtime:1.9.0")
    testImplementation("androidx.compose.ui:ui:1.9.0")
    testImplementation("androidx.compose.foundation:foundation:1.9.0")
    testImplementation("androidx.compose.material3:material3:1.3.1")
    testImplementation("androidx.compose.material:material-icons-extended:1.7.6")
    testImplementation("androidx.core:core-ktx:1.15.0")
    testImplementation("androidx.appcompat:appcompat:1.7.0")
}
