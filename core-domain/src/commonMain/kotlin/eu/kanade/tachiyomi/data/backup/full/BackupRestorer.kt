package eu.kanade.tachiyomi.data.backup.full

import eu.kanade.tachiyomi.data.backup.full.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.full.models.Backup
import eu.kanade.tachiyomi.data.backup.full.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.full.models.BackupManga
import eu.kanade.tachiyomi.data.database.DatabaseHandler
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.domain.backup.mergeBackupCategories
import eu.kanade.tachiyomi.domain.backup.mergeBackupChapters
import eu.kanade.tachiyomi.domain.backup.mergeBackupManga
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.math.max

/**
 * Puts a backup's contents back into the database.
 *
 * The counterpart to [BackupBuilder], and shared for the same reason. Restoring is where a backup
 * either keeps what the reader had or quietly loses it, and each app used to decide that for
 * itself.
 *
 * The divergences that produced are the argument for this file existing. Restoring a title's
 * categories is the clearest: membership travels as a category's *order*, and resolving it takes
 * two hops -- the order names a category in the backup, and that category's name finds the one in
 * this database. Going straight from the backup's order to a stored category with the same order
 * looks equivalent and is not, because restoring appends categories the database did not have and
 * gives them fresh orders. The two numberings agree until the first restore that adds a category,
 * and after that every title lands in the wrong one. Nothing about either implementation looks
 * wrong on its own; they only disagree with each other.
 *
 * This is the Android implementation, moved. Where the two disagreed that one is what survived, so
 * a difference here is the iOS app being brought into line rather than a new behaviour for both.
 *
 * What stays with the caller: deciding whether a title's source is installed, fetching details for
 * new entries, and reporting progress. Those need the network and the extension list.
 */
@OptIn(ExperimentalSerializationApi::class)
object BackupRestorer {
    /** Result of a complete no-network restore. */
    data class RestoreReport(
        val restoredSourceIds: List<Long>,
        val skippedSourceIds: List<Long>
    ) {
        val restoredCount: Int get() = restoredSourceIds.size
    }

    /**
     * Restores a complete backup without consulting a source.
     *
     * File access, source downloads and UI progress stay with the platform. The ordering and the
     * transaction do not: categories must exist before membership is restored, and a partially
     * written backup must never be committed. This used to be a second orchestration loop in
     * Swift around the shared per-entry helpers below.
     */
    fun restoreBackupNoFetch(
        db: DatabaseHandler,
        backup: Backup,
        canRestoreSource: (Long) -> Boolean = { true },
        isLogged: (Int) -> Boolean = { true },
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): RestoreReport {
        val restored = mutableListOf<Long>()
        val skipped = mutableListOf<Long>()
        val total = backup.backupManga.size * 2
        var completed = 0

        db.inTransaction {
            restoreCategories(db, backup.backupCategories)

            for (entry in backup.backupManga) {
                if (!canRestoreSource(entry.source)) {
                    skipped += entry.source
                    completed += 2
                    onProgress(completed, total)
                    continue
                }

                val manga = entry.getMangaImpl()
                val stored = db.getManga(manga.url, manga.source) ?: Manga.create(manga.url, manga.title, manga.source)
                restoreMangaNoFetch(db, manga, stored)
                completed++
                onProgress(completed, total)

                val written = db.getManga(manga.url, manga.source)
                if (written == null) {
                    skipped += entry.source
                    completed++
                    onProgress(completed, total)
                    continue
                }

                restoreRelated(db, entry, written, backup.backupCategories, isLogged)
                restored += entry.source
                completed++
                onProgress(completed, total)
            }
        }

        return RestoreReport(restored, skipped)
    }

    /**
     * Adds the categories the backup has and this database does not.
     *
     * Matching is by name, through `mergeBackupCategories`: ids are assigned per database, so the
     * same "Reading" category has a different id on every device and matching on id would
     * duplicate every category on every restore.
     *
     * A restored category keeps the order it had on the other device -- `getCategoryImpl` carries
     * it out of the backup and nothing here overwrites it. That matters beyond ordering: it is the
     * number [restoreCategoriesForManga] resolves membership through.
     */
    fun restoreCategories(
        db: DatabaseHandler,
        backupCategories: List<BackupCategory>
    ) {
        val merged =
            mergeBackupCategories(
                backupCategories.map { it.getCategoryImpl() },
                db.getCategories()
            )

        // Matched ones already carry the stored id, so only the new ones need writing.
        // insertCategory assigns the generated id back onto the category.
        merged.toInsert.forEach { db.insertCategory(it) }
    }

    /**
     * Files [manga] under the categories the backup had it in.
     *
     * @param categories the entry's `categories`, which are orders within [backupCategories].
     */
    fun restoreCategoriesForManga(
        db: DatabaseHandler,
        manga: Manga,
        categories: List<Int>,
        backupCategories: List<BackupCategory>
    ) {
        if (categories.isEmpty()) return

        val nameByOrder = backupCategories.associateBy({ it.order }, { it.name })
        val storedByName = db.getCategories().associateBy { it.name }

        val links =
            categories
                .mapNotNull { nameByOrder[it] }
                .mapNotNull { storedByName[it] }
                .map { MangaCategory.create(manga, it) }
        if (links.isEmpty()) return

        db.deleteOldMangasCategories(listOf(manga))
        db.insertMangasCategories(links)
    }

    /**
     * Restores when each chapter was last read.
     *
     * Keyed by chapter url, because ids do not survive the trip. The later timestamp wins: the
     * backup may be older than what this device already has.
     *
     * `readDuration` is carried in the file but deliberately not written back. Doing so would
     * change what a restore does on Android, which is the behaviour this is a copy of, and read
     * time is accumulated locally rather than being progress the reader would notice losing. It is
     * a gap to close on purpose, not as a side effect of moving this here.
     */
    fun restoreHistoryForManga(
        db: DatabaseHandler,
        history: List<BackupHistory>
    ) {
        val updates = mutableListOf<History>()
        for (record in history) {
            val stored = db.getHistoryByChapterUrl(record.url)
            if (stored != null) {
                stored.last_read = max(record.lastRead, stored.last_read)
                updates.add(stored)
            } else {
                val chapter = db.getChapter(record.url) ?: continue
                updates.add(History.create(chapter).apply { last_read = record.lastRead })
            }
        }

        db.updateHistoryLastRead(updates)
    }

    /**
     * Writes a backup entry's manga row, merged onto the stored one if there is one.
     *
     * The no-fetch path: nothing here asks the source for anything, so it is the whole of a
     * restore for a title already known and the offline half of one that is not. Returns the row,
     * so a caller can hang chapters and history off it without looking it up again.
     */
    fun restoreMangaNoFetch(
        db: DatabaseHandler,
        manga: Manga,
        dbManga: Manga
    ) {
        mergeBackupManga(manga, dbManga)
        db.insertManga(manga)
    }

    /**
     * Writes an entry's chapters, keeping the read state that is already stored.
     *
     * Matched chapters are updated and unseen ones inserted, and the update writes only read,
     * bookmark and last_page_read -- a restore cannot rewrite chapter metadata the source owns.
     * Which is which is `mergeBackupChapters`, not this.
     */
    fun restoreChaptersForManga(
        db: DatabaseHandler,
        manga: Manga,
        chapters: List<Chapter>
    ) {
        val merged = mergeBackupChapters(manga, chapters, db.getChapters(manga))
        db.updateChaptersBackup(merged.toUpdate)
        db.insertChapters(merged.toInsert)
    }

    /**
     * Relinks tracked titles.
     *
     * A service already linked here keeps its row and takes the backup's ids, and its progress
     * only ever moves forward -- `last_chapter_read` is the higher of the two, so restoring an
     * older backup cannot rewind a tracker.
     *
     * @param isLogged whether a service is signed in, by `sync_id`. Links for services that are
     *   not are skipped: a row pointing at an account this device cannot reach would sit in the
     *   database looking like a working link. Checking that needs the tracker list, which is not
     *   portable, so the caller answers it.
     */
    fun restoreTrackForManga(
        db: DatabaseHandler,
        manga: Manga,
        tracks: List<Track>,
        isLogged: (Int) -> Boolean
    ) {
        val mangaId = manga.id ?: return
        tracks.forEach { it.manga_id = mangaId }

        val dbTracks = db.getTracks(manga)
        val toWrite = mutableListOf<Track>()

        for (track in tracks) {
            if (!isLogged(track.sync_id)) continue

            val stored = dbTracks.firstOrNull { it.sync_id == track.sync_id }
            if (stored != null) {
                stored.media_id = track.media_id
                stored.library_id = track.library_id
                stored.last_chapter_read = max(stored.last_chapter_read, track.last_chapter_read)
                toWrite.add(stored)
            } else {
                // Let the database assign the id rather than carrying the other device's.
                track.id = null
                toWrite.add(track)
            }
        }

        if (toWrite.isNotEmpty()) {
            db.insertTracks(toWrite)
        }
    }

    /** Everything hanging off one restored entry, once its manga row exists. */
    fun restoreRelated(
        db: DatabaseHandler,
        entry: BackupManga,
        manga: Manga,
        backupCategories: List<BackupCategory>,
        isLogged: (Int) -> Boolean
    ) {
        restoreChaptersForManga(db, manga, entry.getChaptersImpl())
        restoreCategoriesForManga(db, manga, entry.categories, backupCategories)
        restoreHistoryForManga(db, entry.history)
        restoreTrackForManga(db, manga, entry.getTrackingImpl(), isLogged)
    }
}
