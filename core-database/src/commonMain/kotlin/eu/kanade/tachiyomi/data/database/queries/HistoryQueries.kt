package eu.kanade.tachiyomi.data.database.queries

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import eu.kanade.tachiyomi.data.database.DbProvider
import eu.kanade.tachiyomi.data.database.mapHistory
import eu.kanade.tachiyomi.data.database.mapMangaChapterHistory
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.MangaChapterHistory
import eu.kanade.tachiyomi.data.database.databaseDispatcher
import kotlinx.coroutines.flow.Flow

interface HistoryQueries : DbProvider {
    /**
     * Insert history into database
     * @param history object containing history information
     */
    fun insertHistory(history: History) {
        sqlDatabase.historyQueries.transaction {
            sqlDatabase.historyQueries.insertHistory(
                history.chapter_id,
                history.last_read,
                history.time_read
            )
            history.id = sqlDatabase.historyQueries.lastInsertRowId().executeAsOne()
        }
    }

    /**
     * Recently read manga, most recent chapter per manga.
     * @param date recent date range
     */
    fun getRecentManga(
        date: Long,
        offset: Int = 0,
        search: String = ""
    ): List<MangaChapterHistory> =
        sqlDatabase.historyQueries
            .getRecentMangas(date, search.lowercase(), 25, offset.toLong(), ::mapMangaChapterHistory)
            .executeAsList()

    fun getRecentMangaAsFlow(
        date: Long,
        offset: Int = 0,
        search: String = ""
    ): Flow<List<MangaChapterHistory>> =
        sqlDatabase.historyQueries
            .getRecentMangas(date, search.lowercase(), 25, offset.toLong(), ::mapMangaChapterHistory)
            .asFlow()
            .mapToList(databaseDispatcher)

    /**
     * Same query with an explicit row limit rather than a fixed page of 25.
     */
    fun getRecentMangaLimit(
        date: Long,
        limit: Int = 0,
        search: String = ""
    ): List<MangaChapterHistory> =
        sqlDatabase.historyQueries
            .getRecentMangas(date, search.lowercase(), limit.toLong(), 0, ::mapMangaChapterHistory)
            .executeAsList()

    fun getRecentMangaLimitAsFlow(
        date: Long,
        limit: Int = 0,
        search: String = ""
    ): Flow<List<MangaChapterHistory>> =
        sqlDatabase.historyQueries
            .getRecentMangas(date, search.lowercase(), limit.toLong(), 0, ::mapMangaChapterHistory)
            .asFlow()
            .mapToList(databaseDispatcher)

    /** Every history row, for whole-library reporting such as the reading statistics. */
    fun getAllHistory(): List<History> =
        sqlDatabase.historyQueries.getAllHistory(::mapHistory).executeAsList()

    fun getHistoryByMangaId(mangaId: Long): List<History> =
        sqlDatabase.historyQueries.getHistoryByMangaId(mangaId, ::mapHistory).executeAsList()

    /** Looks up history by the chapter's database identity, avoiding source-local URL collisions. */
    fun getHistoryByChapterId(chapterId: Long): History? =
        sqlDatabase.historyQueries
            .getHistoryByChapterId(chapterId, ::mapHistory)
            .executeAsOneOrNull()

    fun getHistoryByChapterUrl(chapterUrl: String): History? =
        sqlDatabase.historyQueries
            .getHistoryByChapterUrl(chapterUrl, ::mapHistory)
            .executeAsOneOrNull()

    /**
     * Updates the history last read.
     * Inserts history object if not yet in database
     * @param history history object
     */
    fun updateHistoryLastRead(history: History) {
        sqlDatabase.historyQueries.transaction {
            sqlDatabase.historyQueries.updateHistoryLastRead(history.last_read, history.chapter_id)
            if (sqlDatabase.historyQueries.changes().executeAsOne() == 0L) {
                sqlDatabase.historyQueries.insertHistory(
                    history.chapter_id,
                    history.last_read,
                    history.time_read
                )
            }
        }
    }

    /**
     * Updates the history last read.
     * Inserts history object if not yet in database
     * @param historyList history object list
     */
    fun updateHistoryLastRead(historyList: List<History>) {
        sqlDatabase.historyQueries.transaction {
            historyList.forEach { updateHistoryLastRead(it) }
        }
    }

    fun deleteHistory() {
        sqlDatabase.historyQueries.deleteHistory()
    }

    fun deleteHistoryNoLastRead() {
        sqlDatabase.historyQueries.deleteHistoryNoLastRead()
    }
}
