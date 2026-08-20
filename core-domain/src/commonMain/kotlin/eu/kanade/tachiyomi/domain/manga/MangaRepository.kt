package eu.kanade.tachiyomi.domain.manga

import eu.kanade.tachiyomi.data.database.DatabaseHandler
import eu.kanade.tachiyomi.data.database.models.Manga

/**
 * Portable persistence operations for source manga and their cache rows.
 *
 * Source adapters turn their results into [Manga] at the platform boundary. From this point on,
 * both applications apply the same rules for preserving user-owned fields, clearing cache rows,
 * and migrating source keys.
 */
class MangaRepository(private val db: DatabaseHandler) {
    fun manga(url: String, sourceId: Long): Manga? = db.getManga(url, sourceId)

    fun mangas(): List<Manga> = db.getMangas()

    fun contains(url: String, sourceId: Long): Boolean = manga(url, sourceId) != null

    fun remove(url: String, sourceId: Long): Boolean {
        val manga = manga(url, sourceId) ?: return false
        db.deleteManga(manga)
        return true
    }

    /** Removes cached source results while retaining the user's library. */
    fun clearCache() = db.deleteMangasNotInLibrary()

    fun sourceReadingMode(url: String, sourceId: Long): Int =
        manga(url, sourceId)?.viewer ?: 0

    fun chapterFlags(url: String, sourceId: Long): Int =
        manga(url, sourceId)?.chapter_flags ?: 0

    /** Updates just the user-controlled chapter filter and sorting flags. */
    fun updateChapterFlags(url: String, sourceId: Long, flags: Int): Manga? {
        val manga = manga(url, sourceId) ?: return null
        manga.chapter_flags = flags
        db.updateFlags(manga)
        return manga
    }

    /**
     * Rewrites a source's manga and chapter URLs without replacing their rows, preserving ids and
     * every relationship keyed by those ids. Parallel lists keep this API straightforward across
     * Kotlin/Native's Objective-C bridge.
     */
    fun migrateSourceIds(
        sourceId: Long,
        oldMangaUrls: List<String>,
        newMangaUrls: List<String>,
        oldChapterUrls: List<String>,
        newChapterUrls: List<String>
    ) {
        val mangaUrls = oldMangaUrls.zip(newMangaUrls).toMap()
        val chapterUrls = oldChapterUrls.zip(newChapterUrls).toMap()
        if (mangaUrls.isEmpty() && chapterUrls.isEmpty()) return

        db.inTransaction {
            val mangas = db.getMangasBySource(sourceId)
            mangas.forEach { manga ->
                db.getChapters(manga).forEach { chapter ->
                    val newUrl = chapterUrls[chapter.url] ?: return@forEach
                    if (newUrl != chapter.url) {
                        chapter.url = newUrl
                        db.insertChapter(chapter)
                    }
                }
            }

            val renamed = mangas.filter { it.url in mangaUrls }
            renamed.forEach { manga -> manga.url = mangaUrls.getValue(manga.url) }
            if (renamed.isNotEmpty()) db.updateMangaUrls(renamed)
        }
    }

    /** Inserts browse summaries only when no row exists, since details are more complete. */
    fun cacheSummaries(mangas: List<Manga>) {
        db.inTransaction {
            mangas.forEach { manga ->
                if (db.getManga(manga.url, manga.source) == null) db.insertManga(manga)
            }
        }
    }

    /**
     * Replaces source-owned metadata with detailed data while retaining user-owned flags and
     * favourite state from an existing row.
     */
    fun cacheDetails(manga: Manga): Manga {
        db.getManga(manga.url, manga.source)?.let { existing ->
            manga.id = existing.id
            manga.favorite = existing.favorite
            manga.chapter_flags = existing.chapter_flags
            manga.viewer = existing.viewer
            manga.date_added = existing.date_added
        }
        manga.initialized = true
        db.insertManga(manga)
        return manga
    }

    /** The previous URL is returned together with a found marker, as it may itself be null. */
    fun updateCover(url: String, sourceId: Long, coverUrl: String?): MangaCoverUpdate? {
        val manga = manga(url, sourceId) ?: return null
        val previousCoverUrl = manga.thumbnail_url
        manga.thumbnail_url = coverUrl
        db.insertManga(manga)
        return MangaCoverUpdate(previousCoverUrl)
    }

    fun save(manga: Manga) = db.insertManga(manga)

    fun updateLastUpdated(manga: Manga, timestamp: Long) {
        manga.last_update = timestamp
        db.updateLastUpdated(manga)
    }
}

/** Result of a cover write; a null [previousCoverUrl] is distinct from a missing manga row. */
data class MangaCoverUpdate(val previousCoverUrl: String?)
