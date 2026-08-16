package eu.kanade.tachiyomi.data.database

import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaChapter
import eu.kanade.tachiyomi.data.database.models.MangaChapterHistory
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

@Suppress("LongParameterList")
fun mapLibraryManga(
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
    memo: ByteArray,
    unread: Long,
    category: Long
): LibraryManga =
    LibraryManga().also {
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
        it.unread = unread.toInt()
        it.category = category.toInt()
    }

/**
 * Maps a manga+chapter join into a [MangaChapter]. Mirrors MangaChapterGetResolver, including
 * its two fixups: the manga id comes from the chapter's manga_id, and the manga url comes from
 * the aliased mangaUrl column because both tables have a `url`.
 */
@Suppress("LongParameterList")
fun mapMangaChapter(
    mangaId: Long,
    source: Long,
    mangaUrl: String,
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
    mangaMemo: ByteArray,
    chapterId: Long,
    chapterMangaId: Long,
    chapterUrl: String,
    chapterName: String,
    scanlator: String?,
    read: Long,
    bookmark: Long,
    lastPageRead: Long,
    chapterNumber: Double,
    sourceOrder: Long,
    dateFetch: Long,
    dateUpload: Long,
    chapterMemo: ByteArray
): MangaChapter {
    val manga =
        mapManga(
            mangaId, source, mangaUrl, artist, author, description, genre, title, status,
            thumbnailUrl, favorite, lastUpdate, initialized, viewer, chapterFlags,
            coverLastModified, dateAdded, updateStrategy, mangaMemo
        )
    val chapter =
        mapChapter(
            chapterId, chapterMangaId, chapterUrl, chapterName, scanlator, read, bookmark,
            lastPageRead, chapterNumber, sourceOrder, dateFetch, dateUpload, chapterMemo
        )
    manga.id = chapter.manga_id
    return MangaChapter(manga, chapter)
}

/** Mirrors MangaChapterHistoryGetResolver. */
@Suppress("LongParameterList")
fun mapMangaChapterHistory(
    mangaId: Long,
    source: Long,
    mangaUrl: String,
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
    mangaMemo: ByteArray,
    chapterId: Long,
    chapterMangaId: Long,
    chapterUrl: String,
    chapterName: String,
    scanlator: String?,
    read: Long,
    bookmark: Long,
    lastPageRead: Long,
    chapterNumber: Double,
    sourceOrder: Long,
    dateFetch: Long,
    dateUpload: Long,
    chapterMemo: ByteArray,
    historyId: Long,
    historyChapterId: Long,
    historyLastRead: Long?,
    historyTimeRead: Long?
): MangaChapterHistory {
    val mangaChapter =
        mapMangaChapter(
            mangaId, source, mangaUrl, artist, author, description, genre, title, status,
            thumbnailUrl, favorite, lastUpdate, initialized, viewer, chapterFlags,
            coverLastModified, dateAdded, updateStrategy, mangaMemo,
            chapterId, chapterMangaId, chapterUrl, chapterName, scanlator, read, bookmark,
            lastPageRead, chapterNumber, sourceOrder, dateFetch, dateUpload, chapterMemo
        )
    val history = mapHistory(historyId, historyChapterId, historyLastRead, historyTimeRead)
    return MangaChapterHistory(mangaChapter.manga, mangaChapter.chapter, history)
}
