package eu.kanade.tachiyomi.domain.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The flags are persisted as a bitmask, so their values are a storage format, not an
 * implementation detail -- shifting a bit would silently migrate the wrong data for anyone whose
 * preference was written by an older build.
 */
class MigrationFlagsTest {
    @Test
    fun `bit values are pinned`() {
        assertEquals(0b001, MigrationFlags.CHAPTERS)
        assertEquals(0b010, MigrationFlags.CATEGORIES)
        assertEquals(0b100, MigrationFlags.TRACK)
    }

    @Test
    fun `each predicate reads only its own bit`() {
        assertTrue(MigrationFlags.hasChapters(MigrationFlags.CHAPTERS))
        assertFalse(MigrationFlags.hasCategories(MigrationFlags.CHAPTERS))
        assertFalse(MigrationFlags.hasTracks(MigrationFlags.CHAPTERS))
    }

    @Test
    fun `combined flags are all detected`() {
        val all = MigrationFlags.CHAPTERS or MigrationFlags.CATEGORIES or MigrationFlags.TRACK
        assertTrue(MigrationFlags.hasChapters(all))
        assertTrue(MigrationFlags.hasCategories(all))
        assertTrue(MigrationFlags.hasTracks(all))
    }

    @Test
    fun `nothing is set in an empty mask`() {
        assertFalse(MigrationFlags.hasChapters(0))
        assertFalse(MigrationFlags.hasCategories(0))
        assertFalse(MigrationFlags.hasTracks(0))
    }

    @Test
    fun `positions round trip back to the same mask`() {
        val mask = MigrationFlags.CHAPTERS or MigrationFlags.TRACK
        val positions = MigrationFlags.getEnabledFlagsPositions(mask)

        assertEquals(listOf(0, 2), positions)
        assertEquals(mask, MigrationFlags.getFlagsFromPositions(positions.toTypedArray()))
    }
}
