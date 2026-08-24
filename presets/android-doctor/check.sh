#!/bin/sh
# Check
# Reports only. Nothing here writes to the project; "Apply build fixes" does that.
PROJ="$JCODE_PROJECT_DIR"
cd "$PROJ" || { echo "Cannot enter $PROJ"; exit 1; }
FAIL=0
note() { printf '      %s\n' "$*"; }
bad()  { printf '  [!] %s\n' "$*"; FAIL=$((FAIL + 1)); }
ok()   { printf '  [ok] %s\n' "$*"; }
# Never look inside build output, Gradle's caches, or J Code's own directory: the fixer
# backs originals up under .jcode, and a scan that read those back would report a project
# as broken because of a copy of how it used to be.
SKIP="--exclude-dir=build --exclude-dir=.gradle --exclude-dir=.jcode --exclude-dir=.git"
# Only BUILD SCRIPTS declare a compileSdk. A version catalog holds a version that a build
# script may or may not use, and reading one as if it were the answer is how a project
# that already overrides its SDK locally gets reported as broken.
SCRIPTS=$(grep -rl $SKIP -E '(^|[^A-Za-z])(compileSdk|targetSdk)[[:space:]]*=' --include='build.gradle' --include='build.gradle.kts' . 2>/dev/null)

# Value of Gradle property $1. The project's gradle.properties wins over the home one,
# which is the order Gradle itself resolves them in.
gradle_prop() {
  esc=$(printf '%s' "$1" | sed 's/\./\\./g')
  V=$(sed -n -E "s/^[[:space:]]*$esc[[:space:]]*=[[:space:]]*(.*)$/\1/p" gradle.properties 2>/dev/null | tail -1 | tr -d '\r')
  [ -n "$V" ] || V=$(sed -n -E "s/^[[:space:]]*$esc[[:space:]]*=[[:space:]]*(.*)$/\1/p" "$HOME/.gradle/gradle.properties" 2>/dev/null | tail -1 | tr -d '\r')
  printf '%s' "$V"
}

# A [versions] entry from the version catalog. Accessors spell `-` as `.`, so try both.
catalog_version() {
  for k in "$1" "$(printf '%s' "$1" | tr '.' '-')"; do
    V=$(sed -n -E "s/^[[:space:]]*$k[[:space:]]*=[[:space:]]*\"([^\"]+)\".*/\1/p" gradle/libs.versions.toml 2>/dev/null | head -1)
    [ -n "$V" ] && { printf '%s' "$V"; return; }
  done
}

# What $2 (compileSdk or targetSdk) actually evaluates to in build script $1, following the
# same order the script does: a Gradle property override first, then the version catalog it
# falls back to, then a plain literal. Sets SDK_VALUE, SDK_SOURCE and SDK_PROP.
#
# The window is three lines because the idiomatic override wraps:
#   compileSdk = (findProperty("x.compileSdk") as String?
#       ?: libs.versions.compileSdk.get()).toInt()
resolve_sdk() {
  SDK_VALUE=""; SDK_SOURCE=""; SDK_PROP=""
  WIN=$(grep -m1 -A2 -E "(^|[^A-Za-z])$2[[:space:]]*=" "$1" 2>/dev/null | tr '\n' ' ')
  [ -n "$WIN" ] || return 1
  SDK_PROP=$(printf '%s' "$WIN" | sed -n -E 's/.*(findProperty|gradleProperty|property)\([[:space:]]*"([^"]+)".*/\2/p')
  if [ -n "$SDK_PROP" ]; then
    V=$(gradle_prop "$SDK_PROP")
    case "$V" in ''|*[!0-9]*) ;; *) SDK_VALUE="$V"; SDK_SOURCE="gradle.properties $SDK_PROP"; return 0 ;; esac
  fi
  CAT=$(printf '%s' "$WIN" | sed -n -E 's/.*libs\.versions\.([A-Za-z0-9_.-]+)\.get\(\).*/\1/p')
  if [ -n "$CAT" ]; then
    V=$(catalog_version "$CAT")
    case "$V" in ''|*[!0-9]*) ;; *) SDK_VALUE="$V"; SDK_SOURCE="libs.versions.toml $CAT"; return 0 ;; esac
  fi
  V=$(printf '%s' "$WIN" | sed -n -E "s/.*$2[[:space:]]*=[[:space:]]*\"?([0-9]+)\"?.*/\1/p")
  case "$V" in ''|*[!0-9]*) return 1 ;; *) SDK_VALUE="$V"; SDK_SOURCE="the build script"; return 0 ;; esac
}

echo "== Can this device build $PROJ? =="

echo "- Android SDK"
SDK="${ANDROID_HOME:-}"
[ -n "$SDK" ] || SDK="${ANDROID_SDK_ROOT:-}"
CEILING=""
if [ -z "$SDK" ] || [ ! -d "$SDK/platforms" ]; then
  bad "No Android SDK. Install the 'android-sdk' toolchain from the Toolchains panel."
else
  ok "ANDROID_HOME=$SDK"
  CEILING=$(tr -dc '0-9' < "$SDK/jcode-compile-sdk.txt" 2>/dev/null)
  if [ -n "$CEILING" ]; then
    ok "Highest compileSdk this device's aapt2 can link against: $CEILING"
  else
    note "No jcode-compile-sdk.txt; reinstall the android-sdk toolchain to record the ceiling."
  fi
fi

echo "- compileSdk"
MAX=""; OVER=""; OVER_PROP=""
for f in $SCRIPTS; do
  resolve_sdk "$f" compileSdk || continue
  # Informational, not a verdict: the verdict is below, once every module has resolved.
  # Saying WHERE the number came from is the point, since a build script that reads a
  # property or a catalog does not carry the answer on its own line.
  note "$f: compileSdk $SDK_VALUE (from $SDK_SOURCE)"
  [ -n "$SDK_PROP" ] && OVER_PROP="$SDK_PROP"
  if [ -z "$MAX" ] || [ "$SDK_VALUE" -gt "$MAX" ]; then MAX="$SDK_VALUE"; fi
  if [ -n "$CEILING" ] && [ "$SDK_VALUE" -gt "$CEILING" ]; then OVER="$OVER $f"; fi
done
if [ -z "$MAX" ]; then
  note "No numeric compileSdk resolved from any build script."
elif [ -n "$OVER" ]; then
  bad "compileSdk $MAX is above this device's ceiling of $CEILING; resource linking fails with 'RES_TABLE_TYPE_TYPE entry offsets overlap actual entry data'."
  if [ -n "$OVER_PROP" ]; then
    note "The build already reads $OVER_PROP, so the fix is local: put $OVER_PROP=$CEILING in gradle.properties. The version catalog keeps the release value."
  else
    note "Declared in:$OVER"
  fi
elif [ -n "$CEILING" ]; then
  ok "compileSdk $MAX is within this device's ceiling of $CEILING."
fi

echo "- Gradle wrapper"
if [ -f gradlew ] && [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  bad "gradle/wrapper/gradle-wrapper.jar is missing (often gitignored), so gradlew cannot start. Restore it with: gradle wrapper"
elif [ -f gradlew ]; then
  WRAP=$(sed -n -E 's/^distributionUrl=.*gradle-([0-9.]+)-(bin|all)\.zip.*/\1/p' gradle/wrapper/gradle-wrapper.properties 2>/dev/null)
  ok "Wrapper Gradle ${WRAP:-(version not readable)}"
elif command -v gradle >/dev/null 2>&1; then
  note "No wrapper, so builds use the runtime's Gradle. That is usually newer than the AGP a project pins; if configuration fails on the AGP version, pin one: gradle wrapper --gradle-version 8.9"
else
  bad "No gradlew and no gradle in the runtime. Install the 'android-sdk' toolchain, which brings Gradle."
fi

echo "- local.properties"
if [ ! -f local.properties ]; then
  ok "Absent, so ANDROID_HOME is what the build uses."
else
  DIR=$(sed -n -E 's/^[[:space:]]*sdk\.dir=(.*)$/\1/p' local.properties | tail -1 | tr -d '\r')
  if [ -z "$DIR" ]; then
    ok "No sdk.dir line, so ANDROID_HOME is what the build uses."
  elif [ -d "$DIR" ]; then
    ok "sdk.dir=$DIR"
  else
    bad "sdk.dir=$DIR does not exist here; it was committed from another machine."
  fi
fi

echo "- aapt2"
GP="$HOME/.gradle/gradle.properties"
if grep -qs '^android.aapt2FromMavenOverride=' "$GP"; then
  ok "$(grep -h '^android.aapt2FromMavenOverride=' "$GP" | tail -1)"
else
  bad "android.aapt2FromMavenOverride is unset in $GP, so AGP fetches its own x86_64 aapt2 and the build dies with 'AAPT2 ... Daemon startup failed'. Reinstalling the android-sdk toolchain writes it."
fi
if grep -qs '^android.aapt2FromMavenOverride=' gradle.properties; then
  note "This project sets it too, and a project property wins:"
  grep -h '^android.aapt2FromMavenOverride=' gradle.properties | sed 's/^/      /'
fi

echo "- Memory"
ARGS=$(grep -hs '^org.gradle.jvmargs' gradle.properties | tail -1)
RAM=$(awk '/MemTotal/ { printf "%d", $2 / 1024 }' /proc/meminfo 2>/dev/null)
if [ -n "$ARGS" ]; then
  note "$ARGS"
  [ -n "$RAM" ] && note "This device has ${RAM} MB of RAM. A heap much past a third of that will thrash rather than build."
else
  ok "No org.gradle.jvmargs, so Gradle picks its own heap."
fi

echo "- Toolchain"
java -version 2>&1 | head -1 | sed 's/^/      /'
if grep -rqs $SKIP 'externalNativeBuild' --include='build.gradle' --include='build.gradle.kts' . ; then
  note "This project compiles native code, so it needs the NDK and CMake as well as the SDK."
fi

echo
if [ "$FAIL" -eq 0 ]; then
  echo "Nothing blocking. Build it from the Build segment, or: ./gradlew assembleDebug"
else
  echo "$FAIL blocker(s). 'Apply build fixes' repairs the local ones and backs up every file it edits."
fi
