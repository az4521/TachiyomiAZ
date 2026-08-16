package eu.kanade.tachiyomi.ui.recent.history

import android.os.Bundle
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaChapterHistory
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.util.lang.asFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import eu.kanade.tachiyomi.ui.recent.DateSectionItem
import eu.kanade.tachiyomi.util.lang.toDateKey
import uy.kohesive.injekt.injectLazy
import java.util.Calendar
import java.util.Comparator
import java.util.Date
import java.util.TreeMap

/**
 * Presenter of HistoryFragment.
 * Contains information and data for fragment.
 * Observable updates should be called from here.
 */
class HistoryPresenter : BasePresenter<HistoryController>() {
    /**
     * Used to connect to database
     */
    val db: DatabaseHelper by injectLazy()
    var lastCount = 25
    var lastSearch = ""

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        // Used to get a list of recently read manga
        updateList()
    }

    fun requestNext(
        offset: Int,
        search: String = ""
    ) {
        lastCount = offset
        lastSearch = search
        getRecentMangaFlow(offset, search)
            .collectLatestCache(
                { view, mangas ->
                    view.onNextManga(mangas)
                },
                HistoryController::onAddPageError
            )
    }

    /**
     * Get recent manga observable
     * @return list of history
     */
    private fun getRecentMangaFlow(
        offset: Int = 0,
        search: String = ""
    ): Flow<List<HistoryItem>> {
        // Set date for recent manga
        val cal = Calendar.getInstance()
        cal.time = Date()
        cal.add(Calendar.YEAR, -50)

        return db.getRecentMangaAsFlow(cal.time, offset, search)
            .map { recents ->
                val map = TreeMap<Date, MutableList<MangaChapterHistory>> { d1, d2 -> d2.compareTo(d1) }
                val byDay =
                    recents
                        .groupByTo(map, { it.history.last_read.toDateKey() })
                byDay.flatMap {
                    val dateItem = DateSectionItem(it.key)
                    it.value.map { HistoryItem(it, dateItem) }
                }
            }
    }

    /**
     * Get recent manga observable
     * @return list of history
     */
    private fun getRecentMangaLimitFlow(
        offset: Int = 0,
        search: String = ""
    ): Flow<List<HistoryItem>> {
        // Set limit for recent manga
        val cal = Calendar.getInstance()
        cal.time = Date()
        cal.add(Calendar.YEAR, -50)

        return db.getRecentMangaLimitAsFlow(cal.time, lastCount, search)
            .map { recents ->
                val map = TreeMap<Date, MutableList<MangaChapterHistory>> { d1, d2 -> d2.compareTo(d1) }
                val byDay =
                    recents
                        .groupByTo(map, { it.history.last_read.toDateKey() })
                byDay.flatMap { entry ->
                    val dateItem = DateSectionItem(entry.key)
                    entry.value.map { HistoryItem(it, dateItem) }
                }
            }
    }

    /**
     * Reset last read of chapter to 0L
     * @param history history belonging to chapter
     */
    fun removeFromHistory(history: History) {
        history.last_read = 0L
        db.updateHistoryLastRead(history)
        updateList()
    }

    fun updateList(search: String? = null) {
        lastSearch = search ?: lastSearch
        getRecentMangaLimitFlow(lastCount, lastSearch).take(1)
            .collectLatestCache(
                { view, mangas ->
                    view.onNextManga(mangas, true)
                },
                HistoryController::onAddPageError
            )
    }

    /**
     * Removes all chapters belonging to manga from history.
     * @param mangaId id of manga
     */
    fun removeAllFromHistory(mangaId: Long) {
        val history = db.getHistoryByMangaId(mangaId)
        history.forEach { it.last_read = 0L }
        db.updateHistoryLastRead(history)
        updateList()
    }

    /**
     * Retrieves the next chapter of the given one.
     *
     * @param chapter the chapter of the history object.
     * @param manga the manga of the chapter.
     */
    fun getNextChapter(
        chapter: Chapter,
        manga: Manga
    ): Chapter? {
        if (!chapter.read) {
            return chapter
        }

        val sortFunction: (Chapter, Chapter) -> Int =
            when (manga.sorting) {
                Manga.SORTING_SOURCE -> { c1, c2 -> c2.source_order.compareTo(c1.source_order) }
                Manga.SORTING_NUMBER -> { c1, c2 -> c1.chapter_number.compareTo(c2.chapter_number) }
                Manga.SORTING_UPLOAD_DATE -> { c1, c2 -> c1.date_upload.compareTo(c2.date_upload) }
                else -> throw NotImplementedError("Unknown sorting method")
            }

        val chapters =
            db.getChapters(manga)
                .sortedWith(Comparator { c1, c2 -> sortFunction(c1, c2) })

        val currChapterIndex = chapters.indexOfFirst { chapter.id == it.id }
        return when (manga.sorting) {
            Manga.SORTING_SOURCE -> chapters.getOrNull(currChapterIndex + 1)
            Manga.SORTING_NUMBER -> {
                val chapterNumber = chapter.chapter_number

                ((currChapterIndex + 1) until chapters.size)
                    .map { chapters[it] }
                    .firstOrNull {
                        it.chapter_number > chapterNumber &&
                            it.chapter_number <= chapterNumber + 1
                    }
            }
            Manga.SORTING_UPLOAD_DATE -> {
                chapters.drop(currChapterIndex + 1)
                    .firstOrNull { it.date_upload >= chapter.date_upload }
            }
            else -> throw NotImplementedError("Unknown sorting method")
        }
    }
}
