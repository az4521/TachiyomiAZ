package eu.kanade.tachiyomi.data.database

import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.HistoryImpl

fun mapHistory(
    id: Long,
    chapterId: Long,
    lastRead: Long?,
    timeRead: Long?
): History =
    HistoryImpl().also {
        it.id = id
        it.chapter_id = chapterId
        it.last_read = lastRead ?: 0
        it.time_read = timeRead ?: 0
    }
