import ExtensionRunner
import Foundation

/// Hikka.
///
/// Written for this port -- tachiyomiazios has no Hikka client. The reference flow, endpoints and
/// status vocabulary follow Android's `HikkaApi`/`HikkaUtils` so both apps write the same values.
///
/// Hikka's OAuth is not a redirect exchange: the site is opened with a client reference, and the
/// app then trades that reference plus its secret for a token. That does not fit `OAuthTracker`'s
/// callback shape, so this is a plain `Tracker` that runs the flow itself.
final class HikkaTracker: Tracker {
    let id = "hikka"
    let name = "Hikka"
    let icon = PlatformImage(named: "hikka")

    private static let apiUrl = "https://api.hikka.io"
    private static let siteUrl = "https://hikka.io"
    private static let scope = "readlist,read:user-details"
    private static let clientReference = "598ef1f5-b9d2-4e66-8b65-06949d5e14fc"
    private static let clientSecret =
        "OKwzrNOZxq40psFgfcCUYddnvaeZWDnd34rt7fdcB5GmHoBBQuNTWX"
        + "61sZs8KECEWVXtMUDtq8QC4t9WX4DwWWYLXEVlgnlUXGT1fWCb-18c"
        + "Zd2m8Co-8HN6JQcjoP-B"

    private var token: String? {
        get { UserDefaults.standard.string(forKey: "Tracker.hikka.token") }
        set { UserDefaults.standard.set(newValue, forKey: "Tracker.hikka.token") }
    }

    var isLoggedIn: Bool { token != nil }

    func getTrackerInfo() async throws -> TrackerInfo {
        .init(supportedStatuses: TrackStatus.defaultStatuses, scoreType: .tenPoint)
    }

    // MARK: - Auth

    /// The page the user authorises on. They approve there, then the app claims the token.
    var authenticationUrl: URL? {
        var components = URLComponents(string: "\(Self.siteUrl)/oauth")
        components?.queryItems = [
            .init(name: "reference", value: Self.clientReference),
            .init(name: "scope", value: Self.scope)
        ]
        return components?.url
    }

    /// Trades the client reference for a token once the user has approved it.
    func claimToken() async throws {
        var request = URLRequest(url: URL(string: "\(Self.apiUrl)/auth/token")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "request_reference": Self.clientReference,
            "client_secret": Self.clientSecret
        ])

        let (data, _) = try await URLSession.shared.data(for: request)
        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let secret = json["secret"] as? String
        else { throw URLError(.userAuthenticationRequired) }
        token = secret
    }

    func logout() async throws {
        token = nil
    }

    // MARK: - Requests

    private func send(
        _ method: String,
        _ path: String,
        body: [String: Any?]? = nil
    ) async throws -> Data {
        guard let token else { throw URLError(.userAuthenticationRequired) }
        var request = URLRequest(url: URL(string: "\(Self.apiUrl)\(path)")!)
        request.httpMethod = method
        // Hikka takes the token in its own header rather than Authorization.
        request.setValue(token, forHTTPHeaderField: "auth")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let body {
            request.httpBody = try JSONSerialization.data(
                withJSONObject: body.compactMapValues { $0 }
            )
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
        let data = try await send("POST", "/manga?page=1&size=20", body: ["query": title])
        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let list = json["list"] as? [[String: Any]]
        else { return [] }

        return list.compactMap { entry in
            // Hikka addresses manga by slug, not a numeric id.
            guard let slug = entry["slug"] as? String else { return nil }
            return TrackSearchItem(
                id: slug,
                title: entry["title_ua"] as? String
                    ?? entry["title_en"] as? String
                    ?? entry["title_original"] as? String
                    ?? "",
                coverUrl: entry["image"] as? String,
                description: entry["description_ua"] as? String,
                status: nil,
                type: nil,
                tracked: false
            )
        }
    }

    func register(trackId: String, highestChapterRead: Float?, earliestReadDate: Date?) async throws -> String? {
        _ = try await send("PUT", "/read/manga/\(trackId)", body: [
            "note": "",
            "chapters": Int(highestChapterRead ?? 0),
            "volumes": 0,
            "rereads": 0,
            "score": 0,
            "status": highestChapterRead != nil ? "reading" : "planned",
            "start_date": earliestReadDate.map { Int($0.timeIntervalSince1970) }
        ])
        return trackId
    }

    func update(trackId: String, update: TrackUpdate) async throws {
        // The endpoint replaces the whole entry, so unset fields are filled from what is stored.
        let current = try? await getState(trackId: trackId)
        let status = update.status ?? current?.status ?? .reading

        _ = try await send("PUT", "/read/manga/\(trackId)", body: [
            "note": "",
            "chapters": Int(update.lastReadChapter ?? current?.lastReadChapter ?? 0),
            "volumes": update.lastReadVolume ?? current?.lastReadVolume ?? 0,
            "rereads": status == .rereading ? 1 : 0,
            "score": update.score ?? current?.score ?? 0,
            "status": Self.remoteStatus(status),
            "start_date": (update.startReadDate ?? current?.startReadDate)
                .map { Int($0.timeIntervalSince1970) },
            "end_date": (update.finishReadDate ?? current?.finishReadDate)
                .map { Int($0.timeIntervalSince1970) }
        ])
    }

    func getState(trackId: String) async throws -> TrackState {
        let data = try await send("GET", "/read/manga/\(trackId)")
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return TrackState()
        }

        var state = TrackState()
        state.status = (json["status"] as? String).flatMap(Self.localStatus)
        state.lastReadChapter = (json["chapters"] as? Int).map(Float.init)
        state.lastReadVolume = json["volumes"] as? Int
        state.score = json["score"] as? Int
        state.startReadDate = (json["start_date"] as? Int)
            .map { Date(timeIntervalSince1970: TimeInterval($0)) }
        state.finishReadDate = (json["end_date"] as? Int)
            .map { Date(timeIntervalSince1970: TimeInterval($0)) }
        return state
    }

    func getUrl(trackId: String) async -> URL? {
        URL(string: "\(Self.siteUrl)/manga/\(trackId)")
    }

    // MARK: - Status vocabulary

    /// Matches Android's `toApiStatus`. Rereading reports as reading, with a reread counted above.
    private static func remoteStatus(_ status: TrackStatus) -> String {
        switch status {
        case .reading, .rereading: "reading"
        case .completed: "completed"
        case .paused: "on_hold"
        case .dropped: "dropped"
        case .planning: "planned"
        default: "reading"
        }
    }

    private static func localStatus(_ value: String) -> TrackStatus? {
        switch value {
        case "reading": .reading
        case "completed": .completed
        case "on_hold": .paused
        case "dropped": .dropped
        case "planned": .planning
        default: nil
        }
    }
}
