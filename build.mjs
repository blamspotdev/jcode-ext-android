// Production build for the Android Dev Pack.
//
// What ships is one file: `lib/android-pack.apk` — the pack's native half, loaded on demand into
// JCode's process by `NativeExtensionLoader`. `jext pack` runs this (npm run build) before
// packaging, so packing the extension is enough to produce it — by hand or in CI.
//
// **An APK, and no longer a bare dex.** It used to be `lib/designer.dex`, taken out of the merge
// task rather than the archive, because the layout designer resolved no resources at all — it parses
// layout XML itself and builds views in code — and the archive around its dex was an empty resource
// table along for the ride. The virtual device moved into this same module and is not like that: its
// status bar, its quick-settings icons and its permission prompt are real drawables, the ids its
// views carry are what `uiautomator dump` reports, and the device's own system apps ship as assets.
// All of that needs a resource table, and a table needs an archive for `addAssetPath` to attach.
//
// The APK is taken unsigned and never installed as an app. JCode verifies the *extension*, not this.
//
// `lib/` is gitignored and `native/` is in `.jextignore`: the archive is rebuilt per release rather
// than committed, and the module that builds it stays out of the package. Without this script CI
// packed neither, and the pack failed to load with "native entry is missing".
import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const win = process.platform === 'win32';
// Absolute, because a bare `gradlew.bat` is not found in the working directory the way `./gradlew`
// is on a POSIX shell, and the two platforms disagree about which relative spelling works.
const gradlew = resolve('native', win ? 'gradlew.bat' : 'gradlew');
const APK = 'native/build/outputs/apk/release/jcode-android-native-release-unsigned.apk';

const build = spawnSync(gradlew, ['assembleRelease'], {
  cwd: 'native',
  stdio: 'inherit',
  shell: win,
});
if (build.status !== 0) process.exit(build.status || 1);

mkdirSync('lib', { recursive: true });
copyFileSync(APK, 'lib/android-pack.apk');

console.log('✓ built native/ → lib/android-pack.apk');
