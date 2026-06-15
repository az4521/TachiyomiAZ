package eu.kanade.tachiyomi.ui.source.latest

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.ui.source.browse.Pager

/**
 * ExhLatestUpdatesPager inherited from the Exh Pager.
 */
class ExhLatestUpdatesPager(val source: CatalogueSource) : Pager() {
    override suspend fun requestNextPage() {
        val mangasPage = source.getLatestUpdates(currentPage)
        onPageReceived(mangasPage)
    }

    override fun onPageReceived(mangasPage: MangasPage) {
        val page = currentPage
        currentPage = urlToId(mangasPage.mangas.lastOrNull()?.url)
        hasNextPage = mangasPage.hasNextPage && mangasPage.mangas.isNotEmpty()
        results.call(Pair(page, mangasPage.mangas))
    }

    private fun urlToId(url: String?): Int {
        val urlstring = url ?: return 0
        val match = Regex("\\/g\\/([0-9]*)\\/").find(urlstring)!!.destructured.toList().first()
        return match.toInt()
    }
}
