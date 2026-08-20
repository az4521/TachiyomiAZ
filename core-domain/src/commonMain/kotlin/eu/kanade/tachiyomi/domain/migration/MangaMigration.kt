package eu.kanade.tachiyomi.domain.migration

import eu.kanade.tachiyomi.data.database.DatabaseHandler
import eu.kanade.tachiyomi.data.database.models.Chapter
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
        val maxChapterRead = highestReadChapterNumber(db.getChapters(prevManga))
        if (maxChapterRead != null) {
            val dbChapters = db.getChapters(manga)
            markChaptersReadUpTo(dbChapters, maxChapterRead)
            db.insertChapters(dbChapters)
        }
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

    manga.favorite = true
    db.updateMangaFavorite(manga)
    db.updateMangaTitle(manga)
}
