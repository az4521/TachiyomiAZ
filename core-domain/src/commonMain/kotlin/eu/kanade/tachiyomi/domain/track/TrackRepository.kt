package eu.kanade.tachiyomi.domain.track

import eu.kanade.tachiyomi.data.database.DatabaseHandler
import eu.kanade.tachiyomi.data.database.models.Track

/** Application-level tracking persistence shared by Android and iOS. */
class TrackRepository(private val db: DatabaseHandler) {
    data class RemoteLink(val remoteId: String, val mangaId: Long)
    fun all(): List<Track> = db.getAllTracks()

    fun forManga(url: String, sourceId: Long): List<Track> =
        db.getManga(url, sourceId)?.let(db::getTracks).orEmpty()

    fun find(url: String, sourceId: Long, syncId: Int): Track? =
        forManga(url, sourceId).firstOrNull { it.sync_id == syncId }

    fun forService(syncId: Int): List<Track> = db.getAllTracks().filter { it.sync_id == syncId }

    fun remoteLinks(syncId: Int): List<RemoteLink> = forService(syncId).map { track ->
        RemoteLink(
            remoteId = if (TrackerIds.usesTrackingUrlAsId(syncId)) track.tracking_url else track.media_id.toString(),
            mangaId = track.manga_id
        )
    }

    fun remove(url: String, sourceId: Long, syncId: Int): Boolean {
        val manga = db.getManga(url, sourceId) ?: return false
        if (db.getTracks(manga).none { it.sync_id == syncId }) return false
        db.deleteTrackForManga(manga, syncId)
        return true
    }

    fun removeServices(syncIds: List<Int>): Int {
        val wanted = syncIds.toSet()
        val mangas = db.getMangas().associateBy { it.id }
        var removed = 0
        db.inTransaction {
            db.getAllTracks().filter { it.sync_id in wanted }.forEach { track ->
                val manga = mangas[track.manga_id] ?: return@forEach
                db.deleteTrackForManga(manga, track.sync_id)
                removed++
            }
        }
        return removed
    }

    fun removeRemoteIds(syncId: Int, remoteIds: List<String>): Int {
        val wanted = remoteIds.toSet()
        val mangas = db.getMangas().associateBy { it.id }
        var removed = 0
        db.inTransaction {
            forService(syncId).forEach { track ->
                val remoteId = if (TrackerIds.usesTrackingUrlAsId(syncId)) {
                    track.tracking_url
                } else {
                    track.media_id.toString()
                }
                if (remoteId !in wanted) return@forEach
                val manga = mangas[track.manga_id] ?: return@forEach
                db.deleteTrackForManga(manga, syncId)
                removed++
            }
        }
        return removed
    }

    fun create(
        remoteId: String,
        syncId: Int,
        mangaUrl: String,
        sourceId: Long,
        title: String?
    ): Track? {
        val manga = db.getManga(mangaUrl, sourceId) ?: return null
        val mangaId = manga.id ?: return null
        return Track.create(syncId).also { track ->
            track.manga_id = mangaId
            track.title = title.orEmpty()
            if (TrackerIds.usesTrackingUrlAsId(syncId)) {
                track.media_id = 0
                track.tracking_url = remoteId
            } else {
                track.media_id = remoteId.toLongOrNull() ?: 0
                track.tracking_url = ""
            }
            db.insertTrack(track)
        }
    }

    fun save(track: Track) = db.insertTrack(track)
}
