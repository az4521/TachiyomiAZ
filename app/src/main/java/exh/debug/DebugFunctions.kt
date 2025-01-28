package exh.debug

import android.app.Application
import com.pushtorefresh.storio.sqlite.queries.RawQuery
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.tables.MangaTable
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.system.jobScheduler
import exh.EH_SOURCE_ID
import exh.EXHMigrations
import exh.EXH_SOURCE_ID
import exh.eh.EHentaiThrottleManager
import exh.eh.EHentaiUpdateWorker
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.base.getFlatMetadataForManga
import exh.metadata.metadata.base.insertFlatMetadata
import exh.util.await
import exh.util.cancellable
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

object DebugFunctions {
    val app: Application by injectLazy()
    val db: DatabaseHelper by injectLazy()
    val prefs: PreferencesHelper by injectLazy()
    val sourceManager: SourceManager by injectLazy()
    val DownloadManager: DownloadManager by injectLazy()

    fun forceUpgradeMigration() {
        prefs.eh_lastVersionCode().set(0)
        EXHMigrations.upgrade(prefs)
    }

    fun forceLibraryUpdateJobSetup() {
        LibraryUpdateJob.setupTask(Injekt.get<Application>())
    }

    fun resetAgedFlagInEXHManga() {
        runBlocking {
            val metadataManga = db.getFavoriteMangaWithMetadata().await()

            val allManga =
                metadataManga.asFlow().cancellable().mapNotNull { manga ->
                    if (manga.source != EH_SOURCE_ID && manga.source != EXH_SOURCE_ID) {
                        return@mapNotNull null
                    }
                    manga
                }.toList()

            for (manga in allManga) {
                val meta = db.getFlatMetadataForManga(manga.id!!).await()?.raise<EHentaiSearchMetadata>()
                if (meta != null) {
                    // remove age flag
                    meta.aged = false
                    db.insertFlatMetadata(meta.flatten()).await()
                }
            }
        }
    }

    private val throttleManager = EHentaiThrottleManager()

    fun ResetEHGalleriesForUpdater() {
        throttleManager.resetThrottle()
        runBlocking {
            val metadataManga = db.getFavoriteMangaWithMetadata().await()

            val allManga =
                metadataManga.asFlow().cancellable().mapNotNull { manga ->
                    if (manga.source != EH_SOURCE_ID && manga.source != EXH_SOURCE_ID) {
                        return@mapNotNull null
                    }
                    manga
                }.toList()
            val eh = sourceManager.getOrStub(EH_SOURCE_ID)
            val ex = sourceManager.getOrStub(EXH_SOURCE_ID)

            for (manga in allManga) {
                throttleManager.throttle()
                if (manga.source == EH_SOURCE_ID) {
                    eh.fetchMangaDetails(manga).map { networkManga ->
                        manga.copyFrom(networkManga)
                        manga.initialized = true
                        db.insertManga(manga).executeAsBlocking()
                    }
                } else if (manga.source == EXH_SOURCE_ID) {
                    ex.fetchMangaDetails(manga).map { networkManga ->
                        manga.copyFrom(networkManga)
                        manga.initialized = true
                        db.insertManga(manga).executeAsBlocking()
                    }
                }
            }
        }
    }

    fun getEHMangaListWithAgedFlagInfo(): String {
        val galleries = mutableListOf(String())
        runBlocking {
            val metadataManga = db.getFavoriteMangaWithMetadata().await()

            val allManga =
                metadataManga.asFlow().cancellable().mapNotNull { manga ->
                    if (manga.source != EH_SOURCE_ID && manga.source != EXH_SOURCE_ID) {
                        return@mapNotNull null
                    }
                    manga
                }.toList()

            for (manga in allManga) {
                val meta = db.getFlatMetadataForManga(manga.id!!).await()?.raise<EHentaiSearchMetadata>()
                if (meta != null) {
                    // remove age flag
                    galleries += "Aged: ${meta.aged}\t Title: ${manga.title}"
                }
            }
        }
        return galleries.joinToString(",\n")
    }

    fun countAgedFlagInEXHManga(): Int {
        var agedAmount = 0
        runBlocking {
            val metadataManga = db.getFavoriteMangaWithMetadata().await()

            val allManga =
                metadataManga.asFlow().cancellable().mapNotNull { manga ->
                    if (manga.source != EH_SOURCE_ID && manga.source != EXH_SOURCE_ID) {
                        return@mapNotNull null
                    }
                    manga
                }.toList()

            for (manga in allManga) {
                val meta = db.getFlatMetadataForManga(manga.id!!).await()?.raise<EHentaiSearchMetadata>()
                if (meta != null && meta.aged) {
                    // remove age flag
                    agedAmount++
                }
            }
        }
        return agedAmount
    }

    fun addAllMangaInDatabaseToLibrary() {
        db.inTransaction {
            db.lowLevel().executeSQL(
                RawQuery.builder()
                    .query(
                        """
                        UPDATE ${MangaTable.TABLE}
                            SET ${MangaTable.COL_FAVORITE} = 1
                        """.trimIndent()
                    )
                    .affectsTables(MangaTable.TABLE)
                    .build()
            )
        }
    }

    fun getStatisticsInfo(): StatisticsInfoClass {
        val statisticsObject = StatisticsInfoClass()
        runBlocking {
            val libraryManga = db.getLibraryMangas().await()
            val databaseManga = db.getMangas().await()
            val databaseTracks = db.getAllTracks().await()
            val databaseChapters = db.getAllChapters().await()

            val databaseMangaMap = databaseManga.associateBy { it.id }

            statisticsObject.apply {
                mangaCount = libraryManga.count()
                completedMangaCount = libraryManga.count { it.status == SManga.COMPLETED && it.unread == 0 }
                startedMangaCount = databaseChapters.distinctBy { it.manga_id }.count { databaseMangaMap[it.manga_id]?.favorite ?: false }
                localMangaCount = databaseManga.count { it.source == LocalSource.ID }
                totalChapterCount = databaseChapters.count { databaseMangaMap[it.manga_id]?.favorite ?: false }
                readChapterCount = databaseChapters.count { it.read }
                downloadedChapterCount = DownloadManager.getDownloadCount()
                trackedMangaCount = databaseTracks.distinctBy { it.manga_id }.count()
                meanMangaScore = if (trackedMangaCount == 0) { Double.NaN } else { databaseTracks.map { it.score }.filter { it > 0 }.average() }
            }
        }
        return statisticsObject
    }

    fun countMangaInDatabaseInLibrary() = db.getMangas().executeAsBlocking().count { it.favorite }

    fun countMangaInDatabaseNotInLibrary() = db.getMangas().executeAsBlocking().count { !it.favorite }

    fun countMangaInDatabase() = db.getMangas().executeAsBlocking().size

    fun countMetadataInDatabase() = db.getSearchMetadata().executeAsBlocking().size

    fun countMangaInLibraryWithMissingMetadata() =
        db.getMangas().executeAsBlocking().count {
            it.favorite && db.getSearchMetadataForManga(it.id!!).executeAsBlocking() == null
        }

    fun clearSavedSearches() = prefs.eh_savedSearches().set(emptySet())

    fun listAllSources() =
        sourceManager.getCatalogueSources().joinToString("\n") {
            "${it.id}: ${it.name} (${it.lang.uppercase()})"
        }

    fun listFilteredSources() =
        sourceManager.getVisibleCatalogueSources().joinToString("\n") {
            "${it.id}: ${it.name} (${it.lang.uppercase()})"
        }

    fun listAllHttpSources() =
        sourceManager.getOnlineSources().joinToString("\n") {
            "${it.id}: ${it.name} (${it.lang.uppercase()})"
        }

    fun listFilteredHttpSources() =
        sourceManager.getVisibleOnlineSources().joinToString("\n") {
            "${it.id}: ${it.name} (${it.lang.uppercase()})"
        }

    fun convertAllEhentaiGalleriesToExhentai() = convertSources(EH_SOURCE_ID, EXH_SOURCE_ID)

    fun convertAllExhentaiGalleriesToEhentai() = convertSources(EXH_SOURCE_ID, EH_SOURCE_ID)

    fun testLaunchEhentaiBackgroundUpdater(): String {
        return EHentaiUpdateWorker.launchBackgroundTest(app)
    }

    fun rescheduleEhentaiBackgroundUpdater() {
        EHentaiUpdateWorker.scheduleBackground(app)
    }

    fun listScheduledJobs() =
        app.jobScheduler.allPendingJobs.map { j ->
            """
            {
                info: ${j.id},
                isPeriod: ${j.isPeriodic},
                isPersisted: ${j.isPersisted},
                intervalMillis: ${j.intervalMillis},
            }
            """.trimIndent()
        }.joinToString(",\n")

    fun cancelAllScheduledJobs() = app.jobScheduler.cancelAll()

    private fun convertSources(
        from: Long,
        to: Long
    ) {
        db.lowLevel().executeSQL(
            RawQuery.builder()
                .query(
                    """
                    UPDATE ${MangaTable.TABLE}
                        SET ${MangaTable.COL_SOURCE} = $to
                        WHERE ${MangaTable.COL_SOURCE} = $from
                    """.trimIndent()
                )
                .affectsTables(MangaTable.TABLE)
                .build()
        )
    }
}
