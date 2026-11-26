// The C++ simulation and Vulkan renderer, behind NativeTank (JNI), plus the
// Kotlin glue that keeps the native tank in step with the session's scene.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.jpcottin.tairoto.engine"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "30.0.14904198"

    defaultConfig {
        // 30: Vulkan 1.1 is everywhere, thermal headroom and ANativeWindow_setFrameRate exist.
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:world"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Native unit tests on the host (no device): the simulation and the mesh builder.
val nativeHostTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds and runs the C++ unit tests on this machine."
    workingDir = rootDir
    commandLine("bash", "scripts/host-tests.sh")
    inputs.dir("src/main/cpp")
    outputs.upToDateWhen { false }
}
