import Foundation
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

/// Reading status, in the encoding the shared `Track.status` column already uses.
enum TrackStatus: Int32, CaseIterable, Identifiable {
    case reading = 1
    case completed = 2
    case onHold = 3
    case dropped = 4
    case planToRead = 5
    case rereading = 6

    var id: Int32 { rawValue }

    var title: String {
        switch self {
        case .reading: return "Reading"
        case .completed: return "Completed"
        case .onHold: return "On hold"
        case .dropped: return "Dropped"
        case .planToRead: return "Plan to read"
        case .rereading: return "Rereading"
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
protocol Tracker {
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
    func tracks(for manga: Manga) -> [Track] {
        library.handler.getTracks(manga: manga)
    }

    /// Binds a search result to a library entry and writes it through the shared query.
    func bind(
        _ result: TrackSearchResult,
        service: TrackerService,
        to manga: Manga,
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
        track.status = status.rawValue
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

    func updateProgress(_ track: Track, manga: Manga, lastChapterRead: Int) async {
        let handler = library.handler
        guard let service = TrackerService(rawValue: track.sync_id) else { return }
        track.last_chapter_read = Int32(lastChapterRead)
        handler.insertTrack(track: track)
        do {
            try await trackers[service]?.update(
                remoteId: track.media_id,
                status: TrackStatus(rawValue: track.status) ?? .reading,
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
        track.status = status.rawValue
        handler.insertTrack(track: track)
        try? await trackers[service]?.update(
            remoteId: track.media_id,
            status: status,
            lastChapterRead: Int(track.last_chapter_read),
            score: track.score_
        )
    }

    func unbind(_ track: Track, from manga: Manga) async {
        let handler = library.handler
        handler.deleteTrackForManga(manga: manga, syncId: Int32(track.sync_id))
    }
}
