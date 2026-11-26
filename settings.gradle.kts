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

rootProject.name = "Tairoto"

// :core:world  the tank model with nothing Android in it: geometry in mm, the
//              camera, species, ownership and links. JVM tests in seconds.
// :engine      the C++ simulation and the Vulkan renderer, behind a small
//              Kotlin facade (JNI).
// :core:net    the relay and the link to it: how devices of a shared lagoon talk.
// :relay       the relay as a program, for emulators on a computer.
// :app         the Compose shell: tank screen, creature picker, settings.
include(":core:world")
include(":core:net")
include(":relay")
include(":engine")
include(":app")
