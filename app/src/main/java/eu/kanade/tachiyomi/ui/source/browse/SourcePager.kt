package eu.kanade.tachiyomi.ui.source.browse

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList

open class SourcePager(val source: CatalogueSource, val query: String, val filters: FilterList) : Pager() {
    override suspend fun requestNextPage() {
        val page = currentPage

        val mangasPage =
            if (query.isBlank() && filters.isEmpty()) {
                source.getPopularManga(page)
            } else {
                source.getSearchManga(page, query, filters)
            }

        if (mangasPage.mangas.isNotEmpty()) {
            onPageReceived(mangasPage)
        } else {
            throw NoResultsException()
        }
    }
}
