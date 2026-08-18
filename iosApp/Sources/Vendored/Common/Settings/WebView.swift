//
//  WebView.swift
//  Aidoku
//
//  Created by Skitty on 5/21/25.
//

import ExtensionRunner
import SwiftUI
import UIKit
import WebKit

@MainActor
enum PersistentWebViewSession {
    static let processPool = WKProcessPool()
    static let browserCompatibilityScript = """
    (() => {
      const installDimensionFallback = (name, values) => {
        if (Number(window[name]) > 0) return;
        const resolve = () => {
          for (const value of values()) {
            const number = Number(value);
            if (Number.isFinite(number) && number > 0) return number;
          }
          return 1;
        };
        try { window[name] = resolve(); } catch (_) {}
        if (Number(window[name]) > 0) return;
        try {
          Object.defineProperty(window, name, {
            configurable: true,
            get: resolve,
          });
        } catch (_) {}
      };
      installDimensionFallback('outerWidth', () => [
        window.innerWidth,
        window.visualViewport && window.visualViewport.width,
        window.screen && window.screen.width,
        document.documentElement && document.documentElement.clientWidth,
      ]);
      installDimensionFallback('outerHeight', () => [
        window.innerHeight,
        window.visualViewport && window.visualViewport.height,
        window.screen && window.screen.height,
        document.documentElement && document.documentElement.clientHeight,
      ]);
    })();
    """
    private static var localStorageSnapshots: [String: [String: String]] = [:]
    private static var localStorageSnapshotOrder: [String] = []

    static func configuration() -> WKWebViewConfiguration {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.processPool = processPool
        configuration.userContentController.addUserScript(WKUserScript(
            source: browserCompatibilityScript,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: false
        ))
        return configuration
    }

    static func saveLocalStorage(_ values: [String: String], for url: URL) {
        guard let origin = origin(for: url) else { return }
        localStorageSnapshotOrder.removeAll { $0 == origin }
        if values.isEmpty {
            localStorageSnapshots[origin] = nil
        } else {
            localStorageSnapshots[origin] = values
            localStorageSnapshotOrder.append(origin)
            while localStorageSnapshotOrder.count > 32 {
                localStorageSnapshots[localStorageSnapshotOrder.removeFirst()] = nil
            }
        }
    }

    static func localStorage(for url: URL) -> [String: String] {
        guard let origin = origin(for: url) else { return [:] }
        return localStorageSnapshots[origin] ?? [:]
    }

    static func origin(for url: URL) -> String? {
        guard
            let scheme = url.scheme?.lowercased(),
            scheme == "http" || scheme == "https",
            let host = url.host?.lowercased()
        else { return nil }
        let defaultPort = scheme == "https" ? 443 : 80
        let port = url.port.flatMap { $0 == defaultPort ? nil : ":\($0)" } ?? ""
        return "\(scheme)://\(host)\(port)"
    }
}

@MainActor
final class WebViewSessionHandle: ObservableObject {
    weak var webView: WKWebView?

    @discardableResult
    func captureLocalStorage() async -> [String: String]? {
        guard
            let webView,
            let url = webView.url,
            let values = await webView.getAllLocalStorage()
        else { return nil }
        PersistentWebViewSession.saveLocalStorage(values, for: url)
        return values
    }
}

@MainActor
struct WebView: UIViewRepresentable {
    let url: URL
    let localStorageKeys: [String]

    @Binding var cookies: [String: String]
    @Binding var detailedCookies: [HTTPCookie]
    @Binding var localStorage: [String: String]
    @Binding var userAgent: String
    @Binding var reloadToggle: Bool
    @Binding var goBackToggle: Bool
    @Binding var goForwardToggle: Bool
    @Binding var canGoBack: Bool
    @Binding var canGoForward: Bool
    @Binding var currentURL: URL?

    let preferredUserAgent: String?
    let initialCookies: [HTTPCookie]
    let sessionHandle: WebViewSessionHandle?

    private let webView = WKWebView(
        frame: .zero,
        configuration: PersistentWebViewSession.configuration()
    )

    init(
        _ url: URL,
        localStorageKeys: [String] = [],
        cookies: Binding<[String: String]> = .constant([:]),
        detailedCookies: Binding<[HTTPCookie]> = .constant([]),
        localStorage: Binding<[String: String]> = .constant([:]),
        userAgent: Binding<String> = .constant(""),
        preferredUserAgent: String? = nil,
        initialCookies: [HTTPCookie] = [],
        sessionHandle: WebViewSessionHandle? = nil,
        reloadToggle: Binding<Bool> = .constant(false),
        goBackToggle: Binding<Bool> = .constant(false),
        goForwardToggle: Binding<Bool> = .constant(false),
        canGoBack: Binding<Bool> = .constant(false),
        canGoForward: Binding<Bool> = .constant(false),
        currentURL: Binding<URL?> = .constant(nil)
    ) {
        self.url = url
        self.localStorageKeys = localStorageKeys
        self._cookies = cookies
        self._detailedCookies = detailedCookies
        self._localStorage = localStorage
        self._userAgent = userAgent
        self.preferredUserAgent = preferredUserAgent
        self.initialCookies = initialCookies
        self.sessionHandle = sessionHandle
        self._reloadToggle = reloadToggle
        self._goBackToggle = goBackToggle
        self._goForwardToggle = goForwardToggle
        self._canGoBack = canGoBack
        self._canGoForward = canGoForward
        self._currentURL = currentURL
    }

    func makeUIView(context: Context) -> WKWebView {
        webView.navigationDelegate = context.coordinator
        webView.customUserAgent = preferredUserAgent
        context.coordinator.webView = webView
        sessionHandle?.webView = webView
        Task { @MainActor in
            let store = webView.configuration.websiteDataStore.httpCookieStore
            for cookie in initialCookies {
                await withCheckedContinuation {
                    (continuation: CheckedContinuation<Void, Never>) in
                    store.setCookie(cookie) { continuation.resume() }
                }
            }
            webView.load(URLRequest(url: url))
        }
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        context.coordinator.parent = self
        if
            let preferredUserAgent,
            !preferredUserAgent.isEmpty,
            uiView.customUserAgent != preferredUserAgent
        {
            uiView.customUserAgent = preferredUserAgent
            uiView.reload()
        }
        if reloadToggle {
            reloadToggle = false
            uiView.reload()
        }
        if goBackToggle {
            goBackToggle = false
            uiView.goBack()
        }
        if goForwardToggle {
            goForwardToggle = false
            uiView.goForward()
        }
    }

    func makeCoordinator() -> Coordinator {
        .init(parent: self)
    }

    class Coordinator: NSObject, WKNavigationDelegate, WKHTTPCookieStoreObserver {
        var parent: WebView
        weak var webView: WKWebView?
        private var visitedHosts: Set<String>

        init(parent: WebView) {
            self.parent = parent
            self.visitedHosts = Set(
                parent.url.host.map { [$0.lowercased()] } ?? []
            )
            super.init()
            WKWebsiteDataStore.default().httpCookieStore.add(self)
        }

        deinit {
            WKWebsiteDataStore.default().httpCookieStore.remove(self)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            Task {
                await updateState(from: webView)
                _ = await parent.sessionHandle?.captureLocalStorage()
                if !parent.localStorageKeys.isEmpty {
                    let storage = await webView.getLocalStorage(keys: parent.localStorageKeys)
                    await MainActor.run {
                        parent.localStorage = storage
                    }
                }
            }
        }

        func webView(
            _ webView: WKWebView,
            didFail navigation: WKNavigation!,
            withError error: Error
        ) {
            Task { await updateState(from: webView) }
        }

        func webView(
            _ webView: WKWebView,
            didFailProvisionalNavigation navigation: WKNavigation!,
            withError error: Error
        ) {
            Task { await updateState(from: webView) }
        }

        func cookiesDidChange(in cookieStore: WKHTTPCookieStore) {
            guard let webView else { return }
            Task {
                await updateState(from: webView)
                if !parent.localStorageKeys.isEmpty {
                    let storage = await webView.getLocalStorage(keys: parent.localStorageKeys)
                    await MainActor.run {
                        parent.localStorage = storage
                    }
                }
            }
        }

        private func updateState(from webView: WKWebView) async {
            if let host = webView.url?.host?.lowercased() {
                visitedHosts.insert(host)
            }
            let allCookies = await webView.configuration.websiteDataStore
                .httpCookieStore
                .allCookies()
            let matching = allCookies.filter { cookie in
                visitedHosts.contains { host in
                    Self.cookie(cookie, matchesHost: host)
                }
            }
            let cookieValues = Dictionary(
                matching.map { ($0.name, $0.value) },
                uniquingKeysWith: { _, latest in latest }
            )
            let currentUserAgent = (
                try? await webView.evaluateJavaScript("navigator.userAgent")
            ) as? String ?? ""
            await MainActor.run {
                parent.cookies = cookieValues
                parent.detailedCookies = matching
                parent.userAgent = currentUserAgent
                parent.currentURL = webView.url
                parent.canGoBack = webView.canGoBack
                parent.canGoForward = webView.canGoForward
            }
        }

        private static func cookie(
            _ cookie: HTTPCookie,
            matchesHost hostValue: String?
        ) -> Bool {
            guard let host = hostValue?.lowercased() else { return false }
            let domain = cookie.domain
                .trimmingCharacters(in: CharacterSet(charactersIn: "."))
                .lowercased()
            return host == domain || host.hasSuffix("." + domain)
        }
    }
}

// SourceWebBrowserView was removed when this file was vendored.
//
// It took a TachiyomiXSourceRunner -- tachiyomiazios's adapter making the JVM host look like an
// AidokuRunner source. This port drives the host through SourceRuntime instead and deliberately
// did not take that adapter, so the view had no type to bind to. The per-source login browser it
// provided needs re-implementing against SourceRuntime; the WebView above, which the Cloudflare
// solver uses, is unaffected.
