package eu.kanade.tachiyomi.source.model

/** No Android Uri off-Android; nothing constructs this. */
actual abstract class PlatformUri {
    actual abstract override fun toString(): String
}
