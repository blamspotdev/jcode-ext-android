plugins {
    id("com.android.application") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

/**
 * The Android layout designer, loaded into JCode's own process as a bare dex.
 *
 * What ships is `classes.dex`, not the APK the build produces around it. The designer resolves no
 * resources at all — it parses layout XML itself, builds views programmatically and applies
 * constraints through ConstraintSet's code API — so there is no resource table for JCode's
 * `addAssetPath` to attach, and the 25 KB one an APK carries was along for the ride. A plugin that
 * DID own drawables or strings would still need the archive. Nothing here is ever installed as an
 * app.
 *
 * **The dependency rules are the ABI.** Anything JCode already ships is `compileOnly`: the plugin
 * must resolve those classes from JCode at runtime, because the composition it returns is spliced
 * into JCode's own and two Compose runtimes in one process do not interoperate. Anything JCode does
 * NOT ship may be bundled — and must be, since nothing else will provide it.
 */
android {
    namespace = "dev.jcode.ext.android.designer"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        // Never installed as an app; this only names the archive.
        applicationId = "dev.jcode.ext.android.designer"
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
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // JCode's, resolved from JCode at runtime. Versions must match what JCode ships — see the
    // extension's README for the pinned set. compileOnly is load-bearing, not tidiness.
    compileOnly(files("libs/jcode-ext-api-abi4.jar"))
    compileOnly(platform("androidx.compose:compose-bom:2025.01.00"))
    compileOnly("androidx.compose.ui:ui")
    compileOnly("androidx.compose.foundation:foundation")
    compileOnly("androidx.compose.material3:material3")
    compileOnly("androidx.compose.material:material-icons-extended")
    compileOnly("androidx.core:core-ktx:1.15.0")
    compileOnly("androidx.appcompat:appcompat:1.7.0")

    // NOT shipped by JCode (it uses Compose Material3, not the View libraries), so these are bundled
    // and are what lets the canvas inflate a real ConstraintLayout instead of drawing a box that
    // looks like one. androidx.core / appcompat are excluded deliberately: they ARE in JCode, and a
    // second copy in this APK is the duplicate-R hazard JCode's GuestLoader documents.
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") {
        exclude(group = "androidx.appcompat")
        exclude(group = "androidx.core")
    }

    // The parsers are plain Kotlin and are the part most likely to be wrong on input nobody
    // anticipated, so they are tested on the JVM rather than by deploying to a device and looking.
    //
    // `compileOnly` does not reach the test compile classpath, and the Compose compiler plugin
    // refuses to run without the runtime in front of it — so the same set is repeated here. These
    // are test-only and never enter the APK, so the ABI rule above is not weakened by them.
    testImplementation(kotlin("test"))
    testImplementation(files("libs/jcode-ext-api-abi2.jar"))
    testImplementation(platform("androidx.compose:compose-bom:2025.01.00"))
    testImplementation("androidx.compose.runtime:runtime")
    testImplementation("androidx.compose.ui:ui")
    testImplementation("androidx.compose.foundation:foundation")
    testImplementation("androidx.compose.material3:material3")
    testImplementation("androidx.compose.material:material-icons-extended")
    testImplementation("androidx.core:core-ktx:1.15.0")
    testImplementation("androidx.appcompat:appcompat:1.7.0")
}
