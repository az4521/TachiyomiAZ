package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import uy.kohesive.injekt.injectLazy

/**
 * Loader used to load a chapter from the downloaded chapters.
 */
class DownloadPageLoader(
    private val chapterDir: UniFile,
    private val downloadManager: DownloadManager
) : PageLoader() {
    /**
     * The application context. Needed to open input streams.
     */
    private val context by injectLazy<Application>()

    /**
     * Returns an observable containing the pages found on this downloaded chapter.
     */
    override suspend fun getPages(): List<ReaderPage> {
        return downloadManager.buildPageList(chapterDir)
            .map { page ->
                ReaderPage(page.index, page.url, page.imageUrl) {
                    context.contentResolver.openInputStream(page.uri ?: Uri.EMPTY)!!
                }.apply {
                    status = Page.READY
                }
            }
    }

    override fun getPage(page: ReaderPage): Flow<Int> {
        return flowOf(Page.READY) // TODO maybe check if file still exists?
    }
}
