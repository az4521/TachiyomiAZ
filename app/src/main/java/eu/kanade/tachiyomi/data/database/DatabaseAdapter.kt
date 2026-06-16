package eu.kanade.tachiyomi.data.database

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Date

val dateAdapter =
    object : ColumnAdapter<Date, Long> {
        override fun decode(databaseValue: Long): Date = Date(databaseValue)

        override fun encode(value: Date): Long = value.time
    }

private const val listOfStringsSeparator = ", "
val listOfStringsAdapter =
    object : ColumnAdapter<List<String>, String> {
        override fun decode(databaseValue: String) =
            if (databaseValue.isEmpty()) {
                emptyList()
            } else {
                databaseValue.split(listOfStringsSeparator)
            }

        override fun encode(value: List<String>) = value.joinToString(separator = listOfStringsSeparator)
    }

val updateStrategyAdapter =
    object : ColumnAdapter<UpdateStrategy, Int> {
        private val enumValues by lazy { UpdateStrategy.values() }

        override fun decode(databaseValue: Int): UpdateStrategy = enumValues.getOrElse(databaseValue) { UpdateStrategy.ALWAYS_UPDATE }

        override fun encode(value: UpdateStrategy): Int = value.ordinal
    }

private val memoJson = Json { ignoreUnknownKeys = true }

/**
 * Stores a [JsonObject] memo as its serialized JSON text. An empty object is stored as an
 * empty string so existing rows (migrated with a default of '') decode cleanly.
 */
val memoColumnAdapter =
    object : ColumnAdapter<JsonObject, String> {
        override fun decode(databaseValue: String): JsonObject =
            if (databaseValue.isBlank()) {
                JsonObject(emptyMap())
            } else {
                runCatching { memoJson.parseToJsonElement(databaseValue).jsonObject }
                    .getOrDefault(JsonObject(emptyMap()))
            }

        override fun encode(value: JsonObject): String =
            if (value.isEmpty()) "" else memoJson.encodeToString(JsonObject.serializer(), value)
    }

interface ColumnAdapter<T : Any, S> {
    /**
     * @return [databaseValue] decoded as type [T].
     */
    fun decode(databaseValue: S): T

    /**
     * @return [value] encoded as database type [S].
     */
    fun encode(value: T): S
}
