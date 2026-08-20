package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import eu.kanade.tachiyomi.util.lang.awaitSingle
import exh.eh.EHentaiThrottleManager
import uy.kohesive.injekt.injectLazy

abstract class AbstractBackupManager(protected val context: Context) {
    internal val databaseHelper: DatabaseHelper by injectLazy()
    internal val sourceManager: SourceManager by injectLazy()
    internal val trackManager: TrackManager by injectLazy()
    protected val preferences: PreferencesHelper by injectLazy()

    abstract fun createBackup(
        uri: Uri,
        flags: Int,
        isJob: Boolean
    ): String?

    /**
     * Returns manga
     *
     * @return [Manga], null if not found
     */
    internal fun getMangaFromDatabase(manga: Manga): Manga? = databaseHelper.getManga(manga.url, manga.source)

    /**
     * Fetches chapter information.
     *
     * @param source source of manga
     * @param manga manga that needs updating
     * @param chapters list of chapters in the backup
     * @return updated manga chapters
     */
    internal open suspend fun restoreChapters(
        source: Source,
        manga: Manga,
        chapters: List<Chapter>,
        throttleManager: EHentaiThrottleManager
    ): Pair<List<Chapter>, List<Chapter>> {
        // EHentai still exposes only the RxJava chapter API because it needs the
        // per-request throttle hook, so bridge it rather than converting exh here.
        val fetchedChapters =
            if (source is EHentai) {
                source.fetchChapterList(manga, throttleManager::throttle).awaitSingle()
            } else {
                source.getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true).chapters
            }
        val syncedChapters = syncChaptersWithSource(databaseHelper, fetchedChapters, manga, source)
        if (syncedChapters.first.isNotEmpty()) {
            chapters.forEach { it.manga_id = manga.id }
            updateChapters(chapters)
        }
        return syncedChapters
    }

    /**
     * Returns list containing manga from library
     *
     * @return [Manga] from library
     */
    protected fun getFavoriteManga(): List<Manga> = databaseHelper.getFavoriteMangas()

    /**
     * Inserts manga and returns id
     *
     * @return id of [Manga], null if not found
     */
    internal fun insertManga(manga: Manga): Long? {
        // insertManga assigns the generated id back onto the manga.
        databaseHelper.insertManga(manga)
        return manga.id
    }

    /**
     * Inserts list of chapters
     */
    protected fun insertChapters(chapters: List<Chapter>) {
        databaseHelper.insertChapters(chapters)
    }

    /**
     * Updates a list of chapters
     */
    protected fun updateChapters(chapters: List<Chapter>) {
        databaseHelper.updateChaptersBackup(chapters)
    }

    /**
     * Return number of backups.
     *
     * @return number of backups selected by user
     */
    protected fun numberOfBackups(): Int = preferences.numberOfBackups().get()
}
