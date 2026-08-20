package eu.kanade.tachiyomi.extension.api

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader

/**
 * Maps a new-store extension list to this fork's [Extension.Available], keeping only extensions
 * whose lib version this build supports. [repoUrl] is carried through only for the repo badge;
 * apk/icon are already absolute URLs in the new format (see getApkUrl's absolute-URL path).
 */
fun NetworkExtensionStore.ExtensionList.toAvailableExtensions(repoUrl: String): List<Extension.Available> {
    return extensions.mapNotNull { extension ->
        val libVersion = extension.extensionLib.toDoubleOrNull() ?: return@mapNotNull null
        if (libVersion < ExtensionLoader.LIB_VERSION_MIN || libVersion > ExtensionLoader.LIB_VERSION_MAX) {
            return@mapNotNull null
        }

        val langs = extension.sources.map { it.language }.toSet()
        Extension.Available(
            name = extension.name.substringAfter("Tachiyomi: "),
            pkgName = extension.packageName,
            versionName = extension.versionName,
            versionCode = extension.versionCode.toInt(),
            libVersion = libVersion,
            lang = if (langs.size == 1) langs.first() else "all",
            isNsfw = extension.contentWarning >= NetworkExtensionStore.ContentWarning.MIXED,
            apkName = extension.resources.apkUrl,
            iconUrl = extension.resources.iconUrl,
            repoUrl = repoUrl
        )
    }
}
