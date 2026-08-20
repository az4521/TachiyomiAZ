plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("app.cash.sqldelight")
}

// Shared persistence layer.
//
// Schema ownership is deliberately asymmetric, and this is the important thing about this module:
//
//  - On Android, DbOpenCallback still owns creation and all 18 upgrade steps, running against
//    real user databases that must never be touched by anything else. SQLDelight is handed an
//    already-open helper and only generates typed queries over it.
//  - On iOS there are no existing databases, so SQLDelight owns the schema outright via
//    Database.Schema.create(). See IosDatabaseFactory in iosMain.
//
// Both read the same .sq files, so the table definitions cannot drift apart.
kotlin {
    compilerOptions {
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

    // Not shipped: this is the portability guard. iOS targets only compile on macOS, so on a
    // Windows box this is what proves commonMain is free of Android APIs.
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
        // Accessor form rather than `by getting`: iosMain is an intermediate source set created
        // by the default hierarchy template, so it does not exist yet at configuration time.
        commonMain.dependencies {
            api(project(":core-model"))
            api("app.cash.sqldelight:runtime:2.0.2")
            implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }

        iosMain.dependencies {
            implementation("app.cash.sqldelight:native-driver:2.0.2")
        }
    }
}

sqldelight {
    databases {
        create("Database") {
            packageName.set("eu.kanade.tachiyomi.data.database")
            // Kept false to match the Android app, which drives these queries synchronously.
            generateAsync.set(false)
        }
    }
}

android {
    namespace = "eu.kanade.tachiyomi.core.database"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
