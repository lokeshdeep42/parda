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

rootProject.name = "parda"

include(":core")
// The Android app needs the Android SDK. Pass -Pparda.coreOnly=true to build and
// test the platform-independent core on a machine without it (CI, cloud sandboxes).
if (providers.gradleProperty("parda.coreOnly").orNull != "true") {
    include(":app")
}
