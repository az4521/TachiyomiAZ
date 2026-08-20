plugins {
    kotlin("multiplatform")
    id("com.android.library")
    kotlin("plugin.serialization")
}

// Shared domain logic: the rules that decide what happens, with no opinion on how the platform
// carries them out.
//
// The rule for what belongs here: if Android and iOS must agree on the answer, it goes in this
// module. Chapter diffing is the clearest case -- both platforms have to reach the same
// conclusion about which chapters are new, which were deleted, and which were silently renamed,
// or the two apps quietly diverge on the same library.
//
// Platform work is injected rather than imported. Anything that touches the network, the
// filesystem, notifications or the extension API is passed in as an interface, so this module
// never grows a dependency on the source layer.
kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
                }
            }
        }
    }

    // The portability guard; see :core-model.
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
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        commonMain.dependencies {
            api(project(":core-model"))
            api(project(":core-database"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            // The backup format is protobuf; this is what makes it identical on both platforms.
            api("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.11.0")
            // Repository indexes are read straight off the wire, so the decoder works on an okio
            // BufferedSource: gzip unwrapping and streaming JSON both come from here. Both are
            // multiplatform, which is what let the decoder move out of :app unchanged, and :app
            // already had both through OkHttp so the Android build gains nothing new.
            api("com.squareup.okio:okio:3.9.1")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json-okio:1.11.0")
        }
    }
}

android {
    namespace = "eu.kanade.tachiyomi.core.domain"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
