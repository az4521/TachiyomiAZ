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
    fun `matched chapters are updated and take the existing row id`() {
        val backup = listOf(chapter("/c1", read = true))
        val db = listOf(chapter("/c1", id = 99L))

        val result = mergeBackupChapters(manga(), backup, db)

        assertEquals(1, result.toUpdate.size)
        assertTrue(result.toInsert.isEmpty())
        assertEquals(99L, result.toUpdate.single().id)
    }

    /**
     * The reason the merge partitions at all. These have no id, so handing them to an update --
     * which writes by id -- silently does nothing, and the chapter is lost.
     */
    @Test
    fun `chapters the database has never seen are inserted rather than updated`() {
        val backup = listOf(chapter("/new"))
        val db = listOf(chapter("/c1", id = 99L))

        val result = mergeBackupChapters(manga(), backup, db)

        assertTrue(result.toUpdate.isEmpty())
        assertEquals(1, result.toInsert.size)
        assertNull(result.toInsert.single().id)
    }

    @Test
    fun `a chapter already stored exactly as the backup has it is not written at all`() {
        val stored = chapter("/c1", read = true, lastPage = 5, bookmark = true, id = 3L)
        val backup = listOf(chapter("/c1", read = true, lastPage = 5, bookmark = true))

        val result = mergeBackupChapters(manga(), backup, listOf(stored))

        assertTrue(result.toUpdate.isEmpty(), "an unchanged chapter should not be rewritten")
        assertTrue(result.toInsert.isEmpty())
    }

    @Test
    fun `every chapter is attached to the manga`() {
        val backup = listOf(chapter("/c1"), chapter("/c2"))

        val result = mergeBackupChapters(manga(), backup, emptyList())

        assertTrue(result.toInsert.all { it.manga_id == 7L })
    }

    @Test
    fun `a chapter already read here is not un-read by the backup`() {
        val backup = listOf(chapter("/c1", read = false))
        val db = listOf(chapter("/c1", read = true, lastPage = 20, id = 1L))

        val merged = mergeBackupChapters(manga(), backup, db).toUpdate.single()

        assertTrue(merged.read, "restoring must never lose read state")
        assertEquals(20, merged.last_page_read)
    }

    @Test
    fun `an existing page position is not rewound to zero`() {
        val backup = listOf(chapter("/c1", lastPage = 0))
        val db = listOf(chapter("/c1", lastPage = 14, id = 1L))

        val merged = mergeBackupChapters(manga(), backup, db).toUpdate.single()

        assertEquals(14, merged.last_page_read)
    }

    @Test
    fun `a further-along backup wins over an earlier local position`() {
        val backup = listOf(chapter("/c1", read = true, lastPage = 30))
        val db = listOf(chapter("/c1", read = false, lastPage = 5, id = 1L))

        val merged = mergeBackupChapters(manga(), backup, db).toUpdate.single()

        assertTrue(merged.read)
        assertEquals(30, merged.last_page_read)
    }

    @Test
    fun `bookmarks are additive`() {
        val backup = listOf(chapter("/c1", bookmark = false), chapter("/c2", bookmark = true))
        val db = listOf(chapter("/c1", bookmark = true, id = 1L), chapter("/c2", bookmark = false, id = 2L))

        val result = mergeBackupChapters(manga(), backup, db)

        assertTrue(result.toUpdate.all { it.bookmark }, "a bookmark on either side must survive")
    }

    @Test
    fun `matching is by url rather than by position`() {
        // read differs, so neither is skipped as unchanged.
        val backup = listOf(chapter("/c2", read = true), chapter("/c1", read = true))
        val db = listOf(chapter("/c1", id = 1L), chapter("/c2", id = 2L))

        val result = mergeBackupChapters(manga(), backup, db)

        assertEquals(2L, result.toUpdate.first { it.url == "/c2" }.id)
        assertEquals(1L, result.toUpdate.first { it.url == "/c1" }.id)
    }
}
