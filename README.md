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

## Testing in the device sandbox

J Code can run a built APK in an editor tab, on a virtual device it hosts itself.
Nothing is installed, no phone has to be paired, and the app it runs is the same
debug APK Gradle just produced.

Turn it on once in **Settings → Environment → "Run in a virtual device"**. After
that, **Add run config** on a Gradle project offers **"Run in a virtual device"**
next to **"Run on this device"** — J Code detects the app module itself, so this
pack contributes no run preset of its own. Pick it, and when the build finishes
the APK opens in a **Device sandbox** tab. You can also open the tab from the
Run panel and paste an APK path in by hand.

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
