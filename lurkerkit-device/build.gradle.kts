// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

// The kit's own test suite, run as instrumented tests on a device (lurker-android#34).
//
// `:lurkerkit` is a JVM module, and its suite runs on the host JVM — whose regex engine is OpenJDK's,
// where a device's is ICU, and whose `java.time` is a different implementation. The suite leans on
// `internal` members, which only the module that declares them can see, so the suite can't be
// pointed at from `:app`'s androidTest. Instead this module compiles the kit's sources AND its tests
// together, as an Android library, so `connectedAndroidTest` runs the same tests against the same
// code on the device's runtime. It ships nothing: no module depends on it, and it's never published.
//
// Run: ./gradlew :lurkerkit-device:connectedDebugAndroidTest (a device or emulator attached).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.amiantos.lurkerkit.device"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets {
        getByName("main") {
            kotlin.srcDir("../lurkerkit/src/main/kotlin")
            // The bundled network catalogue is a Java resource the kit reads from its classpath.
            resources.srcDir("../lurkerkit/src/main/resources")
        }
        getByName("androidTest") { kotlin.srcDir("../lurkerkit/src/test/kotlin") }
    }
    // The kit's tests are written for a JVM; lint has nothing to say about running them.
    lint { checkReleaseBuilds = false }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.junit)
}
