package eu.kanade.tachiyomi.source.online

import com.elvishew.xlog.XLog
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.util.lang.runAsObservable
import rx.Observable

fun HttpSource.fetchImageUrlWithStatus(page: Page): Observable<Page> {
    page.status = Page.LOAD_PAGE
    // Use the suspend API so sources that only override `getImageUrl` resolve correctly.
    return runAsObservable({ getImageUrl(page) })
        .doOnError { page.status = Page.ERROR }
        .onErrorReturn {
            // [EXH]
            XLog.w("> Failed to fetch image URL!", it)
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
        .doOnNext { page.imageUrl = it }
        .map { page }
}

fun HttpSource.fetchAllImageUrlsFromPageList(pages: List<Page>): Observable<Page> {
    return Observable.from(pages)
        .filter { !it.imageUrl.isNullOrEmpty() }
        .mergeWith(fetchRemainingImageUrlsFromPageList(pages))
}

fun HttpSource.fetchRemainingImageUrlsFromPageList(pages: List<Page>): Observable<Page> {
    return Observable.from(pages)
        .filter { it.imageUrl.isNullOrEmpty() }
        .concatMap { fetchImageUrlWithStatus(it) }
}
