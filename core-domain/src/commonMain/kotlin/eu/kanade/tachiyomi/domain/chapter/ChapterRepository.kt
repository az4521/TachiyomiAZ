package eu.kanade.tachiyomi.domain.chapter

import eu.kanade.tachiyomi.data.database.DatabaseHandler
import eu.kanade.tachiyomi.data.database.models.Chapter

/**
 * Shared chapter persistence used by both applications.
 *
 * The UI-facing source and manga keys are deliberately not part of this class: the database stores
 * the source as a number and the manga key as [mangaUrl]. Platform bridges translate their own
 * source identity at the edge.
 */
class ChapterRepository(private val db: DatabaseHandler) {
    fun getChapters(sourceId: Long, mangaUrl: String): List<Chapter> =
        db.getManga(mangaUrl, sourceId)?.let { db.getChapters(it) } ?: emptyList()

    fun getChaptersBySource(sourceId: Long): List<Chapter> =
        db.getMangasBySource(sourceId).flatMap { db.getChapters(it) }

    fun chaptersWithMangaBySource(sourceId: Long): List<ChapterWithMangaUrl> =
        db.getMangasBySource(sourceId).flatMap { manga ->
            db.getChapters(manga).map { ChapterWithMangaUrl(it, manga.url) }
        }

    fun getAllChapters(): List<Chapter> = db.getAllChapters()

    fun getChapter(sourceId: Long, mangaUrl: String, chapterUrl: String): Chapter? =
        getChapters(sourceId, mangaUrl).firstOrNull { it.url == chapterUrl }

    fun hasChapter(sourceId: Long, mangaUrl: String, chapterUrl: String): Boolean =
        getChapter(sourceId, mangaUrl, chapterUrl) != null

    fun deleteChapters(sourceId: Long, mangaUrl: String) {
        db.deleteChapters(getChapters(sourceId, mangaUrl))
    }

    fun clearChapters() = db.deleteChapters(db.getAllChapters())

    fun saveProgress(chapter: Chapter) = db.updateChapterProgress(chapter)

    fun unreadCount(sourceId: Long, mangaUrl: String, scanlators: List<String>? = null): Int =
        matching(sourceId, mangaUrl, scanlators).count { !it.read }

    fun readCount(sourceId: Long, mangaUrl: String, scanlators: List<String>? = null): Int =
        matching(sourceId, mangaUrl, scanlators).count { it.read }

    fun startedCount(sourceId: Long, mangaUrl: String, scanlators: List<String>? = null): Int =
        matching(sourceId, mangaUrl, scanlators).count { !it.read && it.last_page_read > 0 }

    fun highestReadNumber(sourceId: Long, mangaUrl: String): Float? =
        getChapters(sourceId, mangaUrl).asSequence()
            .filter { it.read }
            .map { it.chapter_number }
            .maxOrNull()

    /** Unread chapter totals for library entries, calculated with one chapter scan. */
    fun libraryUnreadCounts(): List<LibraryUnreadCount> {
        val unreadByMangaId = HashMap<Long, Int>()
        db.getAllChapters()
            .filter { !it.read }
            .forEach { chapter ->
                chapter.manga_id?.let { id -> unreadByMangaId[id] = (unreadByMangaId[id] ?: 0) + 1 }
            }

        return db.getLibraryMangas().mapNotNull { manga ->
            val id = manga.id ?: return@mapNotNull null
            LibraryUnreadCount(
                mangaUrl = manga.url,
                sourceId = manga.source,
                count = unreadByMangaId[id] ?: 0
            )
        }
    }

    private fun matching(sourceId: Long, mangaUrl: String, scanlators: List<String>?): List<Chapter> {
        val chapters = getChapters(sourceId, mangaUrl)
        if (scanlators.isNullOrEmpty()) return chapters
        return chapters.filter { it.scanlator != null && it.scanlator in scanlators }
    }
}

/** A library manga's unread count, with the database-native identity. */
class LibraryUnreadCount(
    val mangaUrl: String,
    val sourceId: Long,
    val count: Int
)

data class ChapterWithMangaUrl(val chapter: Chapter, val mangaUrl: String)
