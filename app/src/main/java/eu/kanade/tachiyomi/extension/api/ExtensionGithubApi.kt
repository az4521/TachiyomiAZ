package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import exh.source.BlacklistedSources
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.protobuf.ProtoBuf
import okio.BufferedSource
import okio.buffer
import okio.gzip
import uy.kohesive.injekt.injectLazy
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException

internal class ExtensionGithubApi {
    private val preferences: PreferencesHelper by injectLazy()
    private val network: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()
    private val protoBuf: ProtoBuf = ProtoBuf { }

    suspend fun findExtensions(): List<Extension.Available> {
        // repo entry -> the index_v2 URL it migrated to, recorded only on a successful fetch.
        val migrations = mutableMapOf<String, String>()

        val extensions = preferences.extensionRepos().get().flatMap { repo ->
            try {
                fetchStore(indexUrlFor(repo)) { migrations[repo] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }

        if (migrations.isNotEmpty()) {
            persistRepoMigrations(migrations)
        }

        return extensions
    }

    /**
     * Rewrites migrated repo entries to their new index_v2 URLs so later refreshes hit the new
     * store directly instead of re-discovering the migration via repo.json. Re-reads the current
     * set and only touches entries still present, to avoid clobbering edits made meanwhile.
     */
    private fun persistRepoMigrations(migrations: Map<String, String>) {
        val current = preferences.extensionRepos().get()
        val updated = current.map { migrations[it] ?: it }.toSet()
        if (updated != current) {
            preferences.extensionRepos().set(updated)
        }
    }

    suspend fun checkForUpdates(context: Context): List<Extension.Installed> {
        val extensions = findExtensions()

        preferences.lastExtCheck().set(Date().time)

        // SY -->
        val blacklistEnabled = preferences.eh_enableSourceBlacklist().get()
        // SY <--

        val installedExtensions =
            ExtensionLoader.loadExtensions(context)
                .filterIsInstance<LoadResult.Success>()
                .map { it.extension }
                // SY -->
                .filterNot { it.isBlacklisted(blacklistEnabled) }
        // SY <--

        val extensionsWithUpdate = mutableListOf<Extension.Installed>()
        for (installedExt in installedExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue

            val hasUpdate = availableExt.versionCode > installedExt.versionCode || availableExt.libVersion > installedExt.libVersion
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        return extensionsWithUpdate
    }

    /**
     * Resolves a configured repo entry to the URL of its index file. A `username/repo` shorthand
     * maps to the GitHub raw legacy index; anything that already looks like a URL is used as-is
     * (may point at index.min.json, repo.json, or a newer index.pb / index.json).
     */
    private fun indexUrlFor(repo: String): String {
        return if ("://" in repo) repo else "$BASE_URL$repo/repo/index.min.json"
    }

    /**
     * Fetches and parses a repo index, transparently handling gzip, the legacy JSON array, the
     * legacy repo.json (following its index_v2 migration pointer), and the newer JSON/protobuf
     * extension store. [forceV2] guards against migration loops once we've followed index_v2.
     */
    private suspend fun fetchStore(
        indexUrl: String,
        forceV2: Boolean = false,
        onMigrated: (String) -> Unit
    ): List<Extension.Available> {
        val response = network.client.newCall(GET(indexUrl)).awaitSuccess()
        return response.body.source().decompressIfGzipped().use { source ->
            when (source.peek().readByte()) {
                // "[...": legacy flat array of extensions.
                '['.code.toByte() -> {
                    val migrated = if (!forceV2) migrateFromRepoJson(indexUrl, onMigrated) else null
                    migrated ?: parseResponse(json.decodeFromBufferedSource(source), indexUrl)
                }
                // "{...": either a legacy repo.json (meta + optional index_v2) or a new JSON store.
                '{'.code.toByte() -> {
                    val legacy = runCatching {
                        json.decodeFromBufferedSource<NetworkLegacyExtensionRepo>(source.peek())
                    }.getOrNull()
                    when {
                        legacy?.indexV2 != null ->
                            fetchStore(legacy.indexV2, forceV2 = true, onMigrated).also { onMigrated(legacy.indexV2) }
                        legacy != null -> legacyListFromRepoJson(indexUrl)
                        else -> extensionsFromStore(json.decodeFromBufferedSource(source), indexUrl)
                    }
                }
                // protobuf store
                else -> extensionsFromStore(protoBuf.decodeFromByteArray(source.readByteArray()), indexUrl)
            }
        }
    }

    /**
     * For a legacy array index, checks the sibling repo.json for an index_v2 migration pointer.
     * Returns the migrated store's extensions (and reports the new URL via [onMigrated] once the
     * fetch succeeds), or null to fall back to parsing the array.
     */
    private suspend fun migrateFromRepoJson(
        indexUrl: String,
        onMigrated: (String) -> Unit
    ): List<Extension.Available>? {
        if (!indexUrl.endsWith("/index.min.json")) return null
        val repoJsonUrl = indexUrl.removeSuffix("index.min.json") + "repo.json"
        return try {
            val repo = network.client.newCall(GET(repoJsonUrl)).awaitSuccess().body.source().use {
                json.decodeFromBufferedSource<NetworkLegacyExtensionRepo>(it)
            }
            repo.indexV2?.let { v2 -> fetchStore(v2, forceV2 = true, onMigrated).also { onMigrated(v2) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** A repo.json object with no index_v2: its extensions still live in the sibling index.min.json. */
    private suspend fun legacyListFromRepoJson(repoJsonUrl: String): List<Extension.Available> {
        val indexUrl = repoJsonUrl.substringBeforeLast("repo.json") + "index.min.json"
        val array = network.client.newCall(GET(indexUrl)).awaitSuccess().body.source().use {
            json.decodeFromBufferedSource<JsonArray>(it)
        }
        return parseResponse(array, indexUrl)
    }

    private suspend fun extensionsFromStore(
        store: NetworkExtensionStore,
        indexUrl: String
    ): List<Extension.Available> {
        val list = store.extensionListUrl?.let { fetchExtensionList(it) }
            ?: store.extensionList
            ?: return emptyList()
        return list.toAvailableExtensions(indexUrl)
    }

    private suspend fun fetchExtensionList(url: String): NetworkExtensionStore.ExtensionList {
        val response = network.client.newCall(GET(url)).awaitSuccess()
        return response.body.source().decompressIfGzipped().use { source ->
            when (source.peek().readByte()) {
                '{'.code.toByte() -> json.decodeFromBufferedSource(source)
                else -> protoBuf.decodeFromByteArray(source.readByteArray())
            }
        }
    }

    private fun parseResponse(
        json: JsonArray,
        repoUrl: String
    ): List<Extension.Available> {
        return json
            .filter { element ->
                val versionName = element.jsonObject["version"]!!.jsonPrimitive.content
                val libVersion = versionName.substringBeforeLast('.').toDouble()
                libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX
            }
            .map { element ->
                val name = element.jsonObject["name"]!!.jsonPrimitive.content.substringAfter("Tachiyomi: ")
                val pkgName = element.jsonObject["pkg"]!!.jsonPrimitive.content
                val apkName = element.jsonObject["apk"]!!.jsonPrimitive.content
                val versionName = element.jsonObject["version"]!!.jsonPrimitive.content
                val versionCode = element.jsonObject["code"]!!.jsonPrimitive.int
                val libVersion = versionName.substringBeforeLast('.').toDouble()
                val lang = element.jsonObject["lang"]!!.jsonPrimitive.content
                val nsfw = element.jsonObject["nsfw"]!!.jsonPrimitive.int == 1
                // SY -->
                val icon = "${repoUrl.substringBeforeLast("index.min.json")}icon/$pkgName.png"
                // SY <--

                Extension.Available(name, pkgName, versionName, versionCode, libVersion, lang, nsfw, apkName, icon /* SY --> */, repoUrl /* SY <-- */)
            }
    }

    private fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzip = peek().use { peeked ->
            try {
                peeked.readShort().toInt() == 0x1f8b
            } catch (_: Exception) {
                false
            }
        }
        return if (isGzip) gzip().buffer() else this
    }

    fun getApkUrl(extension: Extension.Available): String {
        val apkName = extension.apkName
        // New-store extensions already carry an absolute apk URL; legacy ones are relative to the repo.
        return if ("://" in apkName) {
            apkName
        } else {
            "${extension.repoUrl.substringBeforeLast("index.min.json")}apk/$apkName"
        }
    }

    fun getIconUrl(extension: Extension.Available): String {
        return "${extension.repoUrl.substringBeforeLast("index.min.json")}icon/${extension.pkgName}.png"
    }

    private fun Extension.isBlacklisted(blacklistEnabled: Boolean = preferences.eh_enableSourceBlacklist().get()): Boolean {
        return pkgName in BlacklistedSources.BLACKLISTED_EXTENSIONS && blacklistEnabled
    }

    companion object {
        const val BASE_URL = "https://raw.githubusercontent.com/"
    }
}
