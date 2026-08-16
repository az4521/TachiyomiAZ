package eu.kanade.tachiyomi.data.database.queries

import eu.kanade.tachiyomi.data.database.DbProvider
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory

interface MangaCategoryQueries : DbProvider {
    fun insertMangaCategory(mangaCategory: MangaCategory) {
        sqlDatabase.mangas_categoriesQueries.insertMangaCategory(
            mangaCategory.manga_id,
            mangaCategory.category_id.toLong()
        )
    }

    fun insertMangasCategories(mangasCategories: List<MangaCategory>) {
        sqlDatabase.mangas_categoriesQueries.transaction {
            mangasCategories.forEach { insertMangaCategory(it) }
        }
    }

    fun deleteOldMangasCategories(mangas: List<Manga>) {
        sqlDatabase.mangas_categoriesQueries.transaction {
            mangas.forEach { manga ->
                manga.id?.let {
                    sqlDatabase.mangas_categoriesQueries.deleteMangasCategoriesForManga(it)
                }
            }
        }
    }

    fun setMangaCategories(
        mangasCategories: List<MangaCategory>,
        mangas: List<Manga>
    ) {
        // The chunking existed to stay under SQLite's bind-variable limit when the old
        // implementation built one IN(...) clause per chunk. Deleting per manga has no such
        // limit, so the whole thing runs in a single transaction.
        sqlDatabase.mangas_categoriesQueries.transaction {
            deleteOldMangasCategories(mangas)
            insertMangasCategories(mangasCategories)
        }
    }
}
