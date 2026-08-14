# JCode — Android Dev Pack

A combined **dev pack** for Android development in J Code. It adds language
support for **Android XML** (layouts and `AndroidManifest.xml`) and **Gradle**
(Groovy `.gradle`) build files — coloring, as-you-type completions, and snippet
helpers — plus a scaffoldable **`android-app`** project template.

Kotlin itself is not re-implemented here: this pack **requires the Kotlin Dev
Pack** (`jcode.lang.kotlin`), which provides `.kt` coloring, completions, and
formatting. J Code installs the dependency automatically.

Building an app also needs the **Android SDK prerequisites** (`android-prereqs`)
configured in the runtime — the JDK, Android SDK, and Gradle used by `./gradlew`.

## What you get

- **Android XML** — layout element and `android:` attribute coloring/completions,
  with full `activity_main.xml` and `AndroidManifest.xml` snippet helpers.
- **Gradle** — Android/Gradle DSL coloring/completions (`plugins`, `android`,
  `dependencies`, `defaultConfig`, `buildTypes`, …) with `android { }` and
  `dependencies { }` helpers.
- **`android-app` template** — scaffolds a minimal but real Kotlin Android app:
  root + `:app` Gradle module, `AndroidManifest.xml`, `MainActivity`, a
  `ConstraintLayout` screen, `strings.xml`, and a **Build APK** task
  (`./gradlew :app:assembleDebug`).
- **Build helpers** — one-tap Gradle tasks and a project check, below.

## Building a project you cloned

A repository written on a desktop assumes a desktop SDK, and none of the ways
that goes wrong here announce itself — the build fails deep inside resource
linking, or Gradle reports an SDK location that belongs to someone else's laptop.

**Add build task** on any Gradle project offers two helpers for exactly that:

- **Check this project builds here** — reports, changes nothing. It resolves the
  **effective** `compileSdk` (below) and compares it against the highest one this
  device's ARM-native `aapt2` can link against (recorded by the `android-sdk`
  toolchain in `jcode-compile-sdk.txt`), then checks `local.properties`, the
  Gradle wrapper and its jar, the `android.aapt2FromMavenOverride` that keeps AGP
  off its own x86_64 `aapt2`, the heap the project asks for against the RAM this
  device has, and the JDK in use.
- **Apply build fixes** — repairs what can be repaired locally: writes `sdk.dir`,
  brings the effective `compileSdk`/`targetSdk` **down** to the ceiling where they
  are above it, and restores `gradlew`'s exec bit. Every file it edits is copied
  to `.jcode/build-fix-backup-<timestamp>/` first and each change is printed, so
  `git diff` afterwards is the whole story.

### Which SDK the project actually uses

A build script rarely carries the number on its own line, so the check follows the
same order the script does rather than grepping for a digit:

1. a **Gradle property** the script reads — `findProperty("x.compileSdk")`,
   `providers.gradleProperty(...)` — looked up in the project's
   `gradle.properties`, then `~/.gradle/gradle.properties`;
2. the **version catalog** entry it falls back to
   (`libs.versions.compileSdk.get()` → `gradle/libs.versions.toml`);
3. a plain **literal** in the script.

This matters for the common shape where a project targets a current SDK for
release and overrides it locally:

```kotlin
compileSdk = (findProperty("app.compileSdk") as String?
    ?: libs.versions.compileSdk.get()).toInt()
```

Reading the catalog as if it were the answer would report a project that is
**already** correctly overridden as broken. So when the effective value is too
high and the script reads a property, the fix is that one line in
`gradle.properties` — the catalog keeps the value a release build uses, which
matters because Play rejects a lowered `targetSdk`. Only a project with a
hardcoded literal gets its build script edited; a catalog value with no property
to override it is reported as manual rather than quietly lowered.

Alongside them: **Assemble release APK**, **Bundle release AAB**, **Install debug
build**, **Run unit tests**, **Run lint** and **Clean**. `assembleDebug` is not
here because J Code detects it itself, per application module.

## Testing in the device sandbox

J Code can run a built APK in an editor tab, on a virtual device it hosts itself.
Nothing is installed, no phone has to be paired, and the app it runs is the same
debug APK Gradle just produced.

Turn it on once in **Settings → Environment → "Run in a virtual device"**. After
that, **Add run config** on a Gradle project offers **"Run in a virtual device"**
next to **"Run on a device"** — J Code detects the application modules itself and
lists one entry per module, so this pack contributes no run preset of its own.
Pick it, and when the build finishes the APK opens in a **Device sandbox** tab.
You can also open the tab from the Run panel and paste an APK path in by hand.

**"Run on a device"** goes through `adb` instead, and the Run panel's target row
says which device that is. Tap it to choose between the virtual device, this
phone, and anything else the runtime's adb server has connected; the choice is
remembered per project and exported as `ANDROID_SERIAL` for that launch.

J Code also serves its **own adbd**, so the sandbox is a normal `adb` target from
any terminal. `ANDROID_SERIAL` is already exported there, so no `-s` is needed:

```bash
adb devices -l     # 127.0.0.1:5620   device   model:JCode_vDevice
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.app/.MainActivity                       # into the tab
adb shell am start -n com.example.app/.MainActivity --windowingMode 1     # full screen
adb exec-out screencap -p > shot.png
```

That daemon answers `install`, `pm list packages`, `am start`, `screencap` and
`getprop`. **`logcat`, `push`/`pull` and `uninstall` are not served** — reach for
a real device or a full-screen run when you need them. Talking to it needs the
`adb` toolchain in the runtime; the tab itself does not.

### What a sandboxed guest gives up

Measured, not assumed — on an Odin2 (Android 13), same APK installed normally and
run in the sandbox:

- **Audio is indistinguishable from a real install.** `AudioTrack` reaches
  `PLAYING` with zero underruns, and sample rate, buffer size and the
  low-latency/pro feature flags come from the real hardware.
- **Manifest components other than the launched activity are never registered.**
  The platform discovers a `<service>` or `<provider>` by scanning *installed*
  packages, and a guest is by definition not one. A `MidiDeviceService` never
  appears; neither does a foreground service meant to outlive the screen. The
  guest still *reads* system lists fine — it just cannot contribute to them.
- **It inherits J Code's permissions and process.** The guest shares J Code's
  uid, so its own `<uses-permission>` entries are neither requested nor enforced.
  This is a preview, not a security boundary.
- **It cannot raise the soft keyboard itself** — use the tab's keyboard button.
- **It lays out against the phone's screen rather than the tab**, which can leave
  content past the edges.

So: build and iterate in the sandbox, then **run it full screen or install it for
real** before trusting anything that depends on services, providers, permissions,
or exact layout.

See `extension.yaml` for the language manifest and
`templates/android-app/template.yaml` for the scaffold recipe.
