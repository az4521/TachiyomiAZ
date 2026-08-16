package eu.kanade.tachiyomi.data.database

import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaImpl

@Suppress("LongParameterList")
fun mapManga(
    id: Long,
    source: Long,
    url: String,
    artist: String?,
    author: String?,
    description: String?,
    genre: String?,
    title: String,
    status: Long,
    thumbnailUrl: String?,
    favorite: Long,
    lastUpdate: Long?,
    initialized: Long,
    viewer: Long,
    chapterFlags: Long,
    coverLastModified: Long,
    dateAdded: Long,
    updateStrategy: Long,
    memo: ByteArray
): Manga =
    MangaImpl().also {
        it.id = id
        it.source = source
        it.url = url
        it.artist = artist
        it.author = author
        it.description = description
        it.genre = genre
        it.title = title
        it.status = status.toInt()
        it.thumbnail_url = thumbnailUrl
        it.favorite = favorite == 1L
        it.last_update = lastUpdate ?: 0
        it.initialized = initialized == 1L
        it.viewer = viewer.toInt()
        it.chapter_flags = chapterFlags.toInt()
        it.cover_last_modified = coverLastModified
        it.date_added = dateAdded
        it.update_strategy = updateStrategyAdapter.decode(updateStrategy.toInt())
        it.memo = memoColumnAdapter.decode(memo)
    }
