package eu.kanade.tachiyomi.domain.library

import eu.kanade.tachiyomi.data.database.DatabaseHandler
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.domain.manga.CoverStore
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.model.setMangaMemoJson
import eu.kanade.tachiyomi.util.removeCovers

/** A stable application-level surface over the shared library tables. */
class LibraryRepository(private val db: DatabaseHandler) {
    data class MangaKey(val url: String, val sourceId: Long)
    data class CategoryMembership(val url: String, val sourceId: Long, val names: List<String>)

    fun categories(): List<Category> = db.getCategories()

    fun category(name: String): Category? = db.getCategories().firstOrNull { it.name == name }

    fun entries(): List<LibraryManga> = db.getLibraryMangas()

    fun entries(categoryId: Int): List<LibraryManga> = entries().filter { it.category == categoryId }

    fun uncategorizedEntries(): List<LibraryManga> = entries(categoryId = 0)

    fun hasUncategorizedEntries(): Boolean = entries().any { it.category == 0 }

    fun contains(url: String, sourceId: Long): Boolean = db.getManga(url, sourceId)?.favorite == true

    /**
     * Adds a source result to the library using the same database model on every platform.
     * Existing rows only need their favourite bit changed: source refresh owns their metadata.
     */
    @Suppress("LongParameterList")
    fun addFavorite(
        url: String,
        title: String,
        sourceId: Long,
        thumbnailUrl: String?,
        author: String?,
        artist: String?,
        description: String?,
        genre: String?,
        status: Int,
        fetchOnce: Boolean,
        memoJson: String?,
        dateAdded: Long
    ): Manga {
        val existing = db.getManga(url, sourceId)
        if (existing != null) {
            existing.favorite = true
            db.updateMangaFavorite(existing)
            return existing
        }

        return Manga.create(url, title, sourceId).also { manga ->
            manga.thumbnail_url = thumbnailUrl
            manga.author = author
            manga.artist = artist
            manga.description = description
            manga.genre = genre
            manga.status = status
            manga.update_strategy = if (fetchOnce) UpdateStrategy.ONLY_FETCH_ONCE else UpdateStrategy.ALWAYS_UPDATE
            setMangaMemoJson(manga, memoJson)
            manga.favorite = true
            manga.initialized = true
            manga.date_added = dateAdded
            db.insertManga(manga)
        }
    }

    fun remove(
        key: MangaKey,
        coverStore: CoverStore? = null
    ): Boolean {
        val manga = db.getManga(key.url, key.sourceId) ?: return false
        manga.favorite = false
        if (coverStore != null) manga.removeCovers(coverStore)
        db.updateMangaFavorite(manga)
        return true
    }

    fun remove(url: String, sourceId: Long, coverStore: CoverStore? = null): Boolean =
        remove(MangaKey(url, sourceId), coverStore)

    /** One transaction for bulk removal; callers refresh observable UI state once afterwards. */
    fun removeAll(
        keys: List<MangaKey>,
        coverStore: CoverStore? = null
    ): Int {
        var removed = 0
        db.inTransaction {
            keys.distinct().forEach { if (remove(it, coverStore)) removed++ }
        }
        return removed
    }

    /** Objective-C-friendly bulk overload; Kotlin/Native cannot bridge a list of [MangaKey]. */
    fun removeAll(
        urls: List<String>,
        sourceIds: List<Long>,
        coverStore: CoverStore? = null
    ): Int = removeAll(
        urls.zip(sourceIds).map { (url, sourceId) -> MangaKey(url, sourceId) },
        coverStore
    )

    fun clear(): Int {
        var removed = 0
        db.inTransaction {
            db.getFavoriteMangas().forEach { manga ->
                manga.favorite = false
                db.updateMangaFavorite(manga)
                removed++
            }
        }
        return removed
    }

    fun addCategory(name: String): Category? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return Category.create(trimmed).also {
            it.order = db.getCategories().size
            db.insertCategory(it)
        }
    }

    fun renameCategory(category: Category, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        category.name = trimmed
        db.insertCategory(category)
        return true
    }

    fun deleteCategory(category: Category) = db.deleteCategory(category)

    fun moveCategory(name: String, toPosition: Int): Boolean {
        val categories = db.getCategories().sortedBy { it.order }.toMutableList()
        val from = categories.indexOfFirst { it.name == name }
        if (from < 0) return false
        val moved = categories.removeAt(from)
        categories.add(toPosition.coerceIn(0, categories.size), moved)
        db.inTransaction {
            categories.forEachIndexed { index, category ->
                if (category.order != index) {
                    category.order = index
                    db.insertCategory(category)
                }
            }
        }
        return true
    }

    fun clearCategories() = db.deleteCategories(db.getCategories())

    fun categoryNames(url: String, sourceId: Long): List<String> {
        val manga = db.getManga(url, sourceId) ?: return emptyList()
        return db.getCategoriesForManga(manga).map { it.name }
    }

    fun categoryMemberships(): List<CategoryMembership> {
        val namesById = db.getCategories().mapNotNull { category ->
            category.id?.let { it to category.name }
        }.toMap()
        return db.getLibraryMangas()
            .filter { it.category != 0 }
            .groupBy { MangaKey(it.url, it.source) }
            .map { (key, rows) ->
                CategoryMembership(key.url, key.sourceId, rows.mapNotNull { namesById[it.category] })
            }
    }

    fun setCategories(url: String, sourceId: Long, names: List<String>): Boolean {
        val manga = db.getManga(url, sourceId) ?: return false
        val wanted = names.toSet()
        val links = db.getCategories()
            .filter { it.name in wanted && it.id != null }
            .map { MangaCategory.create(manga, it) }
        db.setMangaCategories(links, listOf(manga))
        return true
    }

    fun addCategories(url: String, sourceId: Long, names: List<String>): Boolean =
        setCategories(url, sourceId, (categoryNames(url, sourceId) + names).distinct())

    fun removeCategories(url: String, sourceId: Long, names: List<String>): Boolean {
        val removing = names.toSet()
        return setCategories(url, sourceId, categoryNames(url, sourceId).filterNot { it in removing })
    }
}
