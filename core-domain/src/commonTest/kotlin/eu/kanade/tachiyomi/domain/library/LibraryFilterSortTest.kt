package eu.kanade.tachiyomi.domain.library

import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.source.model.SManga
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryFilterSortTest {
    private fun entry(
        id: Long = 1,
        title: String = "A",
        unread: Int = 0,
        status: Int = SManga.ONGOING,
        source: Long = 1,
        lastUpdate: Long = 0,
        dateAdded: Long = 0
    ) = LibraryManga().apply {
        this.id = id
        this.title = title
        this.unread = unread
        this.status = status
        this.source = source
        this.last_update = lastUpdate
        this.date_added = dateAdded
    }

    /**
     * The whole of the tri-state, pinned because "include" and "exclude" are not opposites of
     * *each other* -- they are both opposites of showing everything, and getting that backwards
     * silently hides the library.
     */
    @Test
    fun `a tri-state filter includes excludes or ignores`() {
        assertTrue(FilterState.passes(FilterState.IGNORE, matches = true))
        assertTrue(FilterState.passes(FilterState.IGNORE, matches = false))
        assertTrue(FilterState.passes(FilterState.INCLUDE, matches = true))
        assertFalse(FilterState.passes(FilterState.INCLUDE, matches = false))
        assertFalse(FilterState.passes(FilterState.EXCLUDE, matches = true))
        assertTrue(FilterState.passes(FilterState.EXCLUDE, matches = false))
    }

    @Test
    fun `unread and completed filter on the row itself`() {
        val unread = entry(unread = 3)
        val read = entry(unread = 0)

        assertTrue(LibraryFilterSort.matchesFilters(unread, filterUnread = FilterState.INCLUDE))
        assertFalse(LibraryFilterSort.matchesFilters(read, filterUnread = FilterState.INCLUDE))
        assertTrue(LibraryFilterSort.matchesFilters(read, filterUnread = FilterState.EXCLUDE))

        val completed = entry(status = SManga.COMPLETED)
        assertTrue(LibraryFilterSort.matchesFilters(completed, filterCompleted = FilterState.INCLUDE))
        assertFalse(LibraryFilterSort.matchesFilters(entry(), filterCompleted = FilterState.INCLUDE))
    }

    /** Nothing is filtered when every filter is off, which is the default library. */
    @Test
    fun `an unfiltered library keeps everything`() {
        assertTrue(LibraryFilterSort.matchesFilters(entry(unread = 0, status = SManga.COMPLETED)))
    }

    /**
     * `downloadedOnly` is a separate preference from the downloaded filter, and turning it on
     * applies the filter even when the filter itself is set to ignore.
     */
    @Test
    fun `downloaded-only applies without the filter being set`() {
        val manga = entry()
        assertFalse(
            LibraryFilterSort.matchesFilters(manga, downloadedOnly = true, isDownloaded = { false })
        )
        assertTrue(
            LibraryFilterSort.matchesFilters(manga, downloadedOnly = true, isDownloaded = { true })
        )
    }

    @Test
    fun `the expensive filters are not asked when ignored`() {
        var asked = false
        LibraryFilterSort.matchesFilters(entry(), hasTracks = { asked = true; true })
        assertFalse(asked)

        LibraryFilterSort.matchesFilters(entry(), filterTracked = FilterState.INCLUDE, hasTracks = { asked = true; true })
        assertTrue(asked)
    }

    @Test
    fun `titles sort alphabetically ignoring case`() {
        val list = listOf(entry(id = 1, title = "beta"), entry(id = 2, title = "Alpha"))
        val sorted = list.sortedWith(LibraryFilterSort.comparator(LibrarySortMode.ALPHA, ascending = true))
        assertEquals(listOf("Alpha", "beta"), sorted.map { it.title })
    }

    /**
     * Descending is the same comparator reversed, so a mode that already reads newest-first --
     * last checked and date added both compare second to first -- flips with it.
     */
    @Test
    fun `descending reverses whatever the mode ordered`() {
        val older = entry(id = 1, title = "older", lastUpdate = 100)
        val newer = entry(id = 2, title = "newer", lastUpdate = 200)

        val ascending = listOf(older, newer)
            .sortedWith(LibraryFilterSort.comparator(LibrarySortMode.LAST_CHECKED, ascending = true))
        assertEquals(listOf("newer", "older"), ascending.map { it.title })

        val descending = listOf(older, newer)
            .sortedWith(LibraryFilterSort.comparator(LibrarySortMode.LAST_CHECKED, ascending = false))
        assertEquals(listOf("older", "newer"), descending.map { it.title })
    }

    /**
     * The modes the database answers arrive as positions. A title with no position sorts last --
     * never read, or no chapters yet -- rather than first, which is what a 0 default would do.
     */
    @Test
    fun `a title missing from a lookup sorts last`() {
        val known = entry(id = 1, title = "read")
        val unknown = entry(id = 2, title = "never read")
        val order = mapOf(1L to 0)

        val sorted = listOf(unknown, known).sortedWith(
            LibraryFilterSort.comparator(LibrarySortMode.LAST_READ, ascending = true, lastReadOrder = { order })
        )
        assertEquals(listOf("read", "never read"), sorted.map { it.title })
    }

    @Test
    fun `drag-and-drop leaves the order alone`() {
        val list = listOf(entry(id = 1, title = "b"), entry(id = 2, title = "a"))
        val sorted = list.sortedWith(LibraryFilterSort.comparator(LibrarySortMode.DRAG_AND_DROP, ascending = true))
        assertEquals(listOf("b", "a"), sorted.map { it.title })
    }

    /**
     * Each ordering is an aggregate over the whole chapters table, so building one the mode does
     * not read is what made sorting cost as much as loading the library.
     */
    @Test
    fun `only the ordering the mode reads is built`() {
        var lastRead = 0
        var total = 0
        var latest = 0

        LibraryFilterSort.comparator(
            mode = LibrarySortMode.LAST_READ,
            ascending = true,
            lastReadOrder = { lastRead++; emptyMap() },
            totalChapterOrder = { total++; emptyMap() },
            latestChapterOrder = { latest++; emptyMap() }
        )

        assertEquals(1, lastRead)
        assertEquals(0, total, "chapter counts are not read by a last-read sort")
        assertEquals(0, latest, "latest chapters are not read by a last-read sort")
    }

    @Test
    fun `an alphabetical sort builds no ordering at all`() {
        var built = 0
        val count = { built++; emptyMap<Long, Int>() }

        LibraryFilterSort.comparator(
            mode = LibrarySortMode.ALPHA,
            ascending = true,
            lastReadOrder = count,
            totalChapterOrder = count,
            latestChapterOrder = count
        )

        assertEquals(0, built)
    }

    /** Resolving a source id reaches the extension manager, so it must not run per comparison. */
    @Test
    fun `a source name is resolved once per source`() {
        val lookups = mutableListOf<Long>()
        val list =
            listOf(
                entry(id = 1, title = "a", source = 3),
                entry(id = 2, title = "b", source = 1),
                entry(id = 3, title = "c", source = 2)
            )

        list.sortedWith(
            LibraryFilterSort.comparator(
                mode = LibrarySortMode.SOURCE,
                ascending = true,
                sourceName = { lookups += it; "source" }
            )
        )

        assertEquals(lookups.distinct().size, lookups.size, "each source should be looked up once")
    }
}
