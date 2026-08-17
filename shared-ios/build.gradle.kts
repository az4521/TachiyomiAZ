import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// The umbrella. This module holds no code -- its only job is to package the three shared modules
// into one framework that Swift can import.
//
// It has to exist. Kotlin/Native can emit a framework per module, but each one carries its own
// copy of the Kotlin runtime, and types are not shared across that boundary: a Manga handed out by
// a :core-model framework would be a different Swift type from the Manga a :core-domain framework
// expects. One framework exporting all three is what keeps them the same types.
//
// export() rather than plain dependencies: without it the modules are linked in but their API is
// not visible to Swift, which produces a framework that compiles and exposes almost nothing. The
// exported projects must be api dependencies below for that to be allowed.
plugins {
    kotlin("multiplatform")
}

kotlin {
    val xcf = XCFramework("TachiyomiKit")

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "TachiyomiKit"
            // Static: the framework is linked into the app at build time rather than shipped as a
            // separate dynamic library that has to be embedded and code-signed.
            isStatic = true
            xcf.add(this)

            export(project(":core-model"))
            export(project(":core-database"))
            export(project(":core-domain"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core-model"))
            api(project(":core-database"))
            api(project(":core-domain"))
        }
    }
}
