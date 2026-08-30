// Production build for the Android Dev Pack.
//
// What ships is three archives under `lib/` — the pack's native half, loaded on demand into JCode's
// process by `NativeExtensionLoader`. `jext pack` runs this (npm run build) before packaging, so
// packing the extension is enough to produce them — by hand or in CI.
//
// **Three separate libraries, not one.** They were a single archive until JCode's `entry.native`
// became a list, and that cost more than it looked: the `:guest` process loaded the designer and the
// SDK manager to reach the container it wanted, ConstraintLayout rode along for all three though
// only the designer inflates it, and `minSdk 33` was imposed by the container on two modules that do
// not need it. Each builds its own dex now, and each owns its own `res/` and `assets/`.
//
// **Archives, not loose dex.** A bare `.dex` is classes and nothing else: no resource table for
// `addAssetPath` to attach and nowhere for assets to live. The container needs both — its status bar
// and permission prompt are real drawables, the ids its views carry are what `uiautomator dump`
// reports, and the device's own system apps ride along as ~700 KB of assets — and the other two are
// built so they can own the same without a packaging change.
//
// The APKs are unsigned and never installed as apps. JCode verifies the *extension*, not these.
//
// `lib/` is gitignored and the build tree is in `.jextignore`: the archives are rebuilt per release
// rather than committed, and the modules that build them stay out of the package. Without this
// script CI packed neither, and the pack failed to load with "native payload is missing".
import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const win = process.platform === 'win32';
// Absolute, because a bare `gradlew.bat` is not found in the working directory the way `./gradlew`
// is on a POSIX shell, and the two platforms disagree about which relative spelling works.
const gradlew = resolve(win ? 'gradlew.bat' : 'gradlew');

// Keyed by the `entry.native[].id` each one backs, so a module renamed here and not in
// extension.yaml is a mismatch somebody can see rather than a load failure at runtime.
const MODULES = {
  designer: 'native/designer/build/outputs/apk/release/designer-release-unsigned.apk',
  sdkmanager: 'native/sdkmanager/build/outputs/apk/release/sdkmanager-release-unsigned.apk',
  vdevice: 'native/vdevice/build/outputs/apk/release/vdevice-release-unsigned.apk',
};

const build = spawnSync(gradlew, ['assembleRelease'], { stdio: 'inherit', shell: win });
if (build.status !== 0) process.exit(build.status || 1);

mkdirSync('lib', { recursive: true });
for (const [id, apk] of Object.entries(MODULES)) {
  copyFileSync(apk, `lib/${id}.apk`);
  console.log(`✓ built native/${id} → lib/${id}.apk`);
}
