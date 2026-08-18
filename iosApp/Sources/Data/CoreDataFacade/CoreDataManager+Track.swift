import Foundation
import TachiyomiKit

/// Tracker links, over the shared `manga_sync` table.
///
/// Upstream keys a track by a string `trackerId`; the shared schema uses Android's numeric
/// `sync_id`, and `TrackerService` is the enum that names those numbers.
extension CoreDataManager {
    func getTracks(context: Any? = nil) -> [Track] {
        handler.getAllTracks()
    }

    func getTracks(sourceId: String, mangaId: String, context: Any? = nil) -> [Track] {
        guard let manga = sharedManga(sourceId: sourceId, mangaId: mangaId) else { return [] }
        return handler.getTracks(manga: manga)
    }

    func getTracks(trackerId: String, context: Any? = nil) -> [Track] {
        guard let sync = TrackerService(name: trackerId)?.rawValue else { return [] }
        return handler.getAllTracks().filter { $0.sync_id == sync }
    }

    func getTrack(trackerId: String, sourceId: String, mangaId: String, context: Any? = nil) -> Track? {
        guard let sync = TrackerService(name: trackerId)?.rawValue else { return nil }
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
            let sync = TrackerService(name: trackerId)?.rawValue,
            let manga = sharedManga(sourceId: sourceId, mangaId: mangaId)
        else { return }
        handler.deleteTrackForManga(manga: manga, syncId: sync)
    }

    /// Deletion is per-manga in the shared schema, so removing a tracker means walking the manga it
    /// is linked to rather than deleting by tracker in one statement.
    func removeTracks(trackerId: String, context: Any? = nil) {
        guard let sync = TrackerService(name: trackerId)?.rawValue else { return }
        remove(syncIds: [sync])
    }

    func clearTracks(context: Any? = nil) {
        remove(syncIds: Set(handler.getAllTracks().map(\.sync_id)))
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
