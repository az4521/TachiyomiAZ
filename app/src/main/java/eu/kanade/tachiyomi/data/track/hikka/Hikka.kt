package eu.kanade.tachiyomi.data.track.hikka

import android.content.Context
import android.graphics.Color
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.hikka.dto.HKOAuth
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.util.lang.runAsObservable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rx.Completable
import rx.Observable
import uy.kohesive.injekt.injectLazy

class Hikka(private val context: Context, id: Int) : TrackService(id) {
    override val name = "Hikka"

    private val json: Json by injectLazy()

    private val interceptor by lazy { HikkaInterceptor(this) }

    private val api by lazy { HikkaApi(id, client, interceptor) }

    override val supportsReadingDates: Boolean = true

    override fun getLogo() = R.drawable.ic_tracker_hikka

    override fun getLogoColor() = Color.rgb(0, 0, 0)

    override fun getStatusList(): List<Int> {
        return listOf(READING, PLAN_TO_READ, COMPLETED, ON_HOLD, DROPPED, REREADING)
    }

    override fun getStatus(status: Int): String =
        with(context) {
            when (status) {
                READING -> getString(R.string.reading)
                PLAN_TO_READ -> getString(R.string.plan_to_read)
                COMPLETED -> getString(R.string.completed)
                ON_HOLD -> getString(R.string.on_hold)
                DROPPED -> getString(R.string.dropped)
                REREADING -> getString(R.string.repeating)
                else -> ""
            }
        }

    override fun getCompletionStatus(): Int = COMPLETED

    override fun getScoreList(): List<String> = SCORE_LIST

    override fun displayScore(track: Track): String {
        return track.score.toInt().toString()
    }

    override fun add(track: Track): Observable<Track> {
        return runAsObservable({ api.addUserManga(track) })
    }

    override fun update(track: Track): Observable<Track> {
        return runAsObservable({ updateInternal(track) })
    }

    private suspend fun updateInternal(track: Track): Track {
        // Deliberately no status juggling here: this app routes both "user picked a status" and
        // "a chapter was read" through update(), so deriving status from progress would silently
        // revert a manually chosen status. Completion is handled by the track sheet instead.
        return api.updateUserManga(track)
    }

    override fun bind(track: Track): Observable<Track> {
        return runAsObservable({
            val readContent = api.getRead(track)
            val remoteTrack = api.getManga(track)

            track.copyPersonalFrom(remoteTrack)
            track.media_id = remoteTrack.media_id
            track.library_id = remoteTrack.library_id

            val hasReadChapters = track.last_chapter_read > 0
            if (track.status != COMPLETED) {
                val isRereading = track.status == REREADING
                track.status = if (!isRereading && hasReadChapters) READING else track.status
            }

            if (readContent != null) {
                track.score = readContent.score.toFloat()
                track.last_chapter_read = readContent.chapters
                track.started_reading_date = (readContent.startDate ?: 0L) * 1000
                track.finished_reading_date = (readContent.endDate ?: 0L) * 1000
                updateInternal(track)
            } else {
                track.status = if (hasReadChapters) READING else PLAN_TO_READ
                track.score = 0f
                updateInternal(track)
            }
        })
    }

    override fun search(query: String): Observable<List<TrackSearch>> {
        return runAsObservable({ api.searchManga(query) })
    }

    override fun refresh(track: Track): Observable<Track> {
        return runAsObservable({
            val remoteTrack = api.getManga(track)
            track.copyPersonalFrom(remoteTrack)
            track.total_chapters = remoteTrack.total_chapters

            val readContent = api.getRead(track) ?: throw Exception("Could not find manga")

            track.score = readContent.score.toFloat()
            track.last_chapter_read = readContent.chapters
            track.status = toTrackStatus(readContent.status)
            track.started_reading_date = (readContent.startDate ?: 0L) * 1000
            track.finished_reading_date = (readContent.endDate ?: 0L) * 1000

            track
        })
    }

    override fun login(
        username: String,
        password: String
    ) = login(password)

    fun login(reference: String): Completable {
        return runAsObservable({
            val oauth = api.accessToken(reference)
            interceptor.setAuth(oauth)
            val user = api.getCurrentUser()
            saveCredentials(user.reference, oauth.accessToken)
        }).doOnError { logout() }
            .toCompletable()
    }

    override fun logout() {
        super.logout()
        preferences.trackToken(this).delete()
        interceptor.setAuth(null)
    }

    fun saveOAuth(oAuth: HKOAuth?) {
        preferences.trackToken(this).set(json.encodeToString(oAuth))
    }

    fun loadOAuth(): HKOAuth? {
        return try {
            json.decodeFromString<HKOAuth>(preferences.trackToken(this).get()!!)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val READING = 0
        const val COMPLETED = 1
        const val ON_HOLD = 2
        const val DROPPED = 3
        const val PLAN_TO_READ = 4
        const val REREADING = 5

        private val SCORE_LIST = IntRange(0, 10).map(Int::toString)
    }
}
