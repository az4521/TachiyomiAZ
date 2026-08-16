package eu.kanade.tachiyomi.data.database.queries

import com.pushtorefresh.storio.sqlite.queries.RawQuery
import eu.kanade.tachiyomi.data.database.DbProvider
import eu.kanade.tachiyomi.data.database.mapHistory
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.MangaChapterHistory
import eu.kanade.tachiyomi.data.database.resolvers.MangaChapterHistoryGetResolver
import eu.kanade.tachiyomi.data.database.tables.HistoryTable
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
     * Returns history of recent manga containing last read chapter
     * @param date recent date range
     */
    fun getRecentManga(
        date: Date,
        offset: Int = 0,
        search: String = ""
    ) = db.get()
        .listOfObjects(MangaChapterHistory::class.java)
        .withQuery(
            RawQuery.builder()
                .query(getRecentMangasQuery(offset, search))
                .args(date.time)
                .observesTables(HistoryTable.TABLE)
                .build()
        )
        .withGetResolver(MangaChapterHistoryGetResolver.INSTANCE)
        .prepare()

    /**
     * Returns history of recent manga containing last read chapter in 25s
     * @param date recent date range
     * @offset offset the db by
     */
    fun getRecentMangaLimit(
        date: Date,
        limit: Int = 0,
        search: String = ""
    ) = db.get()
        .listOfObjects(MangaChapterHistory::class.java)
        .withQuery(
            RawQuery.builder()
                .query(getRecentMangasLimitQuery(limit, search))
                .args(date.time)
                .observesTables(HistoryTable.TABLE)
                .build()
        )
        .withGetResolver(MangaChapterHistoryGetResolver.INSTANCE)
        .prepare()

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
