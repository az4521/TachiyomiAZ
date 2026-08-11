package eu.kanade.tachiyomi.data.track.mangabaka

import android.content.Context
import android.graphics.Color
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaOAuth
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.util.lang.runAsObservable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rx.Completable
import rx.Observable
import uy.kohesive.injekt.injectLazy

class MangaBaka(private val context: Context, id: Int) : TrackService(id) {
    override val name = "MangaBaka"

    private val json: Json by injectLazy()

    private val interceptor by lazy { MangaBakaInterceptor(this) }

    private val api by lazy { MangaBakaApi(id, client, interceptor) }

    override val supportsReadingDates: Boolean = true

    override fun getLogo() = R.drawable.ic_tracker_mangabaka

    override fun getLogoColor() = Color.rgb(255, 103, 64)

    override fun getStatusList(): List<Int> {
        return listOf(READING, COMPLETED, PAUSED, DROPPED, PLAN_TO_READ, REREADING, CONSIDERING)
    }

    override fun getStatus(status: Int): String =
        with(context) {
            when (status) {
                CONSIDERING -> getString(R.string.considering)
                COMPLETED -> getString(R.string.completed)
                DROPPED -> getString(R.string.dropped)
                PAUSED -> getString(R.string.paused)
                PLAN_TO_READ -> getString(R.string.plan_to_read)
                READING -> getString(R.string.reading)
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
        return runAsObservable({ api.addLibManga(track) })
    }

    override fun update(track: Track): Observable<Track> {
        return runAsObservable({ updateInternal(track) })
    }

    private suspend fun updateInternal(track: Track): Track {
        // Deliberately no status juggling here: this app routes both "user picked a status" and
        // "a chapter was read" through update(), so deriving status from progress would silently
        // revert a manually chosen status. Completion is handled by the track sheet instead.
        return api.updateLibManga(track)
    }

    override fun bind(track: Track): Observable<Track> {
        return runAsObservable({
            val remoteTrack = api.findLibManga(track)
            if (remoteTrack != null) {
                track.copyPersonalFrom(remoteTrack)
                track.library_id = remoteTrack.library_id
                if (track.status != COMPLETED) {
                    track.status = if (track.last_chapter_read > 0) READING else track.status
                }
                updateInternal(track)
            } else {
                track.status = if (track.last_chapter_read > 0) READING else PLAN_TO_READ
                track.score = 0f
                api.addLibManga(track)
            }
        })
    }

    override fun search(query: String): Observable<List<TrackSearch>> {
        return runAsObservable({ api.search(query) })
    }

    override fun refresh(track: Track): Observable<Track> {
        return runAsObservable({
            val remoteTrack = api.findLibManga(track) ?: throw Exception("Could not find manga")
            track.copyPersonalFrom(remoteTrack)
            track.total_chapters = remoteTrack.total_chapters
            track
        })
    }

    override fun login(
        username: String,
        password: String
    ) = login(password)

    fun login(code: String): Completable {
        return runAsObservable({
            val oauth = api.getAccessToken(code)
            interceptor.setAuth(oauth)
            val user = api.getCurrentUser()
            saveCredentials(user.preferredUsername ?: user.nickname ?: user.id, oauth.accessToken)
        }).doOnError { logout() }
            .toCompletable()
    }

    override fun logout() {
        super.logout()
        preferences.trackToken(this).delete()
        interceptor.setAuth(null)
    }

    fun saveToken(oAuth: MangaBakaOAuth?) {
        preferences.trackToken(this).set(json.encodeToString(oAuth))
    }

    fun restoreToken(): MangaBakaOAuth? {
        return try {
            json.decodeFromString<MangaBakaOAuth>(preferences.trackToken(this).get()!!)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val READING = 0
        const val COMPLETED = 1
        const val PAUSED = 2
        const val DROPPED = 3
        const val PLAN_TO_READ = 4
        const val REREADING = 5
        const val CONSIDERING = 6

        // MangaBaka rates 0-100, not 0-10. Using every step so any score coming back from the
        // API maps onto an entry in this list.
        private val SCORE_LIST = IntRange(0, 100).map(Int::toString)
    }
}
