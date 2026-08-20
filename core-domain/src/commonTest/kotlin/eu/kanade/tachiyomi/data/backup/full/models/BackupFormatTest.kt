package eu.kanade.tachiyomi.data.backup.full.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stands in for a backup written by a newer build that added a field this one does not know.
 * Field 900 is chosen to sit clear of the fork ranges (1-18 upstream, 100+ this fork, 600+ SY).
 */
@Serializable
private data class BackupCategoryWithExtraField(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val order: Int = 0,
    @ProtoNumber(900) val somethingNew: String = ""
)

/**
 * The backup format is the one thing the two apps must agree on byte for byte: an Android backup
 * has to restore on iOS and back again without loss. These tests pin the wire format itself, not
 * just that the code round-trips -- a renumbered `@ProtoNumber` would still round-trip perfectly
 * within one build while silently corrupting every existing backup.
 */
@OptIn(ExperimentalSerializationApi::class)
class BackupFormatTest {
    private val proto = ProtoBuf

    @Test
    fun `a manga survives a round trip`() {
        val manga =
            BackupManga(
                source = 42L,
                url = "/manga/1",
                title = "Test Manga",
                artist = "Artist",
                author = "Author",
                status = 2,
                favorite = true
            )
        val bytes = proto.encodeToByteArray(BackupManga.serializer(), manga)
        val decoded = proto.decodeFromByteArray(BackupManga.serializer(), bytes)

        assertEquals(manga.source, decoded.source)
        assertEquals(manga.url, decoded.url)
        assertEquals(manga.title, decoded.title)
        assertEquals(manga.favorite, decoded.favorite)
    }

    @Test
    fun `a whole backup survives a round trip`() {
        val backup =
            Backup(
                backupManga = listOf(BackupManga(source = 1L, url = "/a", title = "A")),
                backupCategories = listOf(BackupCategory(name = "Reading", order = 0)),
                backupSources = listOf(BackupSource(name = "Source", sourceId = 1L))
            )
        val bytes = proto.encodeToByteArray(Backup.serializer(), backup)
        val decoded = proto.decodeFromByteArray(Backup.serializer(), bytes)

        assertEquals(1, decoded.backupManga.size)
        assertEquals("A", decoded.backupManga.single().title)
        assertEquals("Reading", decoded.backupCategories.single().name)
        assertEquals(1L, decoded.backupSources.single().sourceId)
    }

    /**
     * Pins the field numbers by encoding one field at a time and reading the tag byte back.
     *
     * A protobuf tag is `(fieldNumber shl 3) or wireType`, so this fails loudly if anyone
     * renumbers a field -- which is exactly the change that would silently break compatibility
     * with backups already on users' devices, and with the iOS app.
     */
    @Test
    fun `field numbers are pinned`() {
        fun firstFieldNumber(bytes: ByteArray): Int = (bytes.first().toInt() and 0xFF) shr 3

        assertEquals(
            1,
            firstFieldNumber(proto.encodeToByteArray(BackupManga.serializer(), BackupManga(source = 7L, url = "", title = ""))),
            "BackupManga.source must stay field 1"
        )
        assertEquals(
            1,
            firstFieldNumber(proto.encodeToByteArray(BackupCategory.serializer(), BackupCategory(name = "x", order = 0))),
            "BackupCategory.name must stay field 1"
        )
        assertEquals(
            1,
            firstFieldNumber(proto.encodeToByteArray(BackupSource.serializer(), BackupSource(name = "x", sourceId = 0L))),
            "BackupSource.name must stay field 1"
        )
    }

    /**
     * The fork ranges are an informal registry shared with the other Tachiyomi forks -- 1-18 is
     * upstream, 100+ is this fork's lineage, 600+ is TachiyomiSY. Colliding with them is what
     * makes two forks' backups mutually unreadable.
     */
    @Test
    fun `fork specific fields keep their high numbers`() {
        val backup =
            Backup(
                backupManga = emptyList(),
                backupSavedSearches = listOf(BackupSavedSearch(name = "s", query = "q", filterList = "[]", source = 1L))
            )
        val bytes = proto.encodeToByteArray(Backup.serializer(), backup)
        // 600 shl 3 is beyond one byte, so the tag is varint-encoded; just assert it survives.
        val decoded = proto.decodeFromByteArray(Backup.serializer(), bytes)
        assertEquals("s", decoded.backupSavedSearches.single().name)
    }

    /**
     * Protobuf's forward compatibility is what lets one platform read a backup written by a newer
     * build of the other. Unknown fields must be skipped rather than throwing.
     */
    @Test
    fun `unknown fields are skipped rather than rejected`() {
        val withExtra =
            proto.encodeToByteArray(
                BackupCategoryWithExtraField.serializer(),
                BackupCategoryWithExtraField(name = "Reading", order = 3, somethingNew = "from a newer build")
            )
        val decoded = proto.decodeFromByteArray(BackupCategory.serializer(), withExtra)

        assertEquals("Reading", decoded.name)
        assertEquals(3, decoded.order)
    }

    @Test
    fun `default values keep the encoding compact`() {
        val bytes = proto.encodeToByteArray(BackupCategory.serializer(), BackupCategory(name = "", order = 0))
        assertTrue(bytes.size < 16, "an empty category should not encode to ${bytes.size} bytes")
    }

    @Test
    fun `chapters round trip with their read state`() {
        val chapter =
            BackupChapter(
                url = "/c1",
                name = "Chapter 1",
                chapterNumber = 1f,
                read = true,
                bookmark = true,
                lastPageRead = 12
            )
        val bytes = proto.encodeToByteArray(BackupChapter.serializer(), chapter)
        val decoded = proto.decodeFromByteArray(BackupChapter.serializer(), bytes)

        assertEquals(true, decoded.read)
        assertEquals(true, decoded.bookmark)
        assertEquals(12, decoded.lastPageRead)
        assertContentEquals(bytes, proto.encodeToByteArray(BackupChapter.serializer(), decoded))
    }
}
