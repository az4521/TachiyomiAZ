package eu.kanade.tachiyomi.domain.chapter

import eu.kanade.tachiyomi.data.database.models.Manga
import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterFlagsTest {
    @Test
    fun `each filter reads back what was written`() {
        for (filter in ChapterFilter.entries) {
            assertEquals(filter, ChapterFlags.readFilter(ChapterFlags.withReadFilter(0, filter)))
            assertEquals(filter, ChapterFlags.downloadedFilter(ChapterFlags.withDownloadedFilter(0, filter)))
            assertEquals(filter, ChapterFlags.bookmarkedFilter(ChapterFlags.withBookmarkedFilter(0, filter)))
        }
        for (sort in ChapterSort.entries) {
            assertEquals(sort, ChapterFlags.sort(ChapterFlags.withSort(0, sort)))
        }
    }

    /**
     * The reason this is one packed integer rather than several: setting one part must not disturb
     * the others. `replace` clears a mask before setting, which a plain `or` would not.
     */
    @Test
    fun `setting one part leaves the rest alone`() {
        var flags = 0
        flags = ChapterFlags.withReadFilter(flags, ChapterFilter.INCLUDE)
        flags = ChapterFlags.withSort(flags, ChapterSort.UPLOAD_DATE)
        flags = ChapterFlags.withAscending(flags, true)
        flags = ChapterFlags.withBookmarkedFilter(flags, ChapterFilter.EXCLUDE)

        assertEquals(ChapterFilter.INCLUDE, ChapterFlags.readFilter(flags))
        assertEquals(ChapterSort.UPLOAD_DATE, ChapterFlags.sort(flags))
        assertEquals(true, ChapterFlags.ascending(flags))
        assertEquals(ChapterFilter.EXCLUDE, ChapterFlags.bookmarkedFilter(flags))

        // Overwriting one leaves the others untouched.
        flags = ChapterFlags.withReadFilter(flags, ChapterFilter.EXCLUDE)
        assertEquals(ChapterFilter.EXCLUDE, ChapterFlags.readFilter(flags))
        assertEquals(ChapterSort.UPLOAD_DATE, ChapterFlags.sort(flags))
        assertEquals(ChapterFilter.EXCLUDE, ChapterFlags.bookmarkedFilter(flags))
    }

    /**
     * Pins the bits themselves, not just the round trip. Every existing database and backup already
     * holds these numbers, so they cannot be redefined -- a test that only checked round-tripping
     * would happily pass on a layout that no longer matched what is stored.
     */
    @Test
    fun `the layout is the one already on disk`() {
        assertEquals(Manga.SHOW_UNREAD, ChapterFlags.withReadFilter(0, ChapterFilter.INCLUDE))
        assertEquals(Manga.SHOW_READ, ChapterFlags.withReadFilter(0, ChapterFilter.EXCLUDE))
        assertEquals(Manga.SORTING_NUMBER, ChapterFlags.withSort(0, ChapterSort.NUMBER))
        assertEquals(Manga.SORTING_UPLOAD_DATE, ChapterFlags.withSort(0, ChapterSort.UPLOAD_DATE))
        assertEquals(Manga.SORT_ASC, ChapterFlags.withAscending(0, true))

        // The layout Aidoku used, which the iOS app read this column with: its sort method sat at
        // bits 1-3, overlapping Android's read and downloaded filters, and its unread filter at bit
        // 6 overlapped bookmarked. A value written by one app was nonsense to the other.
        val aidokuUnreadFilter = 1 shl 6
        assertEquals(ChapterFilter.EXCLUDE, ChapterFlags.bookmarkedFilter(aidokuUnreadFilter))
    }

    @Test
    fun `display mode round-trips and sits clear of the filters`() {
        for (mode in ChapterDisplayMode.entries) {
            assertEquals(mode, ChapterFlags.displayMode(ChapterFlags.withDisplayMode(0, mode)))
        }
        assertEquals(Manga.DISPLAY_NUMBER, ChapterFlags.withDisplayMode(0, ChapterDisplayMode.NUMBER))

        // It lives at bit 20, well clear of everything else in the column.
        var flags = ChapterFlags.withReadFilter(0, ChapterFilter.INCLUDE)
        flags = ChapterFlags.withSort(flags, ChapterSort.NUMBER)
        flags = ChapterFlags.withDisplayMode(flags, ChapterDisplayMode.NUMBER)
        assertEquals(ChapterFilter.INCLUDE, ChapterFlags.readFilter(flags))
        assertEquals(ChapterSort.NUMBER, ChapterFlags.sort(flags))
        assertEquals(ChapterDisplayMode.NUMBER, ChapterFlags.displayMode(flags))
    }
}
