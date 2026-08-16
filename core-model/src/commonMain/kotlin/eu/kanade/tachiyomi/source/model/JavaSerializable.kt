package eu.kanade.tachiyomi.source.model

/**
 * Alias for `java.io.Serializable` on JVM targets, and nothing at all elsewhere.
 *
 * [SManga] and [SChapter] extend `java.io.Serializable`, and extensions are compiled against
 * that, so it is part of the extension API's ABI and cannot simply be dropped. Expressing it as
 * an expect declaration that aliases the real interface on Android and the JVM keeps the emitted
 * bytecode byte-for-byte identical, while letting the same source compile for iOS, where the
 * interface does not exist.
 */
expect interface JavaSerializable
