package eu.kanade.tachiyomi.data.glide

import com.bumptech.glide.load.ImageHeaderParser
import com.bumptech.glide.load.engine.bitmap_recycle.ArrayPool
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Wraps an [ImageHeaderParser] so it can't read more than [limit] bytes of a stream.
 *
 * Glide marks streams with a 5 MiB read limit before asking parsers for the orientation and
 * rewinds them afterwards. [com.bumptech.glide.load.resource.bitmap.ExifInterfaceImageHeaderParser]
 * scans a PNG without an eXIf chunk all the way to IEND, so on a PNG larger than 5 MiB the rewind
 * fails and the image never loads. Past the limit the wrapped parser just sees the end of the
 * stream and reports no orientation.
 */
class BoundedImageHeaderParser(
    private val parser: ImageHeaderParser,
    private val limit: Long = 4L * 1024 * 1024
) : ImageHeaderParser {
    override fun getType(`is`: InputStream): ImageHeaderParser.ImageType {
        return parser.getType(BoundedInputStream(`is`, limit))
    }

    override fun getOrientation(`is`: InputStream, byteArrayPool: ArrayPool): Int {
        return parser.getOrientation(BoundedInputStream(`is`, limit), byteArrayPool)
    }

    // Buffers rewind without a limit, so these can go straight to the parser.
    override fun getType(byteBuffer: ByteBuffer): ImageHeaderParser.ImageType = parser.getType(byteBuffer)

    override fun getOrientation(byteBuffer: ByteBuffer, byteArrayPool: ArrayPool): Int {
        return parser.getOrientation(byteBuffer, byteArrayPool)
    }

    private class BoundedInputStream(stream: InputStream, private var remaining: Long) : FilterInputStream(stream) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val byte = super.read()
            if (byte != -1) remaining--
            return byte
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val read = super.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (read > 0) remaining -= read
            return read
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(minOf(n, remaining))
            remaining -= skipped
            return skipped
        }

        override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()

        override fun markSupported(): Boolean = false

        override fun mark(readlimit: Int) {}

        override fun reset() {
            throw IOException("mark/reset not supported")
        }

        // The caller owns the underlying stream and rewinds it afterwards.
        override fun close() {}
    }
}
