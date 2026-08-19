import Foundation
import TachiyomiKit

/// Tracker links, over the shared `manga_sync` table.
///
/// Upstream keys a track by a string `trackerId`; the shared schema uses Android's numeric
/// `sync_id`, and `TrackerSyncId` is the mapping between them -- pinned to Android's numbers so a
/// title tracked on one app is recognised by the other.
extension CoreDataManager {
    func getTracks(context: Any? = nil) -> [Track] {
        handler.getAllTracks()
    }

    func getTracks(sourceId: String, mangaId: String, context: Any? = nil) -> [Track] {
        guard let manga = sharedManga(sourceId: sourceId, mangaId: mangaId) else { return [] }
        return handler.getTracks(manga: manga)
    }

    func getTracks(trackerId: String, context: Any? = nil) -> [Track] {
        guard let sync = TrackerSyncId.syncId(for: trackerId) else { return [] }
        return handler.getAllTracks().filter { $0.sync_id == sync }
    }

    func getTrack(trackerId: String, sourceId: String, mangaId: String, context: Any? = nil) -> Track? {
        guard let sync = TrackerSyncId.syncId(for: trackerId) else { return nil }
        return getTracks(sourceId: sourceId, mangaId: mangaId).first { $0.sync_id == sync }
    }

    func hasTrack(sourceId: String, mangaId: String, context: Any? = nil) -> Bool {
        !getTracks(sourceId: sourceId, mangaId: mangaId).isEmpty
    }

    func hasTrack(trackerId: String, sourceId: String, mangaId: String, context: Any? = nil) -> Bool {
        getTrack(trackerId: trackerId, sourceId: sourceId, mangaId: mangaId) != nil
    }

    func removeTrack(trackerId: String, sourceId: String, mangaId: String, context: Any? = nil) {
        guard
            let sync = TrackerSyncId.syncId(for: trackerId),
            let manga = sharedManga(sourceId: sourceId, mangaId: mangaId)
        else { return }
        handler.deleteTrackForManga(manga: manga, syncId: sync)
    }

    /// Deletion is per-manga in the shared schema, so removing a tracker means walking the manga it
    /// is linked to rather than deleting by tracker in one statement.
    func removeTracks(trackerId: String, context: Any? = nil) {
        guard let sync = TrackerSyncId.syncId(for: trackerId) else { return }
        remove(syncIds: [sync])
    }

    func clearTracks(context: Any? = nil) {
        remove(syncIds: Set(handler.getAllTracks().map(\.sync_id)))
    }

    /// Removes a tracker's links whose stored id satisfies `matches`.
    ///
    /// The enhanced trackers need this to drop every link to one server when its source is
    /// removed: their id carries the source key, so which rows to delete is a question about the
    /// id's contents rather than about a manga. Upstream fetches `TrackObject` with a predicate;
    /// there is no such entity here.
    /// `matches` escapes because the deletion runs inside a database transaction block.
    func removeTracks(trackerId: String, where matches: @escaping (String) -> Bool) {
        guard let sync = TrackerSyncId.syncId(for: trackerId) else { return }
        let handler = self.handler
        let byId = Dictionary(
            handler.getMangas().compactMap { manga in manga.id.map { ($0.int64Value, manga) } },
            uniquingKeysWith: { first, _ in first }
        )
        handler.inTransaction {
            for track in handler.getAllTracks() where track.sync_id == sync {
                let storedId = TrackerSyncId.usesTrackingUrlAsId(syncId: sync)
                    ? track.tracking_url
                    : String(track.media_id)
                guard matches(storedId), let manga = byId[track.manga_id_] else { continue }
                handler.deleteTrackForManga(manga: manga, syncId: sync)
            }
        }
    }

    private func remove(syncIds: Set<Int32>) {
        let handler = self.handler
        let byId = Dictionary(
            handler.getMangas().compactMap { manga in manga.id.map { (Int64($0.int64Value), manga) } },
            uniquingKeysWith: { first, _ in first }
        )
        handler.inTransaction {
            for track in handler.getAllTracks() where syncIds.contains(track.sync_id) {
                guard let manga = byId[track.manga_id_] else { continue }
                handler.deleteTrackForManga(manga: manga, syncId: track.sync_id)
            }
        }
    }
}

extension CoreDataManager {
    /// Links a title to a tracker, writing a row into the shared `manga_sync` table.
    ///
    /// `sync_id` comes from `TrackerSyncId`, so the row is the one the Android app would have
    /// written for the same service.
    @discardableResult
    func createTrack(
        id: String,
        trackerId: String,
        sourceId: String,
        mangaId: String,
        title: String?,
        chapterOffset: Int = 0,
        context: Any? = nil
    ) -> Track? {
        guard
            let sync = TrackerSyncId.syncId(for: trackerId),
            let manga = sharedManga(sourceId: sourceId, mangaId: mangaId),
            let mangaRowId = manga.id?.int64Value
        else { return nil }

        let track = TrackImpl()
        track.manga_id_ = mangaRowId
        track.sync_id = sync
        track.title = title ?? ""
        // The enhanced trackers' id is a composite string, which does not fit the integer column;
        // it goes where Android puts it instead.
        if TrackerSyncId.usesTrackingUrlAsId(syncId: sync) {
            track.media_id = 0
            track.tracking_url = id
        } else {
            track.media_id = Int64(id) ?? 0
            track.tracking_url = ""
        }
        handler.insertTrack(track: track)

        setTrackChapterOffset(
            trackerId: trackerId,
            sourceId: sourceId,
            mangaId: mangaId,
            chapterOffset: chapterOffset
        )
        return track
    }

    /// Android's `manga_sync` has no offset column -- it is this app's own adjustment for sources
    /// whose chapter numbering differs from the tracker's -- so it is kept alongside.
    func setTrackChapterOffset(
        trackerId: String,
        sourceId: String,
        mangaId: String,
        chapterOffset: Int,
        context: Any? = nil
    ) {
        UserDefaults.standard.set(
            chapterOffset,
            forKey: "Tracking.chapterOffset.\(trackerId).\(sourceId).\(mangaId)"
        )
    }

    func trackChapterOffset(trackerId: String, sourceId: String, mangaId: String) -> Int {
        UserDefaults.standard.integer(
            forKey: "Tracking.chapterOffset.\(trackerId).\(sourceId).\(mangaId)"
        )
    }
}
