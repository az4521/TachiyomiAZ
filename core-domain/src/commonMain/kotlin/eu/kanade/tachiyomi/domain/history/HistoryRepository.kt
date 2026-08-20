package eu.kanade.tachiyomi.domain.history

import eu.kanade.tachiyomi.data.database.DatabaseHandler
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.MangaChapterHistory

data class ReadingHistoryEntry(val chapterUrl: String, val page: Int, val dateSeconds: Long)

data class ChapterProgress(val completed: Boolean, val progress: Int?)

data class HistoryIdentifier(val mangaUrl: String, val chapterUrl: String)

data class HistoryDetail(val readAt: Long?, val completed: Boolean)

data class RecentHistoryEntry(
    val sourceId: Long,
    val mangaUrl: String,
    val chapterUrl: String,
    val readAt: Long,
    val progress: Int,
    val completed: Boolean
)

/** Shared reading-history writes and their chapter-progress side effects. */
class HistoryRepository(private val db: DatabaseHandler) {
    fun recent(limit: Int = 300): List<MangaChapterHistory> =
        db.getRecentMangaLimit(date = 0, limit = limit, search = "")

    fun record(mangaUrl: String, sourceId: Long, chapterUrl: String, readAt: Long): Boolean {
        val manga = db.getManga(mangaUrl, sourceId) ?: return false
        val chapter = db.getChapters(manga).firstOrNull { it.url == chapterUrl } ?: return false
        touch(chapter, readAt)
        return true
    }

    fun record(chapter: Chapter, readAt: Long) = touch(chapter, readAt)

    fun readingHistory(mangaUrl: String, sourceId: Long): List<ReadingHistoryEntry> {
        val manga = db.getManga(mangaUrl, sourceId) ?: return emptyList()
        return db.getChapters(manga).mapNotNull { chapter ->
            val history = db.getHistoryByChapterUrl(chapter.url) ?: return@mapNotNull null
            ReadingHistoryEntry(
                chapterUrl = chapter.url,
                page = if (chapter.read) -1 else chapter.last_page_read,
                dateSeconds = history.last_read / 1000
            )
        }
    }

    fun progress(mangaUrl: String, sourceId: Long, chapterUrl: String): ChapterProgress {
        val manga = db.getManga(mangaUrl, sourceId) ?: return ChapterProgress(false, null)
        val chapter = db.getChapters(manga).firstOrNull { it.url == chapterUrl }
            ?: return ChapterProgress(false, null)
        return ChapterProgress(chapter.read, chapter.last_page_read.takeUnless { it == 0 })
    }

    fun detail(mangaUrl: String, sourceId: Long, chapterUrl: String): HistoryDetail? {
        val manga = db.getManga(mangaUrl, sourceId) ?: return null
        val chapter = db.getChapters(manga).firstOrNull { it.url == chapterUrl } ?: return null
        val history = db.getHistoryByChapterUrl(chapterUrl)
        if (history == null) return null
        return HistoryDetail(history.last_read.takeIf { it > 0 }, chapter.read)
    }

    fun recentEntries(limit: Int, offset: Int): List<RecentHistoryEntry> {
        if (limit <= 0 || offset < 0) return emptyList()
        val chapters = db.getAllChapters().mapNotNull { chapter -> chapter.id?.let { it to chapter } }.toMap()
        val mangas = db.getMangas().mapNotNull { manga -> manga.id?.let { it to manga } }.toMap()
        return db.getAllHistory().asSequence()
            .filter { it.last_read > 0 }
            .sortedByDescending { it.last_read }
            .drop(offset)
            .take(limit)
            .mapNotNull { history ->
                val chapter = chapters[history.chapter_id] ?: return@mapNotNull null
                val manga = chapter.manga_id?.let(mangas::get) ?: return@mapNotNull null
                RecentHistoryEntry(
                    sourceId = manga.source,
                    mangaUrl = manga.url,
                    chapterUrl = chapter.url,
                    readAt = history.last_read,
                    progress = chapter.last_page_read,
                    completed = chapter.read
                )
            }
            .toList()
    }

    fun setProgress(chapter: Chapter, progress: Int, completed: Boolean, readAt: Long) {
        db.inTransaction {
            chapter.last_page_read = progress
            chapter.read = completed
            db.updateChapterProgress(chapter)
            touch(chapter, readAt)
        }
    }

    fun markCompleted(chapters: List<Chapter>, readAt: Long) {
        db.inTransaction {
            chapters.forEach { chapter ->
                chapter.read = true
                db.updateChapterProgress(chapter)
                touch(chapter, readAt)
            }
        }
    }

    fun markCompleted(
        mangaUrl: String,
        sourceId: Long,
        chapterUrls: List<String>,
        readAt: Long
    ): Boolean {
        val manga = db.getManga(mangaUrl, sourceId) ?: return false
        val wanted = chapterUrls.toSet()
        val chapters = db.getChapters(manga).filter { it.url in wanted }
        if (chapters.isEmpty()) return false
        markCompleted(chapters, readAt)
        return true
    }

    fun remove(chapterUrl: String): Boolean {
        val history = db.getHistoryByChapterUrl(chapterUrl) ?: return false
        history.last_read = 0
        db.updateHistoryLastRead(history)
        return true
    }

    fun remove(mangaUrl: String, sourceId: Long, chapterUrls: List<String>? = null): Int {
        val manga = db.getManga(mangaUrl, sourceId) ?: return 0
        val wanted = chapterUrls?.toSet()
        var removed = 0
        db.inTransaction {
            db.getChapters(manga)
                .filter { wanted == null || it.url in wanted }
                .forEach { if (remove(it.url)) removed++ }
        }
        return removed
    }

    fun hasHistory(mangaUrl: String, sourceId: Long, chapterUrl: String? = null): Boolean {
        if (chapterUrl != null) return db.getHistoryByChapterUrl(chapterUrl) != null
        val manga = db.getManga(mangaUrl, sourceId) ?: return false
        return db.getChapters(manga).any { db.getHistoryByChapterUrl(it.url) != null }
    }

    fun earliestRead(mangaUrl: String, sourceId: Long): Long? {
        val manga = db.getManga(mangaUrl, sourceId) ?: return null
        return db.getChapters(manga)
            .mapNotNull { db.getHistoryByChapterUrl(it.url)?.last_read }
            .filter { it > 0 }
            .minOrNull()
    }

    fun identifiersForSource(sourceId: Long): List<HistoryIdentifier> =
        db.getMangasBySource(sourceId).flatMap { manga ->
            db.getChapters(manga).mapNotNull { chapter ->
                db.getHistoryByChapterUrl(chapter.url) ?: return@mapNotNull null
                HistoryIdentifier(manga.url, chapter.url)
            }
        }

    fun clear() = db.deleteHistory()

    fun clearWithoutLastRead() = db.deleteHistoryNoLastRead()

    fun addReadTime(chapterUrl: String, amount: Long): Boolean {
        val history = db.getHistoryByChapterUrl(chapterUrl) ?: return false
        history.time_read += amount
        db.updateHistoryLastRead(history)
        return true
    }

    private fun touch(chapter: Chapter, readAt: Long) {
        val history = db.getHistoryByChapterUrl(chapter.url) ?: History.create(chapter)
        history.last_read = readAt
        db.updateHistoryLastRead(history)
    }
}
