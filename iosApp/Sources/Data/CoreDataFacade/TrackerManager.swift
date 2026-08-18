import ExtensionRunner
import Foundation

/// The name the vendored UI uses to ask about trackers.
///
/// Upstream's `TrackerManager` fronts eight services behind an `OAuthTracker`/`EnhancedTracker`
/// hierarchy. This app supports MyAnimeList and AniList, written against the smaller `Tracker`
/// protocol in `Tracking.swift`, with the track rows themselves living in the shared `manga_sync`
/// table. Only the questions the vendored views actually ask are answered here.
struct TrackerManager {
    /// Whether any tracker is signed in -- what the library's "tracking" filter keys off.
    ///
    /// Reads the stored token rather than asking `TrackingStore`, because the filter that calls this
    /// is a plain computed property with no actor to hop to.
    static var hasAvailableTrackers: Bool {
        TrackerService.allCases.contains { TokenStore.token($0) != nil }
    }

    static let shared = TrackerManager()

    /// Every tracker this app implements. Upstream lists eight, including the self-hosted ones that
    /// belong to Aidoku's own sources; this port has MyAnimeList and AniList.
    static let trackers: [Tracker] = [MyAnimeListTracker(), AniListTracker()]

    /// Upstream identifies a tracker by string id. This port has MyAnimeList and AniList, named by
    /// `TrackerService`, and does not yet conform them to upstream's richer `Tracker` protocol --
    /// see Vendored/_excluded/Tracking. Until it does, nothing resolves and the callers below skip
    /// their sync work rather than acting on a tracker that cannot answer.
    static func getTracker(id: String) -> Tracker? {
        trackers.first { $0.id == id }
    }

    /// Whether any tracker could take this title -- upstream asks per-title because its
    /// self-hosted trackers only apply to their own sources. Here it is the same question as
    /// whether a tracker is signed in at all.
    func hasAvailableTrackers(sourceKey: String, mangaKey: String) async -> Bool {
        Self.hasAvailableTrackers
    }

    /// Whether a title is linked to any tracker.
    func isTracking(sourceId: String, mangaId: String) -> Bool {
        CoreDataManager.shared.hasTrack(sourceId: sourceId, mangaId: mangaId)
    }

    /// The per-title chapter offset applied when syncing progress.
    ///
    /// Android's `manga_sync` has no offset column, so this is kept alongside rather than written
    /// into the shared row -- it only affects how this app reports progress outward.
    func setTrackChapterOffset(item: TrackItem, chapterOffset: Int) async {
        UserDefaults.standard.set(
            chapterOffset,
            forKey: "Tracking.chapterOffset.\(item.trackerId).\(item.sourceId).\(item.mangaId)"
        )
    }

    func chapterOffset(for trackItem: TrackItem) -> Int {
        UserDefaults.standard.integer(
            forKey: "Tracking.chapterOffset.\(trackItem.trackerId).\(trackItem.sourceId).\(trackItem.mangaId)"
        )
    }

    /// Unlinks a title from one tracker.
    func removeTrackItem(item: TrackItem) async {
        CoreDataManager.shared.removeTrack(
            trackerId: item.trackerId,
            sourceId: item.sourceId,
            mangaId: item.mangaId
        )
        NotificationCenter.default.post(name: NSNotification.Name("updateTrackers"), object: nil)
    }
}

/// Marks a tracker that reports progress per page rather than per chapter.
///
/// Declared so the vendored manga screen's `tracker is PageTracker` check compiles. No tracker here
/// conforms to it yet.
protocol PageTracker: Tracker {}

/// The sync entry points the vendored manga screen calls.
///
/// Each is inert while `getTracker(id:)` returns nil: with no tracker to talk to there is no remote
/// progress to reconcile. They are kept so the call sites stay unmodified, and so restoring
/// tracking is a matter of filling these in rather than editing vendored views.
extension TrackerManager {
    func syncPageTrackerHistory(manga: ExtensionRunner.Manga, chapters: [ExtensionRunner.Chapter]) async {}

    func syncPageTrackerHistory(
        tracker: Tracker,
        manga: ExtensionRunner.Manga,
        chapters: [ExtensionRunner.Chapter]?
    ) async {}

    func getChaptersToSyncProgressFromTracker(
        tracker: Tracker,
        trackItem: TrackItem,
        manga: ExtensionRunner.Manga,
        chapters: [ExtensionRunner.Chapter]
    ) async -> [ExtensionRunner.Chapter] {
        []
    }

    func syncProgressFromTracker(
        tracker: Tracker,
        trackItem: TrackItem,
        manga: ExtensionRunner.Manga,
        chapters: [ExtensionRunner.Chapter]
    ) async {}

    func register(tracker: Tracker, manga: ExtensionRunner.Manga, item: TrackSearchItem) async {}
}
