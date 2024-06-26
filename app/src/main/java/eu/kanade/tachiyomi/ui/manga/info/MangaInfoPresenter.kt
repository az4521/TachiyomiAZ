package eu.kanade.tachiyomi.ui.manga.info

import android.os.Bundle
import com.google.gson.Gson
import com.jakewharton.rxrelay.BehaviorRelay
import com.jakewharton.rxrelay.PublishRelay
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.database.models.toMangaInfo
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.toSManga
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.ui.source.SourceController
import eu.kanade.tachiyomi.util.lang.isNullOrUnsubscribed
import eu.kanade.tachiyomi.util.lang.runAsObservable
import eu.kanade.tachiyomi.util.prepUpdateCover
import eu.kanade.tachiyomi.util.removeCovers
import exh.MERGED_SOURCE_ID
import exh.util.await
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
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
    private val chapterCountRelay: BehaviorRelay<Float>,
    private val lastUpdateRelay: BehaviorRelay<Date>,
    private val mangaFavoriteRelay: PublishRelay<Boolean>,
    private val db: DatabaseHelper = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val gson: Gson = Injekt.get()
) : BasePresenter<MangaInfoController>() {
    /**
     * Subscription to update the manga from the source.
     */
    private var fetchMangaSubscription: Subscription? = null

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        getMangaObservable()
            .subscribeLatestCache({ view, manga -> view.onNextManga(manga, source) })

        // Update chapter count
        chapterCountRelay
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeLatestCache(MangaInfoController::setChapterCount)

        // Update favorite status
        mangaFavoriteRelay.observeOn(AndroidSchedulers.mainThread())
            .subscribe { setFavorite(it) }
            .apply { add(this) }

        // update last update date
        lastUpdateRelay
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeLatestCache(MangaInfoController::setLastUpdateDate)
    }

    private fun getMangaObservable(): Observable<Manga> {
        return db.getManga(manga.url, manga.source).asRxObservable()
            .observeOn(AndroidSchedulers.mainThread())
    }

    /**
     * Fetch manga information from source.
     */
    fun fetchMangaFromSource(manualFetch: Boolean = false) {
        if (!fetchMangaSubscription.isNullOrUnsubscribed()) return
        fetchMangaSubscription =
            Observable.defer {
                runAsObservable({
                    val networkManga = source.getMangaDetails(manga.toMangaInfo())
                    val sManga = networkManga.toSManga()
                    manga.prepUpdateCover(coverCache, sManga, manualFetch)
                    manga.copyFrom(sManga)
                    manga.initialized = true
                    db.insertManga(manga).executeAsBlocking()
                    manga
                })
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeFirst(
                    { view, _ ->
                        view.onFetchMangaDone()
                    },
                    MangaInfoController::onFetchMangaError
                )
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
        db.insertManga(manga).executeAsBlocking()
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
        return db.getCategories().executeAsBlocking()
    }

    /**
     * Gets the category id's the manga is in, if the manga is not in a category, returns the default id.
     *
     * @param manga the manga to get categories from.
     * @return Array of category ids the manga is in, if none returns default id
     */
    fun getMangaCategoryIds(manga: Manga): Array<Int> {
        val categories = db.getCategoriesForManga(manga).executeAsBlocking()
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
            db.getManga(originalMangaId).await()
                ?: throw IllegalArgumentException("Unknown manga ID: $originalMangaId")
        val toInsert =
            if (originalManga.source == MERGED_SOURCE_ID) {
                originalManga.apply {
                    val originalChildren = MergedSource.MangaConfig.readFromUrl(gson, url).children
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
                        ).writeAsUrl(gson)
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
                Manga.create(newMangaConfig.writeAsUrl(gson), originalManga.title, MERGED_SOURCE_ID).apply {
                    copyFrom(originalManga)
                    favorite = true
                    last_update = originalManga.last_update
                    viewer = originalManga.viewer
                    chapter_flags = originalManga.chapter_flags
                    sorting = Manga.SORTING_NUMBER
                }
            }

        // Note that if the manga are merged in a different order, this won't trigger, but I don't care lol
        val existingManga = db.getManga(toInsert.url, toInsert.source).await()
        if (existingManga != null) {
            withContext(NonCancellable) {
                if (toInsert.id != null) {
                    db.deleteManga(toInsert).await()
                }
            }

            return existingManga
        }

        // Reload chapters immediately
        toInsert.initialized = false

        val newId = db.insertManga(toInsert).await().insertedId()
        if (newId != null) toInsert.id = newId

        return toInsert
    }
}
