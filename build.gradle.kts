plugins {
    id("com.android.application") version BuildPluginsVersion.AGP apply false
    id("com.android.library") version BuildPluginsVersion.AGP apply false
    kotlin("android") version BuildPluginsVersion.KOTLIN apply false
    kotlin("multiplatform") version BuildPluginsVersion.KOTLIN apply false
    kotlin("plugin.serialization") version BuildPluginsVersion.KOTLIN
    id("org.jlleitschuh.gradle.ktlint") version BuildPluginsVersion.KTLINT
    id("com.github.ben-manes.versions") version BuildPluginsVersion.VERSIONS_PLUGIN
}

allprojects {
    repositories {
        mavenCentral()
        google()
        jcenter()
        maven { setUrl("https://www.jitpack.io") }
        maven { setUrl("https://oss.sonatype.org/content/repositories/snapshots/") }
        maven { setUrl("https://dl.bintray.com/ibm-cloud-sdks/ibm-cloud-sdk-repo") }
        maven { setUrl("https://plugins.gradle.org/m2/") }
    }
}

subprojects {
    apply {
        plugin("org.jlleitschuh.gradle.ktlint")
    }

    ktlint {
        debug.set(false)
        version.set(Versions.KTLINT)
        verbose.set(true)
        android.set(false)
        outputToConsole.set(true)
        ignoreFailures.set(false)
        enableExperimentalRules.set(true)

        disabledRules.set(setOf("standard:comment-wrapping", "experimental:comment-wrapping", "standard:enum-entry-name-case"))

        filter {
            exclude("**/generated/**")
            include("**/kotlin/**")
        }
    }
}

buildscript {
    dependencies {
        // Overrides the R8 8.13 that AGP 8.13 bundles, whose kotlin-metadata-jvm only
        // parses kotlin.Metadata up to 2.3.0. The Kotlin 2.4.0 deps carry metadata 2.4.0,
        // which that R8 reports as malformed and leaves unrewritten. R8 9.x reads 2.4.0.
        classpath("com.android.tools:r8:9.1.31")
        classpath("com.github.ben-manes:gradle-versions-plugin:0.52.0")
        classpath("com.google.gms:google-services:4.4.4")
        classpath("app.cash.sqldelight:gradle-plugin:${BuildPluginsVersion.SQLDELIGHT}")
        classpath("com.google.android.gms:oss-licenses-plugin:0.10.10")
        classpath(kotlin("serialization", version = "1.9.22"))
        // Realm (EH)
        classpath("io.realm:realm-gradle-plugin:10.19.0")

        // Firebase (EH)
        //classpath("io.fabric.tools:gradle:1.31.2")
        classpath ("com.google.firebase:firebase-crashlytics-gradle:3.0.6")

    }
    repositories {
        google()
        mavenCentral()
    }
}

/**
 * Checks that the shared modules really are shareable.
 *
 * The metadata compilations are the part that matters. Building for plain JVM is not enough: it
 * shares almost everything with Android, so JVM-only API in commonMain sails straight through.
 * Both of these did exactly that until the metadata check was added --
 *
 *   - `javaClass`, used in four model classes' equals()
 *   - `Dispatchers.IO`, which coroutines declares in its JVM and Native source sets but not in
 *     the common API
 *
 * -- and neither would have surfaced until someone tried to build for iOS. Metadata compilation
 * resolves commonMain and iosMain against the real shared API and needs no Apple toolchain, so it
 * runs anywhere, including a Windows or Linux CI runner.
 *
 * It still is not a substitute for linking the iOS binaries, which needs macOS. It catches
 * everything that is a source-level portability mistake, which is nearly all of them.
 */
tasks.register("checkSharedPortability") {
    group = "verification"
    description = "Type-checks the shared modules for iOS and JVM, and runs their tests."
    dependsOn(
        // The real portability check: resolves commonMain and iosMain against the shared API.
        ":core-model:compileIosMainKotlinMetadata",
        ":core-database:compileIosMainKotlinMetadata",
        ":core-domain:compileIosMainKotlinMetadata",
        ":core-model:compileKotlinJvm",
        ":core-database:compileKotlinJvm",
        ":core-domain:compileKotlinJvm",
        // The shared tests run here too: they cover the rules both platforms must agree on, so
        // a regression in them is a divergence between the two apps, not just a local bug.
        ":core-model:jvmTest",
        ":core-domain:jvmTest"
    )
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
