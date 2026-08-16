package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.storage.EpubFileCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File

/**
 * Loader used to load a chapter from a .epub file.
 */
class EpubPageLoaderCompat(private val epub: EpubFileCompat) : PageLoader() {
    constructor(file: File) : this(EpubFileCompat(file))

    /**
     * Recycles this loader and the open zip.
     */
    override fun recycle() {
        super.recycle()
        epub.close()
    }

    /**
     * Returns an observable containing the pages found on this zip archive ordered with a natural
     * comparator.
     */
    override suspend fun getPages(): List<ReaderPage> {
        return epub.getImagesFromPages()
            .mapIndexed { i, path ->
                val streamFn = { epub.getInputStream(epub.getEntry(path)!!) }
                ReaderPage(i).apply {
                    stream = streamFn
                    status = Page.READY
                }
            }
    }

    /**
     * Returns an observable that emits a ready state unless the loader was recycled.
     */
    override fun getPage(page: ReaderPage): Flow<Int> {
        return flowOf(
            if (isRecycled) {
                Page.ERROR
            } else {
                Page.READY
            }
        )
    }
}
