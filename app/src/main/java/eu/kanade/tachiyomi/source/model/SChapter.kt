package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import java.io.Serializable

interface SChapter : Serializable {
    var url: String

    var name: String

    /**
     * Volume number in string format (e.g. "1", "1.5", "1a"), or null if unnumbered.
     *
     * @since tachiyomix 1.6
     */
    var volume: String?
        get() = null
        set(_) {}

    var chapter_number: Float

    /**
     * Chapter number in string format (e.g. "1", "1.5", "1a"), or null if unnumbered.
     * Bridges to [chapter_number] so existing storage and sources that only set
     * [chapter_number] keep working.
     *
     * @since tachiyomix 1.6
     */
    var number: String?
        get() = chapter_number.takeIf { it >= 0f }?.let {
            if (it == it.toLong().toFloat()) it.toLong().toString() else it.toString()
        }
        set(value) { chapter_number = value?.toFloatOrNull() ?: -1f }

    var scanlator: String?

    /**
     * Chapter scanlators in list form. Bridges to [scanlator] (comma separated).
     *
     * @since tachiyomix 1.6
     */
    var scanlators: List<String>
        get() = scanlator?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        set(value) { scanlator = value.joinToString(", ").ifBlank { null } }

    var date_upload: Long

    /**
     * Optional free-form note associated with the chapter (e.g. availability date or lock status).
     *
     * @since tachiyomix 1.6
     */
    var note: String?
        get() = null
        set(_) {}

    /**
     * Extra metadata associated with the chapter. Not visible to users; intended for
     * internal or source-specific purposes (apps may use namespaced keys like "mihon.*").
     *
     * @since tachiyomix 1.6
     */
    var memo: JsonObject
        get() = JsonObject(emptyMap())
        set(_) {}

    fun copyFrom(other: SChapter) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        chapter_number = other.chapter_number
        scanlator = other.scanlator
        if (other.volume != null) {
            volume = other.volume
        }
        if (other.note != null) {
            note = other.note
        }
    }

    companion object {
        fun create(): SChapter {
            return SChapterImpl()
        }
    }
}
