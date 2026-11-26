// How devices reach each other: a small relay that forwards frames between
// the devices of a session, and the link that talks to it. The relay runs on
// a computer for emulators (they cannot see each other) and inside the host
// device on a local network. Plain JVM sockets, so it works on both.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:world"))
    testImplementation(testFixtures(project(":core:world")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}
