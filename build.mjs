// Production build for the Android Dev Pack.
//
// What ships is one file: `lib/designer.dex`, the layout designer's own code, loaded on demand into
// JCode's process by `NativeExtensionLoader`. `jext pack` runs this (npm run build) before packaging,
// so packing the extension is enough to produce it — by hand or in CI.
//
// The dex is taken from the merge task's output rather than unzipped back out of the APK the Android
// plugin builds around it: the designer resolves no resources, so there is no resource table for
// JCode to attach and nothing else in that archive is worth keeping.
//
// `lib/` is gitignored and `designer/` is in `.jextignore`: the dex is rebuilt per release rather
// than committed, and the module that builds it stays out of the package. Without this script CI
// packed neither, and the designer failed to load with "native entry is missing".
import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const win = process.platform === 'win32';
// Absolute, because a bare `gradlew.bat` is not found in the working directory the way `./gradlew`
// is on a POSIX shell, and the two platforms disagree about which relative spelling works.
const gradlew = resolve('designer', win ? 'gradlew.bat' : 'gradlew');
const DEX = 'designer/build/intermediates/dex/release/mergeDexRelease/classes.dex';

const build = spawnSync(gradlew, ['assembleRelease'], {
  cwd: 'designer',
  stdio: 'inherit',
  shell: win,
});
if (build.status !== 0) process.exit(build.status || 1);

mkdirSync('lib', { recursive: true });
copyFileSync(DEX, 'lib/designer.dex');

console.log('✓ built designer/ → lib/designer.dex');
