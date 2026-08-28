package eu.kanade.tachiyomi.domain.migration

import eu.kanade.tachiyomi.data.database.DatabaseHandler
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory

/**
 * The highest chapter number the reader has finished, or null if none have been.
 *
 * Only recognised numbers count: an unnumbered chapter carries the -1 sentinel, and treating that
 * as a real number would make it the maximum in reverse-sorted lists.
 */
fun highestReadChapterNumber(chapters: List<Chapter>): Float? = chapters.filter { it.read }.maxByOrNull { it.chapter_number }?.chapter_number

/**
 * Marks everything up to and including [upTo] as read, and returns the chapters it changed.
 *
 * This is how reading progress survives a migration: the new source numbers its chapters
 * differently and shares no urls with the old one, so progress can only be carried across by
 * number.
 */
fun markChaptersReadUpTo(
    chapters: List<Chapter>,
    upTo: Float
): List<Chapter> =
    chapters.filter { it.isRecognizedNumber && it.chapter_number <= upTo }
        .onEach { it.read = true }

/** Ranks two copies of the same chapter by how far the reader got, least first. */
private val chapterProgressOrder = compareBy<Chapter>({ it.read }, { it.last_page_read }, { it.bookmark })

/**
 * Pairs each chapter in [to] with the chapter in [from] that it corresponds to.
 *
 * Two sources share no urls and no ids, so a recognized number is all their listings have in
 * common. Unnumbered chapters carry the -1 sentinel and would otherwise all match each other.
 */
fun pairChaptersByNumber(
    from: List<Chapter>,
    to: List<Chapter>
): List<Pair<Chapter, Chapter>> {
    if (from.isEmpty() || to.isEmpty()) return emptyList()

    val previous =
        from.filter { it.isRecognizedNumber }
            .groupBy { it.chapter_number }
            .mapValues { (_, duplicates) -> duplicates.maxWith(chapterProgressOrder) }
    if (previous.isEmpty()) return emptyList()

    return to.filter { it.isRecognizedNumber }
        .mapNotNull { chapter -> previous[chapter.chapter_number]?.let { it to chapter } }
}

/**
 * Carries bookmarks and page position across the pairs from [pairChaptersByNumber], on top of the
 * read flag [markChaptersReadUpTo] sets, and returns the chapters it changed.
 *
 * Merged rather than assigned, so a re-run cannot undo reading done since.
 */
fun copyChapterProgress(pairs: List<Pair<Chapter, Chapter>>): List<Chapter> =
    pairs.mapNotNull { (from, to) ->
        var changed = false

        if (from.read && !to.read) {
            to.read = true
            changed = true
        }
        if (from.bookmark && !to.bookmark) {
            to.bookmark = true
            changed = true
        }
        // A read chapter has no page to return to.
        if (!to.read && from.last_page_read > to.last_page_read) {
            to.last_page_read = from.last_page_read
            changed = true
        }

        to.takeIf { changed }
    }

/**
 * Moves the reading history onto the migrated entry.
 *
 * It hangs off chapter ids, so it belonged entirely to the old entry: without this the new manga
 * reads as never opened even though its chapters are marked read.
 */
private fun migrateHistory(
    db: DatabaseHandler,
    prevManga: Manga,
    pairs: List<Pair<Chapter, Chapter>>
) {
    val prevMangaId = prevManga.id ?: return
    if (pairs.isEmpty()) return

    val previous = db.getHistoryByMangaId(prevMangaId).associateBy { it.chapter_id }
    if (previous.isEmpty()) return

    val migrated =
        pairs.mapNotNull { (from, to) ->
            val fromId = from.id ?: return@mapNotNull null
            val history = previous[fromId] ?: return@mapNotNull null
            // Would otherwise stamp the entry as last read at the epoch.
            if (history.last_read <= 0L) return@mapNotNull null
            to.id ?: return@mapNotNull null

            History.create(to).apply {
                last_read = history.last_read
                time_read = history.time_read
            }
        }

    if (migrated.isNotEmpty()) {
        db.updateHistoryLastRead(migrated)
    }
}

/**
 * Moves a manga's data to the copy of it on another source.
 *
 * Which parts move is the user's choice, held as a [MigrationFlags] bitmask. This is a rule rather
 * than a job -- both platforms must carry the same things across, or migrating on one and syncing
 * to the other loses progress -- so the flags and the replace decision are passed in and nothing
 * here touches the UI or preferences.
 *
 * @param replace whether the old manga leaves the library. The new one always joins it.
 */
@Throws(Exception::class)
fun migrateMangaData(
    db: DatabaseHandler,
    prevManga: Manga,
    manga: Manga,
    flags: Int,
    replace: Boolean
) {
    if (MigrationFlags.hasChapters(flags)) {
        val prevChapters = db.getChapters(prevManga)
        val dbChapters = db.getChapters(manga)

        val maxChapterRead = highestReadChapterNumber(prevChapters)
        if (maxChapterRead != null) {
            markChaptersReadUpTo(dbChapters, maxChapterRead)
        }

        val pairs = pairChaptersByNumber(prevChapters, dbChapters)
        copyChapterProgress(pairs)

        db.insertChapters(dbChapters)

        // After the insert, so newly saved chapters carry an id.
        migrateHistory(db, prevManga, pairs)
    }

    if (MigrationFlags.hasCategories(flags)) {
        val categories = db.getCategoriesForManga(prevManga)
        db.setMangaCategories(categories.map { MangaCategory.create(manga, it) }, listOf(manga))
    }

    if (MigrationFlags.hasTracks(flags)) {
        val tracks = db.getTracks(prevManga)
        tracks.forEach { track ->
            // Cleared so the insert creates a new row against the new manga rather than moving
            // the old one, which still belongs to the manga being migrated from.
            track.id = null
            track.manga_id = manga.id!!
        }
        db.insertTracks(tracks)
    }

    if (replace) {
        prevManga.favorite = false
        db.updateMangaFavorite(prevManga)
    }

    // Settings that describe the library entry rather than the source it came from, so they are
    // not behind a flag. Entries predating the date_added column hold 0, which is not a date.
    if (prevManga.date_added > 0L) {
        manga.date_added = prevManga.date_added
    }
    manga.viewer = prevManga.viewer
    manga.chapter_flags = prevManga.chapter_flags

    manga.favorite = true
    db.updateMangaFavorite(manga)
    db.updateMangaTitle(manga)
    db.updateMangaDateAdded(manga)
    db.updateMangaViewer(manga)
    db.updateFlags(manga)
}
