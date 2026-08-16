package eu.kanade.tachiyomi.domain.library

import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which library entries an update touches. Both platforms must agree, or the same library
 * refreshes a different set of manga depending on which app ran the update.
 */
class LibraryUpdateSelectionTest {
    private fun entry(
        id: Long,
        category: Int,
        status: Int = SManga.ONGOING,
        strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
    ) = LibraryManga().apply {
        this.id = id
        this.category = category
        this.source = 1L
        this.url = "/m$id"
        this.title = "Manga $id"
        this.status = status
        this.update_strategy = strategy
    }

    @Test
    fun `no restrictions selects everything once`() {
        val library = listOf(entry(1, 0), entry(2, 0), entry(3, 1))
        assertEquals(3, selectLibraryMangaToUpdate(library).size)
    }

    @Test
    fun `a named category is not deduplicated`() {
        // One category cannot list the same manga twice, so distinctBy is deliberately skipped.
        val library = listOf(entry(1, 5), entry(2, 5), entry(3, 6))
        val result = selectLibraryMangaToUpdate(library, categoryId = 5)
        assertEquals(2, result.size)
        assertTrue(result.all { it.category == 5 })
    }

    @Test
    fun `a manga in several selected categories is only updated once`() {
        // The library table has one row per manga-category pairing, so the same manga appears
        // twice here. Updating it twice would double every network request.
        val library = listOf(entry(1, 0), entry(1, 1), entry(2, 0))
        val result = selectLibraryMangaToUpdate(library, categoriesToUpdate = listOf(0, 1))
        assertEquals(2, result.size)
        assertEquals(setOf(1L, 2L), result.map { it.id }.toSet())
    }

    @Test
    fun `unselected categories are skipped`() {
        val library = listOf(entry(1, 0), entry(2, 1), entry(3, 2))
        val result = selectLibraryMangaToUpdate(library, categoriesToUpdate = listOf(1))
        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test
    fun `completed series are dropped only when asked`() {
        val library = listOf(entry(1, 0), entry(2, 0, status = SManga.COMPLETED))
        assertEquals(2, selectLibraryMangaToUpdate(library, excludeCompleted = false).size)
        assertEquals(listOf(1L), selectLibraryMangaToUpdate(library, excludeCompleted = true).map { it.id })
    }

    @Test
    fun `fetch-once series are never selected`() {
        val library =
            listOf(
                entry(1, 0),
                entry(2, 0, strategy = UpdateStrategy.ONLY_FETCH_ONCE)
            )
        assertEquals(listOf(1L), selectLibraryMangaToUpdate(library).map { it.id })
    }

    @Test
    fun `fetch-once wins even inside an explicitly named category`() {
        val library = listOf(entry(1, 3, strategy = UpdateStrategy.ONLY_FETCH_ONCE))
        assertTrue(selectLibraryMangaToUpdate(library, categoryId = 3).isEmpty())
    }

    @Test
    fun `a named category takes precedence over the configured ones`() {
        val library = listOf(entry(1, 0), entry(2, 1))
        val result = selectLibraryMangaToUpdate(library, categoryId = 1, categoriesToUpdate = listOf(0))
        assertEquals(listOf(2L), result.map { it.id })
    }
}
