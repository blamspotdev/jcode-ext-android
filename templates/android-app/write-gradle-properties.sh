#!/bin/sh
# Write gradle.properties
set -e

cat > "$JCODE_PROJECT_DIR/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx2560m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
EOF
