package eu.kanade.tachiyomi.domain.backup

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga

/**
 * What a backup restore should do to the stored chapters.
 *
 * @param toUpdate chapters that matched an existing row and carry its id.
 * @param toInsert chapters the database has never seen.
 */
class BackupChapterMerge(
    val toUpdate: List<Chapter>,
    val toInsert: List<Chapter>
)

/**
 * Merges the chapters from a backup onto the ones already stored for [manga].
 *
 * Sharing the backup wire format only guarantees both apps *parse* a backup the same way. This
 * decides what actually lands in the database, and it is where read state survives a restore, so
 * the two platforms disagreeing would show up as lost progress.
 *
 * Follows Mihon's MangaRestorer.restoreChapters: match by url, skip chapters whose state is
 * already identical, and split the rest into updates and inserts. Splitting is what stops a
 * chapter the database has never seen being silently dropped -- passing it to an update is a
 * no-op, because the update writes by id and it does not have one yet.
 *
 * The merge is deliberately conservative in one direction: an existing read chapter is never
 * un-read by a backup that has it unread, and an existing page position is never reset to zero. A
 * backup is a snapshot of one moment, so the device may legitimately be further along.
 *
 * Mutates the chapters in [backupChapters], matching how the restore path already worked.
 */
fun mergeBackupChapters(
    manga: Manga,
    backupChapters: List<Chapter>,
    dbChapters: List<Chapter>
): BackupChapterMerge {
    val dbChaptersByUrl = dbChapters.associateBy { it.url }

    val toUpdate = mutableListOf<Chapter>()
    val toInsert = mutableListOf<Chapter>()

    backupChapters.forEach { chapter ->
        chapter.manga_id = manga.id

        val dbChapter = dbChaptersByUrl[chapter.url]
        if (dbChapter == null) {
            toInsert += chapter
            return@forEach
        }

        // Nothing to write if the backup agrees with what is stored.
        if (chapter.hasSameStateAs(dbChapter)) {
            return@forEach
        }

        chapter.id = dbChapter.id
        chapter.copyFrom(dbChapter)

        // A bookmark on either side survives.
        chapter.bookmark = chapter.bookmark || dbChapter.bookmark

        if (dbChapter.read && !chapter.read) {
            // Already read on this device; keep that, and the position it was read to.
            chapter.read = true
            chapter.last_page_read = dbChapter.last_page_read
        } else if (chapter.last_page_read == 0 && dbChapter.last_page_read != 0) {
            // The backup has no position but this device does; do not rewind the reader.
            chapter.last_page_read = dbChapter.last_page_read
        }

        toUpdate += chapter
    }

    return BackupChapterMerge(toUpdate, toInsert)
}

/**
 * Whether a backup chapter is already stored exactly as it is.
 *
 * Ignores the fields Mihon's `forComparison` ignores -- identity and fetch/upload timestamps --
 * because they differ routinely between two devices without meaning the chapter changed.
 */
private fun Chapter.hasSameStateAs(other: Chapter): Boolean =
    name == other.name &&
        scanlator == other.scanlator &&
        chapter_number == other.chapter_number &&
        source_order == other.source_order &&
        read == other.read &&
        bookmark == other.bookmark &&
        last_page_read == other.last_page_read &&
        memo == other.memo
