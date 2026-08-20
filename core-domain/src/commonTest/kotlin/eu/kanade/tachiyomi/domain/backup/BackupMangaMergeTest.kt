package eu.kanade.tachiyomi.domain.backup

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Manga
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Restoring must never take a manga out of the library, or drop a category on the floor. Both
 * failures are invisible until the user notices something missing.
 */
class BackupMangaMergeTest {
    private fun manga(
        url: String = "/m",
        favorite: Boolean = false,
        initialized: Boolean = false,
        id: Long? = null
    ) = Manga.create(1L).apply {
        this.url = url
        this.title = "Manga"
        this.favorite = favorite
        this.initialized = initialized
        this.id = id
    }

    @Test
    fun `the stored row id is adopted so the write updates rather than duplicates`() {
        val backup = manga()
        mergeBackupManga(backup, manga(id = 42L))
        assertEquals(42L, backup.id)
    }

    /** The defect this rule exists for: the manga is in the library now. */
    @Test
    fun `a backup that is not favorite does not unfavorite a library entry`() {
        val backup = manga(favorite = false)
        mergeBackupManga(backup, manga(favorite = true, id = 1L))
        assertTrue(backup.favorite, "restoring must never remove a manga from the library")
    }

    @Test
    fun `a favorite backup adds a stored manga to the library`() {
        val backup = manga(favorite = true)
        mergeBackupManga(backup, manga(favorite = false, id = 1L))
        assertTrue(backup.favorite)
    }

    @Test
    fun `neither side favorite stays out of the library`() {
        val backup = manga(favorite = false)
        mergeBackupManga(backup, manga(favorite = false, id = 1L))
        assertFalse(backup.favorite)
    }

    @Test
    fun `initialized survives from either side`() {
        val fromDb = manga(initialized = false)
        mergeBackupManga(fromDb, manga(initialized = true, id = 1L))
        assertTrue(fromDb.initialized)

        val fromBackup = manga(initialized = true)
        mergeBackupManga(fromBackup, manga(initialized = false, id = 1L))
        assertTrue(fromBackup.initialized)
    }
}

/**
 * Category ids are assigned per database, so the same "Reading" category has a different id on
 * every device. Name is the only identity that crosses, and matching on it is what stops a restore
 * duplicating every category the user has.
 */
class BackupCategoryMergeTest {
    private fun category(
        name: String,
        id: Int? = null
    ) = Category.create(name).apply { this.id = id }

    @Test
    fun `a category that already exists adopts its stored id and is not reinserted`() {
        val backup = listOf(category("Reading", id = 900))
        val result = mergeBackupCategories(backup, listOf(category("Reading", id = 3)))

        assertEquals(1, result.existing.size)
        assertTrue(result.toInsert.isEmpty())
        assertEquals(3, result.existing.single().id, "the backup's id belongs to another device")
    }

    @Test
    fun `an unknown category is inserted with no id`() {
        val backup = listOf(category("Webtoons", id = 900))
        val result = mergeBackupCategories(backup, listOf(category("Reading", id = 3)))

        assertTrue(result.existing.isEmpty())
        assertEquals(1, result.toInsert.size)
        assertNull(result.toInsert.single().id, "the database must assign the id")
    }

    @Test
    fun `matching is by name rather than by id`() {
        // Same name, wildly different ids: still the same category.
        val backup = listOf(category("Reading", id = 77))
        val result = mergeBackupCategories(backup, listOf(category("Reading", id = 1)))

        assertEquals(1, result.existing.single().id)
    }

    @Test
    fun `a mixed set is split correctly`() {
        val backup = listOf(category("Reading"), category("New"), category("Done"))
        val db = listOf(category("Reading", id = 1), category("Done", id = 2))

        val result = mergeBackupCategories(backup, db)

        assertEquals(setOf("Reading", "Done"), result.existing.map { it.name }.toSet())
        assertEquals(listOf("New"), result.toInsert.map { it.name })
    }
}
