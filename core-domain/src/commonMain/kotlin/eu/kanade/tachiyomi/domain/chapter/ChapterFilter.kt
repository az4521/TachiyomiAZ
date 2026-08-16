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
): List<T> {
    var filtered = chapters.asSequence()

    when (manga.readFilter) {
        Manga.SHOW_UNREAD -> filtered = filtered.filter { !it.read }
        Manga.SHOW_READ -> filtered = filtered.filter { it.read }
    }

    if (forceDownloaded || manga.downloadedFilter == Manga.SHOW_DOWNLOADED) {
        // Local manga are always "downloaded": their pages are already on disk.
        filtered = filtered.filter { isDownloaded(it) || manga.isLocal() }
    }

    if (manga.bookmarkedFilter == Manga.SHOW_BOOKMARKED) {
        filtered = filtered.filter { it.bookmark }
    }

    val descending = manga.sortDescending()
    val sortFunction: (Chapter, Chapter) -> Int =
        when (manga.sorting) {
            // Source order is stored newest-first, so it compares the opposite way round to the
            // other two modes. That inversion is intentional, not a bug.
            Manga.SORTING_SOURCE ->
                if (descending) {
                    { c1, c2 -> c1.source_order.compareTo(c2.source_order) }
                } else {
                    { c1, c2 -> c2.source_order.compareTo(c1.source_order) }
                }
            Manga.SORTING_NUMBER ->
                if (descending) {
                    { c1, c2 -> c2.chapter_number.compareTo(c1.chapter_number) }
                } else {
                    { c1, c2 -> c1.chapter_number.compareTo(c2.chapter_number) }
                }
            Manga.SORTING_UPLOAD_DATE ->
                if (descending) {
                    { c1, c2 -> c2.date_upload.compareTo(c1.date_upload) }
                } else {
                    { c1, c2 -> c1.date_upload.compareTo(c2.date_upload) }
                }
            else -> throw NotImplementedError("Unimplemented sorting method")
        }

    return filtered.sortedWith { c1, c2 -> sortFunction(c1, c2) }.toList()
}
