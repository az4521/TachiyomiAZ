package eu.kanade.tachiyomi.data.database.queries

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import eu.kanade.tachiyomi.data.database.DbProvider
import eu.kanade.tachiyomi.data.database.mapChapter
import eu.kanade.tachiyomi.data.database.mapMangaChapter
import eu.kanade.tachiyomi.data.database.memoColumnAdapter
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaChapter
import eu.kanade.tachiyomi.data.database.databaseDispatcher
import kotlinx.coroutines.flow.Flow

interface ChapterQueries : DbProvider {
    fun getChapters(manga: Manga): List<Chapter> = getChaptersByMangaId(manga.id)

    fun getAllChapters(): List<Chapter> =
        sqlDatabase.chaptersQueries.getAllChapters(::mapChapter).executeAsList()

    fun getChaptersByMangaId(mangaId: Long?): List<Chapter> =
        sqlDatabase.chaptersQueries
            .getChaptersByMangaId(mangaId ?: 0L, ::mapChapter)
            .executeAsList()

    fun getChaptersByMangaIdAsFlow(mangaId: Long?): Flow<List<Chapter>> =
        sqlDatabase.chaptersQueries
            .getChaptersByMangaId(mangaId ?: 0L, ::mapChapter)
            .asFlow()
            .mapToList(databaseDispatcher)

    fun getChaptersByMergedMangaId(mangaId: Long): List<Chapter> =
        sqlDatabase.chaptersQueries
            .getChaptersByMergedMangaId(mangaId, ::mapChapter)
            .executeAsList()

    fun getRecentChapters(date: Long): List<MangaChapter> =
        sqlDatabase.chaptersQueries
            .getRecentChapters(date, ::mapMangaChapter)
            .executeAsList()

    fun getRecentChaptersAsFlow(date: Long): Flow<List<MangaChapter>> =
        sqlDatabase.chaptersQueries
            .getRecentChapters(date, ::mapMangaChapter)
            .asFlow()
            .mapToList(databaseDispatcher)

    fun getChapter(id: Long): Chapter? =
        sqlDatabase.chaptersQueries.getChapterById(id, ::mapChapter).executeAsOneOrNull()

    fun getChapter(url: String): Chapter? =
        sqlDatabase.chaptersQueries.getChapterByUrl(url, ::mapChapter).executeAsOneOrNull()

    fun getChapter(
        url: String,
        mangaId: Long
    ): Chapter? =
        sqlDatabase.chaptersQueries
            .getChapterByUrlAndMangaId(url, mangaId, ::mapChapter)
            .executeAsOneOrNull()

    fun getChapters(url: String): List<Chapter> =
        sqlDatabase.chaptersQueries.getChaptersByUrl(url, ::mapChapter).executeAsList()

    fun insertChapter(chapter: Chapter) {
        sqlDatabase.chaptersQueries.transaction {
            val id = chapter.id
            if (id == null) {
                sqlDatabase.chaptersQueries.insertChapter(
                    chapter.manga_id!!,
                    chapter.url,
                    chapter.name,
                    chapter.scanlator,
                    if (chapter.read) 1L else 0L,
                    if (chapter.bookmark) 1L else 0L,
                    chapter.last_page_read.toLong(),
                    chapter.chapter_number.toDouble(),
                    chapter.source_order.toLong(),
                    chapter.date_fetch,
                    chapter.date_upload,
                    memoColumnAdapter.encode(chapter.memo)
                )
                chapter.id = sqlDatabase.chaptersQueries.lastInsertRowId().executeAsOne()
            } else {
                sqlDatabase.chaptersQueries.updateChapter(
                    chapter.manga_id!!,
                    chapter.url,
                    chapter.name,
                    chapter.scanlator,
                    if (chapter.read) 1L else 0L,
                    if (chapter.bookmark) 1L else 0L,
                    chapter.last_page_read.toLong(),
                    chapter.chapter_number.toDouble(),
                    chapter.source_order.toLong(),
                    chapter.date_fetch,
                    chapter.date_upload,
                    memoColumnAdapter.encode(chapter.memo),
                    id
                )
            }
        }
    }

    fun insertChapters(chapters: List<Chapter>) {
        sqlDatabase.chaptersQueries.transaction {
            chapters.forEach { insertChapter(it) }
        }
    }

    fun deleteChapter(chapter: Chapter) {
        chapter.id?.let { sqlDatabase.chaptersQueries.deleteChapter(it) }
    }

    fun deleteChapters(chapters: List<Chapter>) {
        sqlDatabase.chaptersQueries.transaction {
            chapters.forEach { deleteChapter(it) }
        }
    }

    /** ChapterBackupPutResolver wrote read, bookmark and last_page_read only. */
    fun updateChaptersBackup(chapters: List<Chapter>) = updateChaptersProgress(chapters)

    /** ChapterProgressPutResolver wrote read, bookmark and last_page_read only. */
    fun updateChapterProgress(chapter: Chapter) {
        chapter.id?.let {
            sqlDatabase.chaptersQueries.updateChapterProgress(
                if (chapter.read) 1L else 0L,
                if (chapter.bookmark) 1L else 0L,
                chapter.last_page_read.toLong(),
                it
            )
        }
    }

    fun updateChaptersProgress(chapters: List<Chapter>) {
        sqlDatabase.chaptersQueries.transaction {
            chapters.forEach { updateChapterProgress(it) }
        }
    }

    /** ChapterSourceOrderPutResolver wrote source_order only. */
    /**
     * Rewrites every chapter's position in the source's listing.
     *
     * Matched on url and manga_id, not on id. These chapters are built fresh from the source list
     * during a sync, so only the ones just inserted carry an id -- keying on id silently skipped
     * every chapter that already existed, leaving its source_order frozen at whatever it was when
     * first seen. Sources list newest first, so that ordering drifts a little further out with
     * every new chapter.
     */
    fun fixChaptersSourceOrder(chapters: List<Chapter>) {
        sqlDatabase.chaptersQueries.transaction {
            chapters.forEach { chapter ->
                // Unlike the id guard this replaced, skipping on a null manga_id skips nothing
                // real: manga_id is NOT NULL in the table, so a null could not match a row.
                chapter.manga_id?.let { mangaId ->
                    sqlDatabase.chaptersQueries.updateChapterSourceOrderByUrl(
                        chapter.source_order.toLong(),
                        chapter.url,
                        mangaId
                    )
                }
            }
        }
    }
}
