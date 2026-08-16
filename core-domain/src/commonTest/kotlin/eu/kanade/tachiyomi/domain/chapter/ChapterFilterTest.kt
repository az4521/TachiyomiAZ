package eu.kanade.tachiyomi.domain.chapter

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chapter filters and sort mode are stored in `Manga.chapter_flags`, so they travel with the
 * manga and with backups. Both platforms have to apply them identically or the same manga shows a
 * different chapter list on each -- which, after restoring a backup, would look like data loss.
 */
class ChapterFilterTest {
    // Deliberately not source 0: that is LOCAL_SOURCE_ID, where every chapter counts as
    // downloaded and the download filter is a no-op.
    private fun manga() =
        Manga.create(1L).apply {
            url = "/manga"
            title = "Manga"
        }

    private fun chapter(
        number: Float,
        order: Int,
        read: Boolean = false,
        bookmark: Boolean = false,
        upload: Long = 0L
    ) = Chapter.create().apply {
        this.url = "/c$number"
        this.name = "Chapter $number"
        this.chapter_number = number
        this.source_order = order
        this.read = read
        this.bookmark = bookmark
        this.date_upload = upload
    }

    @Test
    fun `no filters keeps every chapter`() {
        val chapters = listOf(chapter(1f, 2), chapter(2f, 1), chapter(3f, 0))
        val result = filterAndSortChapters(chapters, manga())
        assertEquals(3, result.size)
    }

    @Test
    fun `unread filter drops read chapters`() {
        val chapters = listOf(chapter(1f, 2, read = true), chapter(2f, 1), chapter(3f, 0))
        val m = manga().apply { readFilter = Manga.SHOW_UNREAD }
        val result = filterAndSortChapters(chapters, m)
        assertEquals(2, result.size)
        assertTrue(result.none { it.read })
    }

    @Test
    fun `read filter is the exact complement of the unread filter`() {
        val chapters = listOf(chapter(1f, 2, read = true), chapter(2f, 1), chapter(3f, 0))
        val unread = filterAndSortChapters(chapters, manga().apply { readFilter = Manga.SHOW_UNREAD })
        val read = filterAndSortChapters(chapters, manga().apply { readFilter = Manga.SHOW_READ })
        assertEquals(chapters.size, unread.size + read.size)
        assertTrue(read.all { it.read })
    }

    @Test
    fun `bookmarked filter keeps only bookmarks`() {
        val chapters = listOf(chapter(1f, 1, bookmark = true), chapter(2f, 0))
        val m = manga().apply { bookmarkedFilter = Manga.SHOW_BOOKMARKED }
        assertEquals(1, filterAndSortChapters(chapters, m).size)
    }

    @Test
    fun `downloaded filter consults the supplied predicate`() {
        val chapters = listOf(chapter(1f, 1), chapter(2f, 0))
        val m = manga().apply { downloadedFilter = Manga.SHOW_DOWNLOADED }
        val result = filterAndSortChapters(chapters, m) { it.chapter_number == 1f }
        assertEquals(1, result.size)
        assertEquals(1f, result.single().chapter_number)
    }

    @Test
    fun `local manga count as downloaded regardless of the predicate`() {
        val chapters = listOf(chapter(1f, 1), chapter(2f, 0))
        // LOCAL_SOURCE_ID: pages are already on disk, so the download filter must not hide them.
        val m = Manga.create(0L).apply { url = "/l"; title = "Local"; downloadedFilter = Manga.SHOW_DOWNLOADED }
        assertEquals(2, filterAndSortChapters(chapters, m) { false }.size)
    }

    @Test
    fun `sorting by number honours the direction flag`() {
        val chapters = listOf(chapter(2f, 0), chapter(1f, 1), chapter(3f, 2))
        val m = manga().apply { sorting = Manga.SORTING_NUMBER }

        m.setChapterOrder(Manga.SORT_ASC)
        assertEquals(listOf(1f, 2f, 3f), filterAndSortChapters(chapters, m).map { it.chapter_number })

        m.setChapterOrder(Manga.SORT_DESC)
        assertEquals(listOf(3f, 2f, 1f), filterAndSortChapters(chapters, m).map { it.chapter_number })
    }

    /**
     * Source order is stored newest-first, so it compares the opposite way round to the other
     * modes. Pinned deliberately: it reads like a bug and is not one.
     */
    @Test
    fun `source order sorts inversely to the other modes`() {
        val chapters = listOf(chapter(1f, 0), chapter(2f, 1), chapter(3f, 2))
        val m = manga().apply { sorting = Manga.SORTING_SOURCE }

        m.setChapterOrder(Manga.SORT_DESC)
        assertEquals(listOf(0, 1, 2), filterAndSortChapters(chapters, m).map { it.source_order })

        m.setChapterOrder(Manga.SORT_ASC)
        assertEquals(listOf(2, 1, 0), filterAndSortChapters(chapters, m).map { it.source_order })
    }

    @Test
    fun `sorting by upload date honours the direction flag`() {
        val chapters = listOf(chapter(1f, 0, upload = 300), chapter(2f, 1, upload = 100), chapter(3f, 2, upload = 200))
        val m = manga().apply { sorting = Manga.SORTING_UPLOAD_DATE }

        m.setChapterOrder(Manga.SORT_ASC)
        assertEquals(listOf(100L, 200L, 300L), filterAndSortChapters(chapters, m).map { it.date_upload })

        m.setChapterOrder(Manga.SORT_DESC)
        assertEquals(listOf(300L, 200L, 100L), filterAndSortChapters(chapters, m).map { it.date_upload })
    }

    @Test
    fun `filters combine`() {
        val chapters =
            listOf(
                chapter(1f, 0, read = true, bookmark = true),
                chapter(2f, 1, bookmark = true),
                chapter(3f, 2)
            )
        val m =
            manga().apply {
                readFilter = Manga.SHOW_UNREAD
                bookmarkedFilter = Manga.SHOW_BOOKMARKED
            }
        val result = filterAndSortChapters(chapters, m)
        assertEquals(1, result.size)
        assertEquals(2f, result.single().chapter_number)
    }
}
