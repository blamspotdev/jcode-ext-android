plugins {
    id("com.android.application") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

/**
 * The Android Dev Pack's native half, loaded into JCode's own process.
 *
 * Two things live here and they have nothing to do with each other except the pack that ships them:
 * the **layout designer** (`designer/`), and the **virtual device** (`vdevice/`) — the container that
 * runs a built APK inside JCode. They share one module because JCode's manifest allows one
 * `entry.native` per extension, so one archive with one entry class is the shape the platform gives.
 *
 * **What ships is the APK, not the dex it used to be.** The designer alone resolved no resources —
 * it parses layout XML itself and builds views in code — so a bare `classes.dex` was enough and the
 * archive around it was 25 KB of empty resource table. The device is not like that: its status bar,
 * its quick-settings icons and its permission prompt are real drawables, and the ids its views carry
 * are what `uiautomator dump` reports and an agent addresses. Those need a resource table, and a
 * table needs an archive for JCode's `addAssetPath` to attach.
 *
 * **The dependency rules are the ABI.** Anything JCode already ships is `compileOnly`: the plugin
 * must resolve those classes from JCode at runtime, because the composition it returns is spliced
 * into JCode's own and two Compose runtimes in one process do not interoperate. Anything JCode does
 * NOT ship may be bundled — and must be, since nothing else will provide it.
 *
 * `targetSdk` is deliberately absent. The container is coupled to one — the hidden members it is
 * built on are greylisted at 33 and would be denied at a higher one — but this archive is never
 * installed as an app, so the `targetSdk` that governs the process is **JCode's**, not this file's.
 * Setting one here would look like a guarantee it cannot make.
 */
android {
    namespace = "dev.jcode.ext.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        // Never installed as an app; this only names the archive.
        applicationId = "dev.jcode.ext.android"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // JCode does not minify either, and an obfuscated entry class cannot be found by name.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        // The wire between the pack's IDE half and its `:guest` half. Both ends are this pack's, so
        // the interface lives here rather than in JCode -- the app's stub passes an IBinder through
        // and never looks inside it.
        aidl = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // JCode's own, resolved from JCode at runtime. Versions must match what JCode ships -- see the
    // extension's README for the pinned set. compileOnly is load-bearing, not tidiness.
    //
    // Three jars rather than one. `ext-api` is the published extension contract; `core-design` and
    // `core-distro` are JCode internals this pack reaches into, and it is allowed to because it is a
    // first-party pack released alongside JCode and refused by `entry.native.abi` when it is stale.
    // A third-party extension gets `ext-api` and nothing else.
    //
    // Vendoring the design system instead was the alternative, and it was rejected: JCodeIcon
    // resolves through the user's chosen icon bundle, so a copied CompactContextMenu would quietly
    // stop following it and the device's menus would drift away from the rest of the IDE.
    compileOnly(files("libs/jcode-ext-api-abi8.jar"))
    compileOnly(files("libs/jcode-core-design.jar"))
    compileOnly(files("libs/jcode-core-distro.jar"))

    // Pinned to what JCode actually RESOLVES, not to the BOM it declares. Those differ: JCode's
    // compose-bom names foundation 1.7.6, and material3-adaptive drags the whole compose group up to
    // 1.9.0 -- so a pack that trusted the BOM alone compiled against an older API than the one it
    // runs on. FlowRow is where that surfaced (experimental at 1.7.6, stable at 1.9.0), and it would
    // have surfaced far less pleasantly as a NoSuchMethodError somewhere else.
    //
    // Re-check these against `./gradlew :app:dependencies` whenever JCode's own versions move.
    compileOnly("androidx.compose.ui:ui:1.9.0")
    compileOnly("androidx.compose.foundation:foundation:1.9.0")
    compileOnly("androidx.compose.runtime:runtime:1.9.0")
    compileOnly("androidx.compose.material3:material3:1.3.1")
    compileOnly("androidx.compose.material:material-icons-extended:1.7.6")
    compileOnly("androidx.activity:activity-compose:1.10.0")
    compileOnly("androidx.core:core-ktx:1.15.0")
    compileOnly("androidx.appcompat:appcompat:1.7.0")
    compileOnly("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // NOT shipped by JCode (it uses Compose Material3, not the View libraries), so this is bundled
    // and is what lets the designer's canvas inflate a real ConstraintLayout instead of drawing a box
    // that looks like one. androidx.core / appcompat are excluded deliberately: they ARE in JCode,
    // and a second copy in this archive is the duplicate-R hazard the guest loader documents.
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") {
        exclude(group = "androidx.appcompat")
        exclude(group = "androidx.core")
    }

    // The designer's parsers are plain Kotlin and are the part most likely to be wrong on input
    // nobody anticipated, so they are tested on the JVM rather than by deploying to a device and
    // looking.
    //
    // `compileOnly` does not reach the test compile classpath, and the Compose compiler plugin
    // refuses to run without the runtime in front of it -- so the same set is repeated here. These
    // are test-only and never enter the archive, so the ABI rule above is not weakened by them.
    testImplementation(kotlin("test"))
    testImplementation(files("libs/jcode-ext-api-abi8.jar"))
    testImplementation("androidx.compose.runtime:runtime:1.9.0")
    testImplementation("androidx.compose.ui:ui:1.9.0")
    testImplementation("androidx.compose.foundation:foundation:1.9.0")
    testImplementation("androidx.compose.material3:material3:1.3.1")
    testImplementation("androidx.compose.material:material-icons-extended:1.7.6")
    testImplementation("androidx.core:core-ktx:1.15.0")
    testImplementation("androidx.appcompat:appcompat:1.7.0")
}
