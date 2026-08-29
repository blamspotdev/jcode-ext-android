#!/bin/sh
# Scaffold from the Android SDK's own template
set -e

TEMPLATE_DIR="$(dirname "$0")"

# The SDK is owned by whichever user the toolchains installed it as, so it is looked for rather than
# assumed — the same search the pack's build tasks do.
ANDROID_HOME=""
for d in /home/*/android-sdk /root/android-sdk; do
  [ -d "$d" ] && { ANDROID_HOME="$d"; break; }
done
[ -n "$ANDROID_HOME" ] || {
  echo "The Android SDK is not installed. Install it from Toolchains → SDKs → Android SDK."
  exit 1
}

TEMPLATES_ZIP="$ANDROID_HOME/build/templates/android-project-templates.zip"
if [ ! -f "$TEMPLATES_ZIP" ]; then
  # Not installed here on the user's behalf, deliberately. It is an SDK package with licence terms,
  # and the Android SDK Manager is where those are shown and agreed to — a scaffold that quietly
  # accepts a licence to get going is exactly the thing that page exists to stop.
  echo "This template comes from the Android SDK's own template package, which is not installed."
  echo
  echo "Install \"Android Project Templates\" in Toolchains → Managers → Android SDK Manager,"
  echo "under SDK Tools, then create the project again."
  exit 1
fi

# `compileSdk` is not offered as a choice: it is whatever this device's aapt2 can actually read,
# which the Android SDK install works out and records. Letting somebody pick a higher one would
# produce a project that scaffolds perfectly and then fails every build inside resource linking.
COMPILE_SDK="$(cat "$ANDROID_HOME/jcode-compile-sdk.txt" 2>/dev/null || true)"
[ -n "$COMPILE_SDK" ] || COMPILE_SDK=36
# Major component only. Android now ships minor platform versions and the SDK install records
# whichever is newest-readable, so this file can say `36.1` -- but `compileSdk` is an `Int?` in the
# Gradle Kotlin DSL, and `compileSdk = 36.1` is a Double literal that fails before any task runs:
# "Assignment type mismatch: actual type is 'Double', but 'Int?' was expected". Measured on a fresh
# install, where 36.1 is what gets picked; a device that happens to record `36` never sees it.
COMPILE_SDK="${COMPILE_SDK%%.*}"

# `gradle/9.0` rather than `lightbuild/0.1`: the runtime has Gradle 9 and the AGP the former asks
# for, and lightbuild is a build system this device has never run.
VARIANT="empty-activity-compose/gradle/9.0"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

echo "== Unpacking $VARIANT from the SDK's template package =="
# python3 rather than unzip: the runtime has no unzip, and this needs a JSON parser two steps later
# anyway.
python3 - "$TEMPLATES_ZIP" "$VARIANT" "$STAGE" <<'PY'
import pathlib, sys, zipfile
archive, prefix, out = sys.argv[1], sys.argv[2].rstrip("/") + "/", pathlib.Path(sys.argv[3])
with zipfile.ZipFile(archive) as z:
    names = [n for n in z.namelist() if n.startswith(prefix) and not n.endswith("/")]
    if not names:
        sys.exit(f"{prefix} is not in {archive}")
    for name in names:
        target = out / name[len(prefix):]
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(z.read(name))
print(f"   {len(names)} files")
PY

echo "== Applying the template =="
python3 "$TEMPLATE_DIR/apply-sdk-template.py" \
  --template "$STAGE" \
  --project "$JCODE_PROJECT_DIR" \
  --sdk-path "$ANDROID_HOME" \
  --arg "name=$JCODE_PROJECT_NAME" \
  --arg "applicationId=${JCODE_INPUT_APPLICATIONID:-}" \
  --arg "namespace=${JCODE_INPUT_APPLICATIONID:-}" \
  --arg "minSdk=${JCODE_INPUT_MINSDK:-24}" \
  --arg "compileSdk=$COMPILE_SDK"

# Ours, not the template's, and separated from the interpreter on purpose: the interpreter is a
# faithful reading of Google's definition, and this is a defect in that definition.
#
# `template-definition.json` 0.1.1 rewrites `applicationId` in app/build.gradle.kts and
# `com.example.myapplication` in `**/*.kt` — but its glob is `*.kt`, which does not match `.kts`, so
# the `namespace = "com.example.myapplication"` line beside it is left alone. A project with a
# namespace that disagrees with its own sources generates R into a package nothing imports.
APP_BUILD="$JCODE_PROJECT_DIR/app/build.gradle.kts"
if grep -q 'namespace = "com.example.myapplication"' "$APP_BUILD" 2>/dev/null; then
  NS="$(sed -n 's/^[[:space:]]*applicationId = "\(.*\)"/\1/p' "$APP_BUILD" | head -1)"
  [ -n "$NS" ] || NS="com.example.$JCODE_PROJECT_NAME"
  sed -i "s|namespace = \"com.example.myapplication\"|namespace = \"$NS\"|" "$APP_BUILD"
  echo "   namespace set to $NS (the SDK template leaves it at its own default)"
fi

mkdir -p "$JCODE_PROJECT_DIR/.jcode"
