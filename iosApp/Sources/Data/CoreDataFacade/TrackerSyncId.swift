import Foundation

/// Maps a tracker's string id to the numeric `sync_id` the shared `manga_sync` table stores.
///
/// These numbers are not ours to choose. They are `TrackManager`'s constants in the Android app,
/// and they are what a row's `sync_id` column holds, so a title tracked on one app is only
/// recognised by the other if both agree on them. The vendored trackers identify themselves by
/// string, so this is the single place the two vocabularies meet.
///
/// Anything unknown maps to nil rather than a guess: writing a wrong `sync_id` would silently
/// attribute a tracked title to the wrong service.
enum TrackerSyncId {
    /// Mirrors `TrackManager` in the Android app.
    private static let ids: [String: Int32] = [
        "myanimelist": 1,
        "anilist": 2,
        "kitsu": 3,
        "shikimori": 4,
        "bangumi": 5,
        "mangaupdates": 6,
        "hikka": 7,
        "mangabaka": 8,
        "komga": 9,
        "kavita": 10,
        "suwayomi": 11
    ]

    static func syncId(for trackerId: String) -> Int32? {
        ids[trackerId.lowercased()]
    }

    static func trackerId(for syncId: Int32) -> String? {
        ids.first { $0.value == syncId }?.key
    }
}
