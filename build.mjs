// Production build for the Android Dev Pack.
//
// What ships is three payloads under `lib/` — the pack's native half, loaded on demand into JCode's
// process by `NativeExtensionLoader`. `jext pack` runs this (npm run build) before packaging, so
// packing the extension is enough to produce them — by hand or in CI.
//
// **Three, not one.** They were a single archive until JCode's `entry.native` became a list, and
// that cost more than it looked: the `:guest` process loaded the designer and the SDK manager to
// reach the container it wanted, ConstraintLayout rode along for all three though only the designer
// inflates it, and `minSdk 33` was imposed by the container on two modules that do not need it.
//
// **Two dex and one archive**, decided by what each module actually owns rather than by one rule:
//
//   designer     a bare `.dex`. It resolves no resources — it parses layout XML itself and builds
//                views in code — so its resource table is empty and an archive around it carries
//                25 KB of nothing.
//   sdkmanager   the same: a table drawn entirely from JCode's own design system.
//   vdevice      an `.apk`, and it has to be. Its status bar, quick-settings icons and permission
//                prompt are real drawables, the ids its views carry are what `uiautomator dump`
//                reports, and the device's own system apps ride along as ~700 KB of assets. A dex
//                carries none of that, and `addAssetPath` needs an archive to attach.
//
// The dex is taken out of the built APK rather than out of `intermediates/`, and that is the whole
// reason this file parses a zip. AGP keeps the project's dex and its bundled libraries' dex separate
// under `intermediates/` and merges them only when packaging — so `mergeDexRelease/classes.dex` is
// the project alone, and shipping it would drop ConstraintLayout silently, until the designer's
// canvas tried to inflate a real one. The APK's `classes.dex` is the merged one.
//
// The payloads are unsigned and never installed as apps. JCode verifies the *extension*, not these.
//
// `lib/` is gitignored and `native/` is in `.jextignore`: the payloads are rebuilt per release rather
// than committed, and the modules that build them stay out of the package. Without this script CI
// packed neither, and the pack failed to load with "native payload is missing".
import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { inflateRawSync } from 'node:zlib';
import { resolve } from 'node:path';

const win = process.platform === 'win32';
// Absolute, because a bare `gradlew.bat` is not found in the working directory the way `./gradlew`
// is on a POSIX shell, and the two platforms disagree about which relative spelling works.
const gradlew = resolve('native', win ? 'gradlew.bat' : 'gradlew');

// Keyed by the `entry.native[].id` each one backs, so a module renamed here and not in
// extension.yaml is a mismatch somebody can see rather than a load failure at runtime.
const MODULES = {
  designer: { apk: 'native/designer/build/outputs/apk/release/designer-release-unsigned.apk', ship: 'dex' },
  sdkmanager: { apk: 'native/sdkmanager/build/outputs/apk/release/sdkmanager-release-unsigned.apk', ship: 'dex' },
  vdevice: { apk: 'native/vdevice/build/outputs/apk/release/vdevice-release-unsigned.apk', ship: 'apk' },
};

/**
 * Pull one entry out of a zip, by hand.
 *
 * Node ships `zlib` but no zip reader, and a dependency for one file read in a build script is a
 * dependency to keep current forever. Deflated and stored are the only methods AGP emits — dex is
 * often stored outright — so those are the only two handled, and anything else is an error rather
 * than a guess.
 */
function readZipEntry(zipPath, entryName) {
  const buf = readFileSync(zipPath);
  // End of central directory, scanned backwards: the comment field is variable-length, so the
  // signature's position is not fixed. 22 bytes is the record with an empty comment.
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0 && i > buf.length - 22 - 0xffff; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error(`${zipPath}: no end-of-central-directory record`);

  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error(`${zipPath}: bad central directory`);
    const method = buf.readUInt16LE(p + 10);
    const compressedSize = buf.readUInt32LE(p + 20);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const localOffset = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
    if (name === entryName) {
      // The local header repeats the name and extra fields, and its extra length can differ from
      // the central one -- so the data offset has to come from the local header, not this record.
      const localNameLen = buf.readUInt16LE(localOffset + 26);
      const localExtraLen = buf.readUInt16LE(localOffset + 28);
      const start = localOffset + 30 + localNameLen + localExtraLen;
      const raw = buf.subarray(start, start + compressedSize);
      if (method === 0) return raw;
      if (method === 8) return inflateRawSync(raw);
      throw new Error(`${zipPath}: ${entryName} uses unsupported compression method ${method}`);
    }
    p += 46 + nameLen + extraLen + commentLen;
  }
  throw new Error(`${zipPath}: no ${entryName}`);
}

const build = spawnSync(gradlew, ['assembleRelease'], {
  cwd: 'native',
  stdio: 'inherit',
  shell: win,
});
if (build.status !== 0) process.exit(build.status || 1);

mkdirSync('lib', { recursive: true });
for (const [id, { apk, ship }] of Object.entries(MODULES)) {
  if (ship === 'apk') {
    copyFileSync(apk, `lib/${id}.apk`);
  } else {
    writeFileSync(`lib/${id}.dex`, readZipEntry(apk, 'classes.dex'));
  }
  console.log(`✓ built native/${id} → lib/${id}.${ship}`);
}
