package eu.kanade.tachiyomi.data.track.kavita

import android.content.Context
import android.graphics.Color
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.lang.runAsObservable
import rx.Completable
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest

class Kavita(private val context: Context, id: Int) : TrackService(id), EnhancedTrackService {
    override val name = "Kavita"

    private val interceptor by lazy { KavitaInterceptor(this) }

    val api by lazy { KavitaApi(client, interceptor) }

    private val sourceManager: SourceManager by injectLazy()

    var authentications: OAuth? = null

    fun loadOAuth() {
        val oauth = OAuth()
        for (sourceIndex in 1..3) {
            val authentication = oauth.authentications[sourceIndex - 1]
            val sourceId by lazy {
                val key = "kavita_$sourceIndex/all/1" // Hardcoded versionID to 1
                val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
                (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }
                    .reduce(Long::or) and Long.MAX_VALUE
            }
            val source = sourceManager.get(sourceId) as? ConfigurableSource ?: continue
            val sourcePreferences = source.getPreferences()

            val prefApiUrl = sourcePreferences.getString("APIURL", "")
            val prefApiKey = sourcePreferences.getString("APIKEY", "")
            if (prefApiUrl.isNullOrEmpty() || prefApiKey.isNullOrEmpty()) {
                // Source not configured. Skip
                continue
            }

            val token = api.getNewToken(apiUrl = prefApiUrl, apiKey = prefApiKey)
            if (token.isNullOrEmpty()) {
                // Source is not accessible. Skip
                continue
            }

            authentication.apiUrl = prefApiUrl
            authentication.jwtToken = token
        }
        authentications = oauth
    }

    override fun getLogo() = R.drawable.ic_tracker_kavita

    override fun getLogoColor() = Color.rgb(74, 198, 148)

    override fun getStatusList(): List<Int> = listOf(UNREAD, READING, COMPLETED)

    override fun getStatus(status: Int): String =
        with(context) {
            when (status) {
                UNREAD -> getString(R.string.unread)
                READING -> getString(R.string.reading)
                COMPLETED -> getString(R.string.completed)
                else -> ""
            }
        }

    override fun getCompletionStatus(): Int = COMPLETED

    override fun getScoreList(): List<String> = emptyList()

    override fun displayScore(track: Track): String = ""

    override fun add(track: Track): Observable<Track> = Observable.just(track)

    override fun update(track: Track): Observable<Track> {
        return runAsObservable({
            if (track.status != COMPLETED) {
                if (track.last_chapter_read > 0) {
                    track.status =
                        if (track.last_chapter_read == track.total_chapters && track.total_chapters > 0) {
                            COMPLETED
                        } else {
                            READING
                        }
                }
            }
            api.updateProgress(track)
        })
    }

    override fun bind(track: Track): Observable<Track> = Observable.just(track)

    override fun search(query: String): Observable<List<TrackSearch>> {
        throw UnsupportedOperationException("Not used: Kavita is an enhanced tracker")
    }

    override fun refresh(track: Track): Observable<Track> {
        return runAsObservable({
            val remoteTrack = api.getTrackSearch(track.tracking_url)
            track.copyPersonalFrom(remoteTrack)
            track.total_chapters = remoteTrack.total_chapters
            track
        })
    }

    override fun login(
        username: String,
        password: String
    ): Completable = Completable.fromAction { loginNoop() }

    // [TrackService].isLogged works by checking that credentials are saved.
    // By saving dummy, unused credentials, we can activate the tracker simply by login/logout.
    override fun loginNoop() {
        saveCredentials("user", "pass")
    }

    override fun getAcceptedSources() = listOf("eu.kanade.tachiyomi.extension.all.kavita.Kavita")

    override suspend fun match(manga: Manga): TrackSearch? =
        try {
            api.getTrackSearch(manga.url)
        } catch (e: Exception) {
            null
        }

    override fun isTrackFrom(
        track: Track,
        manga: Manga,
        source: Source?
    ): Boolean = track.tracking_url == manga.url && source?.let { accept(it) } == true

    override fun migrateTrack(
        track: Track,
        manga: Manga,
        newSource: Source
    ): Track? =
        if (accept(newSource)) {
            track.also { it.tracking_url = manga.url }
        } else {
            null
        }

    companion object {
        const val UNREAD = 1
        const val READING = 2
        const val COMPLETED = 3
    }
}
