plugins {
    id("com.android.application")
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
            kotlin.directories += "java"
            res.directories += "res"
            assets.directories += "assets"
        }
        getByName("test") { kotlin.directories += "test/java" }
    }
}

dependencies {
    // NOT shipped by JCode (it uses Compose Material3, not the View libraries), so this is bundled
    // and is what lets the canvas inflate a real ConstraintLayout instead of drawing a box that
    // looks like one. Bundled HERE only: the container and the SDK manager never inflate a View, and
    // carrying this for them was one of the costs of a single archive. androidx.core / appcompat are
    // excluded deliberately: they ARE in JCode, and a second copy is the duplicate-R hazard. So are
    // profileinstaller and annotation, which ConstraintLayout pulls in from 2.2 on and JCode already
    // has.
    implementation("androidx.constraintlayout:constraintlayout:2.2.2") {
        exclude(group = "androidx.appcompat")
        exclude(group = "androidx.core")
        exclude(group = "androidx.profileinstaller")
        exclude(group = "androidx.annotation")
    }
    compileOnly("androidx.appcompat:appcompat:1.7.0")

    // The parsers are plain Kotlin and are the part most likely to be wrong on input nobody
    // anticipated, so they are tested on the JVM rather than by deploying to a device and looking.
    //
    // `compileOnly` does not reach the test compile classpath, and the Compose compiler plugin
    // refuses to run without the runtime in front of it -- so the same set is repeated here. These
    // are test-only and never enter the archive, so the ABI rule is not weakened by them.
    //
    // The JUnit flavour of kotlin-test is named outright: the Kotlin plugin used to infer it from the
    // test task, and AGP's built-in Kotlin does not, which leaves `kotlin.test.Test` unresolved.
    testImplementation(kotlin("test-junit"))
    testImplementation(rootProject.files("build-libs/jcode-ext-api-abi3.jar"))
    testImplementation("androidx.compose.runtime:runtime:1.9.0")
    testImplementation("androidx.compose.ui:ui:1.9.0")
    testImplementation("androidx.compose.foundation:foundation:1.9.0")
    testImplementation("androidx.compose.material3:material3:1.3.1")
    testImplementation("androidx.compose.material:material-icons-extended:1.7.6")
    testImplementation("androidx.core:core-ktx:1.15.0")
    testImplementation("androidx.appcompat:appcompat:1.7.0")
}
