import ExtensionRunner
import Foundation

/// Kitsu.
///
/// Written for this port rather than vendored: tachiyomiazios has no Kitsu client, and the Android
/// app does. Endpoints, the status vocabulary and the score scale are taken from Android's
/// `KitsuApi`/`KitsuModels` so both apps write the same values to the same account.
///
/// Kitsu authenticates with a password grant rather than a redirect, so this is a `Tracker` with
/// its own login sheet rather than an `OAuthTracker`.
final class KitsuTracker: Tracker {
    let id = "kitsu"
    let name = "Kitsu"
    let icon = PlatformImage(named: "kitsu")

    private static let clientId = "dd031b32d2f56c990b1425efe6c42ad847e7fe3ab46bf1299f05ecd856bdb7dd"
    private static let clientSecret = "54d7307928f63414defd96399fc31ba847961ceaecef3a5fd93144e960c0e151"
    private static let apiUrl = "https://kitsu.io/api/edge"
    private static let tokenUrl = "https://kitsu.io/api/oauth/token"

    private var token: String? {
        get { UserDefaults.standard.string(forKey: "Tracker.kitsu.token") }
        set { UserDefaults.standard.set(newValue, forKey: "Tracker.kitsu.token") }
    }

    private var userId: String? {
        get { UserDefaults.standard.string(forKey: "Tracker.kitsu.userId") }
        set { UserDefaults.standard.set(newValue, forKey: "Tracker.kitsu.userId") }
    }

    var isLoggedIn: Bool { token != nil }

    func getTrackerInfo() async throws -> TrackerInfo {
        // Kitsu stores a rating out of 20 and shows it as 10 points.
        .init(supportedStatuses: TrackStatus.defaultStatuses, scoreType: .tenPoint)
    }

    // MARK: - Auth

    /// Exchanges a username and password for a token, then records the account id that library
    /// entries have to be attributed to.
    func logIn(username: String, password: String) async throws {
        var request = URLRequest(url: URL(string: Self.tokenUrl)!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let body: [String: String] = [
            "grant_type": "password",
            "username": username,
            "password": password,
            "client_id": Self.clientId,
            "client_secret": Self.clientSecret
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, _) = try await URLSession.shared.data(for: request)
        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let token = json["access_token"] as? String
        else { throw URLError(.userAuthenticationRequired) }
        self.token = token
        userId = try await fetchCurrentUserId()
    }

    func logout() async throws {
        token = nil
        userId = nil
    }

    private func fetchCurrentUserId() async throws -> String? {
        let data = try await get("\(Self.apiUrl)/users?filter[self]=true")
        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let entries = json["data"] as? [[String: Any]],
            let id = entries.first?["id"] as? String
        else { return nil }
        return id
    }

    // MARK: - Requests

    private func get(_ url: String) async throws -> Data {
        guard let token else { throw URLError(.userAuthenticationRequired) }
        var request = URLRequest(url: URL(string: url)!)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/vnd.api+json", forHTTPHeaderField: "Accept")
        let (data, _) = try await URLSession.shared.data(for: request)
        return data
    }

    private func send(_ method: String, _ url: String, body: [String: Any]) async throws -> Data {
        guard let token else { throw URLError(.userAuthenticationRequired) }
        var request = URLRequest(url: URL(string: url)!)
        request.httpMethod = method
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/vnd.api+json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, _) = try await URLSession.shared.data(for: request)
        return data
    }

    // MARK: - Tracker

    /// The protocol has no default for this; searching by title is what these services support.
    func search(for manga: ExtensionRunner.Manga, includeNsfw: Bool) async throws -> [TrackSearchItem] {
        try await search(title: manga.title, includeNsfw: includeNsfw)
    }

    func search(title: String, includeNsfw: Bool) async throws -> [TrackSearchItem] {
        // Android searches through Algolia for ranking. The public filter endpoint returns the
        // same records without needing an Algolia key, which is enough to pick a title.
        let query = title.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        let data = try await get("\(Self.apiUrl)/manga?filter[text]=\(query)&page[limit]=20")
        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let entries = json["data"] as? [[String: Any]]
        else { return [] }

        return entries.compactMap { entry in
            guard let id = entry["id"] as? String else { return nil }
            let attributes = entry["attributes"] as? [String: Any] ?? [:]
            let titles = attributes["titles"] as? [String: Any]
            let cover = (attributes["posterImage"] as? [String: Any])?["small"] as? String
            return TrackSearchItem(
                id: id,
                title: attributes["canonicalTitle"] as? String
                    ?? titles?["en"] as? String
                    ?? "",
                coverUrl: cover,
                description: attributes["synopsis"] as? String,
                status: nil,
                type: nil,
                tracked: false
            )
        }
    }

    func register(trackId: String, highestChapterRead: Float?, earliestReadDate: Date?) async throws -> String? {
        guard let userId else { throw URLError(.userAuthenticationRequired) }
        let body: [String: Any] = [
            "data": [
                "type": "libraryEntries",
                "attributes": [
                    "status": highestChapterRead != nil ? "current" : "planned",
                    "progress": Int(highestChapterRead ?? 0)
                ],
                "relationships": [
                    "user": ["data": ["id": userId, "type": "users"]],
                    "media": ["data": ["id": trackId, "type": "manga"]]
                ]
            ]
        ]
        let data = try await send("POST", "\(Self.apiUrl)/library-entries", body: body)
        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let entry = json["data"] as? [String: Any]
        else { return nil }
        // The library entry has its own id, which is what later updates address.
        return entry["id"] as? String
    }

    func update(trackId: String, update: TrackUpdate) async throws {
        var attributes: [String: Any] = [:]
        if let status = update.status { attributes["status"] = Self.remoteStatus(status) }
        if let chapter = update.lastReadChapter { attributes["progress"] = Int(chapter) }
        // Kitsu stores the score out of 20; the UI works in 10 points.
        if let score = update.score { attributes["ratingTwenty"] = score * 2 }
        guard !attributes.isEmpty else { return }

        _ = try await send(
            "PATCH",
            "\(Self.apiUrl)/library-entries/\(trackId)",
            body: ["data": ["type": "libraryEntries", "id": trackId, "attributes": attributes]]
        )
    }

    func getState(trackId: String) async throws -> TrackState {
        let data = try await get("\(Self.apiUrl)/library-entries/\(trackId)")
        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let entry = json["data"] as? [String: Any],
            let attributes = entry["attributes"] as? [String: Any]
        else { return TrackState() }

        var state = TrackState()
        state.status = (attributes["status"] as? String).flatMap(Self.localStatus)
        state.lastReadChapter = (attributes["progress"] as? Int).map(Float.init)
        state.score = (attributes["ratingTwenty"] as? Int).map { $0 / 2 }
        return state
    }

    func getUrl(trackId: String) async -> URL? {
        URL(string: "https://kitsu.io/manga/\(trackId)")
    }

    // MARK: - Status vocabulary

    /// Kitsu's own names, matching Android's `toKitsuStatus`.
    private static func remoteStatus(_ status: TrackStatus) -> String {
        switch status {
        case .reading, .rereading: "current"
        case .completed: "completed"
        case .paused: "on_hold"
        case .dropped: "dropped"
        case .planning: "planned"
        default: "current"
        }
    }

    private static func localStatus(_ value: String) -> TrackStatus? {
        switch value {
        case "current": .reading
        case "completed": .completed
        case "on_hold": .paused
        case "dropped": .dropped
        case "planned": .planning
        default: nil
        }
    }
}
