//
//  UserAgentProvider.swift
//  Aidoku
//
//  Created by Skitty on 3/24/25.
//

import Foundation
import WebKit

class UserAgentProvider {
    static let shared = UserAgentProvider()

    static let extensionNetworkUserAgentKey =
        "Advanced.extensionNetworkUserAgent"

    private var task: Task<String?, Never>?
    private var userAgent: String?

    private init() {
        // start fetching user agent immediately
        task = Task {
            await fetchUserAgent()
        }
    }

    @MainActor
    private func fetchUserAgent() async -> String? {
        let webView = WKWebView()
        do {
            let reported = try await webView.evaluateJavaScript("navigator.userAgent") as? String
            let userAgent = reported.map(Self.completedSafariUserAgent)
            self.userAgent = userAgent
            return userAgent
        } catch {
            LogManager.logger.error("Error getting user agent: \(error)")
            return nil
        }
    }

    /// A WKWebView's user agent, completed into the one Safari would send.
    ///
    /// WKWebView leaves out two tokens that Safari includes: `Version/<n>` and the trailing
    /// `Safari/604.1`. What it reports here is
    ///
    ///     ...(KHTML, like Gecko) Mobile/15E148
    ///
    /// where Safari sends
    ///
    ///     ...(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1
    ///
    /// so `/Safari\//.test(navigator.userAgent)` -- the most common "is this a real browser" test
    /// there is -- is false for every page this app opens. A source that gates itself on a browser
    /// check sees a string no shipping browser sends. Restoring the two tokens makes the value
    /// honest about the engine actually rendering the page, which is WebKit either way.
    ///
    /// Left alone if the tokens are already there, so a user-supplied agent is never rewritten.
    static func completedSafariUserAgent(_ reported: String) -> String {
        guard !reported.contains("Safari/") else { return reported }

        var value = reported
        if !value.contains("Version/") {
            let version = ProcessInfo.processInfo.operatingSystemVersion
            let marketing = "\(version.majorVersion).\(version.minorVersion)"
            if let range = value.range(of: "Mobile/") {
                value.replaceSubrange(range, with: "Version/\(marketing) Mobile/")
            } else {
                value += " Version/\(marketing)"
            }
        }
        return value + " Safari/604.1"
    }

    /// A desktop Safari string, used when the WebView cannot be asked.
    ///
    /// The fallback used to be an empty string, which sites read as a missing User-Agent -- and
    /// some, WeebCentral among them, answer that with a block page. Anything is better than
    /// nothing here, and this matches what the WebView would have reported.
    static let fallback = """
        Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 \
        (KHTML, like Gecko) Version/17.0 Safari/605.1.15
        """

    func getUserAgent() async -> String {
        if let userAgent, !userAgent.isEmpty {
            return userAgent
        }
        let fetched = await task?.value ?? nil
        if let fetched, !fetched.isEmpty {
            return fetched
        }
        return Self.fallback
    }

    func getUserAgentBlocking() -> String {
        if let userAgent, !userAgent.isEmpty {
            return userAgent
        }
        return BlockingTask {
            await self.getUserAgent()
        }.get()
    }

    func getExtensionNetworkUserAgentBlocking() -> String {
        BlockingTask {
            await self.getExtensionNetworkUserAgent()
        }.get()
    }

    func getExtensionNetworkUserAgent() async -> String {
        let configured = UserDefaults.standard.string(
            forKey: Self.extensionNetworkUserAgentKey
        )?
        .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !configured.isEmpty {
            return configured
        }
        return await getUserAgent()
    }
}
