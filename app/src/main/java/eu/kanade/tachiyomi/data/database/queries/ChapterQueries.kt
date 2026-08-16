package eu.kanade.tachiyomi.data.database.queries

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import eu.kanade.tachiyomi.data.database.DbProvider
import eu.kanade.tachiyomi.data.database.mapChapter
import eu.kanade.tachiyomi.data.database.memoColumnAdapter
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaChapter
import eu.kanade.tachiyomi.data.database.resolvers.MangaChapterGetResolver
import eu.kanade.tachiyomi.data.database.tables.ChapterTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import java.util.Date
import com.pushtorefresh.storio.sqlite.queries.RawQuery

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
            .mapToList(Dispatchers.IO)

    fun getChaptersByMergedMangaId(mangaId: Long): List<Chapter> =
        sqlDatabase.chaptersQueries
            .getChaptersByMergedMangaId(mangaId, ::mapChapter)
            .executeAsList()

    /**
     * Still on storio: this projects a manga+chapter join through MangaChapterGetResolver and
     * needs its own composite mapper, which the mangas migration will provide.
     */
    fun getRecentChapters(date: Date) =
        db.get()
            .listOfObjects(MangaChapter::class.java)
            .withQuery(
                RawQuery.builder()
                    .query(getRecentsQuery())
                    .args(date.time)
                    .observesTables(ChapterTable.TABLE)
                    .build()
            )
            .withGetResolver(MangaChapterGetResolver.INSTANCE)
            .prepare()

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
    fun fixChaptersSourceOrder(chapters: List<Chapter>) {
        sqlDatabase.chaptersQueries.transaction {
            chapters.forEach { chapter ->
                chapter.id?.let {
                    sqlDatabase.chaptersQueries.updateChapterSourceOrder(
                        chapter.source_order.toLong(),
                        it
                    )
                }
            }
        }
    }
}
