#!/bin/sh
# Configure Build & Run
set -e

cat > "$JCODE_PROJECT_DIR/.jcode/run.yaml" <<EOF
version: 1
name: Android app
terminals:
  - label: Build APK
    command: |
      clear
      set -e
      export JAVA_HOME="\$(dirname "\$(dirname "\$(readlink -f "\$(command -v javac)")")")"
      # The SDK Manager installs as the jcode user, so the SDK may live in a different home.
      ANDROID_HOME="\$HOME/android-sdk"
      [ -d "\$ANDROID_HOME" ] || ANDROID_HOME="\$(ls -d /home/*/android-sdk /root/android-sdk 2>/dev/null | head -1)"
      export ANDROID_HOME ANDROID_SDK_ROOT="\$ANDROID_HOME"
      GRADLE_HOME="\$(ls -d /opt/gradle/gradle-* 2>/dev/null | sort -V | tail -1)"
      export PATH="\${GRADLE_HOME:-/opt/gradle/current}/bin:\$JAVA_HOME/bin:\$PATH"
      # Google ships only x86_64 aapt2; point AGP at the newest installed ARM-native copy.
      AAPT2="\$(ls -d "\$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V | tail -1)aapt2"
      SRC="$JCODE_PROJECT_DIR"
      STAGE="\$HOME/.jcode-run/$JCODE_PROJECT_NAME-android"
      echo '== J Code: Build APK (assembleDebug) =='
      echo 'Needs the Android SDK prerequisites + Android SDK from the SDK Manager.'
      # /workspace is FUSE (no symlinks); Gradle builds on the ext4 home, then the APK is copied back.
      echo '[1/3] Staging project to ext4 home...'
      rm -rf "\$STAGE" && mkdir -p "\$STAGE" && cp -a "\$SRC/." "\$STAGE/"
      cd "\$STAGE"
      echo "sdk.dir=\$ANDROID_HOME" > local.properties
      echo '[2/3] Building (gradle :app:assembleDebug)...'
      gradle --no-daemon --console=plain -Pandroid.aapt2FromMavenOverride="\$AAPT2" :app:assembleDebug
      echo '[3/3] Copying APK back into the project (build-output/)...'
      APK="\$(ls "\$STAGE"/app/build/outputs/apk/debug/*.apk | head -1)"
      mkdir -p "\$SRC/build-output" && cp -f "\$APK" "\$SRC/build-output/"
      echo "APK ready: build-output/\$(basename "\$APK")"
EOF
