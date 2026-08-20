package eu.kanade.tachiyomi.source.online

import com.elvishew.xlog.XLog
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
suspend fun HttpSource.getImageUrlWithStatus(page: Page): Page {
    page.status = Page.LOAD_PAGE
    // Use the suspend API so sources that only override `getImageUrl` resolve correctly.
    page.imageUrl =
        try {
            getImageUrl(page)
        } catch (e: Throwable) {
            page.status = Page.ERROR
            // [EXH]
            XLog.w("> Failed to fetch image URL!", e)
            XLog.w(
                "> (source.id: %s, source.name: %s, page.index: %s, page.url: %s, page.imageUrl: %s)",
                id,
                name,
                page.index,
                page.url,
                page.imageUrl
            )

            null
        }
    return page
}

/**
 * Emits each page once its image URL is known.
 *
 * A Flow rather than a List on purpose. This has to stay a pipeline: the downloader starts
 * fetching a page the moment its URL resolves, so resolution and downloading overlap. Returning a
 * List instead made it two phases -- resolve every URL, then download everything -- which left a
 * long silent stretch at the start of every chapter with no progress, and made the whole download
 * slower for no benefit.
 *
 * Pages that already carry a URL need no request and go first; the rest are resolved one at a
 * time, as the previous `concatMap` did, so the source is never hit with parallel URL requests.
 */
fun HttpSource.getAllImageUrlsFromPageList(pages: List<Page>): Flow<Page> =
    flow {
        pages.filter { !it.imageUrl.isNullOrEmpty() }.forEach { emit(it) }
        pages.filter { it.imageUrl.isNullOrEmpty() }.forEach { emit(getImageUrlWithStatus(it)) }
    }
