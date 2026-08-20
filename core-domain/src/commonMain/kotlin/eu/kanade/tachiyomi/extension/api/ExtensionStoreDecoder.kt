package eu.kanade.tachiyomi.extension.api

import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.gzip
// okio's own use, not kotlin.io's: that one is for java.io.Closeable and does not exist in common
// code. This is the only edit the decoder needed to leave :app.
import okio.use

/**
 * Reads a store index off the wire, in whichever form the repository served it.
 *
 * Lifted out of `ExtensionGithubApi` unchanged rather than rewritten -- this is the logic that
 * already works against real repositories, and a second implementation for iOS would be a second
 * opinion about what a repository contains. `:app` calls these too, so there is one decoder.
 *
 * Shared safely because okio and kotlinx-serialization are both multiplatform: only the *fetching*
 * was Android-specific, and that stays with each platform's HTTP client.
 *
 * Format is decided by reading the content, never the URL: an index may be served under any file
 * name and any extension, so the name promises nothing.
 *
 * The legacy flat-array index is not handled here on purpose. It describes APK extensions, which
 * only Android can load, so `:app` keeps that path to itself.
 */
class ExtensionStoreDecoder(
    private val json: Json,
    private val protoBuf: ProtoBuf = ProtoBuf { }
) {
    /**
     * Gzip is detected by magic number rather than by Content-Encoding, because a repository may
     * simply serve a pre-compressed file, which no HTTP layer will unwrap for you.
     */
    fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzip = peek().use { peeked ->
            try {
                peeked.readShort().toInt() == 0x1f8b
            } catch (_: Exception) {
                false
            }
        }
        return if (isGzip) gzip().buffer() else this
    }

    /** `{` means JSON; anything else is taken for protobuf, as protobuf has no magic number. */
    fun decodeStore(source: BufferedSource): NetworkExtensionStore =
        when (source.peek().readByte()) {
            '{'.code.toByte() -> json.decodeFromBufferedSource(source)
            else -> protoBuf.decodeFromByteArray(source.readByteArray())
        }

    /** The same choice for a standalone list, which an index may point at via extensionListUrl. */
    fun decodeExtensionList(source: BufferedSource): NetworkExtensionStore.ExtensionList =
        when (source.peek().readByte()) {
            '{'.code.toByte() -> json.decodeFromBufferedSource(source)
            else -> protoBuf.decodeFromByteArray(source.readByteArray())
        }

    /**
     * For callers holding the whole body already, which is every caller that is not streaming --
     * Swift among them, since `URLSession` hands back a complete `Data`.
     *
     * An adapter over the streaming path above, not a second implementation: same gzip check, same
     * format choice, same decoders.
     */
    fun decodeStore(bytes: ByteArray): NetworkExtensionStore =
        Buffer().write(bytes).decompressIfGzipped().use { decodeStore(it) }

    companion object {
        /**
         * The decoder configured the way repositories need it.
         *
         * `:app` passes its own injected `Json`; this exists so Swift does not have to build a
         * `Json` across the interop boundary to get the same behaviour.
         */
        fun default(): ExtensionStoreDecoder =
            ExtensionStoreDecoder(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
            )
    }
}
