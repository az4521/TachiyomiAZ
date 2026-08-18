import AuthenticationServices
import CryptoKit
import Foundation

/// Token storage for OAuth trackers.
///
/// UserDefaults rather than the Keychain, matching what the rest of this port does for now. Worth
/// moving before anyone signs in with a real account on a shared device.
enum TokenStore {
    static func token(_ service: TrackerService) -> String? {
        UserDefaults.standard.string(forKey: "tracker.\(service.rawValue).token")
    }

    static func setToken(_ token: String?, for service: TrackerService) {
        let key = "tracker.\(service.rawValue).token"
        if let token {
            UserDefaults.standard.set(token, forKey: key)
        } else {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }
}

/// Presents the system OAuth sheet.
@MainActor
final class OAuthSession: NSObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = OAuthSession()

    private var session: ASWebAuthenticationSession?

    func authenticate(url: URL, callbackScheme: String) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(
                url: url,
                callbackURLScheme: callbackScheme
            ) { callback, error in
                if let callback {
                    continuation.resume(returning: callback)
                } else {
                    continuation.resume(throwing: error ?? URLError(.userCancelledAuthentication))
                }
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            self.session = session
            session.start()
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }?
            .windows.first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }
}

/// MyAnimeList, via its OAuth2 + REST API.
///
/// The client id is the one TachiyomiAZ itself registers. PKCE with a plain challenge is not a
/// shortcut: MAL only accepts `code_challenge_method=plain`, which is why the verifier is sent
/// unhashed here while AniList needs no PKCE at all.
final class MyAnimeListTracker: Tracker {
    let service: TrackerService = .myAnimeList

    private let clientId = "0e8fbd48d8f2b5e77e2a5ca23e1ba1b1"
    private let redirectScheme = "tachiyomiaz"

    var isLoggedIn: Bool { TokenStore.token(.myAnimeList) != nil }

    @MainActor
    func logIn() async throws {
        let verifier = Self.codeVerifier()
        var components = URLComponents(string: "https://myanimelist.net/v1/oauth2/authorize")!
        components.queryItems = [
            .init(name: "client_id", value: clientId),
            .init(name: "response_type", value: "code"),
            .init(name: "code_challenge", value: verifier),
            .init(name: "code_challenge_method", value: "plain"),
            .init(name: "redirect_uri", value: "\(redirectScheme)://oauth")
        ]
        let callback = try await OAuthSession.shared.authenticate(
            url: components.url!,
            callbackScheme: redirectScheme
        )
        guard let code = URLComponents(url: callback, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "code" })?.value else {
            throw URLError(.badServerResponse)
        }
        try await exchange(code: code, verifier: verifier)
    }

    private func exchange(code: String, verifier: String) async throws {
        var request = URLRequest(url: URL(string: "https://myanimelist.net/v1/oauth2/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let body = [
            "client_id": clientId,
            "code": code,
            "code_verifier": verifier,
            "grant_type": "authorization_code",
            "redirect_uri": "\(redirectScheme)://oauth"
        ]
        request.httpBody = body
            .map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "")" }
            .joined(separator: "&")
            .data(using: .utf8)

        let (data, _) = try await URLSession.shared.data(for: request)
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let token = json["access_token"] as? String else {
            throw URLError(.userAuthenticationRequired)
        }
        TokenStore.setToken(token, for: .myAnimeList)
    }

    private static func codeVerifier() -> String {
        var bytes = [UInt8](repeating: 0, count: 64)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    func search(_ query: String) async throws -> [TrackSearchResult] {
        guard let token = TokenStore.token(.myAnimeList) else { throw URLError(.userAuthenticationRequired) }
        var components = URLComponents(string: "https://api.myanimelist.net/v2/manga")!
        components.queryItems = [
            .init(name: "q", value: query),
            .init(name: "limit", value: "20"),
            .init(name: "fields", value: "id,title,main_picture,num_chapters,synopsis")
        ]
        var request = URLRequest(url: components.url!)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let (data, _) = try await URLSession.shared.data(for: request)
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let list = json["data"] as? [[String: Any]] else { return [] }

        return list.compactMap { entry in
            guard let node = entry["node"] as? [String: Any],
                  let id = node["id"] as? Int,
                  let title = node["title"] as? String else { return nil }
            let picture = (node["main_picture"] as? [String: Any])?["medium"] as? String
            return TrackSearchResult(
                remoteId: Int64(id),
                title: title,
                coverURL: picture,
                totalChapters: node["num_chapters"] as? Int ?? 0,
                summary: node["synopsis"] as? String,
                trackingURL: "https://myanimelist.net/manga/\(id)"
            )
        }
    }

    func update(
        remoteId: Int64,
        status: TrackStatus,
        lastChapterRead: Int,
        score: Float
    ) async throws {
        guard let token = TokenStore.token(.myAnimeList) else { throw URLError(.userAuthenticationRequired) }
        var request = URLRequest(
            url: URL(string: "https://api.myanimelist.net/v2/manga/\(remoteId)/my_list_status")!
        )
        request.httpMethod = "PATCH"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let body = [
            "status": Self.remoteStatus(status),
            "num_chapters_read": String(lastChapterRead),
            "score": String(Int(score))
        ]
        request.httpBody = body
            .map { "\($0.key)=\($0.value)" }
            .joined(separator: "&")
            .data(using: .utf8)
        _ = try await URLSession.shared.data(for: request)
    }

    private static func remoteStatus(_ status: TrackStatus) -> String {
        switch status {
        case .reading, .rereading: return "reading"
        case .completed: return "completed"
        case .onHold: return "on_hold"
        case .dropped: return "dropped"
        case .planToRead: return "plan_to_read"
        }
    }

    func logOut() { TokenStore.setToken(nil, for: .myAnimeList) }
}

/// AniList, via its GraphQL API.
final class AniListTracker: Tracker {
    let service: TrackerService = .aniList

    private let clientId = "12030"
    private let endpoint = URL(string: "https://graphql.anilist.co")!

    var isLoggedIn: Bool { TokenStore.token(.aniList) != nil }

    @MainActor
    func logIn() async throws {
        // AniList's implicit flow returns the token in the URL fragment, so there is no exchange.
        let url = URL(
            string: "https://anilist.co/api/v2/oauth/authorize?client_id=\(clientId)&response_type=token"
        )!
        let callback = try await OAuthSession.shared.authenticate(
            url: url,
            callbackScheme: "tachiyomiaz"
        )
        guard let fragment = callback.fragment,
              let token = fragment
                  .split(separator: "&")
                  .first(where: { $0.hasPrefix("access_token=") })?
                  .dropFirst("access_token=".count) else {
            throw URLError(.userAuthenticationRequired)
        }
        TokenStore.setToken(String(token), for: .aniList)
    }

    private func query(_ document: String, variables: [String: Any]) async throws -> [String: Any] {
        guard let token = TokenStore.token(.aniList) else { throw URLError(.userAuthenticationRequired) }
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(
            withJSONObject: ["query": document, "variables": variables]
        )
        let (data, _) = try await URLSession.shared.data(for: request)
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let payload = json["data"] as? [String: Any] else {
            throw URLError(.badServerResponse)
        }
        return payload
    }

    func search(_ text: String) async throws -> [TrackSearchResult] {
        let document = """
        query ($search: String) {
          Page(perPage: 20) {
            media(search: $search, type: MANGA, format_not_in: [NOVEL]) {
              id title { romaji } coverImage { large } chapters description
            }
          }
        }
        """
        let payload = try await query(document, variables: ["search": text])
        guard let page = payload["Page"] as? [String: Any],
              let media = page["media"] as? [[String: Any]] else { return [] }

        return media.compactMap { entry in
            guard let id = entry["id"] as? Int else { return nil }
            let title = (entry["title"] as? [String: Any])?["romaji"] as? String ?? "Unknown"
            let cover = (entry["coverImage"] as? [String: Any])?["large"] as? String
            return TrackSearchResult(
                remoteId: Int64(id),
                title: title,
                coverURL: cover,
                totalChapters: entry["chapters"] as? Int ?? 0,
                summary: entry["description"] as? String,
                trackingURL: "https://anilist.co/manga/\(id)"
            )
        }
    }

    func update(
        remoteId: Int64,
        status: TrackStatus,
        lastChapterRead: Int,
        score: Float
    ) async throws {
        let document = """
        mutation ($mediaId: Int, $status: MediaListStatus, $progress: Int, $score: Float) {
          SaveMediaListEntry(mediaId: $mediaId, status: $status, progress: $progress, scoreRaw: $score) {
            id
          }
        }
        """
        _ = try await query(document, variables: [
            "mediaId": Int(remoteId),
            "status": Self.remoteStatus(status),
            "progress": lastChapterRead,
            "score": score
        ])
    }

    private static func remoteStatus(_ status: TrackStatus) -> String {
        switch status {
        case .reading: return "CURRENT"
        case .completed: return "COMPLETED"
        case .onHold: return "PAUSED"
        case .dropped: return "DROPPED"
        case .planToRead: return "PLANNING"
        case .rereading: return "REPEATING"
        }
    }

    func logOut() { TokenStore.setToken(nil, for: .aniList) }
}
