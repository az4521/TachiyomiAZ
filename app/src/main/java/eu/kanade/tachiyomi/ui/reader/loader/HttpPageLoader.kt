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
import eu.kanade.tachiyomi.util.lang.plusAssign
import eu.kanade.tachiyomi.util.system.ImageUtil
import exh.EH_SOURCE_ID
import exh.EXH_SOURCE_ID
import rx.Completable
import rx.Observable
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.subjects.SerializedSubject
import rx.subscriptions.CompositeSubscription
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
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
     * Current active subscriptions.
     */
    private val subscriptions = CompositeSubscription()

    private val preloadSize = prefs.eh_preload_size().get()

    init {
        // EXH -->
        repeat(prefs.eh_readerThreads().get()) {
            // EXH <--
            subscriptions +=
                Observable.defer { Observable.just(queue.take().page) }
                    .filter { it.status == Page.QUEUE }
                    .concatMap {
                        source.fetchImageFromCacheThenNet(it).doOnNext {
                            XLog.d("Downloaded page: ${it.number}!")
                        }
                    }
                    .repeat()
                    .subscribeOn(Schedulers.io())
                    .subscribe(
                        {
                        },
                        { error ->
                            if (error !is InterruptedException) {
                                Timber.e(error)
                            }
                        }
                    )
            // EXH -->
        }
        // EXH <--
    }

    /**
     * Recycles this loader and the active subscriptions and queue.
     */
    override fun recycle() {
        super.recycle()
        subscriptions.unsubscribe()
        queue.clear()

        // Cache current page list progress for online chapters to allow a faster reopen
        val pages = chapter.pages
        if (pages != null) {
            Completable
                .fromAction {
                    // Convert to pages without reader information
                    val pagesToSave = pages.map { Page(it.index, it.url, it.imageUrl) }
                    chapterCache.putPageListToCache(chapter.chapter, pagesToSave)
                }
                .onErrorComplete()
                .subscribeOn(Schedulers.io())
                .subscribe()
        }
    }

    /**
     * Returns an observable with the page list for a chapter. It tries to return the page list from
     * the local cache, otherwise fallbacks to network.
     */
    override fun getPages(): Observable<List<ReaderPage>> {
        return chapterCache
            .getPageListFromCache(chapter.chapter)
            .onErrorResumeNext { source.fetchPageList(chapter.chapter) }
            .map { pages ->
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
                rp
            }
    }

    /**
     * Returns an observable that loads a page through the queue and listens to its result to
     * emit new states. It handles re-enqueueing pages if they were evicted from the cache.
     */
    override fun getPage(page: ReaderPage): Observable<Int> {
        return Observable.defer {
            val imageUrl = page.imageUrl

            // Check if the image has been deleted
            if (page.status == Page.READY && imageUrl != null && !chapterCache.isImageInCache(imageUrl)) {
                page.status = Page.QUEUE
            }

            // Automatically retry failed pages when subscribed to this page
            if (page.status == Page.ERROR) {
                page.status = Page.QUEUE
            }

            val statusSubject = SerializedSubject(PublishSubject.create<Int>())
            page.setStatusSubject(statusSubject)

            val queuedPages = mutableListOf<PriorityPage>()
            if (page.status == Page.QUEUE) {
                queuedPages += PriorityPage(page, 1).also { queue.offer(it) }
            }
            queuedPages += preloadNextPages(page, preloadSize)

            statusSubject.startWith(page.status)
                .doOnUnsubscribe {
                    queuedPages.forEach {
                        if (it.page.status == Page.QUEUE) {
                            queue.remove(it)
                        }
                    }
                }
        }
            .subscribeOn(Schedulers.io())
            .unsubscribeOn(Schedulers.io())
    }

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
    private fun HttpSource.fetchImageFromCacheThenNet(page: ReaderPage): Observable<ReaderPage> {
        return if (page.imageUrl.isNullOrEmpty()) {
            getImageUrl(page).flatMap { getCachedImage(it) }
        } else {
            getCachedImage(page)
        }
    }

    private fun HttpSource.getImageUrl(page: ReaderPage): Observable<ReaderPage> {
        page.status = Page.LOAD_PAGE
        return fetchImageUrl(page)
            .doOnError { page.status = Page.ERROR }
            .onErrorReturn {
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
            .doOnNext { page.imageUrl = it }
            .map { page }
    }

    /**
     * Returns an observable of the page that gets the image from the chapter or fallbacks to
     * network and copies it to the cache calling [cacheImage].
     *
     * @param page the page.
     */
    private fun HttpSource.getCachedImage(page: ReaderPage): Observable<ReaderPage> {
        val imageUrl = page.imageUrl ?: return Observable.just(page)

        return Observable.just(page)
            .flatMap {
                if (!chapterCache.isImageInCache(imageUrl)) {
                    cacheImage(page)
                } else {
                    Observable.just(page)
                }
            }
            .doOnNext {
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
            .doOnError {
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
            .onErrorReturn { page }
    }

    /**
     * Returns an observable of the page that downloads the image to [ChapterCache].
     *
     * @param page the page.
     */
    private fun HttpSource.cacheImage(page: ReaderPage): Observable<ReaderPage> {
        page.status = Page.DOWNLOAD_IMAGE
        return fetchImage(page)
            .doOnNext { chapterCache.putImageToCache(page.imageUrl!!, it) }
            .map { page }
    }

    // EXH -->
    fun boostPage(page: ReaderPage) {
        if (page.status == Page.QUEUE) {
            subscriptions +=
                Observable.just(page)
                    .concatMap { source.fetchImageFromCacheThenNet(it) }
                    .subscribeOn(Schedulers.io())
                    .subscribe(
                        {
                        },
                        { error ->
                            if (error !is InterruptedException) {
                                Timber.e(error)
                            }
                        }
                    )
        }
    }
    // EXH <--
}
