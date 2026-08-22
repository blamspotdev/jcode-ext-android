#!/bin/sh
# Write app/build.gradle.kts
set -e

# No Kotlin plugin: AGP 9+ ships built-in Kotlin support and errors if
# org.jetbrains.kotlin.android is applied.
cat > "$JCODE_PROJECT_DIR/app/build.gradle.kts" <<EOF
plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.$JCODE_PROJECT_NAME"
    compileSdk = $JCODE_INPUT_TARGETSDK

    defaultConfig {
        applicationId = "com.example.$JCODE_PROJECT_NAME"
        minSdk = $JCODE_INPUT_MINSDK
        targetSdk = $JCODE_INPUT_TARGETSDK
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
EOF
