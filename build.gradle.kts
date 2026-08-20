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
 * Two things about the coverage that are easy to get wrong:
 *
 *  - compileIosMainKotlinMetadata does nothing for a module with no iosMain source set, and
 *    :core-domain has none -- it is pure commonMain. What actually guards that module is
 *    compileCommonMainKotlinMetadata, which resolves commonMain against the intersection of every
 *    declared target, iOS included. It is listed explicitly below so the coverage does not depend
 *    on it happening to be pulled in as somebody else's dependency.
 *
 *  - commonTest was not checked against Native at all until the Apple block was added. The tests
 *    ran only on the JVM, so three test names containing commas -- legal on the JVM, rejected by
 *    Kotlin/Native, which turns backticked names into Objective-C selectors -- compiled clean
 *    everywhere and failed on the first native build.
 *
 * The Apple half needs macOS, so it is conditional rather than a hard dependency; on a Windows or
 * Linux runner the task still does everything it did before. It reports which half it ran, because
 * a guard whose coverage silently varies by host is worse than one that only does less.
 */
val isMacOs = System.getProperty("os.name").startsWith("Mac")

tasks.register("checkSharedPortability") {
    group = "verification"
    description = "Type-checks the shared modules for iOS and JVM, and runs their tests. " +
        "On macOS it also compiles the shared tests for Kotlin/Native."
    dependsOn(
        // The real portability check: resolves commonMain and iosMain against the shared API.
        ":core-model:compileCommonMainKotlinMetadata",
        ":core-database:compileCommonMainKotlinMetadata",
        ":core-domain:compileCommonMainKotlinMetadata",
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

    if (isMacOs) {
        // commonTest compiled for real Kotlin/Native. Cheap next to running the tests, and it is
        // what catches source-level mistakes the JVM accepts.
        dependsOn(
            ":core-model:compileTestKotlinIosSimulatorArm64",
            ":core-domain:compileTestKotlinIosSimulatorArm64"
        )
    }

    doLast {
        if (isMacOs) {
            logger.lifecycle("checkSharedPortability: metadata + JVM + Kotlin/Native test compile.")
        } else {
            logger.lifecycle(
                "checkSharedPortability: metadata + JVM only. The Kotlin/Native test compile " +
                    "needs macOS and was skipped -- run this on the Mac or the macos CI job " +
                    "before trusting a green result for iOS."
            )
        }
    }
}

/**
 * The shared tests executed on an iOS simulator, rather than merely compiled for it.
 *
 * Separate from checkSharedPortability because it needs a simulator runtime and takes minutes
 * rather than seconds. This is the check that proves the two platforms actually agree on the
 * rules, instead of proving only that both can compile them.
 */
if (isMacOs) {
    tasks.register("checkSharedPortabilityNative") {
        group = "verification"
        description = "Runs the shared tests on an iOS simulator. macOS only."
        dependsOn(
            ":core-model:iosSimulatorArm64Test",
            ":core-domain:iosSimulatorArm64Test"
        )
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
