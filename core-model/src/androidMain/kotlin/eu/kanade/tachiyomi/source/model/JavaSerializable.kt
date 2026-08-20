package eu.kanade.tachiyomi.source.model

/** Resolves to the real `java.io.Serializable`, preserving the extension API's ABI. */
actual typealias JavaSerializable = java.io.Serializable
