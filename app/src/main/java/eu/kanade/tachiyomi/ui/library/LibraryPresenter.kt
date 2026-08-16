package eu.kanade.tachiyomi.ui.library

import android.content.Context
import android.net.Uri
import android.os.Bundle
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.ui.migration.MigrationFlags
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.lang.combineLatest
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.lang.removeArticles
import eu.kanade.tachiyomi.util.lang.asFlow
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.updateCoverLastModified
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.TriStateGroup.Companion.STATE_EXCLUDE
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.TriStateGroup.Companion.STATE_IGNORE
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.TriStateGroup.Companion.STATE_INCLUDE
import exh.EH_SOURCE_ID
import exh.EXH_SOURCE_ID
import exh.favorites.FavoritesSyncHelper
import exh.util.isLewd
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator

/**
 * Class containing library information.
 */
private data class Library(val categories: List<Category>, val mangaMap: LibraryMap)

/**
 * Typealias for the library manga, using the category as keys, and list of manga as values.
 */
private typealias LibraryMap = Map<Int, List<LibraryItem>>

/**
 * Presenter of [LibraryController].
 */
class LibraryPresenter(
    private val db: DatabaseHelper = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get()
) : BasePresenter<LibraryController>() {
    private val context = preferences.context

    /**
     * Categories of the library.
     */
    var categories: List<Category> = emptyList()
        private set

    /**
     * Relay used to apply the UI filters to the last emission of the library.
     */
    // A counter rather than Unit: StateFlow conflates equal values, so re-emitting Unit
    // would never notify collectors the way BehaviorRelay.call(Unit) did.
    private val filterTriggerFlow = MutableStateFlow(0)

    /**
     * Relay used to apply the UI update to the last emission of the library.
     */
    private val badgeTriggerFlow = MutableStateFlow(0)

    /**
     * Relay used to apply the selected sorting method to the last emission of the library.
     */
    private val sortTriggerFlow = MutableStateFlow(0)

    /**
     * Library subscription.
     */
    private var libraryJob: Job? = null

    // --> EXH
    val favoritesSync = FavoritesSyncHelper(context)
    // <-- EXH

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        subscribeLibrary()
    }

    /**
     * Subscribes to library if needed.
     */
    fun subscribeLibrary() {
        if (libraryJob?.isActive != true) {
            libraryJob =
                getLibraryFlow()
                    .combine(badgeTriggerFlow) { lib, _ ->
                        lib.apply { setBadges(mangaMap) }
                    }
                    .combine(filterTriggerFlow) { lib, _ ->
                        lib.copy(mangaMap = applyFilters(lib.mangaMap))
                    }
                    .combine(sortTriggerFlow) { lib, _ ->
                        lib.copy(mangaMap = applySort(lib.mangaMap))
                    }
                    // Badging, filtering and sorting all walk the whole library, so they stay
                    // off the main thread as the observeOn(Schedulers.io()) hops did.
                    .flowOn(Dispatchers.IO)
                    .collectLatestCache(onNext = { view, (categories, mangaMap) ->
                        view.onNextLibraryUpdate(categories, mangaMap)
                    })
        }
    }

    /**
     * Applies library filters to the given map of manga.
     *
     * @param map the map to filter.
     */
    private fun applyFilters(map: LibraryMap): LibraryMap {
        val filterDownloaded = preferences.filterDownloaded().get()
        val filterDownloadedOnly = preferences.downloadedOnly().get()
        val filterUnread = preferences.filterUnread().get()
        val filterCompleted = preferences.filterCompleted().get()
        val filterTracked = preferences.filterTracked().get()
        val filterLewd = preferences.filterLewd().get()

        val filterFn: (LibraryItem) -> Boolean = f@{ item ->
            // Filter when there isn't unread chapters.
            if (filterUnread == STATE_INCLUDE && item.manga.unread == 0) {
                return@f false
            }
            if (filterUnread == STATE_EXCLUDE && item.manga.unread > 0) {
                return@f false
            }
            if (filterCompleted == STATE_INCLUDE && item.manga.status != SManga.COMPLETED) {
                return@f false
            }
            if (filterCompleted == STATE_EXCLUDE && item.manga.status == SManga.COMPLETED) {
                return@f false
            }
            if (filterTracked != STATE_IGNORE) {
                val tracks = db.getTracks(item.manga)
                if (filterTracked == STATE_INCLUDE && tracks.isEmpty()) {
                    return@f false
                } else if (filterTracked == STATE_EXCLUDE && tracks.isNotEmpty()) {
                    return@f false
                }
            }
            if (filterLewd != STATE_IGNORE) {
                val isLewd = item.manga.isLewd()
                if (filterLewd == STATE_INCLUDE && !isLewd) {
                    return@f false
                } else if (filterLewd == STATE_EXCLUDE && isLewd) {
                    return@f false
                }
            }
            // Filter when there are no downloads.
            if (filterDownloaded != STATE_IGNORE || filterDownloadedOnly) {
                val isDownloaded =
                    when {
                        item.manga.isLocal() -> true
                        item.downloadCount != -1 -> item.downloadCount > 0
                        else -> downloadManager.getDownloadCount(item.manga) > 0
                    }
                return@f if (filterDownloaded == STATE_INCLUDE) isDownloaded else !isDownloaded
            }
            true
        }

        return map.mapValues { entry -> entry.value.filter(filterFn) }
    }

    /**
     * Sets downloaded chapter count to each manga.
     *
     * @param map the map of manga.
     */
    private fun setBadges(map: LibraryMap) {
        val showDownloadBadges = preferences.downloadBadge().get()
        val showUnreadBadges = preferences.unreadBadge().get()
        val startReadingButton = preferences.startReadingButton().get()

        for ((_, itemList) in map) {
            for (item in itemList) {
                item.downloadCount =
                    if (showDownloadBadges) {
                        downloadManager.getDownloadCount(item.manga)
                    } else {
                        // Unset download count if not enabled
                        -1
                    }

                item.unreadCount =
                    if (showUnreadBadges) {
                        item.manga.unread
                    } else {
                        // Unset unread count if not enabled
                        -1
                    }

                // SY -->
                item.startReadingButton = startReadingButton
                // SY <--
            }
        }
    }

    /**
     * Applies library sorting to the given map of manga.
     *
     * @param map the map to sort.
     */
    private fun applySort(map: LibraryMap): LibraryMap {
        val sortingMode = preferences.librarySortingMode().get()

        val lastReadManga by lazy {
            var counter = 0
            db.getLastReadManga().executeAsBlocking().associate { it.id!! to counter++ }
        }
        val totalChapterManga by lazy {
            var counter = 0
            db.getTotalChapterManga().executeAsBlocking().associate { it.id!! to counter++ }
        }
        val latestChapterManga by lazy {
            var counter = 0
            db.getLatestChapterManga().executeAsBlocking().associate { it.id!! to counter++ }
        }

        val sortFn: (LibraryItem, LibraryItem) -> Int = { i1, i2 ->
            when (sortingMode) {
                LibrarySort.ALPHA -> i1.manga.title.compareTo(i2.manga.title, true)
                LibrarySort.LAST_READ -> {
                    // Get index of manga, set equal to list if size unknown.
                    val manga1LastRead = lastReadManga[i1.manga.id!!] ?: lastReadManga.size
                    val manga2LastRead = lastReadManga[i2.manga.id!!] ?: lastReadManga.size
                    manga1LastRead.compareTo(manga2LastRead)
                }
                LibrarySort.LAST_CHECKED -> i2.manga.last_update.compareTo(i1.manga.last_update)
                LibrarySort.UNREAD -> i1.manga.unread.compareTo(i2.manga.unread)
                LibrarySort.TOTAL -> {
                    val manga1TotalChapter = totalChapterManga[i1.manga.id!!] ?: 0
                    val mange2TotalChapter = totalChapterManga[i2.manga.id!!] ?: 0
                    manga1TotalChapter.compareTo(mange2TotalChapter)
                }
                LibrarySort.LATEST_CHAPTER -> {
                    val manga1latestChapter =
                        latestChapterManga[i1.manga.id!!]
                            ?: latestChapterManga.size
                    val manga2latestChapter =
                        latestChapterManga[i2.manga.id!!]
                            ?: latestChapterManga.size
                    manga1latestChapter.compareTo(manga2latestChapter)
                }
                LibrarySort.DATE_ADDED -> i2.manga.date_added.compareTo(i1.manga.date_added)
                LibrarySort.SOURCE -> {
                    val source1Name = sourceManager.getOrStub(i1.manga.source).name
                    val source2Name = sourceManager.getOrStub(i2.manga.source).name
                    source1Name.compareTo(source2Name)
                }
                LibrarySort.DRAG_AND_DROP -> {
                    0
                }
                else -> throw Exception("Unknown sorting mode")
            }
        }

        val comparator =
            if (preferences.librarySortingAscending().get()) {
                Comparator(sortFn)
            } else {
                Collections.reverseOrder(sortFn)
            }

        return map.mapValues { entry -> entry.value.sortedWith(comparator) }
    }

    private fun sortAlphabetical(
        i1: LibraryItem,
        i2: LibraryItem
    ): Int {
        // return if (preferences.removeArticles().getOrDefault())
        return i1.manga.title.removeArticles().compareTo(i2.manga.title.removeArticles(), true)
        // else i1.manga.title.compareTo(i2.manga.title, true)
    }

    /**
     * Get the categories and all its manga from the database.
     *
     * @return an observable of the categories and its manga.
     */
    private fun getLibraryFlow(): Flow<Library> {
        return combine(getCategoriesFlow(), getLibraryMangasFlow()) { dbCategories, libraryManga ->
            val categories =
                if (libraryManga.containsKey(0)) {
                    arrayListOf(Category.createDefault()) + dbCategories
                } else {
                    dbCategories
                }

            this.categories = categories
            Library(categories, libraryManga)
        }
    }

    /**
     * Get the categories from the database.
     *
     * @return an observable of the categories.
     */
    private fun getCategoriesFlow(): Flow<List<Category>> {
        return db.getCategoriesAsFlow()
    }

    /**
     * Get the manga grouped by categories.
     *
     * @return an observable containing a map with the category id as key and a list of manga as the
     * value.
     */
    private fun getLibraryMangasFlow(): Flow<LibraryMap> {
        val libraryDisplayMode = preferences.libraryDisplayMode()
        return db.getLibraryMangas().asRxObservable()
            .asFlow()
            .map { list ->
                list.map { LibraryItem(it, libraryDisplayMode) }.groupBy { it.manga.category }
            }
    }

    /**
     * Requests the library to be filtered.
     */
    fun requestFilterUpdate() {
        filterTriggerFlow.value++
    }

    /**
     * Requests the library to have download badges added.
     */
    fun requestBadgesUpdate() {
        badgeTriggerFlow.value++
    }

    /**
     * Requests the library to be sorted.
     */
    fun requestSortUpdate() {
        sortTriggerFlow.value++
    }

    /**
     * Called when a manga is opened.
     */
    fun onOpenManga() {
        // Avoid further db updates for the library when it's not needed
        libraryJob?.cancel()
    }

    /**
     * Returns the common categories for the given list of manga.
     *
     * @param mangas the list of manga.
     */
    fun getCommonCategories(mangas: List<Manga>): Collection<Category> {
        if (mangas.isEmpty()) return emptyList()
        return mangas.toSet()
            .map { db.getCategoriesForManga(it) }
            .reduce { set1: Iterable<Category>, set2 -> set1.intersect(set2).toMutableList() }
    }

    /**
     * Queues all unread chapters from the given list of manga.
     *
     * @param mangas the list of manga.
     */
    fun downloadUnreadChapters(mangas: List<Manga>) {
        mangas.forEach { manga ->
            launchIO {
                val chapters =
                    db.getChapters(manga)
                        .filter { !it.read }

                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    /**
     * Marks mangas' chapters read status.
     *
     * @param mangas the list of manga.
     */
    fun markReadStatus(
        mangas: List<Manga>,
        read: Boolean
    ) {
        mangas.forEach { manga ->
            launchIO {
                val chapters = db.getChapters(manga)
                chapters.forEach {
                    it.read = read
                    if (!read) {
                        it.last_page_read = 0
                    }
                }
                db.updateChaptersProgress(chapters)

                if (preferences.removeAfterMarkedAsRead()) {
                    deleteChapters(manga, chapters)
                }
            }
        }
    }

    private fun deleteChapters(
        manga: Manga,
        chapters: List<Chapter>
    ) {
        sourceManager.get(manga.source)?.let { source ->
            downloadManager.deleteChapters(chapters, manga, source)
        }
    }

    /**
     * Remove the selected manga from the library.
     *
     * @param mangas the list of manga to delete.
     * @param deleteChapters whether to also delete downloaded chapters.
     */
    fun removeMangaFromLibrary(
        mangas: List<Manga>,
        deleteChapters: Boolean
    ) {
        launchIO {
            val mangaToDelete = mangas.distinctBy { it.id }

            mangaToDelete.forEach {
                it.favorite = false
                it.removeCovers(coverCache)
            }
            db.insertMangas(mangaToDelete).executeAsBlocking()

            if (deleteChapters) {
                mangaToDelete.forEach { manga ->
                    val source = sourceManager.get(manga.source) as? HttpSource
                    if (source != null) {
                        downloadManager.deleteManga(manga, source)
                    }
                }
            }
        }
    }

    /**
     * Move the given list of manga to categories.
     *
     * @param categories the selected categories.
     * @param mangas the list of manga to move.
     */
    fun moveMangasToCategories(
        categories: List<Category>,
        mangas: List<Manga>
    ) {
        val mc = ArrayList<MangaCategory>()

        for (manga in mangas) {
            for (cat in categories) {
                mc.add(MangaCategory.create(manga, cat))
            }
        }

        db.setMangaCategories(mc, mangas)
    }

    fun migrateManga(
        prevManga: Manga,
        manga: Manga,
        replace: Boolean
    ) {
        val source = sourceManager.get(manga.source) ?: return

        // state = state.copy(isReplacingManga = true)

        presenterScope.launch {
            val chapters =
                try {
                    withIOContext {
                        source.getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true).chapters
                    }
                } catch (e: Throwable) {
                    // Matches the previous onErrorReturn { emptyList() }.
                    emptyList()
                }
            try {
                migrateMangaInternal(source, chapters, prevManga, manga, replace)
            } catch (e: Throwable) {
                // The second onErrorReturn swallowed failures from the migration itself.
            }
        }
    }

    private fun migrateMangaInternal(
        source: Source,
        sourceChapters: List<SChapter>,
        prevManga: Manga,
        manga: Manga,
        replace: Boolean
    ) {
        val flags = preferences.migrateFlags().get()
        val migrateChapters = MigrationFlags.hasChapters(flags)
        val migrateCategories = MigrationFlags.hasCategories(flags)
        val migrateTracks = MigrationFlags.hasTracks(flags)

        db.inTransaction {
            // Update chapters read
            if (migrateChapters) {
                try {
                    syncChaptersWithSource(db, sourceChapters, manga, source)
                } catch (e: Exception) {
                    // Worst case, chapters won't be synced
                }

                val prevMangaChapters = db.getChapters(prevManga)
                val maxChapterRead =
                    prevMangaChapters.filter { it.read }.maxByOrNull { it.chapter_number }?.chapter_number
                if (maxChapterRead != null) {
                    val dbChapters = db.getChapters(manga)
                    for (chapter in dbChapters) {
                        if (chapter.isRecognizedNumber && chapter.chapter_number <= maxChapterRead) {
                            chapter.read = true
                        }
                    }
                    db.insertChapters(dbChapters)
                }
            }
            // Update categories
            if (migrateCategories) {
                val categories = db.getCategoriesForManga(prevManga)
                val mangaCategories = categories.map { MangaCategory.create(manga, it) }
                db.setMangaCategories(mangaCategories, listOf(manga))
            }
            // Update track
            if (migrateTracks) {
                val tracks = db.getTracks(prevManga)
                for (track in tracks) {
                    track.id = null
                    track.manga_id = manga.id!!
                }
                db.insertTracks(tracks)
            }
            // Update favorite status
            if (replace) {
                prevManga.favorite = false
                db.updateMangaFavorite(prevManga).executeAsBlocking()
            }
            manga.favorite = true
            db.updateMangaFavorite(manga).executeAsBlocking()

            // SearchPresenter#networkToLocalManga may have updated the manga title, so ensure db gets updated title
            db.updateMangaTitle(manga).executeAsBlocking()
        }
    }

    /**
     * Update cover with local file.
     *
     * @param manga the manga edited.
     * @param context Context.
     * @param data uri of the cover resource.
     */
    fun editCover(
        manga: Manga,
        context: Context,
        data: Uri
    ) {
        presenterScope.launch {
            try {
                withIOContext {
                    context.contentResolver.openInputStream(data)?.use {
                        if (manga.isLocal()) {
                            LocalSource.updateCover(context, manga, it)
                            manga.updateCoverLastModified(db)
                        } else if (manga.favorite) {
                            coverCache.setCustomCoverToCache(manga, it)
                            manga.updateCoverLastModified(db)
                        }
                    }
                }
                view?.onSetCoverSuccess()
            } catch (e: Throwable) {
                view?.onSetCoverError(e)
            }
        }
    }

    fun deleteCustomCover(manga: Manga) {
        presenterScope.launch {
            try {
                withIOContext {
                    coverCache.deleteCustomCover(manga)
                    manga.updateCoverLastModified(db)
                }
                view?.onSetCoverSuccess()
            } catch (e: Throwable) {
                view?.onSetCoverError(e)
            }
        }
    }
    // SY -->

    /** Returns first unread chapter of a manga */
    fun getFirstUnread(manga: Manga): Chapter? {
        val chapters = db.getChapters(manga)
        return if (manga.source == EH_SOURCE_ID || manga.source == EXH_SOURCE_ID) {
            val chapter = chapters.sortedBy { it.source_order }.getOrNull(0)
            if (chapter?.read == false) chapter else null
        } else {
            chapters.sortedByDescending { it.source_order }.find { !it.read }
        }
    }
    // SY <--
}
