package eu.kanade.tachiyomi.data.updater.github

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.updater.Release
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Release object.
 * Contains information about the latest release from GitHub.
 *
 * @param version version of latest release.
 * @param info log of latest release.
 * @param assets assets of latest release.
 */
@Serializable
class GithubRelease(
    @SerialName("tag_name") val version: String,
    @SerialName("body") override val info: String,
    @SerialName("assets") private val assets: List<Assets>
) : Release {
    /**
     * Get the download link for the APK matching this build's variant.
     *
     * A release ships two APKs (android5 = minSdk 21, android7 = minSdk 24). Pick the
     * one whose file name contains this build's [BuildConfig.APK_VARIANT] so an install
     * of the Android 5/6 build doesn't pull the Android 7+ APK (which it can't install)
     * and vice versa. Falls back to the first asset if no match is found.
     */
    override val downloadLink: String
        get() {
            val variant = BuildConfig.APK_VARIANT
            return assets.firstOrNull { it.name.contains(variant, ignoreCase = true) }?.downloadLink
                ?: assets.first().downloadLink
        }

    /**
     * Assets class containing download url.
     * @param name file name of the asset.
     * @param downloadLink download url.
     */
}

@Serializable
class Assets(
    @SerialName("name") val name: String = "",
    @SerialName("browser_download_url") val downloadLink: String
)
