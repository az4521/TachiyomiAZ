package eu.kanade.tachiyomi.ui.reader.loader

import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.ImageUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors

/**
 * Loader used to load a chapter from a .rar or .cbr file.
 */
class RarPageLoader(private val archive: Archive) : PageLoader() {
    constructor(inputStream: InputStream) : this(Archive(inputStream))
    constructor(file: File) : this(Archive(file))

    /**
     * Pool for copying compressed files to an input stream.
     */
    private val pool = Executors.newFixedThreadPool(1)

    /**
     * Recycles this loader and the open archive.
     */
    override fun recycle() {
        super.recycle()
        archive.close()
        pool.shutdown()
    }

    /**
     * Returns an observable containing the pages found on this rar archive ordered with a natural
     * comparator.
     */
    override suspend fun getPages(): List<ReaderPage> {
        return archive.fileHeaders
            .filter { !it.isDirectory && ImageUtil.isImage(it.fileName) { archive.getInputStream(it) } }
            .sortedWith(Comparator<FileHeader> { f1, f2 -> f1.fileName.compareToCaseInsensitiveNaturalOrder(f2.fileName) })
            .mapIndexed { i, header ->
                val streamFn = { getStream(header) }

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

    /**
     * Returns an input stream for the given [header].
     */
    private fun getStream(header: FileHeader): InputStream {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        pool.execute {
            try {
                pipeOut.use {
                    archive.extractFile(header, it)
                }
            } catch (_: Exception) {
            }
        }
        return pipeIn
    }
}
