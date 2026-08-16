package eu.kanade.tachiyomi.data.database.queries

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import eu.kanade.tachiyomi.data.database.DbProvider
import eu.kanade.tachiyomi.data.database.mangaOrderToString
import eu.kanade.tachiyomi.data.database.mapCategory
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Manga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/**
 * Category access, backed by SQLDelight. These return plain values rather than storio
 * PreparedOperations, so call sites no longer wrap them in executeAsBlocking.
 */
interface CategoryQueries : DbProvider {
    fun getCategories(): List<Category> =
        sqlDatabase.categoriesQueries.getCategories(::mapCategory).executeAsList()

    /** Re-emits whenever the categories table changes, replacing storio's asRxObservable. */
    fun getCategoriesAsFlow(): Flow<List<Category>> =
        sqlDatabase.categoriesQueries
            .getCategories(::mapCategory)
            .asFlow()
            .mapToList(Dispatchers.IO)

    fun getCategoriesForManga(manga: Manga): List<Category> =
        sqlDatabase.categoriesQueries
            .getCategoriesForManga(manga.id ?: 0L, ::mapCategory)
            .executeAsList()

    fun insertCategory(category: Category) {
        sqlDatabase.categoriesQueries.transaction {
            val id = category.id
            if (id == null || id == 0) {
                sqlDatabase.categoriesQueries.insertCategory(
                    category.name,
                    category.order.toLong(),
                    category.flags.toLong(),
                    category.mangaOrderToString()
                )
                category.id =
                    sqlDatabase.categoriesQueries.lastInsertRowId().executeAsOne().toInt()
            } else {
                sqlDatabase.categoriesQueries.updateCategory(
                    category.name,
                    category.order.toLong(),
                    category.flags.toLong(),
                    category.mangaOrderToString(),
                    id.toLong()
                )
            }
        }
    }

    fun insertCategories(categories: List<Category>) {
        sqlDatabase.categoriesQueries.transaction {
            categories.forEach { insertCategory(it) }
        }
    }

    fun deleteCategory(category: Category) {
        category.id?.let { sqlDatabase.categoriesQueries.deleteCategory(it.toLong()) }
    }

    fun deleteCategories(categories: List<Category>) {
        sqlDatabase.categoriesQueries.transaction {
            categories.forEach { deleteCategory(it) }
        }
    }
}
