package eu.kanade.tachiyomi.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.pushtorefresh.storio.sqlite.impl.DefaultStorIOSQLite
import eu.kanade.tachiyomi.data.database.mappers.CategoryTypeMapping
import eu.kanade.tachiyomi.data.database.mappers.ChapterTypeMapping
import eu.kanade.tachiyomi.data.database.mappers.HistoryTypeMapping
import eu.kanade.tachiyomi.data.database.mappers.MangaCategoryTypeMapping
import eu.kanade.tachiyomi.data.database.mappers.MangaTypeMapping
import eu.kanade.tachiyomi.data.database.mappers.TrackTypeMapping
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.database.queries.CategoryQueries
import eu.kanade.tachiyomi.data.database.queries.ChapterQueries
import eu.kanade.tachiyomi.data.database.queries.HistoryQueries
import eu.kanade.tachiyomi.data.database.queries.MangaCategoryQueries
import eu.kanade.tachiyomi.data.database.queries.MangaQueries
import eu.kanade.tachiyomi.data.database.queries.TrackQueries
import exh.metadata.sql.mappers.SearchMetadataTypeMapping
import exh.metadata.sql.mappers.SearchTagTypeMapping
import exh.metadata.sql.mappers.SearchTitleTypeMapping
import exh.metadata.sql.models.SearchMetadata
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
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
     * Hoisted so storio and SQLDelight share one open helper, and therefore one connection and
     * one transaction scope. DbOpenCallback remains the sole owner of schema creation and the
     * version-18 upgrade path; SQLDelight is given a driver over the already-open database and
     * never applies a schema of its own.
     */
    private val openHelper = RequerySQLiteOpenHelperFactory().create(configuration)

    /**
     * Typed query access, generated from app/src/main/sqldelight. Call sites migrate off storio
     * onto this incrementally; both read the same database in the meantime.
     */
    override val sqlDatabase: Database = Database(AndroidSqliteDriver(openHelper))

    override val db =
        DefaultStorIOSQLite.builder()
            .sqliteOpenHelper(openHelper)
            .addTypeMapping(Manga::class.java, MangaTypeMapping())
            .addTypeMapping(Chapter::class.java, ChapterTypeMapping())
            .addTypeMapping(Track::class.java, TrackTypeMapping())
            .addTypeMapping(Category::class.java, CategoryTypeMapping())
            .addTypeMapping(MangaCategory::class.java, MangaCategoryTypeMapping())
            .addTypeMapping(SearchMetadata::class.java, SearchMetadataTypeMapping())
            .addTypeMapping(History::class.java, HistoryTypeMapping())
            .addTypeMapping(SearchTag::class.java, SearchTagTypeMapping())
            .addTypeMapping(SearchTitle::class.java, SearchTitleTypeMapping())
            .build()

    inline fun inTransaction(block: () -> Unit) = db.inTransaction(block)

    fun lowLevel() = db.lowLevel()
}
