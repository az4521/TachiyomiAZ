package eu.kanade.tachiyomi.data.backup.legacy

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.AbstractBackupRestore
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.MANGAS
import eu.kanade.tachiyomi.data.backup.legacy.models.DHistory
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.source.Source
import exh.EXHMigrations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rx.Observable
import java.util.Date

class LegacyBackupRestore(context: Context, notifier: BackupNotifier) : AbstractBackupRestore<LegacyBackupManager>(context, notifier) {
    override fun performRestore(uri: Uri): Boolean {
        // SY -->
        throttleManager.resetThrottle()
        // SY <--
        val json = context.contentResolver.openInputStream(uri)!!.bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }

        val version = json[Backup.VERSION]?.jsonPrimitive?.int ?: 1
        backupManager = LegacyBackupManager(context, version)

        val mangasJson = json[MANGAS]!!.jsonArray
        restoreAmount = mangasJson.size + 2 // +1 for categories, +1 for saved searches, +1 for merged manga references

        // Restore categories
        json[Backup.CATEGORIES]?.let { restoreCategories(it) }

        // SY -->
        json[Backup.SAVEDSEARCHES]?.let { restoreSavedSearches(it) }
        // SY <--

        // Store source mapping for error messages
        sourceMapping = LegacyBackupRestoreValidator.getSourceMapping(json)

        // Restore individual manga
        mangasJson.forEach {
            if (job?.isActive != true) {
                return false
            }

            restoreManga(it.jsonObject)
        }

        return true
    }

    private fun restoreCategories(categoriesJson: JsonElement) {
        db.inTransaction {
            backupManager.restoreCategories(categoriesJson.jsonArray)
        }

        restoreProgress += 1
        showRestoreProgress(restoreProgress, restoreAmount, context.getString(R.string.categories))
    }

    // SY -->
    private fun restoreSavedSearches(savedSearchesJson: JsonElement) {
        backupManager.restoreSavedSearches(savedSearchesJson)

        restoreProgress += 1
        showRestoreProgress(restoreProgress, restoreAmount, context.getString(R.string.saved_searches))
    }
    // SY <--

    private fun restoreManga(mangaJson: JsonObject) {
        // SY -->
        var /* SY <-- */ manga = backupManager.jsonToManga(mangaJson[Backup.MANGA]!!)
        val chapters = (mangaJson[Backup.CHAPTERS]?.jsonArray ?: emptyList()).map { backupManager.jsonToChapter(it) }
        val categories = (mangaJson[Backup.CATEGORIES]?.jsonArray ?: emptyList()).map { it.jsonPrimitive.content }
        val history = (mangaJson[Backup.HISTORY]?.jsonArray ?: emptyList()).map { backupManager.jsonToHistory(it) }
        val tracks = (mangaJson[Backup.TRACK]?.jsonArray ?: emptyList()).map { backupManager.jsonToTrack(it) }

        // EXH -->
        manga = EXHMigrations.migrateBackupEntry(manga)
        // <-- EXH

        try {
            val source = backupManager.sourceManager.get(manga.source)
            if (source != null) {
                restoreMangaData(manga, source, chapters, categories, history, tracks)
            } else {
                val sourceName = sourceMapping[manga.source] ?: manga.source.toString()
                errors.add(Date() to "${manga.title} - ${context.getString(R.string.source_not_found_name, sourceName)}")
            }
        } catch (e: Exception) {
            errors.add(Date() to "${manga.title} - ${e.message}")
        }

        restoreProgress += 1
        showRestoreProgress(restoreProgress, restoreAmount, manga.title)
    }

    /**
     * Returns a manga restore observable
     *
     * @param manga manga data from json
     * @param source source to get manga data from
     * @param chapters chapters data from json
     * @param categories categories data from json
     * @param history history data from json
     * @param tracks tracking data from json
     */
    private fun restoreMangaData(
        manga: Manga,
        source: Source,
        chapters: List<Chapter>,
        categories: List<String>,
        history: List<DHistory>,
        tracks: List<Track>
    ) {
        val dbManga = backupManager.getMangaFromDatabase(manga)

        db.inTransaction {
            if (dbManga == null) {
                // Manga not in database
                restoreMangaFetch(source, manga, chapters, categories, history, tracks)
            } else { // Manga in database
                // Copy information from manga already in database
                backupManager.restoreMangaNoFetch(manga, dbManga)
                // Fetch rest of manga information
                restoreMangaNoFetch(source, manga, chapters, categories, history, tracks)
            }
        }
    }

    /**
     * [Observable] that fetches manga information
     *
     * @param manga manga that needs updating
     * @param chapters chapters of manga that needs updating
     * @param categories categories that need updating
     */
    private fun restoreMangaFetch(
        source: Source,
        manga: Manga,
        chapters: List<Chapter>,
        categories: List<String>,
        history: List<DHistory>,
        tracks: List<Track>
    ) {
        backupManager.restoreMangaFetchObservable(source, manga)
            .onErrorReturn {
                errors.add(Date() to "${manga.title} - ${it.message}")
                manga
            }
            .filter { it.id != null }
            .flatMap {
                chapterFetchObservable(source, it, chapters)
                    // Convert to the manga that contains new chapters.
                    .map { manga }
            }
            .doOnNext {
                restoreExtraForManga(it, categories, history, tracks)
            }
            .flatMap {
                trackingFetchObservable(it, tracks)
            }
            .subscribe()
    }

    private fun restoreMangaNoFetch(
        source: Source,
        backupManga: Manga,
        chapters: List<Chapter>,
        categories: List<String>,
        history: List<DHistory>,
        tracks: List<Track>
    ) {
        Observable.just(backupManga)
            .flatMap { manga ->
                if (!backupManager.restoreChaptersForManga(manga, chapters)) {
                    chapterFetchObservable(source, manga, chapters)
                        .map { manga }
                } else {
                    Observable.just(manga)
                }
            }
            .doOnNext {
                restoreExtraForManga(it, categories, history, tracks)
            }
            .flatMap { manga ->
                trackingFetchObservable(manga, tracks)
            }
            .subscribe()
    }

    private fun restoreExtraForManga(
        manga: Manga,
        categories: List<String>,
        history: List<DHistory>,
        tracks: List<Track>
    ) {
        // Restore categories
        backupManager.restoreCategoriesForManga(manga, categories)

        // Restore history
        backupManager.restoreHistoryForManga(history)

        // Restore tracking
        backupManager.restoreTrackForManga(manga, tracks)
    }
}
