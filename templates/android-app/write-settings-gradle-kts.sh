#!/bin/sh
# Write settings.gradle.kts
set -e

cat > "$JCODE_PROJECT_DIR/settings.gradle.kts" <<EOF
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "$JCODE_PROJECT_NAME"
include(":app")
EOF
