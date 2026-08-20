import ExtensionRunner
import Foundation

/// Server access for the enhanced trackers.
///
/// Upstream's helpers of these names are full source clients -- they also fetch series, parse
/// listings and drive the source's own browsing. Only three things are used from them here
/// (`getServerUrl`, `getAuthorizationHeader`, `request`), because the browsing side is the JVM
/// extension's job in this port. These are written to that surface rather than vendored, which
/// keeps the parked source implementations out of the build.
///
/// The server address and credentials come from `EnhancedSourceBridge`, which mirrors them out of
/// the extension's settings inside the VM.

/// Where an enhanced source's server lives, and how to authenticate to it.
protocol EnhancedServerHelper: Sendable {
    var sourceKey: String { get }
}

extension EnhancedServerHelper {
    /// Basic auth from the source's stored credentials, or a bare token when one is configured.
    func getAuthorizationHeader() -> String? {
        if let token = UserDefaults.standard.string(forKey: "\(sourceKey).token"), !token.isEmpty {
            return "Bearer \(token)"
        }
        guard
            let username = UserDefaults.standard.string(forKey: "\(sourceKey).login.username"),
            let password = UserDefaults.standard.string(forKey: "\(sourceKey).login.password"),
            !username.isEmpty
        else { return nil }
        let encoded = Data("\(username):\(password)".utf8).base64EncodedString()
        return "Basic \(encoded)"
    }

    func getConfiguredServer() throws -> URL {
        guard
            let server = UserDefaults.standard.string(forKey: "\(sourceKey).server"),
            let url = URL(string: server.hasSuffix("/") ? server : server + "/")
        else {
            throw SourceError.message("NO_SERVER_CONFIGURED")
        }
        return url
    }

    func getServerUrl(path: String) throws -> URL {
        guard let url = URL(string: path, relativeTo: try getConfiguredServer()) else {
            throw SourceError.message("INVALID_SERVER_URL")
        }
        return url
    }

    /// One authenticated request, decoding the response.
    func perform<T: Decodable>(
        path: String,
        method: String = "GET",
        body: Data? = nil
    ) async throws -> T {
        var request = URLRequest(url: try getServerUrl(path: path))
        request.httpMethod = method
        if let auth = getAuthorizationHeader() {
            request.setValue(auth, forHTTPHeaderField: "Authorization")
        }
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = body

        let (data, _) = try await URLSession.shared.data(for: request)
        // Some of these endpoints answer a bare `true` rather than an object.
        if T.self == Bool.self, data.isEmpty {
            return true as! T
        }
        return try JSONDecoder().decode(T.self, from: data)
    }
}

struct KomgaHelper: EnhancedServerHelper {
    let sourceKey: String

    /// Komga's endpoints are plain REST paths under the configured server.
    func request<T: Decodable>(
        path: String,
        method: HttpMethod = .GET,
        body: Data? = nil
    ) async throws -> T {
        try await perform(path: path, method: method.rawValue, body: body)
    }
}

struct KavitaHelper: EnhancedServerHelper {
    let sourceKey: String

    /// Kavita's endpoints are addressed by path, with an optional JSON body.
    func request<T: Decodable>(
        path: String,
        method: HttpMethod = .GET,
        body: Data? = nil
    ) async throws -> T {
        try await perform(path: path, method: method.rawValue, body: body)
    }
}

struct SuwayomiHelper: EnhancedServerHelper {
    let sourceKey: String

    /// Suwayomi speaks GraphQL: every call is a POST of a query document to one endpoint.
    func request<T: Decodable, U: Encodable>(body: U) async throws -> T {
        try await perform(
            path: "api/graphql",
            method: "POST",
            body: try JSONEncoder().encode(body)
        )
    }
}

/// The verbs these helpers are called with.
///
/// Named to match what the vendored tracker APIs already pass (`.GET`, `.POST`).
enum HttpMethod: String, Sendable {
    case GET
    case POST
    case PUT
    case PATCH
    case DELETE
}
