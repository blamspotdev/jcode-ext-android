#!/bin/sh
# Write root build.gradle.kts
set -e

cat > "$JCODE_PROJECT_DIR/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.application") version "9.2.1" apply false
}
EOF
