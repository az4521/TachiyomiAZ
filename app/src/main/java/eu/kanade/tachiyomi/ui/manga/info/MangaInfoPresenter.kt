package eu.kanade.tachiyomi.ui.manga.info

import android.os.Bundle
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.ui.manga.MangaUpdateCoordinator
import eu.kanade.tachiyomi.ui.source.SourceController
import eu.kanade.tachiyomi.util.lang.asFlow
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import eu.kanade.tachiyomi.util.removeCovers
import exh.MERGED_SOURCE_ID
import exh.util.await
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

/**
 * Presenter of MangaInfoFragment.
 * Contains information and data for fragment.
 * Observable updates should be called from here.
 */
class MangaInfoPresenter(
    val manga: Manga,
    val source: Source,
    val smartSearchConfig: SourceController.SmartSearchConfig?,
    private val chapterCountFlow: MutableSharedFlow<Float>,
    private val lastUpdateFlow: MutableSharedFlow<Date>,
    private val mangaFavoriteFlow: MutableSharedFlow<Boolean>,
    private val updateCoordinator: MangaUpdateCoordinator,
    private val db: DatabaseHelper = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val json: Json = Injekt.get()
) : BasePresenter<MangaInfoController>() {
    /**
     * Subscription to update the manga from the source.
     */
    private var fetchMangaJob: Job? = null

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        getMangaFlow()
            .collectLatestCache(onNext = { view, manga -> view.onNextManga(manga, source) })

        // Update chapter count
        chapterCountFlow
            .collectLatestCache(MangaInfoController::setChapterCount)

        // Update favorite status
        mangaFavoriteFlow
            .onEach { setFavorite(it) }
            .launchIn(presenterScope)

        // update last update date
        lastUpdateFlow
            .collectLatestCache(MangaInfoController::setLastUpdateDate)
    }

    private fun getMangaFlow(): Flow<Manga> {
        return db.getMangaAsFlow(manga.url, manga.source)
            // StorIO transiently emits null while the row is being (re)written elsewhere, and
            // onNextManga needs a non-null Manga. Fall back to the manga this presenter already
            // holds so the view always gets valid, current info instead of an empty screen;
            // the fresh DB copy replaces it as soon as StorIO re-emits the persisted row.
            .map { it ?: manga }
    }

    /**
     * Fetch manga information from source.
     */
    fun fetchMangaFromSource(manualFetch: Boolean = false) {
        if (fetchMangaJob?.isActive == true) return
        fetchMangaJob =
            presenterScope.launch {
                try {
                    withIOContext {
                        // The coordinator saves both halves of the update; the view picks the
                        // manga back up from the db flow.
                        updateCoordinator.awaitUpdate(force = manualFetch)
                    }
                    view?.onFetchMangaDone()
                } catch (e: Throwable) {
                    view?.onFetchMangaError(e)
                }
            }
    }

    /**
     * Update favorite status of manga, (removes / adds) manga (to / from) library.
     *
     * @return the new status of the manga.
     */
    fun toggleFavorite(): Boolean {
        manga.favorite = !manga.favorite
        manga.date_added =
            when (manga.favorite) {
                true -> Date().time
                false -> 0
            }
        if (!manga.favorite) {
            manga.removeCovers(coverCache)
        }
        db.insertManga(manga)
        return manga.favorite
    }

    private fun setFavorite(favorite: Boolean) {
        if (manga.favorite == favorite) {
            return
        }
        toggleFavorite()
    }

    /**
     * Returns true if the manga has any downloads.
     */
    fun hasDownloads(): Boolean {
        return downloadManager.getDownloadCount(manga) > 0
    }

    /**
     * Deletes all the downloads for the manga.
     */
    fun deleteDownloads() {
        downloadManager.deleteManga(manga, source)
    }

    /**
     * Get the default, and user categories.
     *
     * @return List of categories, default plus user categories
     */
    fun getCategories(): List<Category> {
        return db.getCategories()
    }

    /**
     * Gets the category id's the manga is in, if the manga is not in a category, returns the default id.
     *
     * @param manga the manga to get categories from.
     * @return Array of category ids the manga is in, if none returns default id
     */
    fun getMangaCategoryIds(manga: Manga): Array<Int> {
        val categories = db.getCategoriesForManga(manga)
        return categories.mapNotNull { it.id }.toTypedArray()
    }

    /**
     * Move the given manga to categories.
     *
     * @param manga the manga to move.
     * @param categories the selected categories.
     */
    fun moveMangaToCategories(
        manga: Manga,
        categories: List<Category>
    ) {
        val mc = categories.filter { it.id != 0 }.map { MangaCategory.create(manga, it) }
        db.setMangaCategories(mc, listOf(manga))
    }

    /**
     * Move the given manga to the category.
     *
     * @param manga the manga to move.
     * @param category the selected category, or null for default category.
     */
    fun moveMangaToCategory(
        manga: Manga,
        category: Category?
    ) {
        moveMangaToCategories(manga, listOfNotNull(category))
    }

    /*
    suspend fun recommendationView(manga: Manga): Manga {
        val title = manga.title
        val source = manga.source

    }*/
    suspend fun smartSearchMerge(
        manga: Manga,
        originalMangaId: Long
    ): Manga {
        val originalManga =
            db.getManga(originalMangaId)
                ?: throw IllegalArgumentException("Unknown manga ID: $originalMangaId")
        val toInsert =
            if (originalManga.source == MERGED_SOURCE_ID) {
                originalManga.apply {
                    val originalChildren = MergedSource.MangaConfig.readFromUrl(json, url).children
                    if (originalChildren.any { it.source == manga.source && it.url == manga.url }) {
                        throw IllegalArgumentException("This manga is already merged with the current manga!")
                    }

                    url =
                        MergedSource.MangaConfig(
                            originalChildren +
                                MergedSource.MangaSource(
                                    manga.source,
                                    manga.url
                                )
                        ).writeAsUrl(json)
                }
            } else {
                val newMangaConfig =
                    MergedSource.MangaConfig(
                        listOf(
                            MergedSource.MangaSource(
                                originalManga.source,
                                originalManga.url
                            ),
                            MergedSource.MangaSource(
                                manga.source,
                                manga.url
                            )
                        )
                    )
                Manga.create(newMangaConfig.writeAsUrl(json), originalManga.title, MERGED_SOURCE_ID).apply {
                    copyFrom(originalManga)
                    favorite = true
                    last_update = originalManga.last_update
                    viewer = originalManga.viewer
                    chapter_flags = originalManga.chapter_flags
                    sorting = Manga.SORTING_NUMBER
                }
            }

        // Note that if the manga are merged in a different order, this won't trigger, but I don't care lol
        val existingManga = db.getManga(toInsert.url, toInsert.source)
        if (existingManga != null) {
            withContext(NonCancellable) {
                if (toInsert.id != null) {
                    db.deleteManga(toInsert)
                }
            }

            return existingManga
        }

        // Reload chapters immediately
        toInsert.initialized = false

        // insertManga assigns the generated id back onto toInsert.
        db.insertManga(toInsert)

        return toInsert
    }
}
