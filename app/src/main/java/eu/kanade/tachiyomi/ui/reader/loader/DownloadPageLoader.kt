package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import android.net.Uri
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.awaitSingle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import uy.kohesive.injekt.injectLazy

/**
 * Loader used to load a chapter from the downloaded chapters.
 */
class DownloadPageLoader(
    private val chapter: ReaderChapter,
    private val manga: Manga,
    private val source: Source,
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
        return downloadManager.buildPageList(source, manga, chapter.chapter)
            .awaitSingle()
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
