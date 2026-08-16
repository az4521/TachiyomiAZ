package eu.kanade.tachiyomi.domain.manga

import eu.kanade.tachiyomi.data.database.models.Manga

/**
 * The cover cache as shared code sees it.
 *
 * Cover storage is filesystem work, so the implementation is per-platform -- CoverCache on
 * Android. Only the two operations the domain rules actually need are exposed, so shared code
 * cannot reach for a `File`.
 */
interface CoverStore {
    /** Whether the user has set a custom cover for this manga. */
    fun hasCustomCover(manga: Manga): Boolean

    /**
     * Drops the cached cover so it is fetched again.
     *
     * @param deleteCustomCover whether a user-set cover should be removed too.
     * @return the number of files deleted.
     */
    fun deleteFromCache(
        manga: Manga,
        deleteCustomCover: Boolean = false
    ): Int
}

/**
 * The download-related preferences the domain rules consult.
 *
 * Preference storage is platform work (SharedPreferences on Android), so shared code sees only
 * the resolved values.
 */
interface DownloadPreferences {
    /** Whether newly found chapters should download automatically. */
    val downloadNewChapters: Boolean

    /** Category ids that auto-download is limited to. Empty means every category. */
    val downloadNewCategories: List<Int>
}
