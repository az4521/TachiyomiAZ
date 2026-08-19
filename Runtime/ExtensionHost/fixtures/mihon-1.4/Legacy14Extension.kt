package fixture

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import rx.Observable

class Legacy14Extension : CatalogueSource {
    override val id = 14L
    override val name = "Mihon 1.4 Rx Fixture"
    override val lang = "en"
    override val supportsLatest = false

    override fun fetchPopularManga(page: Int): Observable<MangasPage> =
        Observable.just(MangasPage(emptyList(), page < 2))

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> = Observable.just(MangasPage(emptyList(), false))

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> =
        Observable.just(MangasPage(emptyList(), false))

    override fun getFilterList() = FilterList()

    override fun fetchPageList(
        chapter: SChapter,
    ): Observable<List<Page>> = Observable.just(emptyList())
}
