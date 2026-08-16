package eu.kanade.tachiyomi.domain.backup

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Manga

/**
 * Merges a backup manga onto the one already stored.
 *
 * Follows Mihon's MangaRestorer.copyFrom: favorite and initialized are OR-ed rather than
 * overwritten. Restoring must never take a manga out of the library -- the backup is a snapshot of
 * one moment, and this manga is in the library now. Getting that wrong is invisible until the user
 * notices something missing from their library, which is why it is a rule rather than a detail.
 *
 * The stored metadata wins for everything else, which is what [Manga.copyFrom] already does.
 * Mihon picks that direction by comparing a `version` field, added for its sync feature; this
 * backup format has no such field, so the database always wins -- the same choice Mihon makes when
 * the backup is not newer.
 *
 * Mutates [manga] in place and leaves the write to the caller.
 */
fun mergeBackupManga(
    manga: Manga,
    dbManga: Manga
) {
    manga.id = dbManga.id
    manga.copyFrom(dbManga)
    manga.favorite = manga.favorite || dbManga.favorite
    manga.initialized = manga.initialized || dbManga.initialized
}

/**
 * What a backup restore should do to the stored categories.
 *
 * @param existing backup categories that matched a stored one and now carry its id.
 * @param toInsert categories the database has never seen.
 */
class BackupCategoryMerge(
    val existing: List<Category>,
    val toInsert: List<Category>
)

/**
 * Matches backup categories against the stored ones by name.
 *
 * Name is the only stable identity a category has across devices: ids are assigned per database,
 * so the same "Reading" category has different ids on each. Matching by name is what stops a
 * restore duplicating every category, and what lets a manga's category assignments resolve to the
 * right rows afterwards.
 *
 * Mutates the ids of [backupCategories] in place, matching how the restore path already worked.
 */
fun mergeBackupCategories(
    backupCategories: List<Category>,
    dbCategories: List<Category>
): BackupCategoryMerge {
    val dbCategoriesByName = dbCategories.associateBy { it.name }

    val existing = mutableListOf<Category>()
    val toInsert = mutableListOf<Category>()

    backupCategories.forEach { category ->
        val dbCategory = dbCategoriesByName[category.name]
        if (dbCategory != null) {
            category.id = dbCategory.id
            existing += category
        } else {
            // Cleared so the database assigns one; the backup's id belongs to another device.
            category.id = null
            toInsert += category
        }
    }

    return BackupCategoryMerge(existing, toInsert)
}
