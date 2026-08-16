package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.os.Bundle
import android.os.Environment
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.job.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.job.DelayedTrackingUpdateJob
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.lang.takeBytes
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.lang.asFlow
import eu.kanade.tachiyomi.util.lang.runAsObservable
import eu.kanade.tachiyomi.util.system.withIOContext
import eu.kanade.tachiyomi.util.system.withUIContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.updateCoverLastModified
import exh.util.defaultReaderType
import rx.Completable
import rx.Observable
import rx.schedulers.Schedulers
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Presenter used by the activity to perform background operations.
 */
class ReaderPresenter(
    private val db: DatabaseHelper = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get(),
    private val delayedTrackingStore: DelayedTrackingStore = Injekt.get()
) : BasePresenter<ReaderActivity>() {
    /**
     * The manga loaded in the reader. It can be null when instantiated for a short time.
     */
    var manga: Manga? = null
        private set

    /**
     * The chapter id of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterId = -1L

    /**
     * The chapter loader for the loaded manga. It'll be null until [manga] is set.
     */
    private var loader: ChapterLoader? = null

    /**
     * Subscription to prevent setting chapters as active from multiple threads.
     */
    private var activeChapterJob: Job? = null

    /**
     * Relay for currently active viewer chapters.
     */
    // [EXH] private
    val viewerChaptersFlow = MutableStateFlow<ViewerChapters?>(null)

    /**
     * Relay used when loading prev/next chapter needed to lock the UI (with a dialog).
     */
    private val isLoadingAdjacentChapterFlow = MutableStateFlow(false)

    /**
     * Chapter list for the active manga. It's retrieved lazily and should be accessed for the first
     * time in a background thread to avoid blocking the UI.
     */
    private val chapterList by lazy {
        val manga = manga!!
        val dbChapters = db.getChapters(manga)

        val selectedChapter =
            dbChapters.find { it.id == chapterId }
                ?: error("Requested chapter of id $chapterId not found in chapter list")

        val chaptersForReader =
            if (preferences.skipRead() || preferences.skipFiltered()) {
                val list =
                    dbChapters
                        .filter {
                            if (preferences.skipRead() && it.read) {
                                return@filter false
                            } else if (preferences.skipFiltered()) {
                                if (
                                    (manga.readFilter == Manga.SHOW_READ && !it.read) ||
                                    (manga.readFilter == Manga.SHOW_UNREAD && it.read) ||
                                    (
                                        manga.downloadedFilter == Manga.SHOW_DOWNLOADED &&
                                            !downloadManager.isChapterDownloaded(it, manga)
                                        ) ||
                                    (manga.bookmarkedFilter == Manga.SHOW_BOOKMARKED && !it.bookmark)
                                ) {
                                    return@filter false
                                }
                            }

                            true
                        }
                        .toMutableList()

                val find = list.find { it.id == chapterId }
                if (find == null) {
                    list.add(selectedChapter)
                }
                list
            } else {
                dbChapters
            }

        when (manga.sorting) {
            Manga.SORTING_SOURCE -> ChapterLoadBySource().get(chaptersForReader)
            Manga.SORTING_NUMBER -> ChapterLoadByNumber().get(chaptersForReader, selectedChapter)
            Manga.SORTING_UPLOAD_DATE -> ChapterLoadByUploadDate().get(chaptersForReader)
            else -> error("Unknown sorting method")
        }.map(::ReaderChapter)
    }

    /**
     * Called when the presenter is created. It retrieves the saved active chapter if the process
     * was restored.
     */
    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        if (savedState != null) {
            chapterId = savedState.getLong(::chapterId.name, -1)
        }
    }

    /**
     * Called when the presenter is destroyed. It saves the current progress and cleans up
     * references on the currently active chapters.
     */
    override fun onDestroy() {
        super.onDestroy()
        val currentChapters = viewerChaptersFlow.value
        if (currentChapters != null) {
            currentChapters.unref()
            saveChapterProgress(currentChapters.currChapter)
            saveChapterHistory(currentChapters.currChapter)
        }
    }

    /**
     * Called when the presenter instance is being saved. It saves the currently active chapter
     * id and the last page read.
     */
    override fun onSave(state: Bundle) {
        super.onSave(state)
        val currentChapter = getCurrentChapter()
        if (currentChapter != null) {
            currentChapter.requestedPage = currentChapter.chapter.last_page_read
            state.putLong(::chapterId.name, currentChapter.chapter.id!!)
        }
    }

    /**
     * Called when the user pressed the back button and is going to leave the reader. Used to
     * trigger deletion of the downloaded chapters.
     */
    fun onBackPressed() {
        deletePendingChapters()
    }

    /**
     * Called when the activity is saved and not changing configurations. It updates the database
     * to persist the current progress of the active chapter.
     */
    fun onSaveInstanceStateNonConfigurationChange() {
        val currentChapter = getCurrentChapter() ?: return
        saveChapterProgress(currentChapter)
    }

    /**
     * Whether this presenter is initialized yet.
     */
    fun needsInit(): Boolean {
        return manga == null
    }

    /**
     * Initializes this presenter with the given [mangaId] and [initialChapterId]. This method will
     * fetch the manga from the database and initialize the initial chapter.
     */
    fun init(
        mangaId: Long,
        initialChapterId: Long
    ) {
        if (!needsInit()) return

        presenterScope.launch {
            try {
                val manga = db.getManga(mangaId).asRxObservable().asFlow().first()
                init(manga, initialChapterId)
            } catch (e: Throwable) {
                view?.setInitialChapterError(e)
            }
        }
    }

    /**
     * Initializes this presenter with the given [manga] and [initialChapterId]. This method will
     * set the chapter loader, view subscriptions and trigger an initial load.
     */
    private fun init(
        manga: Manga,
        initialChapterId: Long
    ) {
        if (!needsInit()) return

        this.manga = manga
        if (chapterId == -1L) chapterId = initialChapterId

        val context = Injekt.get<Application>()
        val source = sourceManager.getOrStub(manga.source)
        loader = ChapterLoader(context, downloadManager, manga, source)

        deliverToView { it.setManga(manga) }
        viewerChaptersFlow.filterNotNull().collectLatestCache(ReaderActivity::setChapters)
        isLoadingAdjacentChapterFlow.collectLatestCache(ReaderActivity::setProgressDialog)

        activeChapterJob?.cancel()
        activeChapterJob =
            presenterScope.launch {
                try {
                    // chapterList is retrieved lazily and would block main.
                    val chapter = withIOContext { chapterList.first { chapterId == it.chapter.id } }
                    loadChapter(loader!!, chapter)
                } catch (e: Throwable) {
                    view?.setInitialChapterError(e)
                }
            }
    }

    /**
     * Returns an observable that loads the given [chapter] with this [loader]. This observable
     * handles main thread synchronization and updating the currently active chapters on
     * [viewerChaptersFlow], however callers must ensure there won't be more than one
     * subscription active by unsubscribing any existing [activeChapterSubscription] before.
     * Callers must also handle the onError event.
     */
    private suspend fun loadChapter(
        loader: ChapterLoader,
        chapter: ReaderChapter
    ): ViewerChapters {
        loader.loadChapter(chapter)

        val chapterPos = chapterList.indexOf(chapter)
        val newChapters =
            ViewerChapters(
                chapter,
                chapterList.getOrNull(chapterPos - 1),
                chapterList.getOrNull(chapterPos + 1)
            )

        withUIContext {
            val oldChapters = viewerChaptersFlow.value

            // Add new references first to avoid unnecessary recycling
            newChapters.ref()
            oldChapters?.unref()

            viewerChaptersFlow.value = newChapters
        }
        return newChapters
    }

    /**
     * Called when the user changed to the given [chapter] when changing pages from the viewer.
     * It's used only to set this chapter as active.
     */
    private fun loadNewChapter(chapter: ReaderChapter) {
        val loader = loader ?: return

        Timber.d("Loading ${chapter.chapter.url}")

        activeChapterJob?.cancel()
        activeChapterJob =
            presenterScope.launch {
                try {
                    loadChapter(loader, chapter)
                } catch (e: Throwable) {
                    // Previously onErrorComplete: failures surface through the chapter state.
                    Timber.e(e)
                }
            }
    }

    /**
     * Called when the user is going to load the prev/next chapter through the menu button. It
     * sets the [isLoadingAdjacentChapterFlow] that the view uses to prevent any further
     * interaction until the chapter is loaded.
     */
    private fun loadAdjacent(chapter: ReaderChapter) {
        val loader = loader ?: return

        Timber.d("Loading adjacent ${chapter.chapter.url}")

        activeChapterJob?.cancel()
        activeChapterJob =
            presenterScope.launch {
                isLoadingAdjacentChapterFlow.value = true
                try {
                    loadChapter(loader, chapter)
                    view?.moveToPageIndex(0)
                } catch (e: Throwable) {
                    // Ignore the error, viewers handle that state
                } finally {
                    // Previously doOnUnsubscribe: also runs on error and cancellation.
                    isLoadingAdjacentChapterFlow.value = false
                }
            }
    }

    /**
     * Called when the viewers decide it's a good time to preload a [chapter] and improve the UX so
     * that the user doesn't have to wait too long to continue reading.
     */
    private fun preload(chapter: ReaderChapter) {
        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error) {
            return
        }

        Timber.d("Preloading ${chapter.chapter.url}")

        val loader = loader ?: return

        presenterScope.launch {
            try {
                loader.loadChapter(chapter)
                // Re-emit the current chapters whenever a chapter is preloaded, so the viewer
                // picks up the newly loaded neighbour.
                viewerChaptersFlow.value?.let { viewerChaptersFlow.value = null; viewerChaptersFlow.value = it }
            } catch (e: Throwable) {
                // Previously onErrorComplete.
                Timber.e(e)
            }
        }
    }

    /**
     * Called every time a page changes on the reader. Used to mark the flag of chapters being
     * read, update tracking services, enqueue downloaded chapter deletion, and updating the active chapter if this
     * [page]'s chapter is different from the currently active.
     */
    fun onPageSelected(page: ReaderPage) {
        val currentChapters = viewerChaptersFlow.value ?: return

        val selectedChapter = page.chapter

        // Save last page read and mark as read if needed
        selectedChapter.chapter.last_page_read = page.index
        if (selectedChapter.pages?.lastIndex == page.index) {
            Timber.d("Finished reading ${selectedChapter.chapter.url}")
            selectedChapter.chapter.read = true
            updateTrackChapterRead(selectedChapter)
            enqueueDeleteReadChapters(selectedChapter)
        }

        if (selectedChapter != currentChapters.currChapter) {
            Timber.d("Setting ${selectedChapter.chapter.url} as active")
            onChapterChanged(currentChapters.currChapter)
            loadNewChapter(selectedChapter)
        }
    }

    /**
     * Called when a chapter changed from [fromChapter] to [toChapter]. It updates [fromChapter]
     * on the database.
     */
    private fun onChapterChanged(fromChapter: ReaderChapter) {
        saveChapterProgress(fromChapter)
        saveChapterHistory(fromChapter)
    }

    /**
     * Saves this [chapter] progress (last read page and whether it's read).
     */
    private fun saveChapterProgress(chapter: ReaderChapter) {
        launchIO {
            try {
                db.updateChapterProgress(chapter.chapter)
            } catch (e: Throwable) {
                // Previously onErrorComplete.
                Timber.e(e)
            }
        }
    }

    /**
     * Saves this [chapter] last read history.
     */
    private fun saveChapterHistory(chapter: ReaderChapter) {
        val history = History.create(chapter.chapter).apply { last_read = Date().time }
        launchIO {
            try {
                db.updateHistoryLastRead(history)
            } catch (e: Throwable) {
                // Previously onErrorComplete.
                Timber.e(e)
            }
        }
    }

    /**
     * Called from the activity to preload the given [chapter].
     */
    fun preloadChapter(chapter: ReaderChapter) {
        preload(chapter)
    }

    /**
     * Called from the activity to load and set the next chapter as active.
     */
    fun loadNextChapter() {
        val nextChapter = viewerChaptersFlow.value?.nextChapter ?: return
        loadAdjacent(nextChapter)
    }

    /**
     * Called from the activity to load and set the previous chapter as active.
     */
    fun loadPreviousChapter() {
        val prevChapter = viewerChaptersFlow.value?.prevChapter ?: return
        loadAdjacent(prevChapter)
    }

    /**
     * Returns the currently active chapter.
     */
    fun getCurrentChapter(): ReaderChapter? {
        return viewerChaptersFlow.value?.currChapter
    }

    /**
     * Bookmarks the currently active chapter.
     */
    fun bookmarkCurrentChapter(bookmarked: Boolean) {
        if (getCurrentChapter()?.chapter == null) {
            return
        }

        val chapter = getCurrentChapter()?.chapter!!
        chapter.bookmark = bookmarked
        db.updateChapterProgress(chapter)
    }

    /**
     * Returns the viewer position used by this manga or the default one.
     */
    fun getMangaViewer(): Int {
        val manga = manga ?: return preferences.defaultViewer()
        return if (manga.viewer == 0 && preferences.eh_useAutoWebtoon().get()) {
            manga.defaultReaderType() ?: if (manga.viewer == 0) preferences.defaultViewer() else manga.viewer
        } else if (manga.viewer == 0) {
            preferences.defaultViewer()
        } else {
            manga.viewer
        }
    }

    /**
     * Updates the viewer position for the open manga.
     */
    fun setMangaViewer(viewer: Int) {
        val manga = manga ?: return
        manga.viewer = viewer
        db.updateMangaViewer(manga).executeAsBlocking()

        presenterScope.launch {
            delay(250)
            deliverToView { view ->
                val currChapters = viewerChaptersFlow.value
                if (currChapters != null) {
                    // Save current page
                    val currChapter = currChapters.currChapter
                    currChapter.requestedPage = currChapter.chapter.last_page_read

                    // Emit manga and chapters to the new viewer
                    view.setManga(manga)
                    view.setChapters(currChapters)
                }
            }
        }
    }

    /**
     * Saves the image of this [page] in the given [directory] and returns the file location.
     */
    private fun saveImage(
        page: ReaderPage,
        directory: File,
        manga: Manga
    ): File {
        val stream = page.stream!!
        val type = ImageUtil.findImageType(stream) ?: throw Exception("Not an image")

        directory.mkdirs()

        val chapter = page.chapter.chapter

        // Build destination file.
        val filenameSuffix = " - ${page.number}.${type.extension}"
        val filename =
            DiskUtil.buildValidFilename(
                "${manga.title} - ${chapter.name}".takeBytes(MAX_FILE_NAME_BYTES - filenameSuffix.byteSize()),
                disallowNonAscii = preferences.disallowNonAsciiFilenames().get()
            ) + filenameSuffix

        val destFile = File(directory, filename)
        stream().use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return destFile
    }

    /**
     * Saves the image of this [page] on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage(page: ReaderPage) {
        if (page.status != Page.READY) return
        val manga = manga ?: return
        val context = Injekt.get<Application>()

        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        // Pictures directory.
        val destDir =
            File(
                Environment.getExternalStorageDirectory().absolutePath +
                    File.separator + Environment.DIRECTORY_PICTURES +
                    File.separator + context.getString(R.string.app_name)
            )

        // Copy file in background.
        presenterScope.launch {
            try {
                val file =
                    withIOContext {
                        saveImage(page, destDir, manga).also {
                            DiskUtil.scanMedia(context, it)
                            notifier.onComplete(it)
                        }
                    }
                view?.onSaveImageResult(SaveImageResult.Success(file))
            } catch (error: Throwable) {
                notifier.onError(error.message)
                view?.onSaveImageResult(SaveImageResult.Error(error))
            }
        }
    }

    /**
     * Shares the image of this [page] and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompresssed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(page: ReaderPage) {
        if (page.status != Page.READY) return
        val manga = manga ?: return
        val context = Injekt.get<Application>()

        val destDir = File(context.cacheDir, "shared_image")

        presenterScope.launch {
            try {
                val file =
                    withIOContext {
                        destDir.deleteRecursively() // Keep only the last shared file
                        saveImage(page, destDir, manga)
                    }
                view?.onShareImageResult(file)
            } catch (e: Throwable) {
                // Empty
            }
        }
    }

    /**
     * Sets the image of this [page] as cover and notifies the UI of the result.
     */
    fun setAsCover(page: ReaderPage) {
        if (page.status != Page.READY) return
        val manga = manga ?: return
        val stream = page.stream ?: return

        presenterScope.launch {
            try {
                val result =
                    withIOContext {
                        if (manga.isLocal()) {
                            val context = Injekt.get<Application>()
                            LocalSource.updateCover(context, manga, stream())
                            manga.updateCoverLastModified(db)
                            SetAsCoverResult.Success
                        } else {
                            if (manga.favorite) {
                                coverCache.setCustomCoverToCache(manga, stream())
                                manga.updateCoverLastModified(db)
                                SetAsCoverResult.Success
                            } else {
                                SetAsCoverResult.AddToLibraryFirst
                            }
                        }
                    }
                view?.onSetAsCoverResult(result)
            } catch (e: Throwable) {
                view?.onSetAsCoverResult(SetAsCoverResult.Error)
            }
        }
    }

    /**
     * Results of the set as cover feature.
     */
    enum class SetAsCoverResult {
        Success,
        AddToLibraryFirst,
        Error
    }

    /**
     * Results of the save image feature.
     */
    sealed class SaveImageResult {
        class Success(val file: File) : SaveImageResult()

        class Error(val error: Throwable) : SaveImageResult()
    }

    /**
     * Starts the service that updates the last chapter read in sync services. This operation
     * will run in a background thread and errors are ignored.
     */
    private fun updateTrackChapterRead(readerChapter: ReaderChapter) {
        if (!preferences.autoUpdateTrack()) return
        val manga = manga ?: return

        val chapterRead = readerChapter.chapter.chapter_number.toInt()

        val trackManager = Injekt.get<TrackManager>()
        val context = Injekt.get<Application>()

        // These should finish even if the presenter is destroyed and leaks for a while;
        // the view can still be garbage collected.
        launchIO {
            try {
                db.getTracks(manga).forEach { track ->
                    val service = trackManager.getService(track.sync_id)
                    if (service != null && service.isLogged && chapterRead > track.last_chapter_read) {
                        track.last_chapter_read = chapterRead

                        try {
                            if (context.isOnline()) {
                                Timber.d("Tracking ONLINE")
                                service.update(track)
                                db.insertTrack(track)
                            } else {
                                Timber.d("Tracking OFFLINE")
                                delayedTrackingStore.addItem(track)
                                DelayedTrackingUpdateJob.setupTask(context)
                            }
                        } catch (e: Throwable) {
                            // Previously onErrorComplete per track.
                            Timber.e(e)
                        }
                    }
                }
            } catch (e: Throwable) {
                Timber.e(e)
            }
        }
    }

    /**
     * Enqueues this [chapter] to be deleted when [deletePendingChapters] is called. The download
     * manager handles persisting it across process deaths.
     */
    private fun enqueueDeleteReadChapters(chapter: ReaderChapter) {
        if (!chapter.chapter.read || chapter.pageLoader !is DownloadPageLoader) return
        val manga = manga ?: return

        // Return if the setting is disabled
        val removeAfterReadSlots = preferences.removeAfterReadSlots()
        if (removeAfterReadSlots == -1) return

        launchIO {
            // Position of the read chapter
            val position = chapterList.indexOf(chapter)

            // Retrieve chapter to delete according to preference
            val chapterToDelete = chapterList.getOrNull(position - removeAfterReadSlots)
            if (chapterToDelete != null) {
                downloadManager.enqueueDeleteChapters(listOf(chapterToDelete.chapter), manga)
            }
        }
    }

    /**
     * Deletes all the pending chapters. This operation will run in a background thread and errors
     * are ignored.
     */
    private fun deletePendingChapters() {
        launchIO {
            downloadManager.deletePendingChapters()
        }
    }

    companion object {
        // Safe theoretical max filename size is 255 bytes and 1 char = 2-4 bytes (UTF-8)
        private const val MAX_FILE_NAME_BYTES = 250
    }
}
