// Production build for the Android Dev Pack.
//
// What ships is three archives under `lib/` — the pack's native half, loaded on demand into JCode's
// process by `NativeExtensionLoader`. `jext pack` runs this (npm run build) before packaging, so
// packing the extension is enough to produce them — by hand or in CI.
//
// **Three, not one.** They were a single `android-pack.apk` until JCode's `entry.native` became a
// list, and that cost more than it looked: the `:guest` process loaded the designer and the SDK
// manager to reach the container, ConstraintLayout rode along for all three though only the designer
// inflates it, and `minSdk 33` was imposed by the container on two modules that do not need it.
//
// All three are archives even though two of them resolve no resources. A bare `classes.dex` was the
// obvious saving and is wrong for the designer, which bundles ConstraintLayout: a bundled library's
// classes land in a dex of their own, so shipping one dex would drop them silently, until the canvas
// tried to inflate a real ConstraintLayout. One rule for all three beats a per-module rule somebody
// has to re-derive whenever a dependency is added; the cost is an empty resource table apiece.
//
// The APKs are taken unsigned and never installed as apps. JCode verifies the *extension*, not these.
//
// `lib/` is gitignored and `native/` is in `.jextignore`: the archives are rebuilt per release rather
// than committed, and the modules that build them stay out of the package. Without this script CI
// packed neither, and the pack failed to load with "native payload is missing".
import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const win = process.platform === 'win32';
// Absolute, because a bare `gradlew.bat` is not found in the working directory the way `./gradlew`
// is on a POSIX shell, and the two platforms disagree about which relative spelling works.
const gradlew = resolve('native', win ? 'gradlew.bat' : 'gradlew');

// Keyed by the `entry.native[].id` each one backs, so a module renamed here and not in
// extension.yaml is a mismatch somebody can see rather than a load failure at runtime.
const MODULES = {
  designer: 'native/designer/build/outputs/apk/release/designer-release-unsigned.apk',
  sdkmanager: 'native/sdkmanager/build/outputs/apk/release/sdkmanager-release-unsigned.apk',
  vdevice: 'native/vdevice/build/outputs/apk/release/vdevice-release-unsigned.apk',
};

const build = spawnSync(gradlew, ['assembleRelease'], {
  cwd: 'native',
  stdio: 'inherit',
  shell: win,
});
if (build.status !== 0) process.exit(build.status || 1);

mkdirSync('lib', { recursive: true });
for (const [id, apk] of Object.entries(MODULES)) {
  copyFileSync(apk, `lib/${id}.apk`);
  console.log(`✓ built native/${id} → lib/${id}.apk`);
}
