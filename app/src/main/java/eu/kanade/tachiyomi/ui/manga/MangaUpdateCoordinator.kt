package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.chapter.syncChaptersFromUpdate
import eu.kanade.tachiyomi.util.saveMangaUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Shares a single [Source.getMangaUpdate] call between the info and chapters tabs of a
 * [MangaController], and saves everything it returns.
 *
 * Both tabs are created at once by the pager and each used to fetch on its own — details in one,
 * chapters in the other — which meant two concurrent calls for the same manga. Some extension
 * bases guard against that and throw on the second one. Here the two tabs share one call that
 * fetches both halves, and both halves are persisted here rather than in each tab, so nothing the
 * source returned is dropped just because the tab that would have saved it never fetched.
 *
 * The request runs in this coordinator's own scope, so a tab that goes away mid-flight (the user
 * leaves the screen, the view is recreated) cancels only its own wait, not the shared fetch. A
 * successful result is kept for the lifetime of the controller so a later non-forced fetch reuses
 * it; a failed one is dropped so the next fetch retries.
 */
class MangaUpdateCoordinator(
    private val manga: Manga,
    private val source: Source,
    private val db: DatabaseHelper = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get()
) {
    /**
     * The saved outcome of one update.
     *
     * @param chapters the chapters added and removed by the sync, or null if the source returned
     *  no chapters at all.
     */
    class Result(val chapters: Pair<List<Chapter>, List<Chapter>>?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutex = Mutex()

    private var inFlight: Deferred<Result>? = null

    /**
     * Returns the shared update, starting the fetch if no other tab has already done so.
     *
     * @param force starts a new fetch even if a result is already available, for manual refreshes.
     */
    suspend fun awaitUpdate(force: Boolean = false): Result {
        val deferred =
            mutex.withLock {
                val current = inFlight
                if (current != null && !(force && current.isCompleted)) {
                    current
                } else {
                    scope.async { fetchAndSave(force) }.also { inFlight = it }
                }
            }

        try {
            return deferred.await()
        } catch (e: Throwable) {
            mutex.withLock {
                if (inFlight === deferred) inFlight = null
            }
            throw e
        }
    }

    private suspend fun fetchAndSave(manualFetch: Boolean): Result {
        val update = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)

        // Opening the manga is an explicit request for its details, so metadata is always updated
        // here; the library setting only governs background library updates.
        manga.saveMangaUpdate(update.manga, db, coverCache, updateMetadata = true, manualFetch = manualFetch)

        return Result(syncChaptersFromUpdate(db, update, manga, source))
    }
}
