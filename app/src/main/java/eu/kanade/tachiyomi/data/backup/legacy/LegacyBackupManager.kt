package eu.kanade.tachiyomi.data.backup.legacy

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.AbstractBackupManager
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_CATEGORY
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_CATEGORY_MASK
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_CHAPTER
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_CHAPTER_MASK
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_HISTORY
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_HISTORY_MASK
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_TRACK
import eu.kanade.tachiyomi.data.backup.BackupCreateService.Companion.BACKUP_TRACK_MASK
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.CATEGORIES
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.CHAPTERS
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.CURRENT_VERSION
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.EXTENSIONS
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.HISTORY
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.MANGA
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.SAVEDSEARCHES
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup.TRACK
import eu.kanade.tachiyomi.data.backup.legacy.models.DHistory
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.CategoryImpl
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.database.models.TrackImpl
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import eu.kanade.tachiyomi.util.lang.runAsObservable
import exh.eh.EHentaiThrottleManager
import exh.savedsearches.JsonSavedSearch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import rx.Observable
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.RuntimeException
import kotlin.math.max

class LegacyBackupManager(context: Context, version: Int = CURRENT_VERSION) : AbstractBackupManager(context) {
    var parserVersion: Int = version
        private set

    /**
     * Set version of parser
     *
     * @param version version of parser
     */
    internal fun setVersion(version: Int) {
        this.parserVersion = version
    }

    // ---- Serialize helpers ----

    private fun mangaToJson(manga: Manga) = buildJsonArray {
        add(manga.url)
        add(manga.title)
        add(manga.source)
        add(manga.viewer)
        add(manga.chapter_flags)
    }

    private fun categoryToJson(category: Category) = buildJsonArray {
        add(category.name)
        add(category.order)
    }

    private fun chapterToJson(chapter: Chapter): JsonElement? {
        if (!chapter.read && !chapter.bookmark && chapter.last_page_read == 0) return null
        return buildJsonObject {
            put("u", chapter.url)
            if (chapter.read) put("r", 1)
            if (chapter.bookmark) put("b", 1)
            if (chapter.last_page_read != 0) put("l", chapter.last_page_read)
        }
    }

    private fun historyToJson(history: DHistory): JsonElement? {
        if (history.lastRead == 0L) return null
        return buildJsonArray {
            add(history.url)
            add(history.lastRead)
        }
    }

    private fun trackToJson(track: Track) = buildJsonObject {
        put("t", track.title)
        put("s", track.sync_id)
        put("r", track.media_id)
        put("ml", track.library_id)
        put("l", track.last_chapter_read)
        put("u", track.tracking_url)
    }

    // ---- Deserialize helpers ----

    internal fun jsonToManga(json: JsonElement): MangaImpl {
        val arr = json.jsonArray
        return MangaImpl().apply {
            url = arr[0].jsonPrimitive.content
            title = arr[1].jsonPrimitive.content
            source = arr[2].jsonPrimitive.long
            viewer = arr[3].jsonPrimitive.int
            chapter_flags = arr[4].jsonPrimitive.int
        }
    }

    internal fun jsonToCategory(json: JsonElement): CategoryImpl {
        val arr = json.jsonArray
        return CategoryImpl().apply {
            name = arr[0].jsonPrimitive.content
            order = arr[1].jsonPrimitive.int
        }
    }

    internal fun jsonToChapter(json: JsonElement): ChapterImpl {
        val obj = json.jsonObject
        return ChapterImpl().apply {
            url = obj["u"]!!.jsonPrimitive.content
            read = obj["r"]?.jsonPrimitive?.int == 1
            bookmark = obj["b"]?.jsonPrimitive?.int == 1
            last_page_read = obj["l"]?.jsonPrimitive?.int ?: 0
        }
    }

    internal fun jsonToHistory(json: JsonElement): DHistory {
        val arr = json.jsonArray
        return DHistory(arr[0].jsonPrimitive.content, arr[1].jsonPrimitive.long)
    }

    internal fun jsonToTrack(json: JsonElement): TrackImpl {
        val obj = json.jsonObject
        return TrackImpl().apply {
            title = obj["t"]!!.jsonPrimitive.content
            sync_id = obj["s"]!!.jsonPrimitive.int
            media_id = obj["r"]!!.jsonPrimitive.int
            library_id = obj["ml"]!!.jsonPrimitive.long
            last_chapter_read = obj["l"]!!.jsonPrimitive.int
            tracking_url = obj["u"]!!.jsonPrimitive.content
        }
    }

    /**
     * Create backup Json file from database
     *
     * @param uri path of Uri
     * @param isJob backup called from job
     */
    override fun createBackup(
        uri: Uri,
        flags: Int,
        isJob: Boolean
    ): String? {
        val mangaEntries = mutableListOf<JsonElement>()
        val categoryEntries = mutableListOf<JsonElement>()
        val extensionEntries = mutableListOf<JsonElement>()
        var savedSearches = ""

        databaseHelper.inTransaction {
            val mangas = getFavoriteManga()

            val extensions: MutableSet<String> = mutableSetOf()

            // Backup library manga and its dependencies
            mangas.forEach { manga ->
                mangaEntries.add(backupMangaObject(manga, flags))

                // Maintain set of extensions/sources used (excludes local source)
                if (manga.source != LocalSource.ID) {
                    sourceManager.get(manga.source)?.let {
                        extensions.add("${manga.source}:${it.name}")
                    }
                }
            }

            // Backup categories
            if ((flags and BACKUP_CATEGORY_MASK) == BACKUP_CATEGORY) {
                backupCategories(categoryEntries)
            }

            // Backup extension ID/name mapping
            backupExtensionInfo(extensionEntries, extensions)
            // SY -->
            savedSearches = Injekt.get<PreferencesHelper>().eh_savedSearches().get().joinToString(separator = "***")
            // SY <--
        }

        val root = buildJsonObject {
            put(Backup.VERSION, CURRENT_VERSION)
            put(Backup.MANGAS, JsonArray(mangaEntries))
            put(CATEGORIES, JsonArray(categoryEntries))
            put(EXTENSIONS, JsonArray(extensionEntries))
            put(SAVEDSEARCHES, savedSearches)
        }

        try {
            val file: UniFile =
                (
                    if (isJob) {
                        // Get dir of file and create
                        var dir = UniFile.fromUri(context, uri)
                        dir = dir.createDirectory("automatic")

                        // Delete older backups
                        val numberOfBackups = numberOfBackups()
                        val backupRegex = Regex("""tachiyomi_\d+-\d+-\d+_\d+-\d+.json""")
                        dir.listFiles { _, filename -> backupRegex.matches(filename) }
                            .orEmpty()
                            .sortedByDescending { it.name }
                            .drop(numberOfBackups - 1)
                            .forEach { it.delete() }

                        // Create new file to place backup
                        dir.createFile(Backup.getDefaultFilename())
                    } else {
                        UniFile.fromUri(context, uri)
                    }
                    )
                    ?: throw Exception("Couldn't create backup file")

            file.openOutputStream().bufferedWriter().use {
                it.write(root.toString())
            }
            return file.uri.toString()
        } catch (e: Exception) {
            Timber.e(e)
            throw e
        }
    }

    private fun backupExtensionInfo(
        root: MutableList<JsonElement>,
        extensions: Set<String>
    ) {
        extensions.sorted().forEach {
            root.add(kotlinx.serialization.json.JsonPrimitive(it))
        }
    }

    /**
     * Backup the categories of library
     *
     * @param root list to add category entries to
     */
    internal fun backupCategories(root: MutableList<JsonElement>) {
        val categories = databaseHelper.getCategories().executeAsBlocking()
        categories.forEach { root.add(categoryToJson(it)) }
    }

    /**
     * Convert a manga to Json
     *
     * @param manga manga that gets converted
     * @return [JsonElement] containing manga information
     */
    internal fun backupMangaObject(
        manga: Manga,
        options: Int
    ): JsonElement {
        return buildJsonObject {
            // Backup manga fields
            put(MANGA, mangaToJson(manga))

            // Check if user wants chapter information in backup
            if (options and BACKUP_CHAPTER_MASK == BACKUP_CHAPTER) {
                // Backup all the chapters
                val chapters = databaseHelper.getChapters(manga).executeAsBlocking()
                if (chapters.isNotEmpty()) {
                    val chaptersJson = JsonArray(chapters.mapNotNull { chapterToJson(it) })
                    if (chaptersJson.size > 0) {
                        put(CHAPTERS, chaptersJson)
                    }
                }
            }

            // Check if user wants category information in backup
            if (options and BACKUP_CATEGORY_MASK == BACKUP_CATEGORY) {
                // Backup categories for this manga
                val categoriesForManga = databaseHelper.getCategoriesForManga(manga).executeAsBlocking()
                if (categoriesForManga.isNotEmpty()) {
                    put(
                        CATEGORIES,
                        buildJsonArray {
                            categoriesForManga.forEach { add(it.name) }
                        }
                    )
                }
            }

            // Check if user wants track information in backup
            if (options and BACKUP_TRACK_MASK == BACKUP_TRACK) {
                val tracks = databaseHelper.getTracks(manga).executeAsBlocking()
                if (tracks.isNotEmpty()) {
                    put(TRACK, JsonArray(tracks.map { trackToJson(it) }))
                }
            }

            // Check if user wants history information in backup
            if (options and BACKUP_HISTORY_MASK == BACKUP_HISTORY) {
                val historyForManga = databaseHelper.getHistoryByMangaId(manga.id!!).executeAsBlocking()
                if (historyForManga.isNotEmpty()) {
                    val historyData =
                        historyForManga.mapNotNull { history ->
                            val url = databaseHelper.getChapter(history.chapter_id).executeAsBlocking()?.url
                            url?.let { DHistory(url, history.last_read) }
                        }
                    val historyJson = JsonArray(historyData.mapNotNull { historyToJson(it) })
                    if (historyJson.size > 0) {
                        put(HISTORY, historyJson)
                    }
                }
            }
        }
    }

    fun restoreMangaNoFetch(
        manga: Manga,
        dbManga: Manga
    ) {
        manga.id = dbManga.id
        manga.copyFrom(dbManga)
        manga.favorite = true
        insertManga(manga)
    }

    /**
     * [Observable] that fetches manga information, including chapters, in as few
     * network requests as possible.
     *
     * @param source source of manga
     * @param manga manga that needs updating
     * @param chapters chapters of manga that needs updating
     * @return [Observable] that contains manga
     */
    fun restoreMangaFetchObservable(
        source: Source,
        manga: Manga,
        chapters: List<Chapter>,
        throttleManager: EHentaiThrottleManager
    ): Observable<Manga> {
        // EHentai requires throttled chapter fetching, so its details and chapters
        // can't share a single network request.
        if (source is EHentai) {
            return runAsObservable({
                val networkManga = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
                manga.copyFrom(networkManga)
                manga.favorite = true
                manga.initialized = true
                manga.id = insertManga(manga)
                manga
            }).flatMap { fetchedManga ->
                restoreChapterFetchObservable(source, fetchedManga, chapters, throttleManager)
                    .map { fetchedManga }
            }
        }

        // Fetch manga details and chapters in a single network request.
        return runAsObservable({
            val mangaUpdate = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
            manga.copyFrom(mangaUpdate.manga)
            manga.favorite = true
            manga.initialized = true
            manga.id = insertManga(manga)

            val (newChapters) = syncChaptersWithSource(databaseHelper, mangaUpdate.chapters, manga, source)
            if (newChapters.isNotEmpty()) {
                chapters.forEach { it.manga_id = manga.id }
                updateChapters(chapters)
            }
            manga
        })
    }

    /**
     * Restore the categories from Json
     *
     * @param jsonCategories array containing categories
     */
    internal fun restoreCategories(jsonCategories: JsonArray) {
        // Get categories from file and from db
        val dbCategories = databaseHelper.getCategories().executeAsBlocking()
        val backupCategories = jsonCategories.map { jsonToCategory(it) }

        // Iterate over them
        backupCategories.forEach { category ->
            // Used to know if the category is already in the db
            var found = false
            for (dbCategory in dbCategories) {
                // If the category is already in the db, assign the id to the file's category
                // and do nothing
                if (category.name == dbCategory.name) {
                    category.id = dbCategory.id
                    found = true
                    break
                }
            }
            // If the category isn't in the db, remove the id and insert a new category
            // Store the inserted id in the category
            if (!found) {
                // Let the db assign the id
                category.id = null
                val result = databaseHelper.insertCategory(category).executeAsBlocking()
                category.id = result.insertedId()?.toInt()
            }
        }
    }

    /**
     * Restores the categories a manga is in.
     *
     * @param manga the manga whose categories have to be restored.
     * @param categories the categories to restore.
     */
    internal fun restoreCategoriesForManga(
        manga: Manga,
        categories: List<String>
    ) {
        val dbCategories = databaseHelper.getCategories().executeAsBlocking()
        val mangaCategoriesToUpdate = mutableListOf<MangaCategory>()
        for (backupCategoryStr in categories) {
            for (dbCategory in dbCategories) {
                if (backupCategoryStr == dbCategory.name) {
                    mangaCategoriesToUpdate.add(MangaCategory.create(manga, dbCategory))
                    break
                }
            }
        }

        // Update database
        if (mangaCategoriesToUpdate.isNotEmpty()) {
            databaseHelper.deleteOldMangasCategories(listOf(manga)).executeAsBlocking()
            databaseHelper.insertMangasCategories(mangaCategoriesToUpdate).executeAsBlocking()
        }
    }

    /**
     * Restore history from Json
     *
     * @param history list containing history to be restored
     */
    internal fun restoreHistoryForManga(history: List<DHistory>) {
        // List containing history to be updated
        val historyToBeUpdated = mutableListOf<History>()
        for ((url, lastRead) in history) {
            val dbHistory = databaseHelper.getHistoryByChapterUrl(url).executeAsBlocking()
            // Check if history already in database and update
            if (dbHistory != null) {
                dbHistory.apply {
                    last_read = max(lastRead, dbHistory.last_read)
                }
                historyToBeUpdated.add(dbHistory)
            } else {
                // If not in database create
                databaseHelper.getChapter(url).executeAsBlocking()?.let {
                    val historyToAdd =
                        History.create(it).apply {
                            last_read = lastRead
                        }
                    historyToBeUpdated.add(historyToAdd)
                }
            }
        }
        databaseHelper.updateHistoryLastRead(historyToBeUpdated).executeAsBlocking()
    }

    /**
     * Restores the sync of a manga.
     *
     * @param manga the manga whose sync have to be restored.
     * @param tracks the track list to restore.
     */
    internal fun restoreTrackForManga(
        manga: Manga,
        tracks: List<Track>
    ) {
        // Fix foreign keys with the current manga id
        tracks.map { it.manga_id = manga.id!! }

        // Get tracks from database
        val dbTracks = databaseHelper.getTracks(manga).executeAsBlocking()
        val trackToUpdate = mutableListOf<Track>()

        tracks.forEach { track ->
            val service = trackManager.getService(track.sync_id)
            if (service != null && service.isLogged) {
                var isInDatabase = false
                for (dbTrack in dbTracks) {
                    if (track.sync_id == dbTrack.sync_id) {
                        // The sync is already in the db, only update its fields
                        if (track.media_id != dbTrack.media_id) {
                            dbTrack.media_id = track.media_id
                        }
                        if (track.library_id != dbTrack.library_id) {
                            dbTrack.library_id = track.library_id
                        }
                        dbTrack.last_chapter_read = max(dbTrack.last_chapter_read, track.last_chapter_read)
                        isInDatabase = true
                        trackToUpdate.add(dbTrack)
                        break
                    }
                }
                if (!isInDatabase) {
                    // Insert new sync. Let the db assign the id
                    track.id = null
                    trackToUpdate.add(track)
                }
            }
        }
        // Update database
        if (trackToUpdate.isNotEmpty()) {
            databaseHelper.insertTracks(trackToUpdate).executeAsBlocking()
        }
    }

    /**
     * Restore the chapters for manga if chapters already in database
     *
     * @param manga manga of chapters
     * @param chapters list containing chapters that get restored
     * @return boolean answering if chapter fetch is not needed
     */
    internal fun restoreChaptersForManga(
        manga: Manga,
        chapters: List<Chapter>
    ): Boolean {
        val dbChapters = databaseHelper.getChapters(manga).executeAsBlocking()

        // Return if fetch is needed
        if (dbChapters.isEmpty() || dbChapters.size < chapters.size) {
            return false
        }

        for (chapter in chapters) {
            val pos = dbChapters.indexOf(chapter)
            if (pos != -1) {
                val dbChapter = dbChapters[pos]
                chapter.id = dbChapter.id
                chapter.copyFrom(dbChapter)
                break
            }
        }
        // Filter the chapters that couldn't be found.
        chapters.filter { it.id != null }
        chapters.map { it.manga_id = manga.id }

        updateChapters(chapters)
        return true
    }

    // SY -->
    internal fun restoreSavedSearches(jsonSavedSearches: JsonElement) {
        val backupSavedSearches = jsonSavedSearches.jsonPrimitive.content.split("***").toSet()

        val newSavedSearches =
            backupSavedSearches.mapNotNull {
                try {
                    val id = it.substringBefore(':').toLong()
                    val content = Json.decodeFromString<JsonSavedSearch>(it.substringAfter(':'))
                    id to content
                } catch (t: RuntimeException) {
                    // Load failed
                    Timber.e(t, "Failed to load saved search!")
                    t.printStackTrace()
                    null
                }
            }.toMutableList()

        val currentSources = newSavedSearches.map { it.first }.toSet()

        newSavedSearches +=
            preferences.eh_savedSearches().get().mapNotNull {
                try {
                    val id = it.substringBefore(':').toLong()
                    val content = Json.decodeFromString<JsonSavedSearch>(it.substringAfter(':'))
                    id to content
                } catch (t: RuntimeException) {
                    // Load failed
                    Timber.e(t, "Failed to load saved search!")
                    t.printStackTrace()
                    null
                }
            }.toMutableList()

        val otherSerialized =
            preferences.eh_savedSearches().get().mapNotNull {
                val sourceId = it.split(":")[0].toLongOrNull() ?: return@mapNotNull null
                if (sourceId in currentSources) return@mapNotNull null
                it
            }

        val newSerialized =
            newSavedSearches.map {
                "${it.first}:" + Json.encodeToString(it.second)
            }
        preferences.eh_savedSearches().set((otherSerialized + newSerialized).toSet())
    }
}
