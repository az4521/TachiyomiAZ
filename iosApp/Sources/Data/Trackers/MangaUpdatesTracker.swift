import ExtensionRunner
import Foundation

/// MangaUpdates.
///
/// Written for this port -- tachiyomiazios has no MangaUpdates client. Endpoints and the list
/// vocabulary follow Android's `MangaUpdatesApi` so both apps drive the same account the same way.
///
/// Authentication is a username and password exchanged for a session token, so this is a plain
/// `Tracker` rather than an `OAuthTracker`.
final class MangaUpdatesTracker: Tracker {
    let id = "mangaupdates"
    let name = "MangaUpdates"
    let icon = PlatformImage(named: "mangaupdates")

    private static let baseUrl = "https://api.mangaupdates.com/v1"

    /// MangaUpdates' list ids, as Android uses them.
    private enum List {
        static let reading = 0
        static let wish = 1
        static let complete = 2
        static let unfinished = 3
        static let onHold = 4
    }

    private var token: String? {
        get { UserDefaults.standard.string(forKey: "Tracker.mangaupdates.token") }
        set { UserDefaults.standard.set(newValue, forKey: "Tracker.mangaupdates.token") }
    }

    var isLoggedIn: Bool { token != nil }

    func getTrackerInfo() async throws -> TrackerInfo {
        // Ratings are 0-10 with one decimal place.
        .init(supportedStatuses: TrackStatus.defaultStatuses, scoreType: .tenPointDecimal)
    }

    // MARK: - Auth

    func logIn(username: String, password: String) async throws {
        var request = URLRequest(url: URL(string: "\(Self.baseUrl)/account/login")!)
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(
            withJSONObject: ["username": username, "password": password]
        )

        let (data, _) = try await URLSession.shared.data(for: request)
        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let context = json["context"] as? [String: Any],
            let token = context["session_token"] as? String
        else { throw URLError(.userAuthenticationRequired) }
        self.token = token
    }

    func logout() async throws {
        token = nil
    }

    // MARK: - Requests

    /// `body` is `Any` rather than a dictionary: the list endpoints take a JSON *array* of entries,
    /// while the rest take an object.
    private func send(
        _ method: String,
        _ path: String,
        body: Any? = nil
    ) async throws -> Data {
        guard let token else { throw URLError(.userAuthenticationRequired) }
        var request = URLRequest(url: URL(string: "\(Self.baseUrl)\(path)")!)
        request.httpMethod = method
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, _) = try await URLSession.shared.data(for: request)
        return data
    }

    // MARK: - Tracker

    /// The protocol has no default for this; searching by title is what these services support.
    func search(for manga: ExtensionRunner.Manga, includeNsfw: Bool) async throws -> [TrackSearchItem] {
        try await search(title: manga.title, includeNsfw: includeNsfw)
    }

    func search(title: String, includeNsfw: Bool) async throws -> [TrackSearchItem] {
        let data = try await send("POST", "/series/search", body: ["search": title, "perpage": 20])
        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let results = json["results"] as? [[String: Any]]
        else { return [] }

        return results.compactMap { result in
            guard let record = result["record"] as? [String: Any] else { return nil }
            // The id is a large integer; keep it as a string so it survives round-tripping.
            let seriesId = (record["series_id"] as? Int64).map(String.init)
                ?? (record["series_id"] as? Int).map(String.init)
            guard let seriesId else { return nil }
            return TrackSearchItem(
                id: seriesId,
                title: record["title"] as? String ?? "",
                coverUrl: (record["image"] as? [String: Any])
                    .flatMap { ($0["url"] as? [String: Any])?["original"] as? String },
                description: record["description"] as? String,
                status: nil,
                type: nil,
                tracked: false
            )
        }
    }

    func register(trackId: String, highestChapterRead: Float?, earliestReadDate: Date?) async throws -> String? {
        guard let seriesId = Int(trackId) else { throw URLError(.badURL) }
        _ = try await send("POST", "/lists/series", body: [[
            "series": ["id": seriesId],
            "list_id": highestChapterRead != nil ? List.reading : List.wish,
            "status": ["chapter": Int(highestChapterRead ?? 0)]
        ]])
        // The series id is the handle for later updates; there is no separate entry id.
        return trackId
    }

    func update(trackId: String, update: TrackUpdate) async throws {
        guard let seriesId = Int(trackId) else { throw URLError(.badURL) }

        var entry: [String: Any] = ["series": ["id": seriesId]]
        if let status = update.status { entry["list_id"] = Self.listId(status) }
        if let chapter = update.lastReadChapter {
            entry["status"] = ["chapter": Int(chapter)]
        }
        _ = try await send("POST", "/lists/series/update", body: [entry])

        // Ratings live on their own endpoint.
        if let score = update.score {
            _ = try await send(
                "PUT",
                "/series/\(seriesId)/rating",
                body: ["rating": Double(score) / 10]
            )
        }
    }

    func getState(trackId: String) async throws -> TrackState {
        guard let seriesId = Int(trackId) else { return TrackState() }
        let data = try await send("GET", "/lists/series/\(seriesId)")
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return TrackState()
        }

        var state = TrackState()
        if let listId = json["list_id"] as? Int {
            state.status = Self.status(forList: listId)
        }
        if let status = json["status"] as? [String: Any], let chapter = status["chapter"] as? Int {
            state.lastReadChapter = Float(chapter)
        }
        return state
    }

    func getUrl(trackId: String) async -> URL? {
        URL(string: "https://www.mangaupdates.com/series.html?id=\(trackId)")
    }

    // MARK: - List vocabulary

    private static func listId(_ status: TrackStatus) -> Int {
        switch status {
        case .reading, .rereading: List.reading
        case .planning: List.wish
        case .completed: List.complete
        case .dropped: List.unfinished
        case .paused: List.onHold
        default: List.reading
        }
    }

    private static func status(forList listId: Int) -> TrackStatus? {
        switch listId {
        case List.reading: .reading
        case List.wish: .planning
        case List.complete: .completed
        case List.unfinished: .dropped
        case List.onHold: .paused
        default: nil
        }
    }
}
