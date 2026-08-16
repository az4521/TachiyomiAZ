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
        read: Boolean = false
    ) = Chapter.create().apply {
        url = "/c$number"
        name = "Chapter $number"
        chapter_number = number
        this.read = read
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
}
