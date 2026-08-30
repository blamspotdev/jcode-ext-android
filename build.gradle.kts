plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}

/**
 * The Android Dev Pack's native half: three archives, each loaded into JCode's own process on demand.
 *
 * The Gradle root is here, at the repository root, so `native/` contains the three module directories
 * and nothing else. `build-libs/` holds JCode's jars -- named apart from `lib/`, which holds the
 * payloads that ship, because one letter between "the jars we compile against" and "the archives we
 * publish" is a mistake waiting to happen.
 *
 * `:designer` draws layouts, `:sdkmanager` manages the Android SDK, `:vdevice` is the container that
 * runs a built APK inside JCode. They were one module until JCode's `entry.native` became a list --
 * before that the manifest allowed one entry per extension, so one archive with one dispatching
 * entry class was the shape the platform gave. It cost more than it looked: the `:guest` process
 * loaded the designer and the SDK manager to reach the container, ConstraintLayout rode along for
 * everyone though only the designer inflates it, `minSdk 33` was imposed by the container on two
 * modules that do not need it, and editing 1.4k lines of SDK manager re-dexed 26k.
 *
 * **The dependency rules are the ABI.** Anything JCode already ships is `compileOnly`: a module must
 * resolve those classes from JCode at runtime, because the composition it returns is spliced into
 * JCode's own and two Compose runtimes in one process do not interoperate. Anything JCode does NOT
 * ship may be bundled -- and must be, since nothing else will provide it. Three class loaders change
 * nothing here: all three parent on JCode's, so they share one Compose.
 *
 * `targetSdk` is deliberately absent everywhere. The container is coupled to one -- the hidden
 * members it is built on are greylisted at 33 and would be denied at a higher one -- but these
 * archives are never installed as apps, so the `targetSdk` that governs the process is **JCode's**.
 * Setting one here would look like a guarantee it cannot make.
 */
subprojects {
    // Everything below hangs off the plugin being applied: a `dependencies` block evaluated before
    // that has no `compileOnly` configuration to add to, which is a "Configuration with name
    // 'compileOnly' not found" at configuration time rather than anything about this pack.
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.gradle.internal.dsl.BaseAppModuleExtension>("android") {
            compileSdk = 36

            defaultConfig {
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

            buildFeatures.compose = true
        }

        // Pinned to what JCode actually RESOLVES, not to the BOM it declares. Those differ: JCode's
        // compose-bom names foundation 1.7.6, and material3-adaptive drags the whole compose group up
        // to 1.9.0 -- so a pack that trusted the BOM alone compiled against an older API than the one
        // it runs on. FlowRow is where that surfaced (experimental at 1.7.6, stable at 1.9.0), and it
        // would have surfaced far less pleasantly as a NoSuchMethodError somewhere else.
        //
        // Re-check these against `./gradlew :app:dependencies` whenever JCode's own versions move.
        dependencies {
            // JCode's own, resolved from JCode at runtime. `ext-api` is the published extension
            // contract; `core-design` and `core-distro` are JCode internals this pack reaches into,
            // and it is allowed to because it is a first-party pack released alongside JCode and
            // refused by `entry.native[].abi` when it is stale. A third-party extension gets
            // `ext-api` and nothing else.
            add("compileOnly", rootProject.files("build-libs/jcode-ext-api-abi9.jar"))
            add("compileOnly", rootProject.files("build-libs/jcode-core-design.jar"))
            add("compileOnly", rootProject.files("build-libs/jcode-core-distro.jar"))

            add("compileOnly", "androidx.compose.ui:ui:1.9.0")
            add("compileOnly", "androidx.compose.foundation:foundation:1.9.0")
            add("compileOnly", "androidx.compose.runtime:runtime:1.9.0")
            add("compileOnly", "androidx.compose.material3:material3:1.3.1")
            add("compileOnly", "androidx.compose.material:material-icons-extended:1.7.6")
            add("compileOnly", "androidx.core:core-ktx:1.15.0")
            add("compileOnly", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
        }
    }

    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension>("kotlin") {
            jvmToolchain(21)
        }
    }
}
