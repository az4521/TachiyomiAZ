package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject

class SChapterImpl : SChapter {
    override lateinit var url: String

    override lateinit var name: String

    override var volume: String? = null

    override var date_upload: Long = 0

    override var chapter_number: Float = -1f

    override var scanlator: String? = null

    override var note: String? = null

    override var memo: JsonObject = JsonObject(emptyMap())

    /**
     * JavaBean ABI alias used by extensions that normalize Kotlin's legacy
     * `chapter_number` property to `chapterNumber` before resolving accessors.
     *
     * Keep this alongside the generated `setChapter_number(float)` method: released
     * extensions exist against both spellings.
     */
    fun setChapterNumber(value: Float) {
        chapter_number = value
    }

    fun getChapterNumber(): Float = chapter_number

    fun setDateUpload(value: Long) {
        date_upload = value
    }

    fun getDateUpload(): Long = date_upload
}
