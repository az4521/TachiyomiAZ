package eu.kanade.tachiyomi.domain.migration

import eu.kanade.tachiyomi.data.database.models.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Carrying reading progress to another source. The two sources share no chapter urls and number
 * their chapters differently, so progress can only cross by number -- which makes these rules the
 * only thing standing between a migration and a library that forgets what you have read.
 */
class MangaMigrationTest {
    private fun chapter(
        number: Float,
        read: Boolean = false,
        bookmark: Boolean = false,
        lastPageRead: Int = 0
    ) = Chapter.create().apply {
        url = "/c$number"
        name = "Chapter $number"
        chapter_number = number
        this.read = read
        this.bookmark = bookmark
        this.last_page_read = lastPageRead
    }

    @Test
    fun `the highest read chapter is found`() {
        val chapters = listOf(chapter(1f, read = true), chapter(3f, read = true), chapter(2f))
        assertEquals(3f, highestReadChapterNumber(chapters))
    }

    @Test
    fun `nothing read yields null`() {
        assertNull(highestReadChapterNumber(listOf(chapter(1f), chapter(2f))))
    }

    @Test
    fun `unread chapters do not raise the mark`() {
        val chapters = listOf(chapter(1f, read = true), chapter(9f))
        assertEquals(1f, highestReadChapterNumber(chapters))
    }

    @Test
    fun `everything up to and including the mark is read`() {
        val chapters = listOf(chapter(1f), chapter(2f), chapter(3f))
        markChaptersReadUpTo(chapters, 2f)

        assertTrue(chapters[0].read)
        assertTrue(chapters[1].read, "the mark itself counts as read")
        assertFalse(chapters[2].read)
    }

    @Test
    fun `decimal chapters either side of the mark are handled`() {
        val chapters = listOf(chapter(1.5f), chapter(2.5f))
        markChaptersReadUpTo(chapters, 2f)

        assertTrue(chapters[0].read)
        assertFalse(chapters[1].read)
    }

    /**
     * Unnumbered chapters carry -1, which is below every real number. Without the
     * isRecognizedNumber guard they would be silently marked read by any migration.
     */
    @Test
    fun `unnumbered chapters are never marked read`() {
        val unnumbered = Chapter.create().apply { url = "/extra"; name = "Extras" }
        val chapters = listOf(unnumbered, chapter(1f))

        markChaptersReadUpTo(chapters, 5f)

        assertFalse(unnumbered.read, "the -1 sentinel must not be treated as a chapter number")
        assertTrue(chapters[1].read)
    }

    @Test
    fun `only the changed chapters are returned`() {
        val chapters = listOf(chapter(1f), chapter(5f))
        val changed = markChaptersReadUpTo(chapters, 2f)

        assertEquals(1, changed.size)
        assertEquals(1f, changed.single().chapter_number)
    }

    @Test
    fun `chapters pair up by number across sources`() {
        val from = listOf(chapter(1f), chapter(2f))
        val to = listOf(chapter(2f), chapter(1f), chapter(3f))

        val pairs = pairChaptersByNumber(from, to)

        assertEquals(2, pairs.size, "the chapter the new source added has no counterpart")
        assertTrue(pairs.all { (a, b) -> a.chapter_number == b.chapter_number })
    }

    @Test
    fun `unnumbered chapters do not pair with each other`() {
        val from = listOf(Chapter.create().apply { url = "/a"; name = "Extras" })
        val to = listOf(Chapter.create().apply { url = "/b"; name = "Omake" })

        assertTrue(pairChaptersByNumber(from, to).isEmpty(), "the -1 sentinel is not a number two chapters share")
    }

    @Test
    fun `a duplicated chapter number resolves to the furthest read copy`() {
        val from = listOf(chapter(1f), chapter(1f, read = true))
        val to = listOf(chapter(1f))

        val (source, _) = pairChaptersByNumber(from, to).single()

        assertTrue(source.read)
    }

    @Test
    fun `bookmarks and the page left off at carry across`() {
        val from = listOf(chapter(1f, bookmark = true), chapter(2f, lastPageRead = 12))
        val to = listOf(chapter(1f), chapter(2f))

        copyChapterProgress(pairChaptersByNumber(from, to))

        assertTrue(to[0].bookmark)
        assertEquals(12, to[1].last_page_read)
    }

    @Test
    fun `progress already on the new entry is never walked backwards`() {
        val from = listOf(chapter(1f, lastPageRead = 3))
        val to = listOf(chapter(1f, lastPageRead = 20))

        copyChapterProgress(pairChaptersByNumber(from, to))

        assertEquals(20, to.single().last_page_read, "a re-run must not undo reading done since")
    }

    @Test
    fun `a read chapter keeps no page to return to`() {
        val from = listOf(chapter(1f, read = true, lastPageRead = 8))
        val to = listOf(chapter(1f))

        copyChapterProgress(pairChaptersByNumber(from, to))

        assertTrue(to.single().read)
        assertEquals(0, to.single().last_page_read)
    }

    @Test
    fun `only the changed chapters come back from a progress copy`() {
        val from = listOf(chapter(1f, read = true), chapter(2f))
        val to = listOf(chapter(1f), chapter(2f))

        val changed = copyChapterProgress(pairChaptersByNumber(from, to))

        assertEquals(1, changed.size)
        assertEquals(1f, changed.single().chapter_number)
    }
}
