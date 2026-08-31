pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "jcode-ext-android"

// One archive per surface the pack offers, not one for all of them. Folded together they shared a
// `minSdk` only the device needs, re-dexed together on every edit, and handed the `:guest` process
// two UIs it never calls -- along with ConstraintLayout, which only the designer inflates.
//
// The Gradle root is the repository root rather than `native/`, so `native/` holds the three
// modules and nothing else: no wrapper, no jars, no build state. Their directories stay where they
// are; only the project paths are declared here.
include(":designer", ":newproject", ":sdkmanager", ":vdevice")
project(":designer").projectDir = file("native/designer")
project(":newproject").projectDir = file("native/newproject")
project(":sdkmanager").projectDir = file("native/sdkmanager")
project(":vdevice").projectDir = file("native/vdevice")
