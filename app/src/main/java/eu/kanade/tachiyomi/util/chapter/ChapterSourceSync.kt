package eu.kanade.tachiyomi.util.chapter

import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.domain.chapter.ChapterSyncPlatform
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.EH_SOURCE_ID
import exh.EXH_SOURCE_ID
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date
import eu.kanade.tachiyomi.domain.chapter.syncChaptersWithSource as syncChapters

/**
 * The Android half of chapter syncing.
 *
 * The diffing itself lives in :core-domain so both platforms reach the same conclusion about which
 * chapters are new, deleted or renamed. What stays here is everything that cannot be shared: the
 * extension API, the download directory, and the exh source ids.
 */
private class AndroidChapterSyncPlatform(
    private val source: Source,
    private val downloadManager: DownloadManager
) : ChapterSyncPlatform {
    override fun prepareNewChapter(
        chapter: SChapter,
        manga: Manga
    ) {
        if (source is HttpSource) {
            @Suppress("DEPRECATION")
            source.prepareNewChapter(chapter, manga)
        }
    }

    override fun isChapterDownloaded(
        chapter: Chapter,
        manga: Manga
    ): Boolean = downloadManager.isChapterDownloaded(chapter, manga)

    override fun renameDownloadedChapter(
        manga: Manga,
        from: Chapter,
        to: Chapter
    ) {
        downloadManager.renameChapter(source, manga, from, to)
    }

    override fun carriesOverReadingProgress(manga: Manga): Boolean = manga.source == EH_SOURCE_ID || manga.source == EXH_SOURCE_ID

    override fun now(): Long = Date().time
}

/**
 * Syncs the chapter half of a [SMangaUpdate], which sources may fill in even when
 * `fetchChapters = false` was requested.
 *
 * @return a pair of new insertions and deletions, or null if the source returned no chapters.
 */
fun syncChaptersFromUpdate(
    db: DatabaseHelper,
    update: SMangaUpdate,
    manga: Manga,
    source: Source
): Pair<List<Chapter>, List<Chapter>>? {
    if (update.chapters.isEmpty()) return null

    return syncChaptersWithSource(db, update.chapters, manga, source)
}

/**
 * Helper method for syncing the list of chapters from the source with the ones from the database.
 *
 * @param db the database.
 * @param rawSourceChapters a list of chapters from the source.
 * @param manga the manga of the chapters.
 * @param source the source of the chapters.
 * @return a pair of new insertions and deletions.
 */
fun syncChaptersWithSource(
    db: DatabaseHelper,
    rawSourceChapters: List<SChapter>,
    manga: Manga,
    source: Source
): Pair<List<Chapter>, List<Chapter>> {
    val downloadManager: DownloadManager = Injekt.get()

    return syncChapters(
        db,
        rawSourceChapters,
        manga,
        AndroidChapterSyncPlatform(source, downloadManager)
    )
}
