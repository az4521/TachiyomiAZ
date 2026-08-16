package eu.kanade.tachiyomi.data.database

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.CategoryImpl

/**
 * Maps a SQLDelight row onto the app's [Category] model.
 *
 * `manga_order` is stored as a slash-joined string rather than a real list, matching what
 * CategoryTypeMapping wrote, so existing rows keep parsing.
 */
fun mapCategory(
    id: Long,
    name: String,
    sort: Long,
    flags: Long,
    mangaOrder: String
): Category =
    CategoryImpl().also {
        it.id = id.toInt()
        it.name = name
        it.order = sort.toInt()
        it.flags = flags.toInt()
        it.mangaOrder = mangaOrder.split("/").mapNotNull { entry -> entry.toLongOrNull() }
    }

fun Category.mangaOrderToString(): String = mangaOrder.joinToString("/")
