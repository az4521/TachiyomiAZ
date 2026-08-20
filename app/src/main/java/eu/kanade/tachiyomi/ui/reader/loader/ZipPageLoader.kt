package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.ImageUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.nio.channels.SeekableByteChannel

/**
 * Loader used to load a chapter from a .zip or .cbz file.
 */
class ZipPageLoader(private val zip: ZipFile) : PageLoader() {
    constructor(channel: SeekableByteChannel) : this(ZipFile(channel))
    constructor(file: File) : this(ZipFile(file))

    /**
     * Recycles this loader and the open zip.
     */
    override fun recycle() {
        super.recycle()
        zip.close()
    }

    /**
     * Returns an observable containing the pages found on this zip archive ordered with a natural
     * comparator.
     */
    override suspend fun getPages(): List<ReaderPage> {
        return zip.entries.toList()
            .filter { !it.isDirectory && ImageUtil.isImage(it.name) { zip.getInputStream(it) } }
            .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
            .mapIndexed { i, entry ->
                val streamFn = { zip.getInputStream(entry) }
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
