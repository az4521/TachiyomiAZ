package eu.kanade.tachiyomi.ui.migration

import android.os.Bundle
import com.jakewharton.rxrelay.BehaviorRelay
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import eu.kanade.tachiyomi.util.lang.combineLatest
import eu.kanade.tachiyomi.util.lang.asFlow
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MigrationPresenter(
    private val sourceManager: SourceManager = Injekt.get(),
    private val db: DatabaseHelper = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get()
) : BasePresenter<MigrationController>() {
    var state = ViewState()
        private set(value) {
            field = value
            stateRelay.call(value)
        }

    private val stateRelay = BehaviorRelay.create(state)

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        db.getFavoriteMangasAsFlow()
            .onEach { state = state.copy(sourcesWithManga = findSourcesWithManga(it)) }
            .combine(
                stateRelay.asFlow()
                    .map { it.selectedSource }
                    .distinctUntilChanged()
            ) { library, source -> library to source }
            .filter { (_, source) -> source != null }
            .onEach { (library, source) ->
                // libraryToMigrationItem walks the whole library, so keep it off the main
                // thread the way the previous observeOn(Schedulers.io()) did, while leaving
                // the state assignment itself on main.
                val items = withIOContext { libraryToMigrationItem(library, source!!.id) }
                state = state.copy(mangaForSource = items)
            }
            .launchIn(presenterScope)

        stateRelay
            .asFlow()
            // Render the view when any field other than isReplacingManga changes
            .distinctUntilChanged { t1, t2 -> t1.isReplacingManga != t2.isReplacingManga }
            .collectLatestCache(MigrationController::render)
    }

    fun setSelectedSource(source: Source) {
        state = state.copy(selectedSource = source, mangaForSource = emptyList())
    }

    fun deselectSource() {
        state = state.copy(selectedSource = null, mangaForSource = emptyList())
    }

    private fun findSourcesWithManga(library: List<Manga>): List<SourceItem> {
        val header = SelectionHeader()
        return library.map { it.source }.toSet()
            .mapNotNull { if (it != LocalSource.ID) sourceManager.getOrStub(it) else null }
            .sortedBy { it.name.lowercase() }
            .map { SourceItem(it, header) }
    }

    private fun libraryToMigrationItem(
        library: List<Manga>,
        sourceId: Long
    ): List<MangaItem> {
        return library.filter { it.source == sourceId }.map(::MangaItem)
    }

    fun migrateManga(
        prevManga: Manga,
        manga: Manga,
        replace: Boolean
    ) {
        val source = sourceManager.get(manga.source) ?: return

        state = state.copy(isReplacingManga = true)

        presenterScope.launch {
            try {
                val chapters =
                    try {
                        withIOContext {
                            source.getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true).chapters
                        }
                    } catch (e: Throwable) {
                        // Matches the previous onErrorReturn { emptyList() }.
                        emptyList()
                    }
                try {
                    migrateMangaInternal(source, chapters, prevManga, manga, replace)
                } catch (e: Throwable) {
                    // The second onErrorReturn swallowed failures from the migration itself.
                }
            } finally {
                // Previously doOnUnsubscribe, which ran on completion, error and cancellation.
                state = state.copy(isReplacingManga = false)
            }
        }
    }

    private fun migrateMangaInternal(
        source: Source,
        sourceChapters: List<SChapter>,
        prevManga: Manga,
        manga: Manga,
        replace: Boolean
    ) {
        val flags = preferences.migrateFlags().get()
        val migrateChapters = MigrationFlags.hasChapters(flags)
        val migrateCategories = MigrationFlags.hasCategories(flags)
        val migrateTracks = MigrationFlags.hasTracks(flags)

        db.inTransaction {
            // Update chapters read
            if (migrateChapters) {
                try {
                    syncChaptersWithSource(db, sourceChapters, manga, source)
                } catch (e: Exception) {
                    // Worst case, chapters won't be synced
                }

                val prevMangaChapters = db.getChapters(prevManga)
                val maxChapterRead =
                    prevMangaChapters.filter { it.read }.maxByOrNull { it.chapter_number }?.chapter_number
                if (maxChapterRead != null) {
                    val dbChapters = db.getChapters(manga)
                    for (chapter in dbChapters) {
                        if (chapter.isRecognizedNumber && chapter.chapter_number <= maxChapterRead) {
                            chapter.read = true
                        }
                    }
                    db.insertChapters(dbChapters)
                }
            }
            // Update categories
            if (migrateCategories) {
                val categories = db.getCategoriesForManga(prevManga)
                val mangaCategories = categories.map { MangaCategory.create(manga, it) }
                db.setMangaCategories(mangaCategories, listOf(manga))
            }
            // Update track
            if (migrateTracks) {
                val tracks = db.getTracks(prevManga)
                for (track in tracks) {
                    track.id = null
                    track.manga_id = manga.id!!
                }
                db.insertTracks(tracks)
            }
            // Update favorite status
            if (replace) {
                prevManga.favorite = false
                db.updateMangaFavorite(prevManga)
            }
            manga.favorite = true
            db.updateMangaFavorite(manga)

            // SearchPresenter#networkToLocalManga may have updated the manga title, so ensure db gets updated title
            db.updateMangaTitle(manga)
        }
    }
}
