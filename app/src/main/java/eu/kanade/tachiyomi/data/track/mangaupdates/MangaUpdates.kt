package eu.kanade.tachiyomi.data.track.mangaupdates

import android.content.Context
import android.graphics.Color
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MUListItem
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MURating
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.copyTo
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.toTrackSearch
import eu.kanade.tachiyomi.data.track.model.TrackSearch

class MangaUpdates(private val context: Context, id: Int) : TrackService(id) {
    override val name = "MangaUpdates"

    private val interceptor by lazy { MangaUpdatesInterceptor(this) }

    private val api by lazy { MangaUpdatesApi(interceptor, client) }

    override fun getLogo() = R.drawable.ic_tracker_mangaupdates

    override fun getLogoColor() = Color.rgb(146, 160, 173)

    override fun getStatusList(): List<Int> {
        return listOf(READING_LIST, COMPLETE_LIST, ON_HOLD_LIST, UNFINISHED_LIST, WISH_LIST)
    }

    override fun getStatus(status: Int): String =
        with(context) {
            when (status) {
                READING_LIST -> getString(R.string.reading_list)
                WISH_LIST -> getString(R.string.wish_list)
                COMPLETE_LIST -> getString(R.string.complete_list)
                ON_HOLD_LIST -> getString(R.string.on_hold_list)
                UNFINISHED_LIST -> getString(R.string.unfinished_list)
                else -> ""
            }
        }

    override fun getCompletionStatus(): Int = COMPLETE_LIST

    override fun getScoreList(): List<String> = SCORE_LIST

    override fun indexToScore(index: Int): Float {
        return if (index == 0) 0f else SCORE_LIST[index].toFloat()
    }

    override fun displayScore(track: Track): String {
        return if (track.score <= 0f) "-" else track.score.toString()
    }

    override suspend fun add(track: Track): Track {
        api.addSeriesToList(track, track.last_chapter_read > 0)
        return track
    }

    override suspend fun update(track: Track): Track {
        // Deliberately no status juggling here: this app routes both "user picked a status"
        // and "a chapter was read" through update(), so promoting to READING_LIST based on
        // progress would silently revert a manually chosen status.
        api.updateSeriesListItem(track)
        return track
    }

    override suspend fun bind(track: Track): Track {
        return try {
            val (series, rating) = api.getSeriesListItem(track)
            track.copyFrom(series, rating)
        } catch (e: Exception) {
            // Not on any list yet, so add it.
            track.score = 0f
            api.addSeriesToList(track, track.last_chapter_read > 0)
            track
        }
    }

    override suspend fun search(query: String): List<TrackSearch> {
        return api.search(query).map { it.toTrackSearch(id) }
    }

    override suspend fun refresh(track: Track): Track {
        val (series, rating) = api.getSeriesListItem(track)
        return track.copyFrom(series, rating)
    }

    private fun Track.copyFrom(
        item: MUListItem,
        rating: MURating?
    ): Track =
        apply {
            item.copyTo(this)
            score = rating?.rating?.toFloat() ?: 0f
        }

    override suspend fun login(
        username: String,
        password: String
    ) {
        try {
            val authenticated = api.authenticate(username, password)
            interceptor.newAuth(authenticated.sessionToken)
            val currentUser = api.getCurrentUser()
            saveCredentials(currentUser.username, authenticated.sessionToken)
        } catch (e: Throwable) {
            logout()
            throw e
        }
    }

    override fun logout() {
        super.logout()
        interceptor.newAuth(null)
    }

    fun restoreSession(): String? {
        return getPassword().ifBlank { null }
    }

    companion object {
        const val READING_LIST = 0
        const val WISH_LIST = 1
        const val COMPLETE_LIST = 2
        const val UNFINISHED_LIST = 3
        const val ON_HOLD_LIST = 4

        private val SCORE_LIST =
            (0..10).flatMap { decimal ->
                when (decimal) {
                    0 -> listOf("-")
                    10 -> listOf("10.0")
                    else -> (0..9).map { fraction -> "$decimal.$fraction" }
                }
            }
    }
}
