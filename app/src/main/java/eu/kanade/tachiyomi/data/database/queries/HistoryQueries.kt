package eu.kanade.tachiyomi.data.database.queries

import eu.kanade.tachiyomi.data.database.DbProvider
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import eu.kanade.tachiyomi.data.database.mapHistory
import eu.kanade.tachiyomi.data.database.mapMangaChapterHistory
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.MangaChapterHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import java.util.Date

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
        date: Date,
        offset: Int = 0,
        search: String = ""
    ): List<MangaChapterHistory> =
        sqlDatabase.historyQueries
            .getRecentMangas(date.time, search.lowercase(), 25, offset.toLong(), ::mapMangaChapterHistory)
            .executeAsList()

    fun getRecentMangaAsFlow(
        date: Date,
        offset: Int = 0,
        search: String = ""
    ): Flow<List<MangaChapterHistory>> =
        sqlDatabase.historyQueries
            .getRecentMangas(date.time, search.lowercase(), 25, offset.toLong(), ::mapMangaChapterHistory)
            .asFlow()
            .mapToList(Dispatchers.IO)

    /**
     * Same query with an explicit row limit rather than a fixed page of 25.
     */
    fun getRecentMangaLimit(
        date: Date,
        limit: Int = 0,
        search: String = ""
    ): List<MangaChapterHistory> =
        sqlDatabase.historyQueries
            .getRecentMangas(date.time, search.lowercase(), limit.toLong(), 0, ::mapMangaChapterHistory)
            .executeAsList()

    fun getRecentMangaLimitAsFlow(
        date: Date,
        limit: Int = 0,
        search: String = ""
    ): Flow<List<MangaChapterHistory>> =
        sqlDatabase.historyQueries
            .getRecentMangas(date.time, search.lowercase(), limit.toLong(), 0, ::mapMangaChapterHistory)
            .asFlow()
            .mapToList(Dispatchers.IO)

    fun getHistoryByMangaId(mangaId: Long): List<History> =
        sqlDatabase.historyQueries.getHistoryByMangaId(mangaId, ::mapHistory).executeAsList()

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
