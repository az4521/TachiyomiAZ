package eu.kanade.tachiyomi.domain.download

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadCleanupTest {
    private fun chapters(count: Int): List<Chapter> =
        (0 until count).map { index ->
            ChapterImpl().apply {
                url = "/chapter/$index"
                name = "Chapter $index"
                chapter_number = index.toFloat()
            }
        }

    /**
     * The settings entries are "last read chapter", "second to last" and so on, stored as 0, 1, 2.
     * Pinning them here because the numbers are what is in preferences already, and because an
     * off-by-one deletes a chapter the reader has not got to yet.
     */
    @Test
    fun `slots count backwards from the chapter just read`() {
        val list = chapters(6)

        assertEquals(list[3], DownloadCleanup.chapterToDeleteAfterRead(list, readIndex = 3, slots = 0))
        assertEquals(list[2], DownloadCleanup.chapterToDeleteAfterRead(list, readIndex = 3, slots = 1))
        assertEquals(list[1], DownloadCleanup.chapterToDeleteAfterRead(list, readIndex = 3, slots = 2))
        assertEquals(list[0], DownloadCleanup.chapterToDeleteAfterRead(list, readIndex = 3, slots = 3))
    }

    @Test
    fun `keeping everything is the default and deletes nothing`() {
        val list = chapters(6)
        assertNull(DownloadCleanup.chapterToDeleteAfterRead(list, readIndex = 5, slots = DownloadCleanup.KEEP_ALL))
        assertNull(DownloadCleanup.chapterToDeleteAfterRead(list, readIndex = 5, slots = -2))
    }

    /**
     * Early in a title there is nothing far enough back to delete. That is the ordinary case, not
     * an error, and it must not wrap around to the end of the list -- which is what a negative
     * index would do if it reached a list accessor that allowed it.
     */
    @Test
    fun `nothing is deleted before enough chapters have been read`() {
        val list = chapters(6)
        assertNull(DownloadCleanup.chapterToDeleteAfterRead(list, readIndex = 1, slots = 2))
        assertNull(DownloadCleanup.chapterToDeleteAfterRead(list, readIndex = 0, slots = 1))
        assertNull(DownloadCleanup.chapterToDeleteAfterRead(list, readIndex = -1, slots = 0))
    }

    /**
     * The reader holds its chapters newest-first, so the caller reverses before asking. Pinning
     * what happens if one does not: with a descending list the rule selects chapters *newer* than
     * the one just read -- unread pages, deleted without warning.
     */
    @Test
    fun `reading order is what the positions mean`() {
        val ascending = chapters(6)
        val descending = ascending.reversed()

        // Read chapter 3 of 0..5. One slot back is chapter 2.
        assertEquals(ascending[2], DownloadCleanup.chapterToDeleteAfterRead(ascending, readIndex = 3, slots = 1))

        // The same arithmetic over the display list lands on chapter 4, which has not been read.
        val readIndexInDescending = descending.indexOfFirst { it.url == ascending[3].url }
        assertEquals(
            ascending[4],
            DownloadCleanup.chapterToDeleteAfterRead(descending, readIndex = readIndexInDescending, slots = 1)
        )
    }

    @Test
    fun `a read index outside the list selects nothing`() {
        assertNull(DownloadCleanup.indexToDeleteAfterRead(count = 6, readIndex = 6, slots = 0))
        assertNull(DownloadCleanup.indexToDeleteAfterRead(count = 0, readIndex = 0, slots = 0))
    }

    @Test
    fun `a chapter can be given instead of its position`() {
        val list = chapters(6)
        assertEquals(list[1], DownloadCleanup.chapterToDeleteAfterRead(list, read = list[3], slots = 2))

        val absent = ChapterImpl().apply { url = "/chapter/elsewhere" }
        assertNull(DownloadCleanup.chapterToDeleteAfterRead(list, read = absent, slots = 0))
    }
}
