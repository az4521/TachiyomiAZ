package eu.kanade.tachiyomi.data.download.model

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.DownloadStore
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.util.lang.asFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import rx.subjects.PublishSubject
import java.util.concurrent.CopyOnWriteArrayList

class DownloadQueue(
    private val store: DownloadStore,
    private val queue: MutableList<Download> = CopyOnWriteArrayList()
) : List<Download> by queue {
    // extraBufferCapacity stands in for the onBackpressureBuffer these streams used to carry:
    // emitters are non-suspending (tryEmit from a @Volatile setter), so they need somewhere to
    // put values when a collector is slow.
    private val statusFlow = MutableSharedFlow<Download>(extraBufferCapacity = 64)

    private val updatedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    fun addAll(downloads: List<Download>) {
        downloads.forEach { download ->
            download.setStatusFlow(statusFlow)
            download.setStatusCallback(::setPagesFor)
            download.status = Download.QUEUE
        }
        queue.addAll(downloads)
        store.addAll(downloads)
        updatedFlow.tryEmit(Unit)
    }

    fun remove(download: Download) {
        val removed = queue.remove(download)
        store.remove(download)
        download.setStatusFlow(null)
        download.setStatusCallback(null)
        if (download.status == Download.DOWNLOADING || download.status == Download.QUEUE) {
            download.status = Download.NOT_DOWNLOADED
        }
        if (removed) {
            updatedFlow.tryEmit(Unit)
        }
    }

    fun remove(chapter: Chapter) {
        find { it.chapter.id == chapter.id }?.let { remove(it) }
    }

    fun remove(chapters: List<Chapter>) {
        for (chapter in chapters) {
            remove(chapter)
        }
    }

    fun remove(manga: Manga) {
        filter { it.manga.id == manga.id }.forEach { remove(it) }
    }

    fun clear() {
        queue.forEach { download ->
            download.setStatusFlow(null)
            download.setStatusCallback(null)
            if (download.status == Download.DOWNLOADING || download.status == Download.QUEUE) {
                download.status = Download.NOT_DOWNLOADED
            }
        }
        queue.clear()
        store.clear()
        updatedFlow.tryEmit(Unit)
    }

    fun getActiveDownloads(): List<Download> = filter { download -> download.status == Download.DOWNLOADING }

    fun getStatusFlow(): Flow<Download> = statusFlow.asSharedFlow()

    fun getUpdatedFlow(): Flow<List<Download>> =
        updatedFlow.asSharedFlow()
            .onStart { emit(Unit) }
            .map { this }

    private fun setPagesFor(download: Download) {
        if (download.status == Download.DOWNLOADED || download.status == Download.ERROR) {
            setPagesSubject(download.pages, null)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getProgressFlow(): Flow<Download> {
        return statusFlow.asSharedFlow()
            .onStart { getActiveDownloads().forEach { emit(it) } }
            .flatMapMerge { download ->
                if (download.status == Download.DOWNLOADING) {
                    // Page still exposes an RxJava status subject because it is part of the
                    // extension-facing API, so bridge it rather than changing that surface.
                    val pageStatusSubject = PublishSubject.create<Int>()
                    setPagesSubject(download.pages, pageStatusSubject)
                    return@flatMapMerge pageStatusSubject
                        .onBackpressureBuffer()
                        .asFlow()
                        .filter { it == Page.READY }
                        .map { download }
                } else if (download.status == Download.DOWNLOADED || download.status == Download.ERROR) {
                    setPagesSubject(download.pages, null)
                }
                flowOf(download)
            }
            .filter { it.status == Download.DOWNLOADING }
    }

    private fun setPagesSubject(
        pages: List<Page>?,
        subject: PublishSubject<Int>?
    ) {
        pages?.forEach { it.setStatusSubject(subject) }
    }
}
