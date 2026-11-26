// The tank model with nothing Android in it: geometry in millimetres, the
// camera and its depth projection, the species catalogue, entity ownership,
// links between devices and the quality governor. Runs on any JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // A fake simulation and a lagoon of fake devices, shared with the other modules' tests.
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}
