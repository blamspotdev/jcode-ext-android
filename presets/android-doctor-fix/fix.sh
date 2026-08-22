#!/bin/sh
# Fix
# Writes to the project. Every edited file is copied into .jcode/build-fix-backup-<stamp>/
# first, and the run prints each change, so `git diff` afterwards is the whole story.
PROJ="$JCODE_PROJECT_DIR"
cd "$PROJ" || { echo "Cannot enter $PROJ"; exit 1; }
SDK="${ANDROID_HOME:-}"
[ -n "$SDK" ] || SDK="${ANDROID_SDK_ROOT:-}"
if [ -z "$SDK" ] || [ ! -d "$SDK/platforms" ]; then
  echo "No Android SDK. Install the 'android-sdk' toolchain first; nothing to fix without it."
  exit 1
fi
CEILING=$(tr -dc '0-9' < "$SDK/jcode-compile-sdk.txt" 2>/dev/null)
BK=".jcode/build-fix-backup-$(date +%Y%m%d-%H%M%S)"
CHANGED=0
backup() { mkdir -p "$BK/$(dirname "$1")" && cp -p "$1" "$BK/$1"; }
# Backups live under .jcode, so the scan below must skip it: a second run would otherwise
# rewrite the copies of what the first run changed, and there would be no original left.
SKIP="--exclude-dir=build --exclude-dir=.gradle --exclude-dir=.jcode --exclude-dir=.git"

echo "== Applying build fixes in $PROJ =="

# 1. Point the build at this device's SDK. Gradle reads local.properties before it reads
#    the environment, so a stale sdk.dir from another machine beats a correct ANDROID_HOME.
NEED_LOCAL=0
if [ ! -f local.properties ]; then
  NEED_LOCAL=1
else
  DIR=$(sed -n -E 's/^[[:space:]]*sdk\.dir=(.*)$/\1/p' local.properties | tail -1 | tr -d '\r')
  [ -n "$DIR" ] && [ ! -d "$DIR" ] && NEED_LOCAL=1
fi
if [ "$NEED_LOCAL" -eq 1 ]; then
  [ -f local.properties ] && backup local.properties
  [ -f local.properties ] && sed -i '/^[[:space:]]*sdk\.dir=/d' local.properties
  printf 'sdk.dir=%s\n' "$SDK" >> local.properties
  echo "  local.properties: sdk.dir=$SDK"
  CHANGED=$((CHANGED + 1))
fi

# 2. Bring the EFFECTIVE compileSdk/targetSdk down to what the ARM-native aapt2 can read.
#
#    "Effective" is the point. A build script that reads a Gradle property before falling
#    back to the version catalog is already telling us where a local override belongs, so
#    the fix is one line in gradle.properties and the catalog keeps the value a release
#    build uses. Rewriting the catalog instead would quietly lower the shipped targetSdk,
#    which Play rejects. Only a project with a hardcoded literal gets its script edited,
#    because there is nowhere else for the value to live.
gradle_prop() {
  esc=$(printf '%s' "$1" | sed 's/\./\\./g')
  V=$(sed -n -E "s/^[[:space:]]*$esc[[:space:]]*=[[:space:]]*(.*)$/\1/p" gradle.properties 2>/dev/null | tail -1 | tr -d '\r')
  [ -n "$V" ] || V=$(sed -n -E "s/^[[:space:]]*$esc[[:space:]]*=[[:space:]]*(.*)$/\1/p" "$HOME/.gradle/gradle.properties" 2>/dev/null | tail -1 | tr -d '\r')
  printf '%s' "$V"
}
catalog_version() {
  for k in "$1" "$(printf '%s' "$1" | tr '.' '-')"; do
    V=$(sed -n -E "s/^[[:space:]]*$k[[:space:]]*=[[:space:]]*\"([^\"]+)\".*/\1/p" gradle/libs.versions.toml 2>/dev/null | head -1)
    [ -n "$V" ] && { printf '%s' "$V"; return; }
  done
}
resolve_sdk() {
  SDK_VALUE=""; SDK_SOURCE=""; SDK_PROP=""
  WIN=$(grep -m1 -A2 -E "(^|[^A-Za-z])$2[[:space:]]*=" "$1" 2>/dev/null | tr '\n' ' ')
  [ -n "$WIN" ] || return 1
  SDK_PROP=$(printf '%s' "$WIN" | sed -n -E 's/.*(findProperty|gradleProperty|property)\([[:space:]]*"([^"]+)".*/\2/p')
  if [ -n "$SDK_PROP" ]; then
    V=$(gradle_prop "$SDK_PROP")
    case "$V" in ''|*[!0-9]*) ;; *) SDK_VALUE="$V"; SDK_SOURCE="property"; return 0 ;; esac
  fi
  CAT=$(printf '%s' "$WIN" | sed -n -E 's/.*libs\.versions\.([A-Za-z0-9_.-]+)\.get\(\).*/\1/p')
  if [ -n "$CAT" ]; then
    V=$(catalog_version "$CAT")
    case "$V" in ''|*[!0-9]*) ;; *) SDK_VALUE="$V"; SDK_SOURCE="catalog"; return 0 ;; esac
  fi
  V=$(printf '%s' "$WIN" | sed -n -E "s/.*$2[[:space:]]*=[[:space:]]*\"?([0-9]+)\"?.*/\1/p")
  case "$V" in ''|*[!0-9]*) return 1 ;; *) SDK_VALUE="$V"; SDK_SOURCE="literal"; return 0 ;; esac
}
# Set Gradle property $1 to $2 in the project's gradle.properties, replacing any line
# already declaring it.
set_gradle_prop() {
  [ -f gradle.properties ] && backup gradle.properties
  esc=$(printf '%s' "$1" | sed 's/\./\\./g')
  [ -f gradle.properties ] && sed -i -E "/^[[:space:]]*$esc[[:space:]]*=/d" gradle.properties
  printf '%s=%s\n' "$1" "$2" >> gradle.properties
  echo "  gradle.properties: $1=$2"
  CHANGED=$((CHANGED + 1))
}
if [ -n "$CEILING" ]; then
  SCRIPTS=$(grep -rl $SKIP -E '(^|[^A-Za-z])(compileSdk|targetSdk)[[:space:]]*=' --include='build.gradle' --include='build.gradle.kts' . 2>/dev/null)
  for f in $SCRIPTS; do
    SAVED=0
    for key in compileSdk targetSdk; do
      resolve_sdk "$f" "$key" || continue
      [ "$SDK_VALUE" -gt "$CEILING" ] || continue
      if [ -n "$SDK_PROP" ]; then
        set_gradle_prop "$SDK_PROP" "$CEILING"
      elif [ "$SDK_SOURCE" = "literal" ]; then
        [ "$SAVED" -eq 1 ] || { backup "$f"; SAVED=1; }
        sed -i -E "s/($key[[:space:]]*=[[:space:]]*\"?)$SDK_VALUE(\"?)/\1$CEILING\2/g" "$f"
        echo "  $f: $key $SDK_VALUE -> $CEILING"
        CHANGED=$((CHANGED + 1))
      else
        echo "  Still manual: $f reads $key from the version catalog with no property to override."
        echo "    Lowering gradle/libs.versions.toml would lower it for release builds too."
      fi
    done
  done
else
  echo "  (compileSdk left alone: the SDK recorded no ceiling; reinstall the android-sdk toolchain.)"
fi

# 3. A wrapper checked out without its exec bit. J Code runs `bash gradlew` so this is not
#    what breaks it here, but everything else that shells out to ./gradlew wants it.
if [ -f gradlew ] && [ ! -x gradlew ]; then
  chmod +x gradlew && echo "  gradlew: made executable" && CHANGED=$((CHANGED + 1))
fi

echo
if [ "$CHANGED" -eq 0 ]; then
  rmdir "$BK" 2>/dev/null
  echo "Nothing to change. Run 'Check this project builds here' for what is left."
else
  echo "$CHANGED change(s). Originals are in $BK. Review with 'git diff' before committing."
fi
if [ -f gradlew ] && [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  echo "Still manual: gradle/wrapper/gradle-wrapper.jar is missing. Restore it with: gradle wrapper"
fi
