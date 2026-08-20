package eu.kanade.tachiyomi.util

import eu.kanade.tachiyomi.data.database.DatabaseHandler
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.domain.manga.CoverStore
import eu.kanade.tachiyomi.domain.manga.DownloadPreferences
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.datetime.Clock

/**
 * Id of the built-in local source. Declared here so shared rules can recognise local manga
 * without depending on LocalSource, which is Android-only; LocalSource.ID reads from this so the
 * two cannot drift apart.
 */
const val LOCAL_SOURCE_ID = 0L

private fun now(): Long = Clock.System.now().toEpochMilliseconds()

fun Manga.isLocal() = source == LOCAL_SOURCE_ID

/**
 * Call before updating [Manga.thumbnail_url] to ensure old cover can be cleared from cache
 */
fun Manga.prepUpdateCover(
    coverCache: CoverStore,
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
            cover_last_modified = now()
        }
        coverCache.hasCustomCover(this) -> {
            coverCache.deleteFromCache(this, false)
        }
        else -> {
            cover_last_modified = now()
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
    if (other.memo == memo) return false

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
    db: DatabaseHandler,
    coverCache: CoverStore,
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

    db.insertManga(this)
}

fun Manga.hasCustomCover(coverCache: CoverStore): Boolean = coverCache.hasCustomCover(this)

fun Manga.removeCovers(coverCache: CoverStore) {
    if (isLocal()) return

    cover_last_modified = now()
    coverCache.deleteFromCache(this, true)
}

fun Manga.updateCoverLastModified(db: DatabaseHandler) {
    cover_last_modified = now()
    db.updateMangaCoverLastModified(this)
}

fun Manga.shouldDownloadNewChapters(
    db: DatabaseHandler,
    prefs: DownloadPreferences
): Boolean {
    if (!favorite) return false

    // Boolean to determine if user wants to automatically download new chapters.
    val downloadNew = prefs.downloadNewChapters
    if (!downloadNew) return false

    val categoriesToDownload = prefs.downloadNewCategories
    if (categoriesToDownload.isEmpty()) return true

    // Get all categories, else default category (0)
    val categoriesForManga =
        db.getCategoriesForManga(this)
            .mapNotNull { it.id }
            .takeUnless { it.isEmpty() } ?: listOf(0)

    return categoriesForManga.intersect(categoriesToDownload).isNotEmpty()
}
