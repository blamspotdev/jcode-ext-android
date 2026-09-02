# The virtual device's own apps

The guests the virtual device is built from, and the guests it is tested with. Sources only — what
ships is the APK each one produces, bundled at `native/vdevice/assets/vdevice/` and reinstalled into
every device on every start.

They came from JCode's own repository (`tools/`), where they stayed when the device itself moved into
this pack at 1.7.0. That left the artifact here and the source that produced it a repository away, so
rebuilding one meant copying the result across a boundary that nothing else in either project
crossed. They are now beside the module that ships them.

## What is here

| Directory | Ships as | What it is |
|---|---|---|
| `browser/` | `browser.apk` | The device's browser — the app that makes a device usable |
| `camera/` | `camera.apk` | Answers `ACTION_IMAGE_CAPTURE`/`ACTION_VIDEO_CAPTURE`, drawn from the simulated sensors |
| `files/` | `files.apk` | The device's file manager, over its own storage |
| `hardware/` | `hardware.apk` | Prints what a guest can see of every simulated capability — the hardware regression test |
| `keyboard/` | `keyboard.apk` | The device's IME |
| `launcher/` | `launcher.apk` | The device's home screen |
| `settings/` | `settings.apk` | Changes real device settings through `VirtualSettingsProvider` |
| `fixtures/` | — | Guests used to test the container; not bundled |

`fixtures/` holds one app per thing worth proving: `appcompat` and `compose` for the two UI toolkits
the container has to satisfy, `lifecycle` for the callback order, `gl` for a rendered surface, `midi`
and `notification` for the services, and `guest` for the plain case. They are built and installed by
hand when something needs checking, which is why none of them is in the assets directory.

## Building one

Each directory's README carries its own build — `javac`, `aapt2`, `d8`, `zipalign`, `apksigner`,
no Gradle, because these are single-activity apps and a Gradle module each would cost more than it
returned. `fixtures/appcompat` and `fixtures/compose` are the exceptions and are ordinary Gradle
builds, since the toolkits they exist to exercise expect one.

A rebuilt system app is not picked up until its APK replaces the bundled copy and the pack is rebuilt
(`npm run build`); the `Copy-Item` line at the end of each README is that step. Gradle has been seen
to miss the asset when a new APK is byte-for-byte the same size as the old one — touch it if a
rebuild does not reach the device.

Nothing here is packaged into the `.jext`: `vdevice-apps` is in `.jextignore`, the same way `native/`
is. What the extension ships is the built `lib/vdevice.apk`, with these APKs inside it as assets.
