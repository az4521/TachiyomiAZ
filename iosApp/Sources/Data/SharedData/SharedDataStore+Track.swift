import Foundation
import TachiyomiKit

/// Tracker links, over the shared `manga_sync` table.
///
/// Upstream keys a track by a string `trackerId`; the shared schema uses Android's numeric
/// `sync_id`, and `TrackerSyncId` is the mapping between them -- pinned to Android's numbers so a
/// title tracked on one app is recognised by the other.
extension SharedDataStore {
    func getTracks(context: Any? = nil) -> [Track] {
        trackRepository.all()
    }

    func getTracks(sourceId: String, mangaId: String, context: Any? = nil) -> [Track] {
        guard let source = SourceIdentity.numericId(sourceId) else { return [] }
        return trackRepository.forManga(url: mangaId, sourceId: source)
    }

    func getTracks(trackerId: String, context: Any? = nil) -> [Track] {
        guard let sync = TrackerSyncId.syncId(for: trackerId) else { return [] }
        return trackRepository.forService(syncId: sync)
    }

    func getTrack(trackerId: String, sourceId: String, mangaId: String, context: Any? = nil) -> Track? {
        guard let sync = TrackerSyncId.syncId(for: trackerId) else { return nil }
        guard let source = SourceIdentity.numericId(sourceId) else { return nil }
        return trackRepository.find(url: mangaId, sourceId: source, syncId: sync)
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
            let source = SourceIdentity.numericId(sourceId)
        else { return }
        trackRepository.remove(url: mangaId, sourceId: source, syncId: sync)
    }

    /// Deletion is per-manga in the shared schema, so removing a tracker means walking the manga it
    /// is linked to rather than deleting by tracker in one statement.
    func removeTracks(trackerId: String, context: Any? = nil) {
        guard let sync = TrackerSyncId.syncId(for: trackerId) else { return }
        remove(syncIds: [sync])
    }

    func clearTracks(context: Any? = nil) {
        remove(syncIds: Set(trackRepository.all().map(\.sync_id)))
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
        let removing = trackRepository.remoteLinks(syncId: sync).map(\.remoteId).filter(matches)
        trackRepository.removeRemoteIds(syncId: sync, remoteIds: removing)
    }

    private func remove(syncIds: Set<Int32>) {
        trackRepository.removeServices(syncIds: syncIds.map { KotlinInt(int: $0) })
    }
}

extension SharedDataStore {
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
            let source = SourceIdentity.numericId(sourceId)
        else { return nil }
        guard let track = trackRepository.create(
            remoteId: id,
            syncId: sync,
            mangaUrl: mangaId,
            sourceId: source,
            title: title
        ) else { return nil }

        setTrackChapterOffset(
            trackerId: trackerId,
            sourceId: sourceId,
            mangaId: mangaId,
            chapterOffset: chapterOffset
        )
        return track
    }

    /// Writes a tracker's reported state onto the stored row.
    ///
    /// These columns were never written here. A title tracked on iOS carried its service and its
    /// remote id and nothing else, so on Android it showed as tracked with no status, no progress
    /// and no score -- the row existed and said nothing.
    ///
    /// `status` holds the *service's* number, not this app's, and the services disagree about what
    /// a number means. `TrackStatusVocabulary` in `:core-domain` owns that translation, so both
    /// apps write the same value for the same state; writing this app's own enum would have marked
    /// a planned title completed on the three largest services.
    func setTrackState(
        trackerId: String,
        sourceId: String,
        mangaId: String,
        state: TrackState,
        context: Any? = nil
    ) {
        guard
            let sync = TrackerSyncId.syncId(for: trackerId),
            let source = SourceIdentity.numericId(sourceId),
            let track = trackRepository.find(url: mangaId, sourceId: source, syncId: sync)
        else { return }

        if let status = state.status,
           let remote = TrackerSyncId.remoteStatus(for: status, trackerId: trackerId) {
            track.status = remote
        }
        // The column is whole chapters; a service reporting 12.5 has read up to 12.
        if let lastRead = state.lastReadChapter { track.last_chapter_read = Int32(lastRead) }
        if let total = state.totalChapters { track.total_chapters = Int32(total) }
        if let score = state.score { track.score_ = Float(score) }
        if let started = state.startReadDate {
            track.started_reading_date = Int64(started.timeIntervalSince1970 * 1000)
        }
        if let finished = state.finishReadDate {
            track.finished_reading_date = Int64(finished.timeIntervalSince1970 * 1000)
        }
        trackRepository.save(track: track)
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

}
