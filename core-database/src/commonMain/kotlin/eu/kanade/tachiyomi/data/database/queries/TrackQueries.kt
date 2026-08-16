package eu.kanade.tachiyomi.data.database.queries

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import eu.kanade.tachiyomi.data.database.DbProvider
import eu.kanade.tachiyomi.data.database.mapTrack
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

interface TrackQueries : DbProvider {
    fun getAllTracks(): List<Track> =
        sqlDatabase.manga_syncQueries.getAllTracks(::mapTrack).executeAsList()

    /** Re-emits when the tracks table changes, replacing storio's asRxObservable. */
    fun getTracksAsFlow(manga: Manga): Flow<List<Track>> =
        sqlDatabase.manga_syncQueries
            .getTracksByMangaIdFlow(manga.id ?: 0L, ::mapTrack)
            .asFlow()
            .mapToList(Dispatchers.IO)

    fun getTracks(manga: Manga): List<Track> =
        sqlDatabase.manga_syncQueries
            .getTracksByMangaId(manga.id ?: 0L, ::mapTrack)
            .executeAsList()

    /**
     * The table has UNIQUE(manga_id, sync_id) ON CONFLICT REPLACE, so an insert for an existing
     * pair replaces the row -- which is what storio's put did here.
     */
    fun insertTrack(track: Track) {
        sqlDatabase.manga_syncQueries.transaction {
            val id = track.id
            if (id == null) {
                sqlDatabase.manga_syncQueries.insertTrack(
                    track.manga_id,
                    track.sync_id.toLong(),
                    track.media_id,
                    track.library_id,
                    track.title,
                    track.last_chapter_read.toLong(),
                    track.total_chapters.toLong(),
                    track.status.toLong(),
                    track.score.toDouble(),
                    track.tracking_url,
                    track.started_reading_date,
                    track.finished_reading_date,
                    if (track.private) 1L else 0L
                )
                track.id = sqlDatabase.manga_syncQueries.lastInsertRowId().executeAsOne()
            } else {
                sqlDatabase.manga_syncQueries.updateTrack(
                    track.manga_id,
                    track.sync_id.toLong(),
                    track.media_id,
                    track.library_id,
                    track.title,
                    track.last_chapter_read.toLong(),
                    track.total_chapters.toLong(),
                    track.status.toLong(),
                    track.score.toDouble(),
                    track.tracking_url,
                    track.started_reading_date,
                    track.finished_reading_date,
                    if (track.private) 1L else 0L,
                    id
                )
            }
        }
    }

    fun insertTracks(tracks: List<Track>) {
        sqlDatabase.manga_syncQueries.transaction {
            tracks.forEach { insertTrack(it) }
        }
    }

    /**
     * @param syncId the tracking service's id. Taken as a plain value rather than a TrackService
     *  so this stays free of the tracking layer, which is Android/JVM-side.
     */
    fun deleteTrackForManga(
        manga: Manga,
        syncId: Int
    ) {
        sqlDatabase.manga_syncQueries.deleteTrackForManga(manga.id ?: 0L, syncId.toLong())
    }
}
