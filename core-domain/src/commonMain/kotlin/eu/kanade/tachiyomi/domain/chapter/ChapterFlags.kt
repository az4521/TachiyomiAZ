package eu.kanade.tachiyomi.domain.chapter

import eu.kanade.tachiyomi.data.database.models.Manga

/**
 * How a title's chapter list is filtered, sorted and displayed.
 *
 * This is one integer column, `mangas.chapter_flags`, shared by both apps -- and they were reading
 * it with different bit layouts, so the same number meant different things on each. Android packs
 * the read filter at bits 1-2 and the sort method at bits 8-9; the iOS app inherited Aidoku's
 * layout, where bits 1-3 are the sort method and bit 6 is the unread filter. Setting a filter on
 * one app therefore produced unrelated filters and a wrong sort order on the other, in both
 * directions, with no error anywhere.
 *
 * The numbers are Android's, because they are the ones already written into every existing
 * database and every backup. This exists so there is one place they are written down.
 */
enum class ChapterFilter {
    /** No filter: every chapter is shown. */
    ALL,

    /** Only chapters matching the trait -- unread, downloaded, bookmarked. */
    INCLUDE,

    /** Only chapters not matching it. */
    EXCLUDE
}

/**
 * What a chapter row is titled with.
 *
 * Two values, because that is what the column holds. The iOS app also offered "volume", which the
 * shared column has no bit for and Android has no concept of -- and which drove volume-based
 * tracker progress that `Track` cannot store either, since it has `last_chapter_read` and nothing
 * for volumes. It was a mode whose effects stopped at the edge of that app.
 */
enum class ChapterDisplayMode {
    /** The name the source gave the chapter. */
    NAME,

    /** "Chapter 12", from its number. */
    NUMBER
}

enum class ChapterSort {
    /** The order the source listed them in. */
    SOURCE,
    NUMBER,
    UPLOAD_DATE
}

object ChapterFlags {
    // MARK: reading

    fun readFilter(flags: Int): ChapterFilter =
        when (flags and Manga.READ_MASK) {
            Manga.SHOW_UNREAD -> ChapterFilter.INCLUDE
            Manga.SHOW_READ -> ChapterFilter.EXCLUDE
            else -> ChapterFilter.ALL
        }

    fun downloadedFilter(flags: Int): ChapterFilter =
        when (flags and Manga.DOWNLOADED_MASK) {
            Manga.SHOW_DOWNLOADED -> ChapterFilter.INCLUDE
            Manga.SHOW_NOT_DOWNLOADED -> ChapterFilter.EXCLUDE
            else -> ChapterFilter.ALL
        }

    fun bookmarkedFilter(flags: Int): ChapterFilter =
        when (flags and Manga.BOOKMARKED_MASK) {
            Manga.SHOW_BOOKMARKED -> ChapterFilter.INCLUDE
            Manga.SHOW_NOT_BOOKMARKED -> ChapterFilter.EXCLUDE
            else -> ChapterFilter.ALL
        }

    fun sort(flags: Int): ChapterSort =
        when (flags and Manga.SORTING_MASK) {
            Manga.SORTING_NUMBER -> ChapterSort.NUMBER
            Manga.SORTING_UPLOAD_DATE -> ChapterSort.UPLOAD_DATE
            else -> ChapterSort.SOURCE
        }

    /** Whether the list runs oldest-first. */
    fun ascending(flags: Int): Boolean = flags and Manga.SORT_MASK == Manga.SORT_ASC

    fun displayMode(flags: Int): ChapterDisplayMode =
        when (flags and Manga.DISPLAY_MASK) {
            Manga.DISPLAY_NUMBER -> ChapterDisplayMode.NUMBER
            else -> ChapterDisplayMode.NAME
        }

    // MARK: writing

    fun withReadFilter(flags: Int, filter: ChapterFilter): Int =
        replace(
            flags,
            Manga.READ_MASK,
            when (filter) {
                ChapterFilter.INCLUDE -> Manga.SHOW_UNREAD
                ChapterFilter.EXCLUDE -> Manga.SHOW_READ
                ChapterFilter.ALL -> Manga.SHOW_ALL
            }
        )

    fun withDownloadedFilter(flags: Int, filter: ChapterFilter): Int =
        replace(
            flags,
            Manga.DOWNLOADED_MASK,
            when (filter) {
                ChapterFilter.INCLUDE -> Manga.SHOW_DOWNLOADED
                ChapterFilter.EXCLUDE -> Manga.SHOW_NOT_DOWNLOADED
                ChapterFilter.ALL -> Manga.SHOW_ALL
            }
        )

    fun withBookmarkedFilter(flags: Int, filter: ChapterFilter): Int =
        replace(
            flags,
            Manga.BOOKMARKED_MASK,
            when (filter) {
                ChapterFilter.INCLUDE -> Manga.SHOW_BOOKMARKED
                ChapterFilter.EXCLUDE -> Manga.SHOW_NOT_BOOKMARKED
                ChapterFilter.ALL -> Manga.SHOW_ALL
            }
        )

    fun withSort(flags: Int, sort: ChapterSort): Int =
        replace(
            flags,
            Manga.SORTING_MASK,
            when (sort) {
                ChapterSort.NUMBER -> Manga.SORTING_NUMBER
                ChapterSort.UPLOAD_DATE -> Manga.SORTING_UPLOAD_DATE
                ChapterSort.SOURCE -> Manga.SORTING_SOURCE
            }
        )

    fun withAscending(flags: Int, ascending: Boolean): Int =
        replace(flags, Manga.SORT_MASK, if (ascending) Manga.SORT_ASC else Manga.SORT_DESC)

    fun withDisplayMode(flags: Int, mode: ChapterDisplayMode): Int =
        replace(
            flags,
            Manga.DISPLAY_MASK,
            when (mode) {
                ChapterDisplayMode.NUMBER -> Manga.DISPLAY_NUMBER
                ChapterDisplayMode.NAME -> Manga.DISPLAY_NAME
            }
        )

    /** Clears the bits [mask] covers, then sets [value]. */
    private fun replace(flags: Int, mask: Int, value: Int): Int = flags and mask.inv() or value
}
