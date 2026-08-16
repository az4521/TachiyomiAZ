package eu.kanade.tachiyomi.domain.backup

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga

/**
 * Merges the chapters from a backup onto the ones already stored for [manga].
 *
 * Sharing the backup wire format only guarantees both apps *parse* a backup the same way. This is
 * what decides what actually lands in the database, and it is where read state survives a restore:
 * chapters are matched by url, the existing row id is carried over so the write updates rather
 * than duplicates, and progress is merged rather than overwritten. Two platforms disagreeing here
 * would show up as lost read state after restoring, which reads as data loss.
 *
 * Mutates [backupChapters] in place, matching how the restore path already worked.
 *
 * The merge is deliberately conservative in one direction: an existing read chapter is never
 * un-read by a backup that has it unread, and an existing page position is never reset to zero.
 * A backup is a snapshot of one point in time, so the device may legitimately be further along.
 *
 * @param dbChapters the chapters currently stored for this manga.
 */
fun mergeBackupChapters(
    manga: Manga,
    backupChapters: List<Chapter>,
    dbChapters: List<Chapter>
) {
    backupChapters.forEach { chapter ->
        val dbChapter = dbChapters.firstOrNull { it.url == chapter.url }
        if (dbChapter != null) {
            chapter.id = dbChapter.id
            chapter.copyFrom(dbChapter)

            if (dbChapter.read && !chapter.read) {
                // Already read on this device; keep that, and the position it was read to.
                chapter.read = dbChapter.read
                chapter.last_page_read = dbChapter.last_page_read
            } else if (chapter.last_page_read == 0 && dbChapter.last_page_read != 0) {
                // The backup has no position but this device does; do not rewind the reader.
                chapter.last_page_read = dbChapter.last_page_read
            }

            if (!chapter.bookmark && dbChapter.bookmark) {
                chapter.bookmark = dbChapter.bookmark
            }
        }

        chapter.manga_id = manga.id
    }
}
