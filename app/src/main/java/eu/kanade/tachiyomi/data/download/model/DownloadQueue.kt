package eu.kanade.tachiyomi.data.download.model

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.DownloadStore
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.util.concurrent.CopyOnWriteArrayList

class DownloadQueue(
    private val store: DownloadStore,
    private val queue: MutableList<Download> = CopyOnWriteArrayList()
) : List<Download> by queue {
    // These replace RxJava subjects that carried an unbounded onBackpressureBuffer, so they
    // never dropped. tryEmit cannot suspend, and the default SUSPEND policy makes it drop the
    // NEWEST value once the buffer fills -- which would leave the queue UI showing stale state
    // forever. DROP_OLDEST keeps the newest instead.
    private val statusFlow =
        MutableSharedFlow<Download>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    private val updatedFlow =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

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
            setPagesFlow(download.pages, null)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getProgressFlow(): Flow<Download> {
        return statusFlow.asSharedFlow()
            .onStart { getActiveDownloads().forEach { emit(it) } }
            .flatMapMerge { download ->
                if (download.status == Download.DOWNLOADING) {
                    // replay = 1: setPagesFlow installs this on the pages immediately, but
                    // flatMapMerge only subscribes once this returns, so page statuses raised in
                    // between would be dropped and the progress bar would sit still.
                    val pageStatusFlow =
                        MutableSharedFlow<Int>(
                            replay = 1,
                            extraBufferCapacity = 64,
                            onBufferOverflow = BufferOverflow.DROP_OLDEST
                        )
                    setPagesFlow(download.pages, pageStatusFlow)
                    return@flatMapMerge pageStatusFlow
                        .filter { it == Page.READY }
                        .map { download }
                } else if (download.status == Download.DOWNLOADED || download.status == Download.ERROR) {
                    setPagesFlow(download.pages, null)
                }
                flowOf(download)
            }
            .filter { it.status == Download.DOWNLOADING }
    }

    private fun setPagesFlow(
        pages: List<Page>?,
        flow: MutableSharedFlow<Int>?
    ) {
        pages?.forEach { it.setStatusFlow(flow) }
    }
}
