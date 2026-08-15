package eu.kanade.tachiyomi.data.track.suwayomi

import android.content.Context
import android.graphics.Color
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.Source

class Suwayomi(private val context: Context, id: Int) : TrackService(id), EnhancedTrackService {
    override val name = "Suwayomi"

    val api by lazy { SuwayomiApi(id) }

    override fun getLogo() = R.drawable.ic_tracker_suwayomi

    override fun getLogoColor() = Color.rgb(255, 35, 35)

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

    override suspend fun add(track: Track): Track = track

    override suspend fun update(track: Track): Track {
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
        return api.updateProgress(track)
    }

    override suspend fun bind(track: Track): Track = track

    override suspend fun search(query: String): List<TrackSearch> {
        throw UnsupportedOperationException("Not used: Suwayomi is an enhanced tracker")
    }

    override suspend fun refresh(track: Track): Track {
        val remoteTrack = api.getTrackSearch(track.media_id)
        track.copyPersonalFrom(remoteTrack)
        track.total_chapters = remoteTrack.total_chapters
        return track
    }

    override suspend fun login(
        username: String,
        password: String
    ) = loginNoop()

    // [TrackService].isLogged works by checking that credentials are saved.
    // By saving dummy, unused credentials, we can activate the tracker simply by login/logout.
    override fun loginNoop() {
        saveCredentials("user", "pass")
    }

    override fun getAcceptedSources() = listOf("eu.kanade.tachiyomi.extension.all.tachidesk.Tachidesk")

    override suspend fun match(manga: Manga): TrackSearch? =
        try {
            api.getTrackSearch(manga.url.substringAfterLast("/").toLong())
        } catch (e: Exception) {
            null
        }

    override fun isTrackFrom(
        track: Track,
        manga: Manga,
        source: Source?
    ): Boolean = track.media_id == manga.url.substringAfterLast("/").toLongOrNull() &&
        source?.let { accept(it) } == true

    override fun migrateTrack(
        track: Track,
        manga: Manga,
        newSource: Source
    ): Track? =
        if (accept(newSource)) {
            track.also { it.media_id = manga.url.substringAfterLast("/").toLong() }
        } else {
            null
        }

    companion object {
        const val UNREAD = 1
        const val READING = 2
        const val COMPLETED = 3
    }
}
