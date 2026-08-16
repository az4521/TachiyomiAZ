package eu.kanade.tachiyomi.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.kanade.tachiyomi.data.database.queries.CategoryQueries
import eu.kanade.tachiyomi.data.database.queries.ChapterQueries
import eu.kanade.tachiyomi.data.database.queries.HistoryQueries
import eu.kanade.tachiyomi.data.database.queries.MangaCategoryQueries
import eu.kanade.tachiyomi.data.database.queries.MangaQueries
import eu.kanade.tachiyomi.data.database.queries.TrackQueries
import exh.metadata.sql.queries.SearchMetadataQueries
import exh.metadata.sql.queries.SearchTagQueries
import exh.metadata.sql.queries.SearchTitleQueries
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory

/**
 * This class provides operations to manage the database through its interfaces.
 */
open class DatabaseHelper(context: Context) :
    MangaQueries,
    ChapterQueries,
    TrackQueries,
    CategoryQueries,
    MangaCategoryQueries,
    HistoryQueries,
    SearchMetadataQueries,
    SearchTagQueries,
    SearchTitleQueries {
    private val configuration =
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DbOpenCallback.DATABASE_NAME)
            .callback(DbOpenCallback())
            .build()

    /**
     * DbOpenCallback remains the sole owner of schema creation and the version-18 upgrade path
     * against real user databases; SQLDelight is given a driver over the already-open database
     * and never applies a schema of its own.
     */
    @PublishedApi
    internal val openHelper = RequerySQLiteOpenHelperFactory().create(configuration)

    /**
     * Typed query access, generated from app/src/main/sqldelight.
     */
    override val sqlDatabase: Database = Database(AndroidSqliteDriver(openHelper))

    /**
     * Runs a dynamically built query and returns the ids in its [idColumn].
     *
     * The exh search engine composes its SQL at runtime, so it cannot be a static SQLDelight
     * query, so it goes straight to the open helper.
     */
    fun rawQueryIds(
        sql: String,
        args: List<Any?>,
        idColumn: String
    ): LongArray {
        openHelper.readableDatabase.query(sql, args.toTypedArray()).use { cursor ->
            val ids = LongArray(cursor.count)
            if (ids.isNotEmpty()) {
                val idCol = cursor.getColumnIndexOrThrow(idColumn)
                cursor.moveToFirst()
                var i = 0
                while (!cursor.isAfterLast) {
                    ids[i++] = cursor.getLong(idCol)
                    cursor.moveToNext()
                }
            }
            return ids
        }
    }

    /**
     * Executes a statement directly. Only for schema-era migrations that predate the typed
     * query layer and must run against whatever shape the database had at the time.
     */
    fun executeSQL(sql: String) {
        openHelper.writableDatabase.execSQL(sql)
    }

    /**
     * Runs [block] in a single transaction on the shared connection.
     *
     * Kept `inline` because callers invoke suspend functions inside it; SQLDelight's own
     * transaction takes a non-inline lambda, which would not allow that.
     */
    inline fun inTransaction(block: () -> Unit) {
        val database = openHelper.writableDatabase
        database.beginTransaction()
        try {
            block()
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

}
