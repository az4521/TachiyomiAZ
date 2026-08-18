import Foundation
import ExtensionRunner
import UIKit
import TachiyomiKit

/// A tracking service.
///
/// `id` is the `sync_id` written into the shared `manga_sync` table, and the numbers match
/// TachiyomiAZ's own service ids so a track written here is the same row the Android app would
/// have written. Getting these wrong would silently mis-attribute every track in a shared backup.
enum TrackerService: Int32, CaseIterable, Identifiable {
    case myAnimeList = 1
    case aniList = 2

    var id: Int32 { rawValue }

    /// The vendored UI identifies a tracker by name, the shared schema by `sync_id`; this is how a
    /// name from the UI reaches the right row.
    init?(name: String) {
        guard let match = Self.allCases.first(where: {
            $0.title.caseInsensitiveCompare(name) == .orderedSame
                || String(describing: $0).caseInsensitiveCompare(name) == .orderedSame
        }) else { return nil }
        self = match
    }

    var title: String {
        switch self {
        case .myAnimeList: return "MyAnimeList"
        case .aniList: return "AniList"
        }
    }

    var accentHex: String {
        switch self {
        case .myAnimeList: return "2E51A2"
        case .aniList: return "02A9FF"
        }
    }
}


/// A search result from a tracker, before it is bound to a library entry.
struct TrackSearchResult: Identifiable, Hashable {
    let remoteId: Int64
    let title: String
    let coverURL: String?
    let totalChapters: Int
    let summary: String?
    let trackingURL: String

    var id: Int64 { remoteId }
}

/// What a tracker must be able to do.
///
/// Deliberately small: search, push an update, and describe itself. Everything about *storing* a
/// track is the shared database's job, so no implementation of this touches persistence.
protocol Tracker: AnyObject {
    var service: TrackerService { get }
    var isLoggedIn: Bool { get }

    func search(_ query: String) async throws -> [TrackSearchResult]
    func update(
        remoteId: Int64,
        status: TrackStatus,
        lastChapterRead: Int,
        score: Float
    ) async throws
    func logOut()
}

/// Marks a tracker bound to a particular source rather than a standalone service.
///
/// Upstream's Komga, Kavita and Suwayomi trackers are enhanced: they track against the same server
/// the manga came from. Neither tracker here is, so nothing conforms -- the protocol exists so the
/// vendored views' `is EnhancedTracker` checks compile and correctly answer false.
protocol EnhancedTracker: Tracker {}

/// The shape the vendored tracking UI addresses a tracker by.
///
/// Upstream declares `id`, `name` and `icon` on the protocol itself and has each tracker supply
/// them. Here they follow from `TrackerService`, so they are derived once rather than reimplemented
/// per tracker -- there is exactly one tracker per service.
extension Tracker {
    /// Stable string identifier, which is what a stored `TrackItem.trackerId` carries.
    var id: String { String(describing: service) }

    var name: String { service.title }

    /// Whether a new entry can be created for this title. Upstream asks per-title because its
    /// enhanced trackers only accept manga from their own server; both trackers here take anything.
    func canRegister(sourceKey: String, mangaKey: String) -> Bool { true }

    /// What the tracker supports. Both services here take the standard statuses and a 0-10 score.
    func getTrackerInfo() async throws -> TrackerInfo {
        TrackerInfo(supportedStatuses: TrackStatus.defaultStatuses, scoreType: .tenPoint)
    }

    /// The option-list label for a score. Neither service here uses an option list -- both are
    /// numeric -- so this only has to be correct for the option-list case if one is added.
    func option(for score: Int, options: [(String, Int)]) async -> String? {
        options.first { $0.1 == score }?.0
    }

    /// The entry's current state on the service.
    ///
    /// Upstream's trackers fetch this from the remote API. Neither service here implements a state
    /// read yet, so this reports what the shared `manga_sync` row holds -- which is what the app
    /// last wrote -- rather than inventing remote values. Filling it in means adding one API call
    /// per service; the UI above it already works.
    func getState(trackId: String) async throws -> TrackState? {
        guard
            let remoteId = Int64(trackId),
            let track = Database.handler.getAllTracks().first(where: {
                $0.sync_id == service.rawValue && $0.media_id == remoteId
            })
        else { return nil }

        var state = TrackState()
        state.score = Int(track.score_)
        state.status = TrackStatus(Int(track.status))
        state.lastReadChapter = Float(track.last_chapter_read)
        state.totalChapters = track.total_chapters == 0 ? nil : Int(track.total_chapters)
        state.startReadDate = track.started_reading_date > 0
            ? Date(timeIntervalSince1970: TimeInterval(track.started_reading_date) / 1000)
            : nil
        state.finishReadDate = track.finished_reading_date > 0
            ? Date(timeIntervalSince1970: TimeInterval(track.finished_reading_date) / 1000)
            : nil
        return state
    }

    /// Pushes an edit made in the tracker sheet.
    func update(trackId: String, update: TrackUpdate) async throws {
        guard let remoteId = Int64(trackId) else { return }
        try await self.update(
            remoteId: remoteId,
            status: update.status ?? .reading,
            lastChapterRead: update.lastReadChapter.map { Int($0) } ?? 0,
            score: Float(update.score ?? 0)
        )
    }

    /// The tracker's web page for an entry, opened from the search results.
    func getUrl(trackId: String) async -> URL? {
        switch service {
        case .myAnimeList: URL(string: "https://myanimelist.net/manga/\(trackId)")
        case .aniList: URL(string: "https://anilist.co/manga/\(trackId)")
        }
    }

    /// Upstream's enhanced trackers look a title up from the manga itself rather than a query.
    /// Falling back to a title search is the closest this port can do.
    func search(for manga: ExtensionRunner.Manga, includeNsfw: Bool) async throws -> [TrackSearchItem] {
        try await search(title: manga.title, includeNsfw: includeNsfw)
    }

    var icon: PlatformImage? { UIImage(named: "tracker.\(id)") }

    /// Upstream's search signature. NSFW filtering is the tracker's own concern and neither
    /// MyAnimeList nor AniList is asked to filter here, so the flag is accepted and unused.
    func search(title: String, includeNsfw: Bool) async throws -> [TrackSearchItem] {
        try await search(title).map { result in
            TrackSearchItem(
                id: String(result.remoteId),
                title: result.title,
                coverUrl: result.coverURL,
                description: result.summary,
                status: nil,
                type: nil,
                tracked: false
            )
        }
    }
}

/// Stores tracks through the shared `TrackQueries`, and drives whichever services are logged in.
///
/// Persistence is entirely shared: `insertTrack`, `getTracks(manga:)` and
/// `deleteTrackForManga(manga:syncId:)` are `:core-database` default methods, writing the same
/// `manga_sync` rows the Android app reads. Only the network side is written per-platform, because
/// the Android services are 98 files of OkHttp and Android auth that cannot cross over.
@MainActor
final class TrackingStore: ObservableObject {
    @Published private(set) var trackers: [TrackerService: Tracker] = [:]
    @Published private(set) var lastError: String?

    private unowned let library: LibraryStore

    init(library: LibraryStore) {
        self.library = library
        trackers = [
            .myAnimeList: MyAnimeListTracker(),
            .aniList: AniListTracker()
        ]
    }

    func tracker(_ service: TrackerService) -> Tracker? { trackers[service] }

    func isLoggedIn(_ service: TrackerService) -> Bool { trackers[service]?.isLoggedIn ?? false }

    /// Existing tracks for a library entry, straight from the shared query.
    func tracks(for manga: DbManga) -> [Track] {
        library.handler.getTracks(manga: manga)
    }

    /// Binds a search result to a library entry and writes it through the shared query.
    func bind(
        _ result: TrackSearchResult,
        service: TrackerService,
        to manga: DbManga,
        status: TrackStatus = .reading,
        lastChapterRead: Int = 0
    ) async {
        let handler = library.handler
        guard let mangaId = manga.id?.int64Value else { return }

        let track = TrackImpl()
        track.manga_id_ = mangaId
        track.sync_id = service.rawValue
        track.media_id = result.remoteId
        track.title = result.title
        track.total_chapters = Int32(result.totalChapters)
        track.last_chapter_read = Int32(lastChapterRead)
        track.status = Int32(status.rawValue)
        track.tracking_url = result.trackingURL
        handler.insertTrack(track: track)

        do {
            try await trackers[service]?.update(
                remoteId: result.remoteId,
                status: status,
                lastChapterRead: lastChapterRead,
                score: 0
            )
            lastError = nil
        } catch {
            // The local row stands even if the push failed -- the binding is still what the user
            // asked for, and a later update retries it.
            lastError = "\(service.title): \(error.localizedDescription)"
        }
    }

    func updateProgress(_ track: Track, manga: DbManga, lastChapterRead: Int) async {
        let handler = library.handler
        guard let service = TrackerService(rawValue: track.sync_id) else { return }
        track.last_chapter_read = Int32(lastChapterRead)
        handler.insertTrack(track: track)
        do {
            try await trackers[service]?.update(
                remoteId: track.media_id,
                status: TrackStatus(Int(track.status)),
                lastChapterRead: lastChapterRead,
                score: track.score_
            )
            lastError = nil
        } catch {
            lastError = "\(service.title): \(error.localizedDescription)"
        }
    }

    func setStatus(_ track: Track, status: TrackStatus) async {
        let handler = library.handler
        guard let service = TrackerService(rawValue: track.sync_id) else { return }
        track.status = Int32(status.rawValue)
        handler.insertTrack(track: track)
        try? await trackers[service]?.update(
            remoteId: track.media_id,
            status: status,
            lastChapterRead: Int(track.last_chapter_read),
            score: track.score_
        )
    }

    func unbind(_ track: Track, from manga: DbManga) async {
        let handler = library.handler
        handler.deleteTrackForManga(manga: manga, syncId: Int32(track.sync_id))
    }
}
