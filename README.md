# J Code — Android Dev Pack

A combined **dev pack** for Android development in J Code. It adds language
support for **Android XML** (layouts and `AndroidManifest.xml`) and **Gradle**
(Groovy `.gradle`) build files — coloring, as-you-type completions, and snippet
helpers — plus a scaffoldable **`android-app`** project template.

Kotlin itself is not re-implemented here: this pack **requires the Kotlin Language
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

See `extension.yaml` for the language manifest and
`templates/android-app/template.yaml` for the scaffold recipe.
