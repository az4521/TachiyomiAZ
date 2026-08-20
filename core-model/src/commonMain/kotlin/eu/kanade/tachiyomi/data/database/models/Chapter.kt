package eu.kanade.tachiyomi.data.database.models

import eu.kanade.tachiyomi.source.model.JavaSerializable
import eu.kanade.tachiyomi.source.model.SChapter

interface Chapter : SChapter, JavaSerializable {
    var id: Long?

    var manga_id: Long?

    var read: Boolean

    var bookmark: Boolean

    var last_page_read: Int

    var date_fetch: Long

    var source_order: Int

    /** The extension-owned memo in its wire format, for platform bridges such as the iOS JVM host. */
    val memoJson: String
        get() = memo.toString()

    val isRecognizedNumber: Boolean
        get() = chapter_number >= 0f

    companion object {
        fun create(): Chapter =
            ChapterImpl().apply {
                chapter_number = -1f
            }
    }
}
