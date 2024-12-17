package eu.kanade.tachiyomi

import eu.kanade.tachiyomi.util.system.ImageUtil

/**
 * Used by extensions.
 *
 * @since extension-lib 1.3
 */
object AppInfo {
    fun getVersionCode() = BuildConfig.VERSION_CODE

    fun getVersionName() = BuildConfig.VERSION_NAME

    fun getSupportedImageMimeTypes() = ImageUtil.ImageType.entries.map { it.mime }
}
