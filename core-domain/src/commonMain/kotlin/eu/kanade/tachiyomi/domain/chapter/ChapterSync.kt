package eu.kanade.tachiyomi.domain.chapter

import eu.kanade.tachiyomi.data.database.DatabaseHandler
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.util.chapter.ChapterRecognition
import eu.kanade.tachiyomi.util.chapter.NoChaptersException

/**
 * The platform-specific work chapter syncing needs done on its behalf.
 *
 * Everything here is something the shared diffing logic cannot do itself: it lives in the source
 * layer, on the filesystem, or on a clock. Passing it in as an interface is what keeps
 * [syncChaptersWithSource] free of the extension API, which stays JVM-side on iOS.
 */
interface ChapterSyncPlatform {
    /**
     * Lets the source normalise a chapter before it is stored -- HttpSource uses this to strip
     * the manga title out of chapter names, among other things.
     */
    fun prepareNewChapter(
        chapter: SChapter,
        manga: Manga
    ) {}

    /** Whether this chapter's pages are already on disk. */
    fun isChapterDownloaded(
        chapter: Chapter,
        manga: Manga
    ): Boolean = false

    /** Moves an existing download when the source renames a chapter, so it is not orphaned. */
    fun renameDownloadedChapter(
        manga: Manga,
        from: Chapter,
        to: Chapter
    ) {}

    /**
     * Whether newly added chapters should inherit reading progress from existing ones. True for
     * the EH/EXH sources, whose galleries are rewritten wholesale rather than appended to.
     */
    fun carriesOverReadingProgress(manga: Manga): Boolean = false

    /** Current time in epoch millis. A parameter so the result is reproducible in tests. */
    fun now(): Long
}

/**
 * Diffs the chapters a source returned against the ones already stored, and applies the result.
 *
 * @return the chapters added and the chapters deleted. Chapters that were deleted and immediately
 *  re-added under a new url are reported as neither, since nothing actually changed for the reader.
 * @throws NoChaptersException if the source returned nothing.
 *
 * Annotated `Exception` rather than just [NoChaptersException], and deliberately so. A
 * Kotlin/Native function that throws a type outside its `@Throws` list terminates the process, and
 * no `catch` on the caller's side can prevent it -- a library refresh meeting one dead source took
 * the whole app down that way. Listing only the expected type would leave the same trap open for
 * every unexpected one: this parses untrusted source output and writes to the database, so a
 * malformed chapter list or a SQLite failure are both reachable, and neither should be fatal
 * halfway through a several-hundred-title refresh.
 */
@Throws(Exception::class)
fun syncChaptersWithSource(
    db: DatabaseHandler,
    rawSourceChapters: List<SChapter>,
    manga: Manga,
    platform: ChapterSyncPlatform
): Pair<List<Chapter>, List<Chapter>> {
    if (rawSourceChapters.isEmpty()) {
        throw NoChaptersException()
    }

    // Chapters from db.
    val dbChapters = db.getChapters(manga)

    val sourceChapters =
        rawSourceChapters
            .distinctBy { it.url }
            .mapIndexed { i, sChapter ->
                Chapter.create().apply {
                    copyFrom(sChapter)
                    manga_id = manga.id
                    source_order = i
                }
            }

    // Chapters from the source not in db.
    val toAdd = mutableListOf<Chapter>()

    // Chapters whose metadata have changed.
    val toChange = mutableListOf<Chapter>()

    for (sourceChapter in sourceChapters) {
        val dbChapter = dbChapters.find { it.url == sourceChapter.url }

        // Add the chapter if not in db already, or update if the metadata changed.
        if (dbChapter == null) {
            toAdd.add(sourceChapter)
        } else {
            // this forces metadata update for the main viewable things in the chapter list
            platform.prepareNewChapter(sourceChapter, manga)

            ChapterRecognition.parseChapterNumber(sourceChapter, manga)

            if (shouldUpdateDbChapter(dbChapter, sourceChapter)) {
                if (dbChapter.name != sourceChapter.name && platform.isChapterDownloaded(dbChapter, manga)) {
                    platform.renameDownloadedChapter(manga, dbChapter, sourceChapter)
                }
                dbChapter.scanlator = sourceChapter.scanlator
                dbChapter.name = sourceChapter.name
                dbChapter.date_upload = sourceChapter.date_upload
                dbChapter.chapter_number = sourceChapter.chapter_number
                dbChapter.memo = sourceChapter.memo
                toChange.add(dbChapter)
            }
        }
    }

    // Recognize number for new chapters.
    toAdd.forEach {
        platform.prepareNewChapter(it, manga)
        ChapterRecognition.parseChapterNumber(it, manga)
    }

    // Chapters from the db not in the source.
    val toDelete =
        dbChapters.filterNot { dbChapter ->
            sourceChapters.any { sourceChapter ->
                dbChapter.url == sourceChapter.url
            }
        }

    // A source can reorder its listing without adding, removing or editing anything -- and a
    // stored source_order can simply be stale. Neither shows up in toAdd/toDelete/toChange, so
    // checking those alone would return early and leave the order wrong forever, with no way for
    // a refresh to repair it.
    val orderChanged = sourceOrderChanged(dbChapters, sourceChapters)

    // Return if there's nothing to add, delete, change or reorder, avoiding unnecessary db
    // transactions.
    if (toAdd.isEmpty() && toDelete.isEmpty() && toChange.isEmpty() && !orderChanged) {
        return Pair(emptyList(), emptyList())
    }

    val readded = mutableListOf<Chapter>()

    db.inTransaction {
        // Plain sets: only membership is ever tested here, never ordering, so the TreeSets this
        // used to build were doing nothing a HashSet does not.
        val deletedChapterNumbers = HashSet<Float>()
        val deletedReadChapterNumbers = HashSet<Float>()
        if (toDelete.isNotEmpty()) {
            for (c in toDelete) {
                if (c.read) {
                    deletedReadChapterNumbers.add(c.chapter_number)
                }
                deletedChapterNumbers.add(c.chapter_number)
            }
            db.deleteChapters(toDelete)
        }

        if (toAdd.isNotEmpty()) {
            // Set the date fetch for new items in reverse order to allow another sorting method.
            // Sources MUST return the chapters from most to less recent, which is common.
            var now = platform.now()

            for (i in toAdd.indices.reversed()) {
                val c = toAdd[i]
                c.date_fetch = now++
                // Try to mark already read chapters as read when the source deletes them
                if (c.isRecognizedNumber && c.chapter_number in deletedReadChapterNumbers) {
                    c.read = true
                }
                if (c.isRecognizedNumber && c.chapter_number in deletedChapterNumbers) {
                    readded.add(c)
                }
            }

            // Carry over reading progress where the source rewrites galleries wholesale.
            if (platform.carriesOverReadingProgress(manga)) {
                val finalAdded = toAdd.subtract(readded.toSet())
                if (finalAdded.isNotEmpty()) {
                    val max = dbChapters.maxByOrNull { it.last_page_read }
                    if (max != null && max.last_page_read > 0) {
                        for (chapter in finalAdded) {
                            chapter.last_page_read = max.last_page_read
                        }
                    }
                }
            }

            // insertChapters assigns each generated id back onto its chapter.
            db.insertChapters(toAdd)
        }

        if (toChange.isNotEmpty()) {
            db.insertChapters(toChange)
        }

        // Fix order in source.
        db.fixChaptersSourceOrder(sourceChapters)

        // Set this manga as updated since chapters were changed
        manga.last_update = platform.now()
        db.updateLastUpdated(manga)
    }

    return Pair(toAdd.subtract(readded.toSet()).toList(), toDelete.subtract(readded.toSet()).toList())
}

/**
 * Whether any stored chapter sits at a different position in the source's listing than it does now.
 *
 * Separate from the add/delete/change checks because a reorder shows up in none of them: the same
 * chapters, with the same metadata, in a different order. Without this a refresh would return
 * early and leave a wrong -- or merely stale -- source_order in place permanently.
 */
internal fun sourceOrderChanged(
    dbChapters: List<Chapter>,
    sourceChapters: List<Chapter>
): Boolean {
    val dbByUrl = dbChapters.associateBy { it.url }
    return sourceChapters.any { sourceChapter ->
        val dbChapter = dbByUrl[sourceChapter.url] ?: return@any false
        dbChapter.source_order != sourceChapter.source_order
    }
}

// checks if the chapter in db needs updated
private fun shouldUpdateDbChapter(
    dbChapter: Chapter,
    sourceChapter: SChapter
): Boolean {
    return dbChapter.scanlator != sourceChapter.scanlator ||
        dbChapter.name != sourceChapter.name ||
        dbChapter.date_upload != sourceChapter.date_upload ||
        dbChapter.chapter_number != sourceChapter.chapter_number ||
        dbChapter.memo != sourceChapter.memo
}
