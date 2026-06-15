package exh.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable

abstract class DelegatedHttpSource(val delegate: HttpSource) : HttpSource() {
    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException("Should never be called!")

    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException("Should never be called!")

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList
    ) = throw UnsupportedOperationException("Should never be called!")

    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException("Should never be called!")

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException("Should never be called!")

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException("Should never be called!")

    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException("Should never be called!")

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException("Should never be called!")

    override fun pageListParse(response: Response) = throw UnsupportedOperationException("Should never be called!")

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException("Should never be called!")

    /**
     * Base url of the website without the trailing slash, like: http://mysite.com
     */
    override val baseUrl get() = delegate.baseUrl

    /**
     * Whether the source has support for latest updates.
     */
    override val supportsLatest get() = delegate.supportsLatest

    /**
     * Name of the source.
     */
    final override val name get() = delegate.name

    // ===> OPTIONAL FIELDS

    override val id get() = delegate.id

    final override val client get() = delegate.client

    /**
     * You must NEVER call super.client if you override this!
     */
    open val baseHttpClient: OkHttpClient? = null
    open val networkHttpClient: OkHttpClient get() = network.client
    open val networkCloudflareClient: OkHttpClient get() = network.cloudflareClient

    override fun toString() = delegate.toString()

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getPopularManga"))
    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        ensureDelegateCompatible()
        @Suppress("DEPRECATION")
        return delegate.fetchPopularManga(page)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        ensureDelegateCompatible()
        return delegate.getPopularManga(page)
    }

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getSearchManga"))
    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList
    ): Observable<MangasPage> {
        ensureDelegateCompatible()
        @Suppress("DEPRECATION")
        return delegate.fetchSearchManga(page, query, filters)
    }

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList
    ): MangasPage {
        ensureDelegateCompatible()
        return delegate.getSearchManga(page, query, filters)
    }

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getLatestUpdates"))
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        ensureDelegateCompatible()
        @Suppress("DEPRECATION")
        return delegate.fetchLatestUpdates(page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        ensureDelegateCompatible()
        return delegate.getLatestUpdates(page)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        ensureDelegateCompatible()
        return delegate.getMangaDetails(manga)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        ensureDelegateCompatible()
        return delegate.mangaDetailsRequest(manga)
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        ensureDelegateCompatible()
        return delegate.getChapterList(manga)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        ensureDelegateCompatible()
        return delegate.getPageList(chapter)
    }

    override suspend fun getImageUrl(page: Page): String {
        ensureDelegateCompatible()
        return delegate.getImageUrl(page)
    }

    override suspend fun getImage(page: Page): Response {
        ensureDelegateCompatible()
        return delegate.getImage(page)
    }

    /**
     * Called before inserting a new chapter into database.
     */
    @Suppress("DEPRECATION")
    @Deprecated("All modifications should be done when constructing the chapter")
    override fun prepareNewChapter(
        chapter: SChapter,
        manga: SManga
    ) {
        ensureDelegateCompatible()
        return delegate.prepareNewChapter(chapter, manga)
    }

    override fun getFilterList() = delegate.getFilterList()

    private fun ensureDelegateCompatible() {
        if (versionId != delegate.versionId ||
            lang != delegate.lang
        ) {
            throw IncompatibleDelegateException(
                "Delegate source is not compatible (versionId: $versionId <=> ${delegate.versionId}, lang: $lang <=> ${delegate.lang})!"
            )
        }
    }

    class IncompatibleDelegateException(message: String) : RuntimeException(message)

    init {
        delegate.bindDelegate(this)
    }
}
