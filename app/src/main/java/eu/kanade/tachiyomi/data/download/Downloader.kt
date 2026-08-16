package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.download.model.DownloadQueue
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.getAllImageUrlsFromPageList
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.saveTo
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.system.launchNow
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.withUIContext
import exh.isEhBasedSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Response
import timber.log.Timber
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * This class is the one in charge of downloading chapters.
 *
 * Its [queue] contains the list of chapters to download. In order to download them, the downloader
 * subscriptions must be running and the list of chapters must be sent to them by [downloadsFlow].
 *
 * The queue manipulation must be done in one thread (currently the main thread) to avoid unexpected
 * behavior, but it's safe to read it from multiple threads.
 *
 * @param context the application context.
 * @param provider the downloads directory provider.
 * @param cache the downloads cache, used to add the downloads to the cache after their completion.
 * @param sourceManager the source manager.
 */
class Downloader(
    private val context: Context,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val sourceManager: SourceManager
) {
    private val preferences: PreferencesHelper by injectLazy()

    private val chapterCache: ChapterCache by injectLazy()

    /**
     * Store for persisting downloads across restarts.
     */
    private val store = DownloadStore(context, sourceManager)

    /**
     * Queue where active downloads are kept.
     */
    val queue = DownloadQueue(store)

    /**
     * Notifier for the downloader state and progress.
     */
    private val notifier by lazy { DownloadNotifier(context) }

    /**
     * Downloader subscriptions.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var downloaderJob: Job? = null

    /**
     * Caps concurrent downloads at 5 sources, matching the previous flatMap(..., 5).
     */
    private val sourceSemaphore = Semaphore(5)

    /**
     * One mutex per source so a source's chapters download in order, as the per-source
     * concatMap did.
     */
    private val sourceMutexes = ConcurrentHashMap<Long, Mutex>()

    /**
     * Relay to send a list of downloads to the downloader.
     */
    private val downloadsFlow = MutableSharedFlow<List<Download>>(extraBufferCapacity = 64)

    /**
     * Relay to subscribe to the downloader status.
     */
    val runningFlow = MutableStateFlow(false)

    /**
     * Whether the downloader is running.
     */
    @Volatile
    var isRunning: Boolean = false
        private set

    init {
        launchNow {
            val chapters = async { store.restore() }
            queue.addAll(chapters.await())
        }
    }

    /**
     * Starts the downloader. It doesn't do anything if it's already running or there isn't anything
     * to download.
     *
     * @return true if the downloader is started, false otherwise.
     */
    fun start(): Boolean {
        if (isRunning || queue.isEmpty()) {
            return false
        }

        if (downloaderJob?.isActive != true) {
            initializeSubscriptions()
        }

        val pending = queue.filter { it.status != Download.DOWNLOADED }
        pending.forEach { if (it.status != Download.QUEUE) it.status = Download.QUEUE }

        notifier.paused = false

        // Dropping here would mean queued chapters silently never download, so make a full
        // buffer visible rather than losing the work quietly.
        if (!downloadsFlow.tryEmit(pending)) {
            Timber.e("Download queue buffer full; ${pending.size} downloads were not dispatched")
        }
        return pending.isNotEmpty()
    }

    /**
     * Stops the downloader.
     */
    fun stop(reason: String? = null) {
        destroySubscriptions()
        queue
            .filter { it.status == Download.DOWNLOADING }
            .forEach { it.status = Download.ERROR }

        if (reason != null) {
            notifier.onWarning(reason)
        } else {
            if (notifier.paused) {
                notifier.paused = false
                notifier.onPaused()
            } else {
                notifier.dismissProgress()
                notifier.onComplete()
            }
        }
    }

    /**
     * Pauses the downloader
     */
    fun pause() {
        destroySubscriptions()
        queue
            .filter { it.status == Download.DOWNLOADING }
            .forEach { it.status = Download.QUEUE }
        notifier.paused = true
    }

    /**
     * Removes everything from the queue.
     *
     * @param isNotification value that determines if status is set (needed for view updates)
     */
    fun clearQueue(isNotification: Boolean = false) {
        destroySubscriptions()

        // Needed to update the chapter view
        if (isNotification) {
            queue
                .filter { it.status == Download.QUEUE }
                .forEach { it.status = Download.NOT_DOWNLOADED }
        }
        queue.clear()
        notifier.dismissProgress()
    }

    /**
     * Prepares the subscriptions to start downloading.
     */
    private fun initializeSubscriptions() {
        if (isRunning) return
        isRunning = true
        runningFlow.value = true

        downloaderJob?.cancel()
        downloaderJob =
            scope.launch {
                downloadsFlow.collect { downloads ->
                    downloads.forEach { download ->
                        launch {
                            // Order matters: take the source's mutex before a permit, so a
                            // download waiting its turn within a source does not sit on one of
                            // the five slots.
                            val mutex = sourceMutexes.getOrPut(download.source.id) { Mutex() }
                            mutex.withLock {
                                sourceSemaphore.withPermit {
                                    try {
                                        val result = downloadChapter(download)
                                        withUIContext { completeDownload(result) }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (error: Throwable) {
                                        withUIContext {
                                            DownloadService.stop(context)
                                            Timber.e(error)
                                            notifier.onError(error.message)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }

    /**
     * Destroys the downloader subscriptions.
     */
    private fun destroySubscriptions() {
        if (!isRunning) return
        isRunning = false
        runningFlow.value = false

        downloaderJob?.cancel()
        downloaderJob = null
    }

    /**
     * Creates a download object for every chapter and adds them to the downloads queue.
     *
     * @param manga the manga of the chapters to download.
     * @param chapters the list of chapters to download.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     */
    fun queueChapters(
        manga: Manga,
        chapters: List<Chapter>,
        autoStart: Boolean
    ) = launchUI {
        val source = sourceManager.get(manga.source) as? HttpSource ?: return@launchUI
        val wasEmpty = queue.isEmpty()
        // Called in background thread, the operation can be slow with SAF.
        val chaptersWithoutDir =
            async {
                chapters
                    // Filter out those already downloaded.
                    .filter { provider.findChapterDir(it, manga, source) == null }
                    // Add chapters to queue from the start.
                    .sortedByDescending { it.source_order }
            }

        // Runs in main thread (synchronization needed).
        val chaptersToQueue =
            chaptersWithoutDir.await()
                // Filter out those already enqueued.
                .filter { chapter -> queue.none { it.chapter.id == chapter.id } }
                // Create a download for each one.
                .map { Download(source, manga, it) }

        if (chaptersToQueue.isNotEmpty()) {
            queue.addAll(chaptersToQueue)

            if (isRunning) {
                // Send the list of downloads to the downloader.
                if (!downloadsFlow.tryEmit(chaptersToQueue)) {
                    Timber.e("Download queue buffer full; ${chaptersToQueue.size} downloads were not dispatched")
                }
            }

            // Start downloader if needed
            if (autoStart && wasEmpty) {
                DownloadService.start(this@Downloader.context)
            }
        }
    }

    /**
     * Returns the observable which downloads a chapter.
     *
     * @param download the chapter to be downloaded.
     */
    private suspend fun downloadChapter(download: Download): Download {
        val mangaDir = provider.getMangaDir(download.manga, download.source)

        val availSpace = DiskUtil.getAvailableStorageSpace(mangaDir)
        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            download.status = Download.ERROR
            notifier.onError(context.getString(R.string.download_insufficient_space), download.chapter.name)
            return download
        }

        val chapterDirname = provider.getChapterDirName(download.chapter)
        val tmpDir = mangaDir.createDirectory(chapterDirname + TMP_DIR_SUFFIX)

        try {
            val pageList =
                if (download.pages == null) {
                    // Pull page list from network and add them to download object
                    download.source.getPageList(download.chapter).also { pages ->
                        if (pages.isEmpty()) {
                            throw Exception(context.getString(R.string.page_list_empty_error))
                        }
                        download.pages = pages
                    }
                } else {
                    // Or if the page list already exists, start from the file
                    download.pages!!
                }

            // Delete all temporary (unfinished) files
            tmpDir.listFiles()
                ?.filter { it.name!!.endsWith(".tmp") }
                ?.forEach { it.delete() }

            download.downloadedImages = 0
            download.status = Download.DOWNLOADING

            // Get all the URLs to the source images, fetch pages if necessary
            val pages = download.source.getAllImageUrlsFromPageList(pageList)

            // Start downloading images, consider we can have downloaded images already.
            // Concurrently do 5 pages at a time, as the inner flatMap(..., 5) did.
            val pageSemaphore = Semaphore(5)
            coroutineScope {
                pages.map { page ->
                    async {
                        pageSemaphore.withPermit {
                            getOrDownloadImage(page, download, tmpDir)
                            // Do when page is downloaded.
                            notifier.onProgressChange(download)
                        }
                    }
                }.awaitAll()
            }

            // Do after download completes
            ensureSuccessfulDownload(download, mangaDir, tmpDir, chapterDirname)
        } catch (e: CancellationException) {
            throw e
        } catch (error: Throwable) {
            // If the page list threw, it will resume here
            download.status = Download.ERROR
            notifier.onError(error.message, download.chapter.name)
        }
        return download
    }

    /**
     * Returns the observable which gets the image from the filesystem if it exists or downloads it
     * otherwise.
     *
     * @param page the page to download.
     * @param download the download of the page.
     * @param tmpDir the temporary directory of the download.
     */
    private suspend fun getOrDownloadImage(
        page: Page,
        download: Download,
        tmpDir: UniFile
    ): Page {
        // If the image URL is empty, do nothing
        if (page.imageUrl == null) {
            return page
        }

        val filename = String.format("%03d", page.number)
        val tmpFile = tmpDir.findFile("$filename.tmp")

        // Delete temp file if it exists.
        tmpFile?.delete()

        // Try to find the image file.
        val imageFile = tmpDir.listFiles()!!.find { it.name!!.startsWith("$filename.") }

        try {
            // If the image is already downloaded, do nothing. Otherwise download from network
            val file =
                when {
                    imageFile != null -> imageFile
                    chapterCache.isImageInCache(
                        page.imageUrl!!
                    ) -> copyImageFromCache(chapterCache.getImageFile(page.imageUrl!!), tmpDir, filename)
                    else -> downloadImage(page, download.source, tmpDir, filename)
                }

            // When the image is ready, set image path, progress (just in case) and status
            page.uri = file.uri
            page.progress = 100
            download.downloadedImages++
            page.status = Page.READY
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Mark this page as error and allow to download the remaining
            page.progress = 0
            page.status = Page.ERROR
        }
        return page
    }

    /**
     * Returns the observable which downloads the image from network.
     *
     * @param page the page to download.
     * @param source the source of the page.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private suspend fun downloadImage(
        page: Page,
        source: HttpSource,
        tmpDir: UniFile,
        filename: String
    ): UniFile {
        page.status = Page.DOWNLOAD_IMAGE
        page.progress = 0

        // Retry 3 times, waiting 2, 4 and 8 seconds between attempts.
        var lastError: Throwable? = null
        repeat(4) { attempt ->
            if (attempt > 0) {
                delay((2 shl attempt - 1) * 1000L)
            }
            try {
                // SY --> If the source is E-Hentai request a new page if null
                if (page.imageUrl == null && source.isEhBasedSource()) {
                    source.getImageUrl(page)?.let { page.imageUrl = it }
                }
                // SY <--
                val response = source.getImage(page)
                val file = tmpDir.createFile("$filename.tmp")
                try {
                    response.body.source().saveTo(file.openOutputStream())
                    val extension = getImageExtension(response, file)
                    file.renameTo("$filename.$extension")
                } catch (e: Exception) {
                    response.close()
                    file.delete()
                    // SY --> E-Hentai sometimes has dead pages, so we request a new one if it fails
                    if (source.isEhBasedSource()) page.imageUrl = null
                    // SY <--
                    throw e
                }
                return file
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: Exception("Failed to download image")
    }

    /**
     * Return the observable which copies the image from cache.
     *
     * @param cacheFile the file from cache.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private fun copyImageFromCache(
        cacheFile: File,
        tmpDir: UniFile,
        filename: String
    ): UniFile {
        val tmpFile = tmpDir.createFile("$filename.tmp")
        cacheFile.inputStream().use { input ->
            tmpFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        val extension = ImageUtil.findImageType(cacheFile.inputStream()) ?: return tmpFile
        tmpFile.renameTo("$filename.${extension.extension}")
        cacheFile.delete()
        return tmpFile
    }

    /**
     * Returns the extension of the downloaded image from the network response, or if it's null,
     * analyze the file. If everything fails, assume it's a jpg.
     *
     * @param response the network response of the image.
     * @param file the file where the image is already downloaded.
     */
    private fun getImageExtension(
        response: Response,
        file: UniFile
    ): String {
        val mime = response.body.contentType()?.run { if (type == "image") "image/$subtype" else null }
        return ImageUtil.getExtensionFromMimeType(mime) { file.openInputStream() }
    }

    /**
     * Checks if the download was successful.
     *
     * @param download the download to check.
     * @param mangaDir the manga directory of the download.
     * @param tmpDir the directory where the download is currently stored.
     * @param dirname the real (non temporary) directory name of the download.
     */
    private fun ensureSuccessfulDownload(
        download: Download,
        mangaDir: UniFile,
        tmpDir: UniFile,
        dirname: String
    ) {
        // Ensure that the chapter folder has all the images.
        val downloadedImages = tmpDir.listFiles().orEmpty().filterNot { it.name!!.endsWith(".tmp") }

        download.status =
            if (downloadedImages.size == download.pages!!.size) {
                Download.DOWNLOADED
            } else {
                Download.ERROR
            }

        // Only rename the directory if it's downloaded.
        if (download.status == Download.DOWNLOADED) {
            tmpDir.renameTo(dirname)
            cache.addChapter(dirname, mangaDir, download.manga)

            DiskUtil.createNoMediaFile(tmpDir, context)
        }
    }

    /**
     * Completes a download. This method is called in the main thread.
     */
    private fun completeDownload(download: Download) {
        // Delete successful downloads from queue
        if (download.status == Download.DOWNLOADED) {
            // remove downloaded chapter from queue
            queue.remove(download)
        }
        if (areAllDownloadsFinished()) {
            DownloadService.stop(context)
        }
    }

    /**
     * Returns true if all the queued downloads are in DOWNLOADED or ERROR state.
     */
    private fun areAllDownloadsFinished(): Boolean {
        return queue.none { it.status <= Download.DOWNLOADING }
    }

    companion object {
        const val TMP_DIR_SUFFIX = "_tmp"

        // Arbitrary minimum required space to start a download: 50 MB
        const val MIN_DISK_SPACE = 50 * 1024 * 1024
    }
}
