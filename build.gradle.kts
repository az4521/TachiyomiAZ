plugins {
    id("com.android.application") version BuildPluginsVersion.AGP apply false
    id("com.android.library") version BuildPluginsVersion.AGP apply false
    kotlin("android") version BuildPluginsVersion.KOTLIN apply false
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

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
