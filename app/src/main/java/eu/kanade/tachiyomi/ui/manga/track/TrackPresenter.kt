package eu.kanade.tachiyomi.ui.manga.track

import android.os.Bundle
import com.elvishew.xlog.XLog
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import eu.kanade.tachiyomi.util.lang.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TrackPresenter(
    val manga: Manga,
    preferences: PreferencesHelper = Injekt.get(),
    private val db: DatabaseHelper = Injekt.get(),
    private val trackManager: TrackManager = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get()
) : BasePresenter<TrackController>() {
    private val context = preferences.context

    private var trackList: List<TrackItem> = emptyList()

    private val loggedServices by lazy { trackManager.services.filter { it.isLogged } }

    private var trackJob: Job? = null

    private var searchJob: Job? = null

    private var refreshJob: Job? = null

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        fetchTrackings()
        autoBindEnhancedTrackers()
    }

    /**
     * Enhanced trackers are never bound manually: if one accepts this manga's source and there is
     * no track for it yet, match it and register the result automatically.
     */
    private fun autoBindEnhancedTrackers() {
        val source = sourceManager.get(manga.source) ?: return
        val enhanced =
            loggedServices
                .filterIsInstance<EnhancedTrackService>()
                .filter { it.accept(source) }
        if (enhanced.isEmpty()) return

        launchIO {
            val existing = db.getTracks(manga).executeAsBlocking()
            enhanced.forEach { service ->
                service as TrackService
                if (existing.any { it.sync_id == service.id }) return@forEach
                try {
                    val matched = service.match(manga) ?: return@forEach
                    matched.manga_id = manga.id!!
                    service.bind(matched)
                    db.insertTrack(matched).executeAsBlocking()
                } catch (e: Exception) {
                    XLog.w("Failed to auto-bind ${service.name}", e)
                }
            }
        }
    }

    fun fetchTrackings() {
        trackJob?.cancel()
        trackJob =
            db.getTracks(manga)
                .asRxObservable()
                .asFlow()
                .map { tracks ->
                    loggedServices.map { service ->
                        TrackItem(tracks.find { it.sync_id == service.id }, service)
                    }
                }
                .onEach { trackList = it }
                .collectLatestCache(TrackController::onNextTrackings)
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob =
            presenterScope.launch {
                try {
                    withIOContext {
                        trackList.filter { it.track != null }.forEach { item ->
                            try {
                                val updated = item.service.refresh(item.track!!)
                                db.insertTrack(updated).executeAsBlocking()
                            } catch (e: Exception) {
                                // Mirrors the previous per-item onErrorReturn: one tracker
                                // failing must not abort refreshing the others.
                                XLog.w("Failed to refresh ${item.service.name}", e)
                            }
                        }
                    }
                    view?.onRefreshDone()
                } catch (e: Exception) {
                    view?.onRefreshError(e)
                }
            }
    }

    fun search(
        query: String,
        service: TrackService
    ) {
        searchJob?.cancel()
        searchJob =
            presenterScope.launch {
                try {
                    val results = withIOContext { service.search(query) }
                    view?.onSearchResults(results)
                } catch (e: Exception) {
                    view?.onSearchResultsError(e)
                }
            }
    }

    fun registerTracking(
        item: Track?,
        service: TrackService
    ) {
        if (item != null) {
            item.manga_id = manga.id!!
            presenterScope.launch {
                try {
                    withIOContext {
                        service.bind(item)
                        db.insertTrack(item).executeAsBlocking()
                    }
                } catch (error: Exception) {
                    context.toast(error.message)
                }
            }
        } else {
            unregisterTracking(service)
        }
    }

    fun unregisterTracking(service: TrackService) {
        db.deleteTrackForManga(manga, service).executeAsBlocking()
    }

    private fun updateRemote(
        track: Track,
        service: TrackService
    ) {
        presenterScope.launch {
            try {
                withIOContext {
                    service.update(track)
                    db.insertTrack(track).executeAsBlocking()
                }
                view?.onRefreshDone()
            } catch (error: Exception) {
                view?.onRefreshError(error)

                // Restart on error to set old values
                fetchTrackings()
            }
        }
    }

    fun setStatus(
        item: TrackItem,
        index: Int
    ) {
        val track = item.track!!
        track.status = item.service.getStatusList()[index]
        if (track.status == item.service.getCompletionStatus() && track.total_chapters != 0) {
            track.last_chapter_read = track.total_chapters
        }
        updateRemote(track, item.service)
    }

    fun setScore(
        item: TrackItem,
        index: Int
    ) {
        val track = item.track!!
        track.score = item.service.indexToScore(index)
        updateRemote(track, item.service)
    }

    fun setLastChapterRead(
        item: TrackItem,
        chapterNumber: Int
    ) {
        val track = item.track!!
        track.last_chapter_read = chapterNumber
        if (track.total_chapters != 0 && track.last_chapter_read == track.total_chapters) {
            track.status = item.service.getCompletionStatus()
        }
        updateRemote(track, item.service)
    }

    fun setStartDate(
        item: TrackItem,
        date: Long
    ) {
        val track = item.track!!
        track.started_reading_date = date
        updateRemote(track, item.service)
    }

    fun setFinishDate(
        item: TrackItem,
        date: Long
    ) {
        val track = item.track!!
        track.finished_reading_date = date
        updateRemote(track, item.service)
    }
}
