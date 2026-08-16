package eu.kanade.tachiyomi.domain.library

import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy

/** Passed as [selectLibraryMangaToUpdate]'s categoryId to mean "not restricted to one category". */
const val ALL_CATEGORIES = -1

/**
 * Decides which library entries a library update should actually touch.
 *
 * Pure on purpose: this is a rule, not a job. It was previously inline in LibraryUpdateService,
 * an Android Service, which meant iOS would have had to reimplement it and the two apps could
 * quietly disagree about which manga get refreshed. Everything platform-shaped -- the intent
 * extra, the preferences, the notification -- is resolved by the caller.
 *
 * @param library every library entry, one row per manga-category pairing.
 * @param categoryId a single category to restrict to, or [ALL_CATEGORIES].
 * @param categoriesToUpdate categories the user limited updates to. Empty means no restriction.
 *  Ignored when [categoryId] names a specific category.
 * @param excludeCompleted drops finished series. Only meaningful for chapter updates; a metadata
 *  refresh still wants them.
 */
fun selectLibraryMangaToUpdate(
    library: List<LibraryManga>,
    categoryId: Int = ALL_CATEGORIES,
    categoriesToUpdate: List<Int> = emptyList(),
    excludeCompleted: Boolean = false
): List<LibraryManga> {
    var listToUpdate =
        if (categoryId != ALL_CATEGORIES) {
            // Deliberately not distinct: one category cannot list the same manga twice.
            library.filter { it.category == categoryId }
        } else if (categoriesToUpdate.isNotEmpty()) {
            // distinctBy because a manga in several selected categories appears once per category.
            library.filter { it.category in categoriesToUpdate }.distinctBy { it.id }
        } else {
            library.distinctBy { it.id }
        }

    if (excludeCompleted) {
        listToUpdate = listToUpdate.filter { it.status != SManga.COMPLETED }
    }

    // Series the source says to fetch only once are never refreshed again.
    return listToUpdate.filter { it.update_strategy != UpdateStrategy.ONLY_FETCH_ONCE }
}
