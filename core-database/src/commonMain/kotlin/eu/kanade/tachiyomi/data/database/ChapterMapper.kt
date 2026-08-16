package eu.kanade.tachiyomi.data.database

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.ChapterImpl

@Suppress("LongParameterList")
fun mapChapter(
    id: Long,
    mangaId: Long,
    url: String,
    name: String,
    scanlator: String?,
    read: Long,
    bookmark: Long,
    lastPageRead: Long,
    chapterNumber: Double,
    sourceOrder: Long,
    dateFetch: Long,
    dateUpload: Long,
    memo: ByteArray
): Chapter =
    ChapterImpl().also {
        it.id = id
        it.manga_id = mangaId
        it.url = url
        it.name = name
        it.scanlator = scanlator
        it.read = read == 1L
        it.bookmark = bookmark == 1L
        it.last_page_read = lastPageRead.toInt()
        it.chapter_number = chapterNumber.toFloat()
        it.source_order = sourceOrder.toInt()
        it.date_fetch = dateFetch
        it.date_upload = dateUpload
        it.memo = memoColumnAdapter.decode(memo)
    }
