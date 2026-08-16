plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

// Shared model layer for the Android app and the iOS port.
//
// commonMain must stay free of Android and JVM APIs. That is not a convention here, it is
// enforced: the ios* and jvm targets simply will not compile if something Android-only creeps
// in, so the boundary is checked by the compiler at Android build time rather than discovered
// at iOS runtime.
//
// The jvm() target exists for exactly that reason. iOS targets can only be compiled on macOS,
// so on a Windows or Linux dev box jvm() is what actually proves commonMain is portable, and
// it is cheap to build.
kotlin {
    compilerOptions {
        // JavaSerializable is an expect interface with an actual typealias, which the compiler
        // still flags as Beta. That pattern is load-bearing here -- it is what keeps the
        // extension API's ABI unchanged -- so opt in rather than carry the warning.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
                }
            }
        }
    }

    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
                }
            }
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }

        // java.io.Serializable is part of the extension API's ABI: extensions are compiled
        // against `SManga extends java.io.Serializable`. Aliasing it on the JVM side keeps that
        // byte-for-byte identical while letting commonMain compile for iOS.
        val androidMain by getting
        val jvmMain by getting
    }
}

android {
    namespace = "eu.kanade.tachiyomi.core.model"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
