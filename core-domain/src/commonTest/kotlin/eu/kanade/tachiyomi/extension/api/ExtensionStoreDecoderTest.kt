package eu.kanade.tachiyomi.extension.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.GzipSink
import okio.buffer
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the decoder that moved out of `:app`.
 *
 * Both formats and the gzip wrapper are covered because a repository picks whichever it likes and
 * the file name says nothing about it. These run on Kotlin/Native too, so iOS is held to the same
 * behaviour rather than assumed to inherit it.
 */
class ExtensionStoreDecoderTest {
    private val decoder = ExtensionStoreDecoder(Json { ignoreUnknownKeys = true; isLenient = true })

    private val store = NetworkExtensionStore(
        name = "Keiyoushi",
        badgeLabel = "KEI",
        signingKey = "key",
        contact = NetworkExtensionStore.Contact(website = "https://example.invalid"),
        extensionList = NetworkExtensionStore.ExtensionList(
            listOf(
                NetworkExtensionStore.Extension(
                    name = "Example",
                    packageName = "eu.kanade.tachiyomi.extension.en.example",
                    resources = NetworkExtensionStore.Resources(
                        apkUrl = "https://example.invalid/example.jar",
                        iconUrl = "https://example.invalid/example.png"
                    ),
                    extensionLib = "1.6",
                    versionCode = 3,
                    versionName = "1.6.3",
                    contentWarning = NetworkExtensionStore.ContentWarning.SAFE,
                    sources = emptyList()
                )
            )
        )
    )

    private fun gzip(bytes: ByteArray): ByteArray {
        val sink = Buffer()
        GzipSink(sink).buffer().use { it.write(bytes) }
        return sink.readByteArray()
    }

    private fun decode(bytes: ByteArray): NetworkExtensionStore = with(decoder) {
        Buffer().write(bytes).decompressIfGzipped().use { decodeStore(it) }
    }

    @Test
    fun `decodes a json store`() {
        val bytes = """
            {"name":"Keiyoushi","badgeLabel":"KEI","signingKey":"k",
             "contact":{"website":"https://example.invalid"}}
        """.trimIndent().encodeToByteArray()

        assertEquals("Keiyoushi", decode(bytes).name)
    }

    @Test
    fun `decodes a protobuf store`() {
        val bytes = ProtoBuf.encodeToByteArray(NetworkExtensionStore.serializer(), store)

        assertEquals("Keiyoushi", decode(bytes).name)
    }

    @Test
    fun `unwraps a gzipped protobuf store as real repositories serve`() {
        // keiyoushi's index.pb is gzipped protobuf, and nothing in HTTP unwraps a pre-compressed
        // file for you -- the magic-number check is what makes it readable at all.
        val bytes = gzip(ProtoBuf.encodeToByteArray(NetworkExtensionStore.serializer(), store))

        val decoded = decode(bytes)
        assertEquals("Keiyoushi", decoded.name)
        assertEquals(1, decoded.extensionList?.extensions?.size)
    }

    @Test
    fun `unwraps a gzipped json store`() {
        val bytes = gzip(
            """
            {"name":"Gz","badgeLabel":"G","signingKey":"k",
             "contact":{"website":"https://example.invalid"}}
            """.trimIndent().encodeToByteArray()
        )

        assertEquals("Gz", decode(bytes).name)
    }

    @Test
    fun `keeps the jar url carried in the apkUrl field`() {
        // The proto calls it apkUrl and that name is part of the wire format, but what it points at
        // for this port is a JAR. Renaming the field is not an option; reading it correctly is.
        val bytes = ProtoBuf.encodeToByteArray(NetworkExtensionStore.serializer(), store)

        assertEquals(
            "https://example.invalid/example.jar",
            decode(bytes).extensionList?.extensions?.single()?.resources?.apkUrl
        )
    }
}
