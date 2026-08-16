package eu.kanade.tachiyomi.ui.reader.loader

import android.graphics.BitmapFactory
import com.elvishew.xlog.XLog
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.system.launchIO
import exh.EH_SOURCE_ID
import exh.EXH_SOURCE_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Loader used to load chapters from an online source.
 */
class HttpPageLoader(
    private val chapter: ReaderChapter,
    private val source: HttpSource,
    private val chapterCache: ChapterCache = Injekt.get()
) : PageLoader() {
    // EXH -->
    private val prefs: PreferencesHelper by injectLazy()
    // EXH <--

    /**
     * A queue used to manage requests one by one while allowing priorities.
     */
    private val queue = PriorityBlockingQueue<PriorityPage>()

    /**
     * Scope owning the worker loops and any boosted page requests.
     */
    private val scope = CoroutineScope(SupervisorJob())

    private val preloadSize = prefs.eh_preload_size().get()

    /**
     * One dedicated thread per worker.
     *
     * The worker loop parks its thread in [PriorityBlockingQueue.take] for as long as the queue is
     * empty, so it must never run on a shared pool. On `Schedulers.io()` the parked thread is
     * released back into the cache — and handed out to unrelated subscribers, whose work then never
     * runs — as soon as `repeat()` swaps subscriptions or this loader is recycled. Owning the
     * executors also means [recycle] can actually interrupt the parked threads.
     */
    private val workerExecutors =
        List(prefs.eh_readerThreads().get()) { index ->
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "HttpPageLoader#$index").apply { isDaemon = true }
            }
        }

    init {
        // EXH -->
        workerExecutors.forEach { executor ->
            // EXH <--
            // Each worker stays pinned to its own single-thread dispatcher. queue.take() parks
            // the thread while the queue is empty, so this must never move onto a shared pool:
            // a parked shared thread would be handed to unrelated work that then never runs, and
            // resuming on an OkHttp dispatcher thread would hold one of its per-host slots.
            val worker = executor.asCoroutineDispatcher()
            scope.launch(worker) {
                while (isActive) {
                    try {
                        val page = queue.take().page
                        if (page.status == Page.QUEUE) {
                            loadPageSafely(page)
                        }
                    } catch (e: InterruptedException) {
                        // recycle() interrupts the parked threads; stop rather than spin.
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Timber.e(e)
                    }
                }
            }
            // EXH -->
        }
        // EXH <--
    }

    /**
     * Recycles this loader and the active subscriptions and queue.
     */
    override fun recycle() {
        super.recycle()
        scope.cancel()
        queue.clear()
        // Unsubscribing does not wake a worker parked in `queue.take()`, so interrupt the threads
        // explicitly. Otherwise every recycled loader leaks its workers for the life of the process.
        workerExecutors.forEach { it.shutdownNow() }

        // Cache current page list progress for online chapters to allow a faster reopen
        val pages = chapter.pages
        if (pages != null) {
            // Deliberately not scope: that has just been cancelled, and this cache write should
            // still complete. Errors were swallowed by onErrorComplete before.
            launchIO {
                try {
                    // Convert to pages without reader information
                    val pagesToSave = pages.map { Page(it.index, it.url, it.imageUrl) }
                    chapterCache.putPageListToCache(chapter.chapter, pagesToSave)
                } catch (e: Throwable) {
                    Timber.e(e)
                }
            }
        }
    }

    /**
     * Returns an observable with the page list for a chapter. It tries to return the page list from
     * the local cache, otherwise fallbacks to network.
     */
    override suspend fun getPages(): List<ReaderPage> {
        val pages =
            try {
                chapterCache.getPageListFromCache(chapter.chapter)
            } catch (e: Throwable) {
                source.getPageList(chapter.chapter)
            }
        val rp =
            pages.mapIndexed { index, page ->
                // Don't trust sources and use our own indexing
                ReaderPage(index, page.url, page.imageUrl)
            }
        if (prefs.eh_aggressivePageLoading().get()) {
            rp.mapNotNull {
                if (it.status == Page.QUEUE) {
                    PriorityPage(it, 0)
                } else {
                    null
                }
            }.forEach { queue.offer(it) }
        }
        return rp
    }

    /**
     * Returns an observable that loads a page through the queue and listens to its result to
     * emit new states. It handles re-enqueueing pages if they were evicted from the cache.
     */
    override fun getPage(page: ReaderPage): Flow<Int> =
        channelFlow {
            val imageUrl = page.imageUrl

            // Check if the image has been deleted
            if (page.status == Page.READY && imageUrl != null && !chapterCache.isImageInCache(imageUrl)) {
                page.status = Page.QUEUE
            }

            // Automatically retry failed pages when collected
            if (page.status == Page.ERROR) {
                page.status = Page.QUEUE
            }

            // replay = 1 because the flow is installed on the page below and the page is queued
            // for the workers before the collector subscribes further down. A worker on another
            // thread can take the page and drive it to READY inside that window, and a
            // replay-less SharedFlow would discard that with nobody subscribed -- leaving the
            // page spinning forever. Replaying the last status means a late collector still sees
            // the current one. DROP_OLDEST for the same reason: the newest status is the one that
            // matters, an older intermediate one is not.
            val statusFlow =
                MutableSharedFlow<Int>(
                    replay = 1,
                    extraBufferCapacity = 8,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
                )
            page.setStatusFlow(statusFlow)

            val queuedPages = mutableListOf<PriorityPage>()
            if (page.status == Page.QUEUE) {
                queuedPages += PriorityPage(page, 1).also { queue.offer(it) }
            }
            queuedPages += preloadNextPages(page, preloadSize)

            send(page.status)
            val job = launch { statusFlow.collect { send(it) } }

            awaitClose {
                job.cancel()
                page.setStatusFlow(null)
                // Previously doOnUnsubscribe: drop anything still queued for a page nobody is
                // watching any more, so the workers do not fetch pages that scrolled away.
                queuedPages.forEach {
                    if (it.page.status == Page.QUEUE) {
                        queue.remove(it)
                    }
                }
            }
        }.flowOn(Dispatchers.IO)

    /**
     * Preloads the given [amount] of pages after the [currentPage] with a lower priority.
     * @return a list of [PriorityPage] that were added to the [queue]
     */
    private fun preloadNextPages(
        currentPage: ReaderPage,
        amount: Int
    ): List<PriorityPage> {
        val pageIndex = currentPage.index
        val pages = currentPage.chapter.pages ?: return emptyList()
        if (pageIndex == pages.lastIndex) return emptyList()

        return pages
            .subList(pageIndex + 1, min(pageIndex + 1 + amount, pages.size))
            .mapNotNull {
                if (it.status == Page.QUEUE) {
                    PriorityPage(it, 0).apply { queue.offer(this) }
                } else {
                    null
                }
            }
    }

    /**
     * Retries a page. This method is only called from user interaction on the viewer.
     */
    override fun retryPage(page: ReaderPage) {
        if (page.status == Page.ERROR) {
            page.status = Page.QUEUE
        }
        // EXH -->
        // Grab a new image URL on EXH sources
        if (source.id == EH_SOURCE_ID || source.id == EXH_SOURCE_ID) {
            page.imageUrl = null
        }

        if (prefs.eh_readerInstantRetry().get()) // EXH <--
            {
                boostPage(page)
            } else {
            // EXH <--
            queue.offer(PriorityPage(page, 2))
        }
    }

    /**
     * Data class used to keep ordering of pages in order to maintain priority.
     */
    private class PriorityPage(
        val page: ReaderPage,
        val priority: Int
    ) : Comparable<PriorityPage> {
        companion object {
            private val idGenerator = AtomicInteger()
        }

        private val identifier = idGenerator.incrementAndGet()

        override fun compareTo(other: PriorityPage): Int {
            val p = other.priority.compareTo(priority)
            return if (p != 0) p else identifier.compareTo(other.identifier)
        }
    }

    /**
     * Returns an observable of the page with the downloaded image.
     *
     * @param page the page whose source image has to be downloaded.
     */
    private suspend fun HttpSource.fetchImageFromCacheThenNet(page: ReaderPage): ReaderPage {
        return if (page.imageUrl.isNullOrEmpty()) {
            getCachedImage(fetchPageImageUrl(page))
        } else {
            getCachedImage(page)
        }
    }

    /**
     * Loads one queued page without allowing a source/cache exception to terminate the worker.
     * The worker subscriptions live for the whole chapter, so an uncaught error here would
     * permanently reduce the configured download concurrency and can eventually leave the queue
     * with no consumers at all.
     */
    private suspend fun loadPageSafely(page: ReaderPage) {
        try {
            val loaded = source.fetchImageFromCacheThenNet(page)
            XLog.d("Downloaded page: ${loaded.number}!")
        } catch (e: CancellationException) {
            throw e
        } catch (error: Throwable) {
            page.status = Page.ERROR
            Timber.e(error, "Reader page worker failed on page ${page.number}")
        }
    }

    private suspend fun HttpSource.fetchPageImageUrl(page: ReaderPage): ReaderPage {
        page.status = Page.LOAD_PAGE
        // Use the suspend API so sources that only override `getImageUrl` (the current
        // extensions-lib surface) resolve correctly instead of falling through to the
        // deprecated no-op default.
        val imageUrl =
            try {
                getImageUrl(page)
            } catch (it: Throwable) {
                page.status = Page.ERROR
                // [EXH]
                XLog.w("> Failed to fetch image URL!", it)
                XLog.w(
                    "> (source.id: %s, source.name: %s, page.index: %s, page.url: %s, page.imageUrl: %s, chapter.id: %s, chapter.url: %s)",
                    source.id,
                    source.name,
                    page.index,
                    page.url,
                    page.imageUrl,
                    page.chapter.chapter.id,
                    page.chapter.chapter.url
                )

                null
            }
        page.imageUrl = imageUrl
        return page
    }

    /**
     * Returns an observable of the page that gets the image from the chapter or fallbacks to
     * network and copies it to the cache calling [cacheImage].
     *
     * @param page the page.
     */
    private suspend fun HttpSource.getCachedImage(page: ReaderPage): ReaderPage {
        val imageUrl = page.imageUrl ?: return page

        try {
            if (!chapterCache.isImageInCache(imageUrl)) {
                cacheImage(page)
            }
            run {
                // SY -->
                val readerTheme = prefs.readerTheme().get()
                if (readerTheme >= 3) {
                    val stream = chapterCache.getImageFile(imageUrl).inputStream()
                    val image = BitmapFactory.decodeStream(stream)
                    page.bg =
                        ImageUtil.autoSetBackground(
                            image,
                            readerTheme == 2,
                            prefs.context
                        )
                    page.bgType = PagerPageHolder.getBGType(readerTheme, prefs.context)
                    stream.close()
                }
                // SY <--
                page.stream = { chapterCache.getImageFile(imageUrl).inputStream() }
                page.status = Page.READY
            }
        } catch (it: Throwable) {
            // [EXH]
            XLog.w("> Failed to fetch image!", it)
            XLog.w(
                "> (source.id: %s, source.name: %s, page.index: %s, page.url: %s, page.imageUrl: %s, chapter.id: %s, chapter.url: %s)",
                source.id,
                source.name,
                page.index,
                page.url,
                page.imageUrl,
                page.chapter.chapter.id,
                page.chapter.chapter.url
            )

            page.status = Page.ERROR
        }
        return page
    }

    /**
     * Returns an observable of the page that downloads the image to [ChapterCache].
     *
     * @param page the page.
     */
    private suspend fun HttpSource.cacheImage(page: ReaderPage): ReaderPage {
        page.status = Page.DOWNLOAD_IMAGE
        chapterCache.putImageToCache(page.imageUrl!!, getImage(page))
        return page
    }

    // EXH -->
    fun boostPage(page: ReaderPage) {
        if (page.status == Page.QUEUE) {
            // Avoid racing the forced request with a stale queued copy of the same page.
            queue.filter { it.page === page }.forEach(queue::remove)
            scope.launch(Dispatchers.IO) {
                loadPageSafely(page)
            }
        }
    }
    // EXH <--
}
