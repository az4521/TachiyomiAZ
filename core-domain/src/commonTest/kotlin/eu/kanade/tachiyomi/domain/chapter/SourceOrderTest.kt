package eu.kanade.tachiyomi.domain.chapter

import eu.kanade.tachiyomi.data.database.models.Chapter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a sync has anything to do when only the ordering differs.
 *
 * This decides whether syncChaptersWithSource takes its early return. If it says no, the sync
 * stops before ever reaching fixChaptersSourceOrder -- so a stored source_order that is wrong,
 * for any reason, can never be repaired by refreshing.
 */
class SourceOrderTest {
    private fun chapter(
        url: String,
        order: Int
    ) = Chapter.create().apply {
        this.url = url
        this.name = url
        this.source_order = order
    }

    @Test
    fun `matching order is not a change`() {
        val db = listOf(chapter("/c1", 0), chapter("/c2", 1))
        val source = listOf(chapter("/c1", 0), chapter("/c2", 1))
        assertFalse(sourceOrderChanged(db, source))
    }

    @Test
    fun `a stale stored order is a change`() {
        // The case that stranded existing libraries: same chapters, same metadata, but the stored
        // positions no longer match the source listing.
        val db = listOf(chapter("/c1", 5), chapter("/c2", 6))
        val source = listOf(chapter("/c1", 0), chapter("/c2", 1))
        assertTrue(sourceOrderChanged(db, source))
    }

    @Test
    fun `one chapter out of position is enough`() {
        val db = listOf(chapter("/c1", 0), chapter("/c2", 9))
        val source = listOf(chapter("/c1", 0), chapter("/c2", 1))
        assertTrue(sourceOrderChanged(db, source))
    }

    @Test
    fun `a source inserting a chapter shifts everything below it`() {
        // A new chapter takes position 0 and pushes the rest down. The new one is handled by
        // toAdd; this is about the ones that merely moved.
        val db = listOf(chapter("/c1", 0), chapter("/c2", 1))
        val source = listOf(chapter("/new", 0), chapter("/c1", 1), chapter("/c2", 2))
        assertTrue(sourceOrderChanged(db, source))
    }

    @Test
    fun `chapters the database has never seen are not counted`() {
        // They have no stored order to disagree with, and toAdd already covers them.
        val db = listOf(chapter("/c1", 0))
        val source = listOf(chapter("/c1", 0), chapter("/brand-new", 1))
        assertFalse(sourceOrderChanged(db, source))
    }

    @Test
    fun `an empty database is not a reorder`() {
        assertFalse(sourceOrderChanged(emptyList(), listOf(chapter("/c1", 0))))
    }
}
