package eu.kanade.tachiyomi.data.backup.full

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.AbstractBackupManager
import eu.kanade.tachiyomi.data.backup.full.models.Backup
import eu.kanade.tachiyomi.data.backup.full.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.full.models.BackupFlatMetadata
import eu.kanade.tachiyomi.data.backup.full.models.BackupFull
import eu.kanade.tachiyomi.data.backup.full.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.full.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.full.models.BackupSource
import eu.kanade.tachiyomi.data.backup.full.models.copyFrom
import eu.kanade.tachiyomi.data.backup.full.models.getFlatMetadata
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.LewdSource
import exh.metadata.metadata.base.getFlatMetadataForManga
import exh.metadata.metadata.base.insertFlatMetadata
import exh.savedsearches.JsonSavedSearch
import exh.source.getMainSource
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.sink
import timber.log.Timber

@OptIn(ExperimentalSerializationApi::class)
class FullBackupManager(context: Context) : AbstractBackupManager(context) {
    val parser = ProtoBuf

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
        // Create root object
        var backup: Backup? = null

        databaseHelper.inTransaction {
            // What goes into a backup is BackupBuilder in :core-domain, so this app and the iOS
            // one produce the same file from the same library rather than each walking the tables
            // their own way. What stays here is what is not portable: resolving sources through
            // the extension manager, and exh's saved searches and metadata.
            backup =
                BackupBuilder.build(
                    db = databaseHelper,
                    flags = flags,
                    sources = ::backupExtensionInfo,
                    savedSearches = backupSavedSearches(),
                    flatMetadata = ::flatMetadataFor
                )
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
                        val oldBackupRegex = Regex("""tachiyomi_full_\d+-\d+-\d+_\d+-\d+.proto.gz""")
                        val newBackupRegex = Regex("""tachiyomi_full_\d+-\d+-\d+_\d+-\d+.tachibk""")
                        dir.listFiles { _, filename -> (oldBackupRegex.matches(filename) || newBackupRegex.matches(filename)) }
                            .orEmpty()
                            .sortedByDescending { it.name }
                            .drop(numberOfBackups - 1)
                            .forEach { it.delete() }

                        // Create new file to place backup
                        dir.createFile(BackupFull.getDefaultFilename())
                    } else {
                        UniFile.fromUri(context, uri)
                    }
                    )
                    ?: throw Exception("Couldn't create backup file")

            val byteArray = parser.encodeToByteArray(Backup.serializer(), backup!!)
            file.openOutputStream().sink().gzip().buffer().use { it.write(byteArray) }
            return file.uri.toString()
        } catch (e: Exception) {
            Timber.e(e)
            throw e
        }
    }

    /**
     * exh search metadata for a title, which only this app has.
     *
     * Passed to [BackupBuilder] rather than reached for inside it: `:core-domain` has the models
     * but not the sources, and whether a title carries metadata depends on its source being a
     * [LewdSource].
     */
    private fun flatMetadataFor(mangaId: Long): BackupFlatMetadata? {
        val manga = databaseHelper.getManga(mangaId) ?: return null
        val source = sourceManager.get(manga.source)?.getMainSource()
        if (source !is LewdSource<*, *>) return null
        return databaseHelper.getFlatMetadataForManga(mangaId)?.let { BackupFlatMetadata.copyFrom(it) }
    }

    private fun backupExtensionInfo(mangas: List<Manga>): List<BackupSource> {
        return mangas
            .asSequence()
            .map { it.source }
            .distinct()
            .map { sourceManager.getOrStub(it) }
            .map { BackupSource.copyFrom(it) }
            .toList()
    }

    // SY -->

    /**
     * Backup the saved searches from sources
     *
     * @return list of [BackupSavedSearch] to be backed up
     */
    private fun backupSavedSearches(): List<BackupSavedSearch> {
        return preferences.eh_savedSearches().get().map {
            val sourceId = it.substringBefore(':').toLong()
            val content = Json.decodeFromString<JsonSavedSearch>(it.substringAfter(':'))
            BackupSavedSearch(
                content.name,
                content.query,
                content.filters.toString(),
                sourceId
            )
        }
    }
    // SY <--

    fun restoreMangaNoFetch(
        manga: Manga,
        dbManga: Manga
    ) {
        BackupRestorer.restoreMangaNoFetch(databaseHelper, manga, dbManga)
    }

    /**
     * [Observable] that fetches manga information
     *
     * @param source source of manga
     * @param manga manga that needs updating
     * @return [Observable] that contains manga
     */
    suspend fun restoreMangaFetch(
        source: Source?,
        manga: Manga,
        online: Boolean
    ): Manga {
        // This is the new-manga path: nothing exists to merge with, so there is no favorite to
        // preserve. A backup only ever contains library entries, so a restored manga belongs in
        // the library -- which is what the ancestor's `manga.favorite = true` said before it was
        // corrupted here into a self-assignment that did nothing.
        manga.favorite = true

        return if (online && source != null) {
            val networkManga = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
            manga.copyFrom(networkManga)
            manga.initialized = true
            manga.id = insertManga(manga)
            manga
        } else {
            manga.initialized = manga.description != null
            manga.id = insertManga(manga)
            manga
        }
    }

    /**
     * Restore the categories from Json
     *
     * @param backupCategories list containing categories
     */
    internal fun restoreCategories(backupCategories: List<BackupCategory>) {
        BackupRestorer.restoreCategories(databaseHelper, backupCategories)
    }

    /**
     * Restores the categories a manga is in.
     *
     * @param manga the manga whose categories have to be restored.
     * @param categories the categories to restore.
     */
    internal fun restoreCategoriesForManga(
        manga: Manga,
        categories: List<Int>,
        backupCategories: List<BackupCategory>
    ) {
        BackupRestorer.restoreCategoriesForManga(databaseHelper, manga, categories, backupCategories)
    }

    /**
     * Restore history from Json
     *
     * @param history list containing history to be restored
     */
    internal fun restoreHistoryForManga(history: List<BackupHistory>) {
        BackupRestorer.restoreHistoryForManga(databaseHelper, history)
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
        BackupRestorer.restoreTrackForManga(databaseHelper, manga, tracks) { syncId ->
            trackManager.getService(syncId)?.isLogged == true
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
        // Return if fetch is needed
        if (databaseHelper.getChapters(manga).let { it.isEmpty() || it.size < chapters.size }) {
            return false
        }

        BackupRestorer.restoreChaptersForManga(databaseHelper, manga, chapters)
        return true
    }

    internal fun restoreChaptersForMangaOffline(
        manga: Manga,
        chapters: List<Chapter>
    ) {
        BackupRestorer.restoreChaptersForManga(databaseHelper, manga, chapters)
    }

    // SY -->
    internal fun restoreSavedSearches(backupSavedSearches: List<BackupSavedSearch>) {
        val currentSavedSearches =
            preferences.eh_savedSearches().get().map {
                val sourceId = it.substringBefore(':').toLong()
                val content = Json.decodeFromString<JsonSavedSearch>(it.substringAfter(':'))
                BackupSavedSearch(
                    content.name,
                    content.query,
                    content.filters.toString(),
                    sourceId
                )
            }

        preferences.eh_savedSearches()
            .set(
                (
                    backupSavedSearches.filter {
                            backupSavedSearch ->
                        currentSavedSearches.none { it.name == backupSavedSearch.name && it.source == backupSavedSearch.source }
                    }
                        .map {
                            "${it.source}:" +
                                Json.encodeToString(
                                    JsonSavedSearch(
                                        it.name,
                                        it.query,
                                        Json.decodeFromString(it.filterList)
                                    )
                                )
                        } + preferences.eh_savedSearches().get()
                    )
                    .toSet()
            )
    }

    internal fun restoreFlatMetadata(
        manga: Manga,
        backupFlatMetadata: BackupFlatMetadata
    ) {
        manga.id?.let { mangaId ->
            databaseHelper.getFlatMetadataForManga(mangaId).let {
                if (it == null) {
                    val flatMetadata = backupFlatMetadata.getFlatMetadata(mangaId)
                    databaseHelper.insertFlatMetadata(flatMetadata)
                }
            }
        }
    }
    // SY <--
}
