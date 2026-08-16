package eu.kanade.tachiyomi.data.database

import eu.kanade.tachiyomi.data.database.queries.CategoryQueries
import eu.kanade.tachiyomi.data.database.queries.ChapterQueries
import eu.kanade.tachiyomi.data.database.queries.HistoryQueries
import eu.kanade.tachiyomi.data.database.queries.MangaCategoryQueries
import eu.kanade.tachiyomi.data.database.queries.MangaQueries

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
 * Deliberately excludes TrackQueries and the exh search-metadata queries: the first depends on
 * TrackService and the second on the exh models, and both stay JVM-side.
 */
interface DatabaseHandler :
    MangaQueries,
    ChapterQueries,
    CategoryQueries,
    MangaCategoryQueries,
    HistoryQueries {
    /** Runs [block] in a single database transaction. */
    fun inTransaction(block: () -> Unit)
}
