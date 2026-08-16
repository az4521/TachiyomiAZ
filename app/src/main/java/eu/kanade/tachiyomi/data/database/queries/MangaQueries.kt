package eu.kanade.tachiyomi.data.database.queries

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import eu.kanade.tachiyomi.data.database.mapLibraryManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import eu.kanade.tachiyomi.data.database.DbProvider
import eu.kanade.tachiyomi.data.database.updateStrategyAdapter
import eu.kanade.tachiyomi.data.database.memoColumnAdapter
import eu.kanade.tachiyomi.data.database.mapManga
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.resolvers.LibraryMangaGetResolver
import eu.kanade.tachiyomi.data.database.resolvers.MangaCoverLastModifiedPutResolver
import eu.kanade.tachiyomi.data.database.resolvers.MangaFavoritePutResolver
import eu.kanade.tachiyomi.data.database.resolvers.MangaFlagsPutResolver
import eu.kanade.tachiyomi.data.database.resolvers.MangaLastUpdatedPutResolver
import eu.kanade.tachiyomi.data.database.resolvers.MangaTitlePutResolver
import eu.kanade.tachiyomi.data.database.resolvers.MangaViewerPutResolver
import eu.kanade.tachiyomi.data.database.tables.CategoryTable
import eu.kanade.tachiyomi.data.database.tables.ChapterTable
import eu.kanade.tachiyomi.data.database.tables.MangaCategoryTable
import eu.kanade.tachiyomi.data.database.tables.MangaTable
import exh.metadata.sql.tables.SearchMetadataTable

interface MangaQueries : DbProvider {
    fun getMangas(): List<Manga> = sqlDatabase.mangasQueries.getMangas(::mapManga).executeAsList()

    fun getLibraryMangas(): List<LibraryManga> =
        sqlDatabase.mangasQueries.getLibraryMangas(::mapLibraryManga).executeAsList()

    fun getLibraryMangasAsFlow(): Flow<List<LibraryManga>> =
        sqlDatabase.mangasQueries
            .getLibraryMangas(::mapLibraryManga)
            .asFlow()
            .mapToList(Dispatchers.IO)

    fun getFavoriteMangas(): List<Manga> =
        sqlDatabase.mangasQueries.getFavoriteMangas(::mapManga).executeAsList()

    fun getManga(
        url: String,
        sourceId: Long
    ): Manga? =
        sqlDatabase.mangasQueries
            .getMangaByUrlAndSource(url, sourceId, ::mapManga)
            .executeAsOneOrNull()

    fun getMangaAsFlow(
        url: String,
        sourceId: Long
    ): Flow<Manga?> =
        sqlDatabase.mangasQueries
            .getMangaByUrlAndSourceFlow(url, sourceId, ::mapManga)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)

    fun getFavoriteMangasAsFlow(): Flow<List<Manga>> =
        sqlDatabase.mangasQueries
            .getFavoriteMangasFlow(::mapManga)
            .asFlow()
            .mapToList(Dispatchers.IO)

    fun getMangasBySource(sourceId: Long): List<Manga> =
        sqlDatabase.mangasQueries.getMangasBySource(sourceId, ::mapManga).executeAsList()

    /** MangaUrlPutResolver wrote url only. */
    fun updateMangaUrls(mangas: List<Manga>) {
        sqlDatabase.mangasQueries.transaction {
            mangas.forEach { manga ->
                manga.id?.let { sqlDatabase.mangasQueries.updateMangaUrl(manga.url, it) }
            }
        }
    }

    fun getManga(id: Long): Manga? =
        sqlDatabase.mangasQueries.getMangaById(id, ::mapManga).executeAsOneOrNull()

    fun getMergedMangas(id: Long): List<Manga> =
        sqlDatabase.mangasQueries.getMergedMangas(id, ::mapManga).executeAsList()

    fun insertManga(manga: Manga) {
        sqlDatabase.mangasQueries.transaction {
            val id = manga.id
            if (id == null) {
                sqlDatabase.mangasQueries.insertManga(
                    manga.source, manga.url, manga.artist, manga.author, manga.description,
                    manga.genre, manga.title, manga.status.toLong(), manga.thumbnail_url,
                    if (manga.favorite) 1L else 0L, manga.last_update,
                    if (manga.initialized) 1L else 0L, manga.viewer.toLong(),
                    manga.chapter_flags.toLong(), manga.cover_last_modified, manga.date_added,
                    updateStrategyAdapter.encode(manga.update_strategy).toLong(),
                    memoColumnAdapter.encode(manga.memo)
                )
                manga.id = sqlDatabase.mangasQueries.lastInsertRowId().executeAsOne()
            } else {
                sqlDatabase.mangasQueries.updateManga(
                    manga.source, manga.url, manga.artist, manga.author, manga.description,
                    manga.genre, manga.title, manga.status.toLong(), manga.thumbnail_url,
                    if (manga.favorite) 1L else 0L, manga.last_update,
                    if (manga.initialized) 1L else 0L, manga.viewer.toLong(),
                    manga.chapter_flags.toLong(), manga.cover_last_modified, manga.date_added,
                    updateStrategyAdapter.encode(manga.update_strategy).toLong(),
                    memoColumnAdapter.encode(manga.memo), id
                )
            }
        }
    }

    fun insertMangas(mangas: List<Manga>) {
        sqlDatabase.mangasQueries.transaction { mangas.forEach { insertManga(it) } }
    }

    fun updateFlags(manga: Manga) {
        manga.id?.let { sqlDatabase.mangasQueries.updateFlags(manga.chapter_flags.toLong(), it) }
    }

    fun updateLastUpdated(manga: Manga) {
        manga.id?.let { sqlDatabase.mangasQueries.updateLastUpdated(manga.last_update, it) }
    }

    fun updateMangaFavorite(manga: Manga) {
        manga.id?.let { sqlDatabase.mangasQueries.updateMangaFavorite(if (manga.favorite) 1L else 0L, it) }
    }

    fun updateMangaViewer(manga: Manga) {
        manga.id?.let { sqlDatabase.mangasQueries.updateMangaViewer(manga.viewer.toLong(), it) }
    }

    fun updateMangaTitle(manga: Manga) {
        manga.id?.let { sqlDatabase.mangasQueries.updateMangaTitle(manga.title, it) }
    }

    fun updateMangaCoverLastModified(manga: Manga) {
        manga.id?.let { sqlDatabase.mangasQueries.updateMangaCoverLastModified(manga.cover_last_modified, it) }
    }

    fun deleteManga(manga: Manga) {
        manga.id?.let { sqlDatabase.mangasQueries.deleteManga(it) }
    }

    fun deleteMangas(mangas: List<Manga>) {
        sqlDatabase.mangasQueries.transaction { mangas.forEach { deleteManga(it) } }
    }

    fun deleteMangasNotInLibrary() {
        sqlDatabase.mangasQueries.deleteMangasNotInLibrary()
    }

    fun deleteMangas() {
        sqlDatabase.mangasQueries.deleteMangas()
    }

    fun getLastReadManga(): List<Manga> =
        sqlDatabase.mangasQueries.getLastReadManga(::mapManga).executeAsList()

    fun getMangaWithMetadata(): List<Manga> =
        sqlDatabase.mangasQueries.getMangaWithMetadata(::mapManga).executeAsList()

    fun getFavoriteMangaWithMetadata(): List<Manga> =
        sqlDatabase.mangasQueries.getFavoriteMangaWithMetadata(::mapManga).executeAsList()

    fun getIdsOfFavoriteMangaWithMetadata(): List<Long> =
        sqlDatabase.mangasQueries.getIdsOfFavoriteMangaWithMetadata().executeAsList()

    fun getTotalChapterManga(): List<Manga> =
        sqlDatabase.mangasQueries.getTotalChapterManga(::mapManga).executeAsList()

    fun getLatestChapterManga(): List<Manga> =
        sqlDatabase.mangasQueries.getLatestChapterManga(::mapManga).executeAsList()
}
