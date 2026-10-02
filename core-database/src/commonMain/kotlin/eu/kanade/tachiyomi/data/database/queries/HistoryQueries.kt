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
import kotlinx.coroutines.flow.map

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
        recentMangaQuery(date, 25, offset, search).executeAsList().searchPage(25, offset, search)

    fun getRecentMangaAsFlow(
        date: Long,
        offset: Int = 0,
        search: String = ""
    ): Flow<List<MangaChapterHistory>> =
        recentMangaQuery(date, 25, offset, search)
            .asFlow()
            .mapToList(databaseDispatcher)
            .map { it.searchPage(25, offset, search) }

    /**
     * Same query with an explicit row limit rather than a fixed page of 25.
     */
    fun getRecentMangaLimit(
        date: Long,
        limit: Int = 0,
        search: String = ""
    ): List<MangaChapterHistory> =
        recentMangaQuery(date, limit, 0, search).executeAsList().searchPage(limit, 0, search)

    fun getRecentMangaLimitAsFlow(
        date: Long,
        limit: Int = 0,
        search: String = ""
    ): Flow<List<MangaChapterHistory>> =
        recentMangaQuery(date, limit, 0, search)
            .asFlow()
            .mapToList(databaseDispatcher)
            .map { it.searchPage(limit, 0, search) }

    /**
     * SQLite's LIKE only case-folds ASCII, so a search matches titles here instead: it fetches
     * every row (LIMIT -1) and [searchPage] filters and pages them. Unsearched queries page in SQL.
     */
    private fun recentMangaQuery(date: Long, limit: Int, offset: Int, search: String) =
        if (search.isEmpty()) {
            sqlDatabase.historyQueries
                .getRecentMangas(date, limit.toLong(), offset.toLong(), ::mapMangaChapterHistory)
        } else {
            sqlDatabase.historyQueries
                .getRecentMangas(date, -1, 0, ::mapMangaChapterHistory)
        }

    private fun List<MangaChapterHistory>.searchPage(
        limit: Int,
        offset: Int,
        search: String
    ): List<MangaChapterHistory> =
        if (search.isEmpty()) {
            this
        } else {
            filter { it.manga.title.contains(search, ignoreCase = true) }
                .drop(offset)
                .take(limit)
        }

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
