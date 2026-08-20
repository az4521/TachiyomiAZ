package eu.kanade.tachiyomi.ui.source.browse

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A general pager for source requests (latest updates, popular, search)
 */
abstract class Pager(var currentPage: Int = 1) {
    var hasNextPage = true
        protected set

    // Unbounded replay: the browse list is built up page by page, and a collector that attaches
    // late (or reattaches after the view was destroyed) needs every page, not just the newest.
    // This is what Nucleus's deliverReplay provided on the consuming side.
    protected val results: MutableSharedFlow<Pair<Int, List<SManga>>> =
        MutableSharedFlow(replay = Int.MAX_VALUE)

    fun results(): Flow<Pair<Int, List<SManga>>> {
        return results.asSharedFlow()
    }

    abstract suspend fun requestNextPage()

    open fun onPageReceived(mangasPage: MangasPage) {
        val page = currentPage
        currentPage++
        hasNextPage = mangasPage.hasNextPage && mangasPage.mangas.isNotEmpty()
        results.tryEmit(Pair(page, mangasPage.mangas))
    }
}
