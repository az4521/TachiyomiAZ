package eu.kanade.tachiyomi.domain.chapter

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.util.isLocal

/**
 * Applies a manga's chapter filters and sort order.
 *
 * The filters and the sort mode are stored in `Manga.chapter_flags`, so they travel with the
 * manga and with backups. That makes this a rule both platforms must apply identically, or the
 * same manga would show a different chapter list on each.
 *
 * Generic over [Chapter] so it works on whatever the UI layer wraps chapters in -- Android's
 * ChapterItem, or whatever iOS uses -- without this module knowing about either.
 *
 * @param forceDownloaded treat every chapter as downloaded-only, for the global "downloaded only"
 *  mode. Separate from the manga's own filter because it comes from app preferences.
 * @param isDownloaded whether a chapter's pages are on disk. Platform work, so it is supplied.
 */
fun <T : Chapter> filterAndSortChapters(
    chapters: List<T>,
    manga: Manga,
    forceDownloaded: Boolean = false,
    isDownloaded: (T) -> Boolean = { false }
): List<T> =
    filterAndSortChapters(
        chapters = chapters,
        flags = manga.chapter_flags,
        isLocal = manga.isLocal(),
        forceDownloaded = forceDownloaded,
        isRead = { it.read },
        isBookmarked = { it.bookmark },
        isDownloaded = isDownloaded,
        number = { it.chapter_number },
        uploadDate = { it.date_upload },
        sourceOrder = { it.source_order }
    )

/**
 * The same rule, for a caller whose chapters are not the shared model.
 *
 * The iOS app's manga screen holds the chapters its extension returned and keeps read state
 * beside them rather than on them, so it cannot pass a `List<Chapter>`. Everything the rule
 * actually needs is read through these accessors, which is why it can be shared at all: the filters
 * and the order are decided by [flags], the one column both apps store them in, and the chapter
 * type is nobody's business but the caller's.
 *
 * @param flags the manga's `chapter_flags`.
 * @param isLocal whether the title's pages are already on the device, which counts as downloaded.
 * @param forceDownloaded the app-wide "downloaded only" mode, separate from the manga's own filter.
 */
fun <T> filterAndSortChapters(
    chapters: List<T>,
    flags: Int,
    isLocal: Boolean = false,
    forceDownloaded: Boolean = false,
    isRead: (T) -> Boolean,
    isBookmarked: (T) -> Boolean = { false },
    isDownloaded: (T) -> Boolean = { false },
    number: (T) -> Float,
    uploadDate: (T) -> Long = { 0 },
    sourceOrder: (T) -> Int = { 0 }
): List<T> {
    var filtered = chapters.asSequence()

    when (ChapterFlags.readFilter(flags)) {
        ChapterFilter.INCLUDE -> filtered = filtered.filter { !isRead(it) }
        ChapterFilter.EXCLUDE -> filtered = filtered.filter { isRead(it) }
        else -> Unit
    }

    // Local manga are always "downloaded": their pages are already on disk.
    if (forceDownloaded) {
        filtered = filtered.filter { isDownloaded(it) || isLocal }
    } else {
        when (ChapterFlags.downloadedFilter(flags)) {
            ChapterFilter.INCLUDE -> filtered = filtered.filter { isDownloaded(it) || isLocal }
            // The Android app's chapter filter offers only "downloaded" and "all", so it never
            // writes this state and this branch is unreachable there. The iOS one offers the
            // exclusion, and honouring it here is what lets both go through the same rule.
            ChapterFilter.EXCLUDE -> filtered = filtered.filter { !(isDownloaded(it) || isLocal) }
            else -> Unit
        }
    }

    when (ChapterFlags.bookmarkedFilter(flags)) {
        ChapterFilter.INCLUDE -> filtered = filtered.filter { isBookmarked(it) }
        ChapterFilter.EXCLUDE -> filtered = filtered.filter { !isBookmarked(it) }
        else -> Unit
    }

    val descending = !ChapterFlags.ascending(flags)
    val sortFunction: (T, T) -> Int =
        when (ChapterFlags.sort(flags)) {
            // Source order is stored newest-first, so it compares the opposite way round to the
            // other two modes. That inversion is intentional, not a bug.
            ChapterSort.SOURCE ->
                if (descending) {
                    { c1, c2 -> sourceOrder(c1).compareTo(sourceOrder(c2)) }
                } else {
                    { c1, c2 -> sourceOrder(c2).compareTo(sourceOrder(c1)) }
                }
            ChapterSort.NUMBER ->
                if (descending) {
                    { c1, c2 -> number(c2).compareTo(number(c1)) }
                } else {
                    { c1, c2 -> number(c1).compareTo(number(c2)) }
                }
            ChapterSort.UPLOAD_DATE ->
                if (descending) {
                    { c1, c2 -> uploadDate(c2).compareTo(uploadDate(c1)) }
                } else {
                    { c1, c2 -> uploadDate(c1).compareTo(uploadDate(c2)) }
                }
        }

    return filtered.sortedWith { c1, c2 -> sortFunction(c1, c2) }.toList()
}
