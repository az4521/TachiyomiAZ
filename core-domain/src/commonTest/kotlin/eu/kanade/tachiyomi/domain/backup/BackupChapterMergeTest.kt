package eu.kanade.tachiyomi.domain.backup

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a backup merges onto what is already stored. This is where read state survives a restore,
 * so the asymmetries are the point: a backup is a snapshot of one moment, and the device may
 * legitimately be further along than it.
 */
class BackupChapterMergeTest {
    private fun manga() = Manga.create(1L).apply { id = 7L; url = "/m"; title = "M" }

    private fun chapter(
        url: String,
        read: Boolean = false,
        lastPage: Int = 0,
        bookmark: Boolean = false,
        id: Long? = null
    ) = Chapter.create().apply {
        this.url = url
        this.name = url
        this.read = read
        this.last_page_read = lastPage
        this.bookmark = bookmark
        this.id = id
    }

    @Test
    fun `matched chapters take the existing row id so the write updates rather than duplicates`() {
        val backup = listOf(chapter("/c1"))
        val db = listOf(chapter("/c1", id = 99L))

        mergeBackupChapters(manga(), backup, db)

        assertEquals(99L, backup.single().id)
    }

    @Test
    fun `unmatched chapters keep a null id`() {
        val backup = listOf(chapter("/new"))
        val db = listOf(chapter("/c1", id = 99L))

        mergeBackupChapters(manga(), backup, db)

        assertNull(backup.single().id, "a chapter with no existing row must not be given one")
    }

    @Test
    fun `every chapter is attached to the manga`() {
        val backup = listOf(chapter("/c1"), chapter("/c2"))

        mergeBackupChapters(manga(), backup, emptyList())

        assertTrue(backup.all { it.manga_id == 7L })
    }

    @Test
    fun `a chapter already read here is not un-read by the backup`() {
        val backup = listOf(chapter("/c1", read = false))
        val db = listOf(chapter("/c1", read = true, lastPage = 20, id = 1L))

        mergeBackupChapters(manga(), backup, db)

        assertTrue(backup.single().read, "restoring must never lose read state")
        assertEquals(20, backup.single().last_page_read)
    }

    @Test
    fun `an existing page position is not rewound to zero`() {
        val backup = listOf(chapter("/c1", lastPage = 0))
        val db = listOf(chapter("/c1", lastPage = 14, id = 1L))

        mergeBackupChapters(manga(), backup, db)

        assertEquals(14, backup.single().last_page_read)
    }

    @Test
    fun `a further-along backup wins over an earlier local position`() {
        val backup = listOf(chapter("/c1", read = true, lastPage = 30))
        val db = listOf(chapter("/c1", read = false, lastPage = 5, id = 1L))

        mergeBackupChapters(manga(), backup, db)

        assertTrue(backup.single().read)
        assertEquals(30, backup.single().last_page_read)
    }

    @Test
    fun `bookmarks are additive`() {
        val backup = listOf(chapter("/c1", bookmark = false), chapter("/c2", bookmark = true))
        val db = listOf(chapter("/c1", bookmark = true, id = 1L), chapter("/c2", bookmark = false, id = 2L))

        mergeBackupChapters(manga(), backup, db)

        assertTrue(backup.all { it.bookmark }, "a bookmark on either side must survive")
    }

    @Test
    fun `matching is by url, not by position`() {
        val backup = listOf(chapter("/c2"), chapter("/c1"))
        val db = listOf(chapter("/c1", id = 1L), chapter("/c2", id = 2L))

        mergeBackupChapters(manga(), backup, db)

        assertEquals(2L, backup[0].id)
        assertEquals(1L, backup[1].id)
    }
}
