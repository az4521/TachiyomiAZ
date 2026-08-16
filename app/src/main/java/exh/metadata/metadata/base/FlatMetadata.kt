package exh.metadata.metadata.base

import com.pushtorefresh.storio.operations.PreparedOperation
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.sql.models.SearchMetadata
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import rx.Completable
import rx.Single
import kotlin.reflect.KClass

@Serializable
data class FlatMetadata(
    val metadata: SearchMetadata,
    val tags: List<SearchTag>,
    val titles: List<SearchTitle>
) {
    inline fun <reified T : RaisedSearchMetadata> raise(): T = raise(T::class)

    @OptIn(InternalSerializationApi::class)
    fun <T : RaisedSearchMetadata> raise(clazz: KClass<T>): T =
        RaisedSearchMetadata.raiseFlattenJson
            .decodeFromString(clazz.serializer(), metadata.extra).apply {
                fillBaseFields(this@FlatMetadata)
            }
}

fun DatabaseHelper.getFlatMetadataForManga(mangaId: Long): FlatMetadata? {
    val meta = getSearchMetadataForManga(mangaId) ?: return null
    val tags = getSearchTagsForManga(mangaId)
    val titles = getSearchTitlesForManga(mangaId)
    return FlatMetadata(meta, tags, titles)
}

fun DatabaseHelper.insertFlatMetadata(flatMetadata: FlatMetadata) {
    require(flatMetadata.metadata.mangaId != -1L)

    inTransaction {
        insertSearchMetadata(flatMetadata.metadata)
        setSearchTagsForManga(flatMetadata.metadata.mangaId, flatMetadata.tags)
        setSearchTitlesForManga(flatMetadata.metadata.mangaId, flatMetadata.titles)
    }
}
