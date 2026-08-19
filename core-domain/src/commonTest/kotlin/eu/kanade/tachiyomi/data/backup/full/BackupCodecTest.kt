package eu.kanade.tachiyomi.data.backup.full

import eu.kanade.tachiyomi.data.backup.full.models.Backup
import eu.kanade.tachiyomi.data.backup.full.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.full.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.full.models.BackupManga
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
class BackupCodecTest {
    private fun sampleBackup() =
        Backup(
            backupManga =
                listOf(
                    BackupManga(source = 42L, url = "/manga/1", title = "One").apply {
                        chapters =
                            listOf(
                                BackupChapter(url = "/manga/1/c1", name = "Chapter 1").apply {
                                    read = true
                                    lastPageRead = 7
                                }
                            )
                        categories = listOf(0)
                        viewerFlags = 2
                        excludedScanlators = listOf("Group A")
                    }
                ),
            backupCategories = listOf(BackupCategory(name = "Reading", order = 0))
        )

    @Test
    fun `a backup survives a round trip`() {
        val restored = BackupCodec.decode(BackupCodec.encode(sampleBackup()))

        assertEquals(1, restored.backupManga.size)
        val manga = restored.backupManga.first()
        assertEquals(42L, manga.source)
        assertEquals("/manga/1", manga.url)
        assertEquals("One", manga.title)
        assertEquals(listOf(0), manga.categories)
        assertEquals(1, manga.chapters.size)
        assertTrue(manga.chapters.first().read)
        assertEquals(7, manga.chapters.first().lastPageRead)
        assertEquals(listOf("Reading"), restored.backupCategories.map { it.name })
    }

    /**
     * The Mihon-only fields, which this app never writes but must read.
     */
    @Test
    fun `mihon reading mode and scanlator filter survive`() {
        val manga = BackupCodec.decode(BackupCodec.encode(sampleBackup())).backupManga.first()

        assertEquals(2, manga.viewerFlags)
        assertEquals(listOf("Group A"), manga.excludedScanlators)
    }

    /**
     * The iOS app appends its own state in field 501, which this model does not declare. A decoder
     * that rejected unknown fields would mean an iOS backup could not be restored on Android at
     * all -- and the same for anything written by a newer version of either app.
     */
    @Test
    fun `an unknown field does not fail the decode`() {
        val encoded = BackupCodec.encode(sampleBackup())

        // field 501, wire type 2 (length-delimited): tag = 501 shl 3 or 2 = 4010, varint-encoded.
        val payload = "ios-only".encodeToByteArray()
        val withUnknownField =
            encoded + byteArrayOf(
                0xAA.toByte(), 0x1F, // tag 4010
                payload.size.toByte()
            ) + payload

        val restored = BackupCodec.decode(withUnknownField)

        assertEquals(1, restored.backupManga.size)
        assertEquals("One", restored.backupManga.first().title)
    }

    /**
     * Two encodes of the same backup must produce the same bytes, or the CI check that compares a
     * rebuilt artifact against a committed one has nothing stable to compare.
     */
    @Test
    fun `encoding is deterministic`() {
        assertContentEquals(BackupCodec.encode(sampleBackup()), BackupCodec.encode(sampleBackup()))
    }

    /**
     * The iOS state field round-trips, and a backup without it stays byte-identical to one from
     * before the field existed -- which is what makes declaring it safe for the Android app.
     */
    @Test
    fun `the ios state field round-trips and is omitted when absent`() {
        val withState = sampleBackup().apply { iosState = "{\"sessions\":[]}".encodeToByteArray() }
        val restored = BackupCodec.decode(BackupCodec.encode(withState))
        assertContentEquals("{\"sessions\":[]}".encodeToByteArray(), restored.iosState)

        val without = BackupCodec.encode(sampleBackup())
        assertEquals(null, BackupCodec.decode(without).iosState)
        // No trace of field 501 in the bytes when it is null.
        assertTrue(without.toList().windowed(2).none { it == listOf(0xAA.toByte(), 0x1F.toByte()) })
    }
}
