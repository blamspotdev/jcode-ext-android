buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // The pack's Kotlin rather than AGP's own 2.2.10, which trips Gradle 9's deprecation of
        // Configuration.setVisible on every build.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.1"
}

android {
    namespace = "com.example.appcompatguest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.appcompatguest"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}
