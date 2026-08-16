package eu.kanade.tachiyomi.source.online

import com.elvishew.xlog.XLog
import eu.kanade.tachiyomi.source.model.Page
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
 * Resolves image URLs for [pages], returning them in the order the downloader should consume
 * them: pages that already have a URL first, then the remaining ones resolved one at a time.
 * That ordering is what the previous `from(pages).filter(...).mergeWith(...)` produced.
 */
suspend fun HttpSource.getAllImageUrlsFromPageList(pages: List<Page>): List<Page> {
    val ready = pages.filter { !it.imageUrl.isNullOrEmpty() }
    val remaining = pages.filter { it.imageUrl.isNullOrEmpty() }.map { getImageUrlWithStatus(it) }
    return ready + remaining
}
