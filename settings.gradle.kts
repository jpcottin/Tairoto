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

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "DriftTank"

// :core:world  the tank model with nothing Android in it: geometry in mm, the
//              camera, species, ownership and links. JVM tests in seconds.
// :engine      the C++ simulation and the Vulkan renderer, behind a small
//              Kotlin facade (JNI).
// :app         the Compose shell: tank screen, creature picker, settings.
include(":core:world")
include(":engine")
include(":app")
