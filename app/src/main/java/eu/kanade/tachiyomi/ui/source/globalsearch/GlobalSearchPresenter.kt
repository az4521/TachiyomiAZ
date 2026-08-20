package eu.kanade.tachiyomi.ui.source.globalsearch

import android.os.Bundle
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourcePresenter
import eu.kanade.tachiyomi.util.system.withUIContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * Presenter of [GlobalSearchController]
 * Function calls should be done from here. UI calls should be done from the controller.
 *
 * @param sourceManager manages the different sources.
 * @param db manages the database calls.
 * @param preferences manages the preference calls.
 */
open class GlobalSearchPresenter(
    private val initialQuery: String? = "",
    private val initialExtensionFilter: String? = null,
    private val sourcesToUse: List<CatalogueSource>? = null,
    val sourceManager: SourceManager = Injekt.get(),
    val db: DatabaseHelper = Injekt.get(),
    val preferences: PreferencesHelper = Injekt.get()
) : BasePresenter<GlobalSearchController>() {
    /**
     * Enabled sources.
     */
    val sources by lazy { getSourcesToQuery() }

    /**
     * Query from the view.
     */
    var query = ""
        private set

    /**
     * Fetches the different sources by user settings.
     */
    private var fetchSourcesJob: Job? = null

    /**
     * Flow which fetches image of given manga.
     */
    private val fetchImageFlow =
        MutableSharedFlow<Pair<List<Manga>, Source>>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    /**
     * Job for fetching images of manga.
     */
    private var fetchImageJob: Job? = null

    private val extensionManager by injectLazy<ExtensionManager>()

    private var extensionFilter: String? = null

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        extensionFilter = savedState?.getString(GlobalSearchPresenter::extensionFilter.name)
            ?: initialExtensionFilter

        // Perform a search with previous or initial state
        search(
            savedState?.getString(BrowseSourcePresenter::query.name)
                ?: initialQuery.orEmpty()
        )
    }

    override fun onDestroy() {
        fetchSourcesJob?.cancel()
        fetchImageJob?.cancel()
        super.onDestroy()
    }

    override fun onSave(state: Bundle) {
        state.putString(BrowseSourcePresenter::query.name, query)
        state.putString(GlobalSearchPresenter::extensionFilter.name, extensionFilter)
        super.onSave(state)
    }

    /**
     * Returns a list of enabled sources ordered by language and name, with pinned catalogues
     * prioritized.
     *
     * @return list containing enabled sources.
     */
    protected open fun getEnabledSources(): List<CatalogueSource> {
        val languages = preferences.enabledLanguages().get()
        val hiddenCatalogues = preferences.hiddenCatalogues().get()
        val pinnedCatalogues = preferences.pinnedCatalogues().get()

        val list =
            sourceManager.getVisibleCatalogueSources()
                .filter { it.lang in languages }
                .filterNot { it.id.toString() in hiddenCatalogues }
                .sortedBy { "(${it.lang}) ${it.name}" }

        return if (preferences.searchPinnedSourcesOnly()) {
            list.filter { it.id.toString() in pinnedCatalogues }
        } else {
            list.sortedBy { it.id.toString() !in pinnedCatalogues }
        }
    }

    private fun getSourcesToQuery(): List<CatalogueSource> {
        if (sourcesToUse != null) return sourcesToUse
        val filter = extensionFilter
        val enabledSources = getEnabledSources()
        var filteredSources: List<CatalogueSource>? = null

        if (!filter.isNullOrEmpty()) {
            filteredSources =
                extensionManager.installedExtensions
                    .filter { it.pkgName == filter }
                    .flatMap { it.sources }
                    .filter { it in enabledSources }
                    .filterIsInstance<CatalogueSource>()
        }

        if (filteredSources != null && filteredSources.isNotEmpty()) {
            return filteredSources
        }

        val onlyPinnedSources = preferences.searchPinnedSourcesOnly()
        val pinnedCatalogues = preferences.pinnedCatalogues().get()

        return enabledSources
            .filter { if (onlyPinnedSources) it.id.toString() in pinnedCatalogues else true }
    }

    /**
     * Creates a catalogue search item
     */
    protected open fun createCatalogueSearchItem(
        source: CatalogueSource,
        results: List<GlobalSearchCardItem>?
    ): GlobalSearchItem {
        return GlobalSearchItem(source, results)
    }

    /**
     * Initiates a search for manga per catalogue.
     *
     * @param query query on which to search.
     */
    fun search(query: String) {
        // Return if there's nothing to do
        if (this.query == query) return

        // Update query
        this.query = query

        // Create image fetch subscription
        initializeFetchImageSubscription()

        // Create items with the initial state
        val initialItems = sources.map { createCatalogueSearchItem(it, null) }
        var items = initialItems

        val pinnedSourceIds = preferences.pinnedCatalogues().get()

        fetchSourcesJob?.cancel()
        fetchSourcesJob =
            channelFlow {
                // Search every source, at most 5 at a time, emitting each source's results as
                // soon as they arrive. This is what flatMap(..., 5) provided.
                val semaphore = Semaphore(5)
                sources.forEach { source ->
                    launch {
                        semaphore.withPermit {
                            val page =
                                try {
                                    source.getSearchManga(1, query, FilterList())
                                } catch (e: Throwable) {
                                    // Ignore timeouts or other exceptions
                                    MangasPage(emptyList(), false)
                                }
                            // Get at most 10 manga from search result, converted to local manga.
                            val localManga = page.mangas.take(10).map { networkToLocalManga(it, source.id) }
                            // Load manga covers.
                            fetchImage(localManga, source)
                            send(createCatalogueSearchItem(source, localManga.map { GlobalSearchCardItem(it) }))
                        }
                    }
                }
            }
                .flowOn(Dispatchers.IO)
                // Update matching source with the obtained results
                .map { result ->
                    items
                        .map { item -> if (item.source == result.source) result else item }
                        .sortedWith(
                            compareBy(
                                // Bubble up sources that actually have results
                                { it.results.isNullOrEmpty() },
                                // Same as initial sort, i.e. pinned first then alphabetically
                                { it.source.id.toString() !in pinnedSourceIds },
                                { "${it.source.name} (${it.source.lang})" }
                            )
                        )
                }
                // Update current state
                .onEach { items = it }
                // Deliver initial state. onStart sits downstream of onEach, so the initial list
                // is shown without being written back into `items`, matching startWith's position.
                .onStart { emit(initialItems) }
                .collectLatestCache(
                    { view, manga ->
                        view.setItems(manga)
                    },
                    { _, error ->
                        Timber.e(error)
                    }
                )
    }

    /**
     * Initialize a list of manga.
     *
     * @param manga the list of manga to initialize.
     */
    private fun fetchImage(
        manga: List<Manga>,
        source: Source
    ) {
        fetchImageFlow.tryEmit(Pair(manga, source))
    }

    /**
     * Subscribes to the initializer of manga details and updates the view if needed.
     */
    private fun initializeFetchImageSubscription() {
        fetchImageJob?.cancel()
        fetchImageJob =
            // UNDISPATCHED so collect() subscribes before this returns. fetchImage() tryEmits into
            // a replay-less SharedFlow, which silently discards a value that has no subscriber
            // yet; a search answered from cache could otherwise beat the collector and leave
            // covers unloaded.
            presenterScope.launch(start = CoroutineStart.UNDISPATCHED) {
                fetchImageFlow.collect { (mangaList, source) ->
                    // One coroutine per batch keeps batches concurrent (the old flatMap) while
                    // the manga inside a batch stay sequential (the old concatMap).
                    launch(Dispatchers.IO) {
                        try {
                            mangaList
                                .filter { it.thumbnail_url == null && !it.initialized }
                                .forEach { manga ->
                                    val initialized = getMangaDetails(manga, source)
                                    withUIContext {
                                        @Suppress("DEPRECATION")
                                        view?.onMangaInitialized(source as CatalogueSource, initialized)
                                    }
                                }
                        } catch (error: Throwable) {
                            Timber.e(error)
                        }
                    }
                }
            }
    }

    /**
     * Returns an observable of manga that initializes the given manga.
     *
     * @param manga the manga to initialize.
     * @return an observable of the manga to initialize
     */
    private suspend fun getMangaDetails(
        manga: Manga,
        source: Source
    ): Manga {
        return try {
            val networkManga =
                source.getMangaUpdate(
                    manga,
                    db.getChapters(manga),
                    fetchDetails = true,
                    fetchChapters = false
                ).manga
            manga.copyFrom(networkManga)
            manga.initialized = true
            db.insertManga(manga)
            manga
        } catch (e: Throwable) {
            // Matches the previous onErrorResumeNext: fall back to the uninitialized manga.
            manga
        }
    }

    /**
     * Returns a manga from the database for the given manga from network. It creates a new entry
     * if the manga is not yet in the database.
     *
     * @param sManga the manga from the source.
     * @return a manga from the database.
     */
    protected open fun networkToLocalManga(
        sManga: SManga,
        sourceId: Long
    ): Manga {
        var localManga = db.getManga(sManga.url, sourceId)
        if (localManga == null) {
            val newManga = Manga.create(sManga.url, sManga.title, sourceId)
            newManga.copyFrom(sManga)
            // insertManga assigns the generated id back onto newManga.
            db.insertManga(newManga)
            localManga = newManga
        }
        return localManga
    }
}
