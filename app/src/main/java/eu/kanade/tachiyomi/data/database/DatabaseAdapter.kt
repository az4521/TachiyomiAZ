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
 * Serialized empty JSON object ("{}"). Used as the default memo value so stored rows and backups
 * always contain a valid JSON object and older backups without a memo decode cleanly.
 */
val jsonObjectEmptyBytes = byteArrayOf(0x7B, 0x7D)

/**
 * Stores a [JsonObject] memo as UTF-8 encoded JSON bytes. Empty or invalid input decodes to an
 * empty object, and an empty object encodes to "{}" ([jsonObjectEmptyBytes]).
 */
val memoColumnAdapter =
    object : ColumnAdapter<JsonObject, ByteArray> {
        override fun decode(databaseValue: ByteArray): JsonObject =
            if (databaseValue.isEmpty()) {
                JsonObject(emptyMap())
            } else {
                runCatching { memoJson.parseToJsonElement(databaseValue.decodeToString()).jsonObject }
                    .getOrDefault(JsonObject(emptyMap()))
            }

        override fun encode(value: JsonObject): ByteArray =
            if (value.isEmpty()) {
                jsonObjectEmptyBytes
            } else {
                memoJson.encodeToString(JsonObject.serializer(), value).encodeToByteArray()
            }
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
