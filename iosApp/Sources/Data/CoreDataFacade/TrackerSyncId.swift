import Foundation
import TachiyomiKit

/// Maps a tracker's string id to the numeric `sync_id` the shared `manga_sync` table stores.
///
/// The numbers are not ours to choose, and they are no longer written down here: `TrackerIds` in
/// `:core-domain` holds them, and the Android app reads the same object. They used to exist twice
/// -- as constants in `TrackManager` and as a dictionary here -- with nothing keeping the two lists
/// the same, which is a poor arrangement for the value that decides whether a title tracked on one
/// app is recognised by the other.
enum TrackerSyncId {
    static func syncId(for trackerId: String) -> Int32? {
        TrackerIds.shared.syncId(name: trackerId)?.int32Value
    }

    static func trackerId(for syncId: Int32) -> String? {
        TrackerIds.shared.name(syncId: syncId)
    }

    /// Whether this service is keyed on `tracking_url` rather than `media_id`.
    ///
    /// Komga, Kavita and Suwayomi identify a title by a composite string that does not fit the
    /// integer column. Android's Komga tracker matches on `tracking_url`, so this follows it.
    static func usesTrackingUrlAsId(syncId: Int32) -> Bool {
        TrackerIds.shared.usesTrackingUrlAsId(syncId: syncId)
    }

    static func usesTrackingUrlAsId(trackerId: String) -> Bool {
        syncId(for: trackerId).map(usesTrackingUrlAsId(syncId:)) ?? false
    }

    /// The number a service stores for this app's status, or nil when it has no equivalent.
    ///
    /// `manga_sync.status` holds the *service's* value, and the services disagree about what a
    /// number means -- this app's `planning` is 2, and 2 is `completed` on MyAnimeList, AniList and
    /// Kitsu. Translating through the shared table is what stops a planned title reading as
    /// finished on the other app.
    static func remoteStatus(for status: TrackStatus, trackerId: String) -> Int32? {
        guard let sync = syncId(for: trackerId) else { return nil }
        return TrackStatusVocabulary.shared.toRemote(syncId: sync, status: status.shared)?.int32Value
    }

    static func status(fromRemote raw: Int32, syncId: Int32) -> TrackStatus {
        TrackStatus(shared: TrackStatusVocabulary.shared.fromRemote(syncId: syncId, raw: raw))
    }
}

private extension TrackStatus {
    /// This app's status as the shared vocabulary names it.
    var shared: TachiyomiKit.TrackStatus {
        switch rawValue {
            case 1: .reading
            case 2: .planning
            case 3: .completed
            case 4: .paused
            case 5: .dropped
            case 6: .rereading
            default: TachiyomiKit.TrackStatus.none
        }
    }

    init(shared: TachiyomiKit.TrackStatus) {
        self = switch shared {
            case .reading: .reading
            case .planning: .planning
            case .completed: .completed
            case .paused: .paused
            case .dropped: .dropped
            case .rereading: .rereading
            default: TrackStatus(7)
        }
    }
}
