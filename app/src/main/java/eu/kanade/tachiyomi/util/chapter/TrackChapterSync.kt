package eu.kanade.tachiyomi.util.chapter

import android.app.Application
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.job.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.job.DelayedTrackingUpdateJob
import eu.kanade.tachiyomi.util.system.isOnline
import rx.Completable
import rx.Observable
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Updates the last read chapter on every logged tracking service for [manga], as long as
 * [chapterRead] is ahead of what that service already has.
 *
 * This mirrors what the reader does when a chapter is finished, so that marking chapters as read
 * from the chapter list or the updates tab syncs the same way. Runs in the background and ignores
 * errors; when offline the update is queued through [DelayedTrackingStore] instead.
 */
fun updateTrackChapterMarkedRead(
    manga: Manga,
    chapterRead: Int
) {
    val db: DatabaseHelper = Injekt.get()
    val trackManager: TrackManager = Injekt.get()
    val delayedTrackingStore: DelayedTrackingStore = Injekt.get()
    val context: Application = Injekt.get()

    db.getTracks(manga).asRxSingle()
        .flatMapCompletable { trackList ->
            Completable.concat(
                trackList.map { track ->
                    val service = trackManager.getService(track.sync_id)
                    if (service != null && service.isLogged && chapterRead > track.last_chapter_read) {
                        track.last_chapter_read = chapterRead

                        // These should finish even if the caller goes away.
                        if (context.isOnline()) {
                            Observable.defer { service.update(track) }
                                .map { db.insertTrack(track).executeAsBlocking() }
                                .toCompletable()
                                .onErrorComplete()
                        } else {
                            Observable.defer { delayedTrackingStore.addItem(track) }
                                .map { DelayedTrackingUpdateJob.setupTask(context) }
                                .toCompletable()
                                .onErrorComplete()
                        }
                    } else {
                        Completable.complete()
                    }
                }
            )
        }
        .onErrorComplete()
        .subscribeOn(Schedulers.io())
        .subscribe()
}
