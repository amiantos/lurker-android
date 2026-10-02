// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

// LurkerKit for Android: the internal model, wire parser, store and client behind the
// Compose app — a Kotlin port of lurker-ios's LurkerKit Swift package, file for file.
//
// A plain JVM module on purpose, for the same two reasons the Swift one is a package:
// the UI cannot reach into I/O (and nothing in here can reach into Android), and the
// tricky, pure core is tested with `./gradlew :lurkerkit:test` on the host — no
// emulator, no Robolectric. What genuinely needs the platform (the Keystore, MIME
// lookups, reachability) is an interface here and an implementation in `:app`.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // `api`: HttpUrl and okio.ByteString appear in the kit's own signatures.
    api(libs.okhttp)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
