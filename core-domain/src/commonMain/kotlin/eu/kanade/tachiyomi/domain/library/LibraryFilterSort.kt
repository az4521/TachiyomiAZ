package eu.kanade.tachiyomi.domain.library

import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.source.model.SManga

/**
 * A three-way filter: ignore it, keep only what matches, or keep only what does not.
 *
 * The numbers are the ones already in preferences on Android, where they were declared inside a
 * navigation-drawer widget -- a fine place for a widget's state and the wrong one for a rule the
 * library is filtered by on two platforms.
 */
object FilterState {
    const val IGNORE = 0
    const val INCLUDE = 1
    const val EXCLUDE = 2

    /**
     * Whether [matches] passes the filter.
     *
     * The whole of the tri-state: including keeps what matches, excluding keeps what does not, and
     * ignoring keeps everything.
     */
    fun passes(
        state: Int,
        matches: Boolean
    ): Boolean =
        when (state) {
            INCLUDE -> matches
            EXCLUDE -> !matches
            else -> true
        }
}

/** Which library sort a title list is in. The numbers are the ones stored in preferences. */
object LibrarySortMode {
    const val ALPHA = 0
    const val LAST_READ = 1
    const val LAST_CHECKED = 2
    const val UNREAD = 3
    const val TOTAL = 4
    const val SOURCE = 5
    const val DRAG_AND_DROP = 6
    const val LATEST_CHAPTER = 7
    const val DATE_ADDED = 8
}

/**
 * Which library entries are shown, and in what order.
 *
 * Both apps let the reader filter the library by unread, completed, tracked and downloaded, and
 * sort it nine ways, and both worked it out for themselves -- Android in `LibraryPresenter`, the
 * iOS app in its library view model. The rules are the same rules; only where the answers come
 * from differs, so those are passed in.
 *
 * This is the Android implementation, moved.
 */
object LibraryFilterSort {
    /**
     * Whether [manga] survives the current filters.
     *
     * @param isDownloaded whether the title has any downloaded chapters. Counting them means
     *   reading the filesystem, so the caller answers it.
     * @param hasTracks whether the title is linked to any tracker.
     * @param isLewd Android-only; the iOS app has no such sources and leaves it false.
     */
    fun matchesFilters(
        manga: LibraryManga,
        filterUnread: Int = FilterState.IGNORE,
        filterCompleted: Int = FilterState.IGNORE,
        filterTracked: Int = FilterState.IGNORE,
        filterLewd: Int = FilterState.IGNORE,
        filterDownloaded: Int = FilterState.IGNORE,
        downloadedOnly: Boolean = false,
        isDownloaded: (LibraryManga) -> Boolean = { false },
        hasTracks: (LibraryManga) -> Boolean = { false },
        isLewd: (LibraryManga) -> Boolean = { false }
    ): Boolean {
        if (!FilterState.passes(filterUnread, manga.unread > 0)) return false
        if (!FilterState.passes(filterCompleted, manga.status == SManga.COMPLETED)) return false
        // Guarded rather than folded into `passes`: both ask the database or the sources, and the
        // ignore case is the common one.
        if (filterTracked != FilterState.IGNORE && !FilterState.passes(filterTracked, hasTracks(manga))) return false
        if (filterLewd != FilterState.IGNORE && !FilterState.passes(filterLewd, isLewd(manga))) return false

        // A local title counts as downloaded: its pages are already on the device.
        if (filterDownloaded != FilterState.IGNORE || downloadedOnly) {
            val downloaded = manga.isLocal() || isDownloaded(manga)
            // `downloadedOnly` is the app-wide "only show downloaded" mode, so it means include.
            //
            // The Android version this is taken from tested only `filterDownloaded == INCLUDE`
            // here, so with the mode on and the filter itself left at ignore it returned
            // `!downloaded` -- showing exactly the titles that are not downloaded. Corrected
            // rather than copied: a setting that does the opposite of its name is a bug, not a
            // behaviour worth keeping the two apps agreeing on.
            val wantDownloaded = downloadedOnly || filterDownloaded == FilterState.INCLUDE
            return if (wantDownloaded) downloaded else !downloaded
        }
        return true
    }

    /**
     * The order titles are listed in.
     *
     * Four of the modes are not answerable from a row on its own -- "last read", "total chapters"
     * and "latest chapter" are positions in a query the database does, and "source" needs the
     * extension's name -- so those arrive as lookups. A title missing from one of them sorts last,
     * which is what happens when it has never been read or has no chapters yet.
     *
     * Drag-and-drop is not a sort: it keeps whatever order the list is already in.
     */
    fun comparator(
        mode: Int,
        ascending: Boolean,
        lastReadOrder: Map<Long, Int> = emptyMap(),
        totalChapterOrder: Map<Long, Int> = emptyMap(),
        latestChapterOrder: Map<Long, Int> = emptyMap(),
        sourceName: (Long) -> String = { "" }
    ): Comparator<LibraryManga> {
        val base =
            Comparator<LibraryManga> { first, second ->
                when (mode) {
                    LibrarySortMode.ALPHA -> first.title.compareTo(second.title, ignoreCase = true)
                    LibrarySortMode.LAST_READ ->
                        position(lastReadOrder, first).compareTo(position(lastReadOrder, second))
                    LibrarySortMode.LAST_CHECKED -> second.last_update.compareTo(first.last_update)
                    LibrarySortMode.UNREAD -> first.unread.compareTo(second.unread)
                    LibrarySortMode.TOTAL ->
                        position(totalChapterOrder, first, missing = 0)
                            .compareTo(position(totalChapterOrder, second, missing = 0))
                    LibrarySortMode.LATEST_CHAPTER ->
                        position(latestChapterOrder, first).compareTo(position(latestChapterOrder, second))
                    LibrarySortMode.DATE_ADDED -> second.date_added.compareTo(first.date_added)
                    LibrarySortMode.SOURCE -> sourceName(first.source).compareTo(sourceName(second.source))
                    else -> 0
                }
            }

        return if (ascending) base else base.reversed()
    }

    private fun position(
        order: Map<Long, Int>,
        manga: LibraryManga,
        missing: Int = Int.MAX_VALUE
    ): Int = manga.id?.let { order[it] } ?: missing
}
