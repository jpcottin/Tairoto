// The relay as a program for a computer: emulators reach it at 10.0.2.2.
//   ./gradlew :relay:installDist && relay/build/install/relay/bin/relay --port 7171
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:net"))
}

application {
    mainClass.set("com.jpcottin.tairoto.relay.MainKt")
}
