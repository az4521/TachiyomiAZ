package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.get
import eu.kanade.tachiyomi.R
import tachiyomi.decoder.Format
import tachiyomi.decoder.ImageDecoder
import java.io.InputStream
import java.net.URLConnection
import kotlin.math.abs

object ImageUtil {
    fun isImage(
        name: String,
        openStream: (() -> InputStream)? = null
    ): Boolean {
        val contentType =
            try {
                URLConnection.guessContentTypeFromName(name)
            } catch (e: Exception) {
                null
            } ?: openStream?.let { findImageType(it)?.mime }
        return contentType?.startsWith("image/") ?: false
    }

    fun getExtensionFromMimeType(mime: String?, openStream: () -> InputStream): String {
        val type = mime?.let { ImageType.entries.find { it.mime == mime } } ?: findImageType(openStream)
        return type?.extension ?: "jpg"
    }

    fun findImageType(openStream: () -> InputStream): ImageType? {
        return openStream().use { findImageType(it) }
    }

    fun findImageType(stream: InputStream): ImageType? {
        return try {
            when (getImageType(stream)?.format) {
                Format.Avif -> ImageType.AVIF
                Format.Gif -> ImageType.GIF
                Format.Heif -> ImageType.HEIF
                Format.Jpeg -> ImageType.JPEG
                Format.Jxl -> ImageType.JXL
                Format.Png -> ImageType.PNG
                Format.Webp -> ImageType.WEBP
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getImageType(stream: InputStream): tachiyomi.decoder.ImageType? {
        val bytes = ByteArray(32)

        val length = if (stream.markSupported()) {
            stream.mark(bytes.size)
            stream.read(bytes, 0, bytes.size).also { stream.reset() }
        } else {
            stream.read(bytes, 0, bytes.size)
        }

        if (length == -1) {
            return null
        }

        return ImageDecoder.findType(bytes)
    }

    private fun ByteArray.comparesWithAnyOf(magics: List<ByteArray>): Boolean {
        for (x in magics) {
            if (this.compareWith(x)) {
                return true
            }
        }
        return false
    }

    private fun ByteArray.compareWith(
        magic: ByteArray,
        offset: Int = 0
    ): Boolean {
        for (i in magic.indices) {
            if (this[i + offset] != magic[i]) return false
        }
        return true
    }

    private fun ByteArray.getSlice(
        offset: Int,
        length: Int
    ): ByteArray {
        return this.slice(IntRange(offset, offset + length - 1)).toByteArray()
    }

    private fun charByteArrayOf(vararg bytes: Int): ByteArray {
        return ByteArray(bytes.size).apply {
            for (i in bytes.indices) {
                set(i, bytes[i].toByte())
            }
        }
    }

    enum class ImageType(val mime: String, val extension: String) {
        AVIF("image/avif", "avif"),
        GIF("image/gif", "gif"),
        HEIF("image/heif", "heif"),
        JPEG("image/jpeg", "jpg"),
        JXL("image/jxl", "jxl"),
        PNG("image/png", "png"),
        WEBP("image/webp", "webp")
    }

    // SY -->
    fun autoSetBackground(
        image: Bitmap?,
        alwaysUseWhite: Boolean,
        context: Context
    ): Drawable {
        val backgroundColor =
            if (alwaysUseWhite) {
                Color.WHITE
            } else {
                context.getResourceColor(androidx.appcompat.R.attr.colorPrimary)
            }
        if (image == null) return backgroundColor.toDrawable()
        if (image.width < 50 || image.height < 50) {
            return backgroundColor.toDrawable()
        }
        val top = 5
        val bot = image.height - 5
        val left = (image.width * 0.0275).toInt()
        val right = image.width - left
        val midX = image.width / 2
        val midY = image.height / 2
        val offsetX = (image.width * 0.01).toInt()
        val offsetY = (image.height * 0.01).toInt()
        val topLeftIsDark = isDark(image[left, top])
        val topRightIsDark = isDark(image[right, top])
        val midLeftIsDark = isDark(image[left, midY])
        val midRightIsDark = isDark(image[right, midY])
        val topMidIsDark = isDark(image[midX, top])
        val botLeftIsDark = isDark(image[left, bot])
        val botRightIsDark = isDark(image[right, bot])

        var darkBG =
            (topLeftIsDark && (botLeftIsDark || botRightIsDark || topRightIsDark || midLeftIsDark || topMidIsDark)) ||
                (topRightIsDark && (botRightIsDark || botLeftIsDark || midRightIsDark || topMidIsDark))

        if (!isWhite(image[left, top]) && pixelIsClose(image[left, top], image[midX, top]) &&
            !isWhite(image[midX, top]) && pixelIsClose(image[midX, top], image[right, top]) &&
            !isWhite(image[right, top]) && pixelIsClose(image[right, top], image[right, bot]) &&
            !isWhite(image[right, bot]) && pixelIsClose(image[right, bot], image[midX, bot]) &&
            !isWhite(image[midX, bot]) && pixelIsClose(image[midX, bot], image[left, bot]) &&
            !isWhite(image[left, bot]) && pixelIsClose(image[left, bot], image[left, top])
        ) {
            return image[left, top].toDrawable()
        }

        if (isWhite(image[left, top]).toInt() +
            isWhite(image[right, top]).toInt() +
            isWhite(image[left, bot]).toInt() +
            isWhite(image[right, bot]).toInt() > 2
        ) {
            darkBG = false
        }

        var blackPixel =
            when {
                topLeftIsDark -> image[left, top]
                topRightIsDark -> image[right, top]
                botLeftIsDark -> image[left, bot]
                botRightIsDark -> image[right, bot]
                else -> backgroundColor
            }

        var overallWhitePixels = 0
        var overallBlackPixels = 0
        var topBlackStreak = 0
        var topWhiteStreak = 0
        var botBlackStreak = 0
        var botWhiteStreak = 0
        outer@ for (x in intArrayOf(left, right, left - offsetX, right + offsetX)) {
            var whitePixelsStreak = 0
            var whitePixels = 0
            var blackPixelsStreak = 0
            var blackPixels = 0
            var blackStreak = false
            var whiteStrak = false
            val notOffset = x == left || x == right
            for ((index, y) in (0 until image.height step image.height / 25).withIndex()) {
                val pixel = image[x, y]
                val pixelOff = image[x + (if (x < image.width / 2) -offsetX else offsetX), y]
                if (isWhite(pixel)) {
                    whitePixelsStreak++
                    whitePixels++
                    if (notOffset) {
                        overallWhitePixels++
                    }
                    if (whitePixelsStreak > 14) {
                        whiteStrak = true
                    }
                    if (whitePixelsStreak > 6 && whitePixelsStreak >= index - 1) {
                        topWhiteStreak = whitePixelsStreak
                    }
                } else {
                    whitePixelsStreak = 0
                    if (isDark(pixel) && isDark(pixelOff)) {
                        blackPixels++
                        if (notOffset) {
                            overallBlackPixels++
                        }
                        blackPixelsStreak++
                        if (blackPixelsStreak >= 14) {
                            blackStreak = true
                        }
                        continue
                    }
                }
                if (blackPixelsStreak > 6 && blackPixelsStreak >= index - 1) {
                    topBlackStreak = blackPixelsStreak
                }
                blackPixelsStreak = 0
            }
            if (blackPixelsStreak > 6) {
                botBlackStreak = blackPixelsStreak
            } else if (whitePixelsStreak > 6) {
                botWhiteStreak = whitePixelsStreak
            }
            when {
                blackPixels > 22 -> {
                    if (x == right || x == right + offsetX) {
                        blackPixel =
                            when {
                                topRightIsDark -> image[right, top]
                                botRightIsDark -> image[right, bot]
                                else -> blackPixel
                            }
                    }
                    darkBG = true
                    overallWhitePixels = 0
                    break@outer
                }
                blackStreak -> {
                    darkBG = true
                    if (x == right || x == right + offsetX) {
                        blackPixel =
                            when {
                                topRightIsDark -> image[right, top]
                                botRightIsDark -> image[right, bot]
                                else -> blackPixel
                            }
                    }
                    if (blackPixels > 18) {
                        overallWhitePixels = 0
                        break@outer
                    }
                }
                whiteStrak || whitePixels > 22 -> darkBG = false
            }
        }

        val topIsBlackStreak = topBlackStreak > topWhiteStreak
        val bottomIsBlackStreak = botBlackStreak > botWhiteStreak
        if (overallWhitePixels > 9 && overallWhitePixels > overallBlackPixels) {
            darkBG = false
        }
        if (topIsBlackStreak && bottomIsBlackStreak) {
            darkBG = true
        }
        if (darkBG) {
            return if (isWhite(image[left, bot]) && isWhite(image[right, bot])) {
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(blackPixel, blackPixel, backgroundColor, backgroundColor)
                )
            } else if (isWhite(image[left, top]) && isWhite(image[right, top])) {
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(backgroundColor, backgroundColor, blackPixel, blackPixel)
                )
            } else {
                blackPixel.toDrawable()
            }
        }
        if (topIsBlackStreak || (
            topLeftIsDark && topRightIsDark &&
                isDark(image[left - offsetX, top]) && isDark(image[right + offsetX, top]) &&
                (topMidIsDark || overallBlackPixels > 9)
            )
        ) {
            return GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(blackPixel, blackPixel, backgroundColor, backgroundColor)
            )
        } else if (bottomIsBlackStreak || (
            botLeftIsDark && botRightIsDark &&
                isDark(image[left - offsetX, bot]) && isDark(image[right + offsetX, bot]) &&
                (isDark(image[midX, bot]) || overallBlackPixels > 9)
            )
        ) {
            return GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(backgroundColor, backgroundColor, blackPixel, blackPixel)
            )
        }
        return backgroundColor.toDrawable()
    }

    private fun isDark(color: Int): Boolean {
        return Color.red(color) < 40 && Color.blue(color) < 40 && Color.green(color) < 40 &&
            Color.alpha(color) > 200
    }

    private fun isWhite(color: Int): Boolean {
        return Color.red(color) + Color.blue(color) + Color.green(color) > 740
    }

    private fun Boolean.toInt() = if (this) 1 else 0

    private fun pixelIsClose(
        color1: Int,
        color2: Int
    ): Boolean {
        return abs(Color.red(color1) - Color.red(color2)) < 30 &&
            abs(Color.green(color1) - Color.green(color2)) < 30 &&
            abs(Color.blue(color1) - Color.blue(color2)) < 30
    }
    // SY <--
}
