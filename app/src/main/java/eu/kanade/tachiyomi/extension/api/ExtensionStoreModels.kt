package eu.kanade.tachiyomi.extension.api

import android.annotation.SuppressLint
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Newer "extension store" index. Served as gzipped protobuf (`index.pb`) or as JSON, either
 * inline in the index or split into a separate extension list (see [extensionListUrl]).
 * Ported from Mihon; only the fields this fork consumes are mapped into [Extension.Available].
 */
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class NetworkExtensionStore(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val badgeLabel: String,
    @ProtoNumber(3) val signingKey: String,
    @ProtoNumber(4) val contact: Contact,
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null
) {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String,
        @ProtoNumber(2) val discord: String? = null
    )

    @Serializable
    data class ExtensionList(@ProtoNumber(1) val extensions: List<Extension>)

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val packageName: String,
        @ProtoNumber(3) val resources: Resources,
        @ProtoNumber(4) val extensionLib: String,
        @ProtoNumber(5) val versionCode: Long,
        @ProtoNumber(6) val versionName: String,
        @ProtoNumber(7) val contentWarning: ContentWarning,
        @ProtoNumber(8) val sources: List<Source>
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String,
        @ProtoNumber(2) val iconUrl: String
    )

    @Serializable
    data class Source(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val language: String,
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
        @ProtoNumber(7) val message: String? = null
    )

    @Suppress("Unused")
    enum class ContentWarning {
        @ProtoNumber(0)
        @JsonNames("CONTENT_WARNING_UNSPECIFIED")
        UNSPECIFIED,

        @ProtoNumber(1)
        @JsonNames("CONTENT_WARNING_SAFE")
        SAFE,

        @ProtoNumber(2)
        @JsonNames("CONTENT_WARNING_MIXED")
        MIXED,

        @ProtoNumber(3)
        @JsonNames("CONTENT_WARNING_NSFW")
        NSFW
    }
}

/**
 * Legacy `repo.json`: repo metadata plus an optional [indexV2] pointer used to auto-migrate a
 * legacy repo onto its newer store index.
 */
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class NetworkLegacyExtensionRepo(
    @SerialName("index_v2") val indexV2: String? = null,
    val meta: Meta
) {
    @Serializable
    data class Meta(
        val name: String,
        val shortName: String? = null,
        val website: String,
        val signingKeyFingerprint: String
    )
}

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
