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
            let userAgent = try await webView.evaluateJavaScript("navigator.userAgent") as? String
            self.userAgent = userAgent
            return userAgent
        } catch {
            LogManager.logger.error("Error getting user agent: \(error)")
            return nil
        }
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
