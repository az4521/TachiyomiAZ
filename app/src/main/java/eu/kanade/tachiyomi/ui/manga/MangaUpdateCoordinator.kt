package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.chapter.syncChaptersFromUpdate
import eu.kanade.tachiyomi.util.saveMangaUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    /** The shared fetch: what the source returned, and the chapter sync it was used for. */
    private class Fetch(val update: SMangaUpdate, val chapters: Pair<List<Chapter>, List<Chapter>>?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutex = Mutex()

    private var inFlight: Deferred<Fetch>? = null

    /** Whether the current [inFlight] fetch has already had its full metadata persisted. */
    private var metadataSaved = false

    /**
     * Returns the shared update, starting the fetch if no other tab has already done so.
     *
     * @param force starts a new fetch even if a result is already available, for manual refreshes.
     * @param updateMetadata persists everything the source returned about the manga -- description,
     *  status, genres, cover. Only the info tab asks for this: a refresh from the chapters tab is a
     *  request for chapters, so it saves only the memo and leaves the rest of the row alone. The
     *  fetch itself is shared either way, so this costs no extra network call.
     */
    suspend fun awaitUpdate(force: Boolean = false, updateMetadata: Boolean = false): Result {
        val deferred =
            mutex.withLock {
                val current = inFlight
                if (current != null && !(force && current.isCompleted)) {
                    current
                } else {
                    metadataSaved = false
                    scope.async { fetch() }.also { inFlight = it }
                }
            }

        val fetched =
            try {
                deferred.await()
            } catch (e: Throwable) {
                mutex.withLock {
                    if (inFlight === deferred) inFlight = null
                }
                throw e
            }

        save(fetched.update.manga, updateMetadata, force)
        return Result(fetched.chapters)
    }

    private suspend fun fetch(): Fetch {
        val update = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
        return Fetch(update, syncChaptersFromUpdate(db, update, manga, source))
    }

    /**
     * Writes the fetched manga row on behalf of one caller.
     *
     * Guarded so the two tabs sharing a fetch cannot fight over it: a full metadata save happens at
     * most once per fetch, and a memo-only save is skipped once one has already run, since the full
     * save wrote the memo too.
     */
    private suspend fun save(sManga: SManga, updateMetadata: Boolean, manualFetch: Boolean) {
        mutex.withLock {
            if (metadataSaved) return
            if (updateMetadata) metadataSaved = true

            withContext(Dispatchers.IO) {
                manga.saveMangaUpdate(sManga, db, coverCache, updateMetadata, manualFetch)
            }
        }
    }
}
