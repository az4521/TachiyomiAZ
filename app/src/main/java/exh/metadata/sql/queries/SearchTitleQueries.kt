package exh.metadata.sql.queries

import eu.kanade.tachiyomi.data.database.DbProvider
import eu.kanade.tachiyomi.data.database.mapSearchTitle
import exh.metadata.sql.models.SearchTitle

interface SearchTitleQueries : DbProvider {
    fun getSearchTitlesForManga(mangaId: Long): List<SearchTitle> =
        sqlDatabase.search_titlesQueries
            .getSearchTitlesForManga(mangaId, ::mapSearchTitle)
            .executeAsList()

    fun deleteSearchTitlesForManga(mangaId: Long) {
        sqlDatabase.search_titlesQueries.deleteSearchTitlesForManga(mangaId)
    }

    fun insertSearchTitle(searchTitle: SearchTitle) {
        sqlDatabase.search_titlesQueries.insertSearchTitle(
            searchTitle.mangaId,
            searchTitle.title,
            searchTitle.type.toLong()
        )
    }

    fun insertSearchTitles(searchTitles: List<SearchTitle>) {
        sqlDatabase.search_titlesQueries.transaction {
            searchTitles.forEach { insertSearchTitle(it) }
        }
    }

    fun deleteSearchTitle(searchTitle: SearchTitle) {
        searchTitle.id?.let { deleteSearchTitlesForManga(searchTitle.mangaId) }
    }

    fun deleteAllSearchTitle() {
        sqlDatabase.search_titlesQueries.deleteAllSearchTitles()
    }

    fun setSearchTitlesForManga(
        mangaId: Long,
        titles: List<SearchTitle>
    ) {
        sqlDatabase.search_titlesQueries.transaction {
            deleteSearchTitlesForManga(mangaId)
            insertSearchTitles(titles)
        }
    }
}
