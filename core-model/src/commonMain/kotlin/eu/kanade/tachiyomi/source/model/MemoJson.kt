package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val memoBridgeJson = Json { ignoreUnknownKeys = true }

/** Sets extension-owned manga metadata from its JSON wire representation. */
fun setMangaMemoJson(manga: SManga, memoJson: String?) {
    manga.memo = parseMemoJson(memoJson)
}

/** Sets extension-owned chapter metadata from its JSON wire representation. */
fun setChapterMemoJson(chapter: SChapter, memoJson: String?) {
    chapter.memo = parseMemoJson(memoJson)
}

private fun parseMemoJson(value: String?): JsonObject {
    if (value.isNullOrBlank()) return JsonObject(emptyMap())
    return runCatching { memoBridgeJson.parseToJsonElement(value).jsonObject }
        .getOrDefault(JsonObject(emptyMap()))
}
