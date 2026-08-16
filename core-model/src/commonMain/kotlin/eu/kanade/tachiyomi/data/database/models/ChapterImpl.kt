package eu.kanade.tachiyomi.data.database.models

import kotlinx.serialization.json.JsonObject

class ChapterImpl : Chapter {
    override var id: Long? = null

    override var manga_id: Long? = null

    override lateinit var url: String

    override lateinit var name: String

    override var scanlator: String? = null

    override var read: Boolean = false

    override var bookmark: Boolean = false

    override var last_page_read: Int = 0

    override var date_fetch: Long = 0

    override var date_upload: Long = 0

    override var chapter_number: Float = 0f

    override var source_order: Int = 0

    override var memo: JsonObject = JsonObject(emptyMap())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        // this::class rather than javaClass: the same exact-class check, but available
        // off the JVM. `is` would not do -- LibraryManga extends MangaImpl, so a subclass would
        // start comparing equal to its parent.
        if (other == null || this::class != other::class) return false

        val chapter = other as Chapter
        if (url != chapter.url) return false
        return id == chapter.id
    }

    override fun hashCode(): Int {
        return url.hashCode() + id.hashCode()
    }
}
