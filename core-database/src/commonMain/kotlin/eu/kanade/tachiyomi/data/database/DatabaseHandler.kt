package eu.kanade.tachiyomi.data.database

import eu.kanade.tachiyomi.data.database.queries.CategoryQueries
import eu.kanade.tachiyomi.data.database.queries.ChapterQueries
import eu.kanade.tachiyomi.data.database.queries.HistoryQueries
import eu.kanade.tachiyomi.data.database.queries.MangaCategoryQueries
import eu.kanade.tachiyomi.data.database.queries.MangaQueries
import eu.kanade.tachiyomi.data.database.queries.TrackQueries

/**
 * The database as shared code sees it.
 *
 * Domain logic cannot depend on `DatabaseHelper`: that class owns the Android open helper and the
 * Requery factory, so it necessarily stays in `:app`. This interface is the portable half of it --
 * the query mixins that already live in this module, plus transactions -- so shared code can be
 * written against the database without knowing which platform opened it.
 *
 * `DatabaseHelper` implements this on Android. On iOS the same queries are reached through
 * [IosDatabaseFactory]'s `Database`.
 *
 * Excludes only the exh search-metadata queries, which depend on the exh models and stay
 * Android-side. TrackQueries used to be excluded too, until its one TrackService parameter was
 * reduced to the service id it actually read.
 */
interface DatabaseHandler :
    MangaQueries,
    ChapterQueries,
    CategoryQueries,
    MangaCategoryQueries,
    HistoryQueries,
    TrackQueries {
    /** Runs [block] in a single database transaction. */
    fun inTransaction(block: () -> Unit)
}
