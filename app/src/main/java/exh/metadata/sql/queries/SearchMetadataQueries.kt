package exh.metadata.sql.queries

import eu.kanade.tachiyomi.data.database.DbProvider
import eu.kanade.tachiyomi.data.database.mapSearchMetadata
import exh.metadata.sql.models.SearchMetadata

interface SearchMetadataQueries : DbProvider {
    fun getSearchMetadataForManga(mangaId: Long): SearchMetadata? =
        sqlDatabase.search_metadataQueries
            .getSearchMetadataForManga(mangaId, ::mapSearchMetadata)
            .executeAsOneOrNull()

    fun getSearchMetadata(): List<SearchMetadata> =
        sqlDatabase.search_metadataQueries.getSearchMetadata(::mapSearchMetadata).executeAsList()

    fun getSearchMetadataByIndexedExtra(extra: String): List<SearchMetadata> =
        sqlDatabase.search_metadataQueries
            .getSearchMetadataByIndexedExtra(extra, ::mapSearchMetadata)
            .executeAsList()

    /**
     * manga_id is the primary key, so INSERT OR REPLACE upserts -- which is what storio's put
     * did here.
     */
    fun insertSearchMetadata(metadata: SearchMetadata) {
        sqlDatabase.search_metadataQueries.insertSearchMetadata(
            metadata.mangaId,
            metadata.uploader,
            metadata.extra,
            metadata.indexedExtra,
            metadata.extraVersion.toLong()
        )
    }

    fun deleteSearchMetadata(metadata: SearchMetadata) {
        sqlDatabase.search_metadataQueries.deleteSearchMetadata(metadata.mangaId)
    }

    fun deleteAllSearchMetadata() {
        sqlDatabase.search_metadataQueries.deleteAllSearchMetadata()
    }
}
