package eu.kanade.tachiyomi.util.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import androidx.core.os.EnvironmentCompat
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.lang.Hash
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

object DiskUtil {
    fun hashKeyForDisk(key: String): String {
        return Hash.md5(key)
    }

    fun getDirectorySize(f: File): Long {
        var size: Long = 0
        if (f.isDirectory) {
            for (file in f.listFiles()!!) {
                size += getDirectorySize(file)
            }
        } else {
            size = f.length()
        }
        return size
    }

    /**
     * Gets the available space for the disk that a file path points to, in bytes.
     */
    fun getAvailableStorageSpace(f: UniFile): Long {
        return try {
            val stat = StatFs(f.uri.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * Returns the root folders of all the available external storages.
     */
    fun getExternalStorages(context: Context): Collection<File> {
        val directories = mutableSetOf<File>()
        directories +=
            ContextCompat.getExternalFilesDirs(context, null)
                .filterNotNull()
                .mapNotNull {
                    val file = File(it.absolutePath.substringBefore("/Android/"))
                    val state = EnvironmentCompat.getStorageState(file)
                    if (state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY) {
                        file
                    } else {
                        null
                    }
                }

        return directories
    }

    /**
     * Don't display downloaded chapters in gallery apps creating `.nomedia`.
     */
    fun createNoMediaFile(
        dir: UniFile?,
        context: Context?
    ) {
        if (dir != null && dir.exists()) {
            val nomedia = dir.findFile(NOMEDIA_FILE)
            if (nomedia == null) {
                dir.createFile(NOMEDIA_FILE)
                context?.let { scanMedia(it, dir.uri) }
            }
        }
    }

    /**
     * Scans the given file so that it can be shown in gallery apps, for example.
     */
    fun scanMedia(
        context: Context,
        file: File
    ) {
        scanMedia(context, Uri.fromFile(file))
    }

    /**
     * Scans the given file so that it can be shown in gallery apps, for example.
     */
    fun scanMedia(
        context: Context,
        uri: Uri
    ) {
        val action = Intent.ACTION_MEDIA_SCANNER_SCAN_FILE
        val mediaScanIntent = Intent(action)
        mediaScanIntent.data = uri
        context.sendBroadcast(mediaScanIntent)
    }

    /**
     * Mutate the given filename to make it valid for a FAT filesystem,
     * replacing any invalid characters with "_". This method doesn't allow hidden files (starting
     * with a dot), but you can manually add it later.
     */
    fun buildValidFilename(
        origName: String,
        maxBytes: Int = MAX_FILE_NAME_BYTES,
        disallowNonAscii: Boolean = false
    ): String {
        val name = origName.trim('.', ' ')
        if (name.isEmpty()) {
            return "(invalid)"
        }
        val sb = StringBuilder(name.length)
        name.forEach { c ->
            if (disallowNonAscii && c >= 0x80.toChar()) {
                sb.append(
                    c.toString().toByteArray(Charsets.UTF_8).toHexString(
                        HexFormat {
                            upperCase = false
                        }
                    )
                )
            } else if (isValidFatFilenameChar(c)) {
                sb.append(c)
            } else {
                sb.append('_')
            }
        }
        return truncateToLength(sb.toString(), maxBytes)
    }

    /**
     * Truncate a string to a maximum length, while maintaining valid Unicode encoding.
     */
    fun truncateToLength(s: String, maxBytes: Int): String {
        val charset = Charsets.UTF_8
        val decoder = charset.newDecoder()
        val sba = s.toByteArray(charset)
        if (sba.size <= maxBytes) {
            return s
        }
        // Ensure truncation by having byte buffer = maxBytes
        val bb = ByteBuffer.wrap(sba, 0, maxBytes)
        val cb = CharBuffer.allocate(maxBytes)
        // Ignore an incomplete character
        decoder.onMalformedInput(CodingErrorAction.IGNORE)
        decoder.decode(bb, cb, true)
        decoder.flush(cb)
        return String(cb.array(), 0, cb.position())
    }

    /**
     * Returns true if the given character is a valid filename character, false otherwise.
     */
    private fun isValidFatFilenameChar(c: Char): Boolean {
        if (0x00.toChar() <= c && c <= 0x1f.toChar()) {
            return false
        }
        return when (c) {
            '"', '*', '/', ':', '<', '>', '?', '\\', '|', 0x7f.toChar() -> false
            else -> true
        }
    }

    // Even though vfat allows 255 UCS-2 chars, we might eventually write to
    // ext4 through a FUSE layer, so use that limit minus 15 reserved characters.
    const val MAX_FILE_NAME_BYTES = 240

    const val NOMEDIA_FILE = ".nomedia"
}
