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

rootProject.name = "jcode-android-native"

// One archive per surface the pack offers, not one for all of them. Folded together they shared a
// `minSdk` only the device needs, re-dexed together on every edit, and handed the `:guest` process
// two UIs it never calls -- along with ConstraintLayout, which only the designer inflates.
include(":designer", ":sdkmanager", ":vdevice")
