package eu.kanade.tachiyomi.util

import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.model.SManga
import java.util.Date

fun Manga.isLocal() = source == LocalSource.ID

/**
 * Call before updating [Manga.thumbnail_url] to ensure old cover can be cleared from cache
 */
fun Manga.prepUpdateCover(
    coverCache: CoverCache,
    remoteManga: SManga,
    refreshSameUrl: Boolean
) {
    // Never refresh covers if the new url is null, as the current url has possibly become invalid
    val newUrl = remoteManga.thumbnail_url ?: return

    // Never refresh covers if the url is empty to avoid "losing" existing covers
    if (newUrl.isEmpty()) return

    if (!refreshSameUrl && thumbnail_url == newUrl) return

    when {
        isLocal() -> {
            cover_last_modified = Date().time
        }
        hasCustomCover(coverCache) -> {
            coverCache.deleteFromCache(this, false)
        }
        else -> {
            cover_last_modified = Date().time
            coverCache.deleteFromCache(this, false)
        }
    }
}

/**
 * Copies the source-provided [SManga.memo] over, if there is one. Sources use it to carry their
 * own metadata across calls, so it's kept in sync even when the user has manga metadata updating
 * turned off.
 *
 * @return whether the memo changed.
 */
fun Manga.copyMemoFrom(other: SManga): Boolean {
    if (other.memo.isEmpty() || other.memo == memo) return false

    memo = other.memo
    return true
}

/**
 * Persists the manga half of a [eu.kanade.tachiyomi.source.model.SMangaUpdate].
 *
 * Sources may return fresh details even when `fetchDetails = false` was requested, so the result
 * is always saved. [updateMetadata] decides how much of it is kept: everything when true, only the
 * memo when false — that way the "refresh manga metadata" library setting still governs what the
 * user sees, while source-internal metadata stays current either way.
 */
fun Manga.saveMangaUpdate(
    sManga: SManga,
    db: DatabaseHelper,
    coverCache: CoverCache,
    updateMetadata: Boolean,
    manualFetch: Boolean = false
) {
    if (updateMetadata) {
        // Avoid "losing" the existing cover when the source doesn't provide one.
        if (!sManga.thumbnail_url.isNullOrEmpty()) {
            prepUpdateCover(coverCache, sManga, manualFetch)
        } else {
            sManga.thumbnail_url = thumbnail_url
        }

        copyFrom(sManga)
        initialized = true
    } else if (!copyMemoFrom(sManga)) {
        return
    }

    db.insertManga(this).executeAsBlocking()
}

fun Manga.hasCustomCover(coverCache: CoverCache): Boolean {
    return coverCache.getCustomCoverFile(this).exists()
}

fun Manga.removeCovers(coverCache: CoverCache) {
    if (isLocal()) return

    cover_last_modified = Date().time
    coverCache.deleteFromCache(this, true)
}

fun Manga.updateCoverLastModified(db: DatabaseHelper) {
    cover_last_modified = Date().time
    db.updateMangaCoverLastModified(this).executeAsBlocking()
}

fun Manga.shouldDownloadNewChapters(
    db: DatabaseHelper,
    prefs: PreferencesHelper
): Boolean {
    if (!favorite) return false

    // Boolean to determine if user wants to automatically download new chapters.
    val downloadNew = prefs.downloadNew().get()
    if (!downloadNew) return false

    val categoriesToDownload = prefs.downloadNewCategories().get().map(String::toInt)
    if (categoriesToDownload.isEmpty()) return true

    // Get all categories, else default category (0)
    val categoriesForManga =
        db.getCategoriesForManga(this)
            .mapNotNull { it.id }
            .takeUnless { it.isEmpty() } ?: listOf(0)

    return categoriesForManga.intersect(categoriesToDownload).isNotEmpty()
}
