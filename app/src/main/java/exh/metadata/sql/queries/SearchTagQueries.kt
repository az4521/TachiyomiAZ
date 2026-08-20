package exh.metadata.sql.queries

import eu.kanade.tachiyomi.data.database.DbProvider
import eu.kanade.tachiyomi.data.database.mapSearchTag
import exh.metadata.sql.models.SearchTag

interface SearchTagQueries : DbProvider {
    fun getSearchTagsForManga(mangaId: Long): List<SearchTag> =
        sqlDatabase.search_tagsQueries
            .getSearchTagsForManga(mangaId, ::mapSearchTag)
            .executeAsList()

    fun deleteSearchTagsForManga(mangaId: Long) {
        sqlDatabase.search_tagsQueries.deleteSearchTagsForManga(mangaId)
    }

    fun insertSearchTag(searchTag: SearchTag) {
        sqlDatabase.search_tagsQueries.insertSearchTag(
            searchTag.mangaId,
            searchTag.namespace,
            searchTag.name,
            searchTag.type.toLong()
        )
    }

    fun insertSearchTags(searchTags: List<SearchTag>) {
        sqlDatabase.search_tagsQueries.transaction {
            searchTags.forEach { insertSearchTag(it) }
        }
    }

    fun deleteSearchTag(searchTag: SearchTag) {
        searchTag.id?.let { deleteSearchTagsForManga(searchTag.mangaId) }
    }

    fun deleteAllSearchTags() {
        sqlDatabase.search_tagsQueries.deleteAllSearchTags()
    }

    fun setSearchTagsForManga(
        mangaId: Long,
        tags: List<SearchTag>
    ) {
        sqlDatabase.search_tagsQueries.transaction {
            deleteSearchTagsForManga(mangaId)
            insertSearchTags(tags)
        }
    }
}
