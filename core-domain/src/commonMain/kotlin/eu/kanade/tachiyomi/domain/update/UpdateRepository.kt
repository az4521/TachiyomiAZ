package eu.kanade.tachiyomi.domain.update

import eu.kanade.tachiyomi.data.database.DatabaseHandler
import eu.kanade.tachiyomi.data.database.models.MangaChapter

/** Shared query for the chapter rows that form the recent-updates feed. */
class UpdateRepository(private val db: DatabaseHandler) {
    fun recentChapters(since: Long): List<MangaChapter> = db.getRecentChapters(since)
}
