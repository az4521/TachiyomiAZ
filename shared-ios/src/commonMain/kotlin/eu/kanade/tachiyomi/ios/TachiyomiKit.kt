package eu.kanade.tachiyomi.ios

/**
 * The umbrella module needs at least one source file to exist.
 *
 * A Kotlin/Native compilation with no sources is NO-SOURCE, which makes Gradle skip the framework
 * link tasks and still report the build as successful -- so an empty umbrella produces no
 * XCFramework and no error either. This file is what stops that.
 *
 * It doubles as a load check from Swift: if `TachiyomiKit.description` returns, the framework is
 * present, linked and running Kotlin code, which separates "the framework is wired up wrong" from
 * "the shared logic is misbehaving" when the first Swift integration goes sideways.
 */
object TachiyomiKit {
    /** Modules packaged into this framework, in dependency order. */
    val modules: List<String> = listOf("core-model", "core-database", "core-domain")

    val description: String get() = "TachiyomiKit packaging ${modules.size} shared modules"
}
