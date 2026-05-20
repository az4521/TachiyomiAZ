package eu.kanade.tachiyomi.data.backup.legacy

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.AbstractBackupRestoreValidator
import eu.kanade.tachiyomi.data.backup.legacy.models.Backup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LegacyBackupRestoreValidator : AbstractBackupRestoreValidator() {
    /**
     * Checks for critical backup file data.
     *
     * @throws Exception if version or manga cannot be found.
     * @return List of missing sources or missing trackers.
     */
    override fun validate(
        context: Context,
        uri: Uri
    ): Results {
        val json = context.contentResolver.openInputStream(uri)!!.bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }

        val version = json[Backup.VERSION]
        val mangasJson = json[Backup.MANGAS]
        if (version == null || mangasJson == null) {
            throw Exception(context.getString(R.string.invalid_backup_file_missing_data))
        }

        val mangas = mangasJson.jsonArray
        if (mangas.size == 0) {
            throw Exception(context.getString(R.string.invalid_backup_file_missing_manga))
        }

        val sources = getSourceMapping(json)
        val missingSources =
            sources
                .filter { sourceManager.get(it.key) == null }
                .values
                .sorted()

        val trackers =
            mangas
                .filter { it.jsonObject.containsKey("track") }
                .flatMap { it.jsonObject["track"]!!.jsonArray }
                .map { it.jsonObject["s"]!!.jsonPrimitive.int }
                .distinct()
        val missingTrackers =
            trackers
                .mapNotNull { trackManager.getService(it) }
                .filter { !it.isLogged }
                .map { it.name }
                .sorted()

        return Results(missingSources, missingTrackers)
    }

    companion object {
        fun getSourceMapping(json: JsonObject): Map<Long, String> {
            val extensionsMapping = json[Backup.EXTENSIONS] ?: return emptyMap()

            return extensionsMapping.jsonArray
                .map {
                    val items = it.jsonPrimitive.content.split(":")
                    items[0].toLong() to items[1]
                }
                .toMap()
        }
    }
}
