package exh.source

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class EnhancedHttpSource(
    val originalSource: HttpSource,
    val enchancedSource: HttpSource
) : HttpSource() {
    private val prefs: PreferencesHelper by injectLazy()

    /**
     * Returns the request for the popular manga given the page.
     *
     * @param page the page number to retrieve.
     */
    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException("Should never be called!")

    /**
     * Parses the response from the site and returns a [MangasPage] object.
     *
     * @param response the response from the site.
     */
    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException("Should never be called!")

    /**
     * Returns the request for the search manga given the page.
     *
     * @param page the page number to retrieve.
     * @param query the search query.
     * @param filters the list of filters to apply.
     */
    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList
    ) = throw UnsupportedOperationException("Should never be called!")

    /**
     * Parses the response from the site and returns a [MangasPage] object.
     *
     * @param response the response from the site.
     */
    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException("Should never be called!")

    /**
     * Returns the request for latest manga given the page.
     *
     * @param page the page number to retrieve.
     */
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException("Should never be called!")

    /**
     * Parses the response from the site and returns a [MangasPage] object.
     *
     * @param response the response from the site.
     */
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException("Should never be called!")

    /**
     * Parses the response from the site and returns the details of a manga.
     *
     * @param response the response from the site.
     */
    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException("Should never be called!")

    /**
     * Parses the response from the site and returns a list of chapters.
     *
     * @param response the response from the site.
     */
    override fun chapterListParse(response: Response) = throw UnsupportedOperationException("Should never be called!")

    /**
     * Parses the response from the site and returns a list of pages.
     *
     * @param response the response from the site.
     */
    override fun pageListParse(response: Response) = throw UnsupportedOperationException("Should never be called!")

    /**
     * Parses the response from the site and returns the absolute url to the source image.
     *
     * @param response the response from the site.
     */
    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException("Should never be called!")

    /**
     * Base url of the website without the trailing slash, like: http://mysite.com
     */
    override val baseUrl get() = source().baseUrl

    /**
     * Whether the source has support for latest updates.
     */
    override val supportsLatest get() = source().supportsLatest

    /**
     * Name of the source.
     */
    override val name get() = source().name

    /**
     * An ISO 639-1 compliant language code (two letters in lower case).
     */
    override val lang get() = source().lang

    // ===> OPTIONAL FIELDS

    /**
     * Id of the source. By default it uses a generated id using the first 16 characters (64 bits)
     * of the MD5 of the string: sourcename/language/versionId
     * Note the generated id sets the sign bit to 0.
     */
    override val id get() = source().id

    /**
     * Default network client for doing requests.
     */
    override val client get() = source().client

    /**
     * Visible name of the source.
     */
    override fun toString() = source().toString()

    @Suppress("DEPRECATION")
    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getPopularManga"))
    override fun fetchPopularManga(page: Int) = source().fetchPopularManga(page)

    override suspend fun getPopularManga(page: Int) = source().getPopularManga(page)

    @Suppress("DEPRECATION")
    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getSearchManga"))
    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList
    ) = source().fetchSearchManga(page, query, filters)

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList
    ) = source().getSearchManga(page, query, filters)

    @Suppress("DEPRECATION")
    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getLatestUpdates"))
    override fun fetchLatestUpdates(page: Int) = source().fetchLatestUpdates(page)

    override suspend fun getLatestUpdates(page: Int) = source().getLatestUpdates(page)

    override suspend fun getMangaDetails(manga: SManga) = source().getMangaDetails(manga)

    override fun mangaDetailsRequest(manga: SManga) = source().mangaDetailsRequest(manga)

    override suspend fun getChapterList(manga: SManga) = source().getChapterList(manga)

    override suspend fun getPageList(chapter: SChapter) = source().getPageList(chapter)

    override suspend fun getImageUrl(page: Page) = source().getImageUrl(page)

    override suspend fun getImage(page: Page) = source().getImage(page)

    /**
     * Called before inserting a new chapter into database. Use it if you need to override chapter
     * fields, like the title or the chapter number. Do not change anything to [manga].
     *
     * @param chapter the chapter to be added.
     * @param manga the manga of the chapter.
     */
    @Suppress("DEPRECATION")
    @Deprecated("All modifications should be done when constructing the chapter")
    override fun prepareNewChapter(
        chapter: SChapter,
        manga: SManga
    ) = source().prepareNewChapter(chapter, manga)

    /**
     * Returns the list of filters for the source.
     */
    override fun getFilterList() = source().getFilterList()

    fun source(): HttpSource {
        return if (prefs.eh_delegateSources().get()) {
            enchancedSource
        } else {
            originalSource
        }
    }
}

fun Source.getMainSource(): Source =
    if (this is EnhancedHttpSource) {
        this.source()
    } else {
        this
    }
