package eu.kanade.tachiyomi.data.backup.full

import eu.kanade.tachiyomi.data.backup.full.models.Backup
import eu.kanade.tachiyomi.data.backup.full.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.full.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.full.models.BackupFlatMetadata
import eu.kanade.tachiyomi.data.backup.full.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.full.models.BackupManga
import eu.kanade.tachiyomi.data.backup.full.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.full.models.BackupSource
import eu.kanade.tachiyomi.data.backup.full.models.BackupTracking
import eu.kanade.tachiyomi.data.database.DatabaseHandler
import eu.kanade.tachiyomi.data.database.models.Manga
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * What a backup includes.
 *
 * A bitmask, because that is what the Android app already stores in preferences and passes to its
 * backup service. The iOS app expressed the same choices as a struct of booleans and mapped them by
 * hand, which is the same list of options written twice.
 */
object BackupOptions {
    const val CATEGORY = 0x1
    const val CHAPTER = 0x2
    const val HISTORY = 0x4
    const val TRACK = 0x8
    const val PREFERENCES = 0x10
    const val ALL = 0x1F

    fun hasCategories(flags: Int): Boolean = flags and CATEGORY != 0

    fun hasChapters(flags: Int): Boolean = flags and CHAPTER != 0

    fun hasHistory(flags: Int): Boolean = flags and HISTORY != 0

    fun hasTracks(flags: Int): Boolean = flags and TRACK != 0

    fun hasPreferences(flags: Int): Boolean = flags and PREFERENCES != 0
}

/**
 * Builds the contents of a `.tachibk` from the database.
 *
 * This is the backup, minus the two things that are not portable: turning it into bytes (the codec
 * does that) and writing those bytes somewhere (each platform's file APIs do that). Everything in
 * between -- which titles are included, which of their chapters, how history and tracks and
 * category membership are attached -- is one implementation for both apps.
 *
 * It was two. Each app walked the same tables and filled the same models in its own way, and they
 * did not agree: the iOS side built its entries by hand and passed only seven fields, so every
 * backup it wrote carried no description, no artist, status 0, reading mode 0 and chapter_flags 0 --
 * the columns simply never left the database. That was not visible until a backup was restored
 * somewhere else and something was missing.
 *
 * This is the Android implementation, moved. Where the two apps disagreed, that one is what
 * survived: it is the older and the one with users, so a difference here is the iOS app being
 * brought into line rather than a new behaviour for both.
 *
 * The per-title work is [BackupManga.copyFrom] and its siblings, which copy every column rather
 * than a chosen few, so a column added to the schema is carried without anyone remembering to add
 * it here.
 */
@OptIn(ExperimentalSerializationApi::class)
object BackupBuilder {
    /**
     * @param sources the extension each backed-up title came from. Resolving a source id to a name
     *   needs the installed extensions, which is platform work, so the caller supplies it.
     * @param savedSearches Android-only; the iOS app has no saved searches to carry.
     * @param flatMetadata exh search metadata for a title, Android-only.
     */
    fun build(
        db: DatabaseHandler,
        flags: Int = BackupOptions.ALL,
        sources: (List<Manga>) -> List<BackupSource> = { emptyList() },
        savedSearches: List<BackupSavedSearch> = emptyList(),
        flatMetadata: (Long) -> BackupFlatMetadata? = { null }
    ): Backup {
        val favorites = db.getFavoriteMangas()
        return Backup(
            backupManga = favorites.map { mangaObject(db, it, flags, flatMetadata) },
            backupCategories =
                if (BackupOptions.hasCategories(flags)) {
                    db.getCategories().map { BackupCategory.copyFrom(it) }
                } else {
                    emptyList()
                },
            backupSources = sources(favorites),
            backupSavedSearches = savedSearches
        )
    }

    /**
     * One library entry and everything hanging off it.
     *
     * Empty lists are left unset rather than written as empty: the format encodes defaults away, so
     * a title with no tracks costs nothing.
     */
    fun mangaObject(
        db: DatabaseHandler,
        manga: Manga,
        flags: Int = BackupOptions.ALL,
        flatMetadata: (Long) -> BackupFlatMetadata? = { null }
    ): BackupManga {
        val entry = BackupManga.copyFrom(manga)
        val id = manga.id

        if (id != null) {
            flatMetadata(id)?.let { entry.flatMetadata = it }
        }

        if (BackupOptions.hasChapters(flags)) {
            val chapters = db.getChapters(manga)
            if (chapters.isNotEmpty()) {
                entry.chapters = chapters.map { BackupChapter.copyFrom(it) }
            }
        }

        if (BackupOptions.hasCategories(flags)) {
            // Membership travels as the category's order, not its id: ids are per-install, and the
            // restoring side matches categories up by the same order.
            val categories = db.getCategoriesForManga(manga).mapNotNull { it.order }
            if (categories.isNotEmpty()) {
                entry.categories = categories
            }
        }

        if (BackupOptions.hasTracks(flags)) {
            val tracks = db.getTracks(manga)
            if (tracks.isNotEmpty()) {
                entry.tracking = tracks.map { BackupTracking.copyFrom(it) }
            }
        }

        if (BackupOptions.hasHistory(flags) && id != null) {
            // History is keyed by chapter id in the database and by chapter url in a backup, since
            // ids do not survive the trip. Chapters are looked up once here rather than per entry.
            val urlsById = db.getChapters(manga).associateBy({ it.id }, { it.url })
            // The url and the timestamp, as the Android app has always written them. `readDuration`
            // exists in the model and is left unset: filling it here would change what that app
            // produces, and its restore does not read it back, so the only effect would be larger
            // files. Worth doing on both sides at once, deliberately.
            val history =
                db.getHistoryByMangaId(id).mapNotNull { record ->
                    urlsById[record.chapter_id]?.let { BackupHistory(it, record.last_read) }
                }
            if (history.isNotEmpty()) {
                entry.history = history
            }
        }

        return entry
    }
}
