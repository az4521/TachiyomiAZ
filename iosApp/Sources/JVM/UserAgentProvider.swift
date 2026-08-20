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
    /// WKWebView leaves out two tokens that Safari includes. What it reports is
    ///
    ///     ...(KHTML, like Gecko) Mobile/15E148
    ///
    /// where Safari sends
    ///
    ///     ...(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1
    ///
    /// so `/Safari\//.test(navigator.userAgent)` -- the most common "is this a real browser" test
    /// there is -- is false for every page this app opens.
    ///
    /// Only those two tokens are added. Everything else is left exactly as WebKit wrote it,
    /// including the system version, which WebKit renders as its own major and minor. Building
    /// that part by hand instead produced `iPhone OS 16_7_16`, a three-component token no Safari
    /// sends, and Cloudflare stopped issuing usable clearances against it.
    ///
    /// Left alone if the tokens are already there, so a user-supplied agent is never rewritten.
    static func completedSafariUserAgent(_ reported: String) -> String {
        guard !reported.contains("Safari/") else { return reported }

        var value = reported
        if !value.contains("Version/") {
            if let range = value.range(of: "Mobile/") {
                value.replaceSubrange(range, with: "Version/\(safariVersion) Mobile/")
            } else {
                value += " Version/\(safariVersion)"
            }
        }
        return value + " Safari/604.1"
    }

    /// Safari's version, from the system's.
    ///
    /// Safari tracks the system's major and minor while the two move together, then stops: iOS
    /// carries on to x.7 and beyond with security updates that ship no new Safari, so the last
    /// Safari for that major is what those systems report.
    ///
    /// That tail has to be the *real* terminal version, not the major with the minor clamped.
    /// Clamping produced `Version/16.6` on iOS 16.7, and 16.6 is a number Safari never shipped --
    /// 16.6.2 is where 16 ended. SchaleNetwork checks the version and refuses the invented one
    /// while accepting the real one, which is the difference between those two strings and
    /// nothing else about them.
    private static var safariVersion: String {
        let system = ProcessInfo.processInfo.operatingSystemVersion

        // Where Safari stopped for each major, for systems that ran on past it.
        let terminalVersions = [
            15: "15.6.1",
            16: "16.6.2",
            17: "17.6.1",
            18: "18.6"
        ]

        // While the system is still on a minor Safari shipped, they agree.
        if system.minorVersion <= 6 {
            return "\(system.majorVersion).\(system.minorVersion)"
        }
        return terminalVersions[system.majorVersion]
            ?? "\(system.majorVersion).6"
    }

    /// The agent used when the WebView cannot be asked.
    ///
    /// It used to be a desktop Safari string, which is worse than it looks: everything else about
    /// this device says iPhone, and an agent claiming macOS alongside that is a contradiction.
    /// This one describes the device it is running on, and is only reached if the WebView fails.
    static var fallback: String {
        let system = ProcessInfo.processInfo.operatingSystemVersion
        return "Mozilla/5.0 (iPhone; CPU iPhone OS "
            + "\(system.majorVersion)_\(system.minorVersion) like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) "
            + "Version/\(safariVersion) Mobile/15E148 Safari/604.1"
    }

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
