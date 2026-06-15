package eu.kanade.tachiyomi.source

import android.graphics.drawable.Drawable
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.lang.awaitSingle
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A basic interface for creating a source. It could be an online source, a local source, etc.
 */
interface Source {
    /**
     * Id for the source. Must be unique.
     */
    val id: Long

    /**
     * Name of the source.
     */
    val name: String

    val lang: String
        get() = ""

    /**
     * Whether the source has support for latest updates.
     *
     * @since tachiyomix 1.6 (moved from CatalogueSource)
     */
    val supportsLatest: Boolean
        get() = false

    /**
     * Returns the list of filters for the source.
     *
     * @since tachiyomix 1.6 (moved from CatalogueSource)
     */
    fun getFilterList(): FilterList = FilterList()

    /**
     * Get a page with a list of manga.
     *
     * @since tachiyomix 1.6 (moved from CatalogueSource)
     * @param page the page number to retrieve.
     */
    suspend fun getPopularManga(page: Int): MangasPage =
        throw IllegalStateException("Not used")

    /**
     * Get a page with a list of latest manga updates.
     *
     * @since tachiyomix 1.6 (moved from CatalogueSource)
     * @param page the page number to retrieve.
     */
    suspend fun getLatestUpdates(page: Int): MangasPage =
        throw IllegalStateException("Not used")

    /**
     * Get a page with a list of manga.
     *
     * @since tachiyomix 1.6 (moved from CatalogueSource)
     * @param page the page number to retrieve.
     * @param query the search query.
     * @param filters the list of filters to apply.
     */
    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        throw IllegalStateException("Not used")

    /**
     * Fetches updated information for a manga and/or its chapters. The host app passes the
     * existing [chapters] and flags indicating which parts to fetch; sources may return the
     * provided values as-is for unrequested data.
     *
     * Default delegates to [getMangaDetails] / [getChapterList] so 1.5 sources continue to work.
     *
     * @since tachiyomix 1.6
     */
    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) getMangaDetails(manga) else manga
        val updatedChapters = if (fetchChapters) getChapterList(manga) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    /**
     * Get the updated details for a manga.
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the updated manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getMangaDetails(manga: SManga): SManga {
        return fetchMangaDetails(manga).awaitSingle()
    }

    /**
     * Get all the available chapters for a manga.
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the chapters for the manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getChapterList(manga: SManga): List<SChapter> {
        return fetchChapterList(manga).awaitSingle()
    }

    /**
     * Get the list of pages a chapter has. Pages should be returned in the expected order;
     * the index is ignored.
     *
     * @since extensions-lib 1.5
     * @param chapter the chapter.
     * @return the pages for the chapter.
     */
    @Suppress("DEPRECATION")
    suspend fun getPageList(chapter: SChapter): List<Page> {
        return fetchPageList(chapter).awaitSingle()
    }

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getMangaDetails")
    )
    fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getChapterList")
    )
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getPageList")
    )
    fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        throw IllegalStateException("Not used")
}

fun Source.icon(): Drawable? = Injekt.get<ExtensionManager>().getAppIconForSource(this)
