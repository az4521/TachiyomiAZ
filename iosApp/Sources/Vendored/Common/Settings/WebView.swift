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
        context.coordinator.installDiagnostics(on: webView)
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

    class Coordinator: NSObject, WKNavigationDelegate, WKHTTPCookieStoreObserver, WKScriptMessageHandler {
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

        /// The name the page posts diagnostics under.
        private static let diagnosticsHandler = "tachiyomiaz_browser_diagnostics"

        /// Reports what the page's own scripts say to the app log.
        ///
        /// The extension-created WebViews have forwarded console output since they were written;
        /// this one never has, so a site whose scripts throw or hang looked identical from here to
        /// one that simply had not finished. A site that gates itself on a browser check --
        /// SchaleNetwork runs one after the Cloudflare challenge, and shows a spinner instead of
        /// its buttons until it passes -- gives no other outward sign of what went wrong.
        func installDiagnostics(on webView: WKWebView) {
            let controller = webView.configuration.userContentController
            controller.removeScriptMessageHandler(forName: Self.diagnosticsHandler)
            controller.add(self, name: Self.diagnosticsHandler)
            controller.addUserScript(WKUserScript(
                source: """
                (() => {
                  if (window.__tachiyomiazBrowserDiagnostics) return;
                  window.__tachiyomiazBrowserDiagnostics = true;
                  const post = (text) => {
                    try {
                      window.webkit.messageHandlers.\(Self.diagnosticsHandler)
                        .postMessage(String(text));
                    } catch (_) {}
                  };
                  // Errors only, and read passively.
                  //
                  // This used to wrap console.error and console.warn and serialise their
                  // arguments. That is precisely what the sites worth debugging are watching for:
                  // SchaleNetwork logs `%c%d` with transparent styling and an object whose getters
                  // fire if anything renders or serialises it, which is how a page tells a hooked
                  // console from a real one. Reading the arguments made this app look like the
                  // thing the check is looking for.
                  window.addEventListener('error', event => {
                    post(
                      'uncaught: ' + event.message + ' @ ' +
                      (event.filename || '?') + ':' + (event.lineno || 0)
                    );
                  });
                  window.addEventListener('unhandledrejection', event => {
                    let described = 'unknown';
                    try {
                      const reason = event.reason;
                      described = reason instanceof Error
                        ? reason.name + ' ' + reason.message
                        : String(reason);
                    } catch (_) {}
                    post('unhandled-rejection: ' + described);
                  });

                  // What this browser looks like from the page's side.
                  //
                  // Reported once per load so it can be compared against Safari on the same
                  // device: a site that gates on a browser check is comparing these, and the
                  // difference is easier to find by reading both than by guessing which one it
                  // dislikes. Read only -- nothing here defines, wraps or overrides anything.
                  window.addEventListener('load', () => {
                    try {
                      const own = (name) => {
                        const descriptor = Object.getOwnPropertyDescriptor(window, name);
                        if (!descriptor) return 'proto';
                        return descriptor.get ? 'own-getter' : 'own-value';
                      };
                      post('fingerprint: ' + JSON.stringify({
                        ua: navigator.userAgent,
                        platform: navigator.platform,
                        touch: navigator.maxTouchPoints,
                        webdriver: navigator.webdriver,
                        langs: (navigator.languages || []).join(','),
                        inner: window.innerWidth + 'x' + window.innerHeight,
                        outer: window.outerWidth + 'x' + window.outerHeight,
                        screen: window.screen.width + 'x' + window.screen.height,
                        dpr: window.devicePixelRatio,
                        outerWidthProp: own('outerWidth'),
                        outerHeightProp: own('outerHeight'),
                        cookieEnabled: navigator.cookieEnabled,
                        storage: (() => {
                          try {
                            window.localStorage.setItem('__t', '1');
                            window.localStorage.removeItem('__t');
                            return 'ok';
                          } catch (error) { return String(error); }
                        })(),
                      }));
                    } catch (error) {
                      post('fingerprint failed: ' + error);
                    }
                  });
                })();
                """,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: false
            ))
        }

        nonisolated func userContentController(
            _ controller: WKUserContentController,
            didReceive message: WKScriptMessage
        ) {
            guard
                message.name == Self.diagnosticsHandler,
                let text = message.body as? String
            else { return }
            LogManager.logger.info("[browser] \(text)")
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

@MainActor
struct SourceWebBrowserView: View {
    private struct Session {
        let userAgent: String?
        let cookies: [HTTPCookie]
    }

    let title: String
    let url: URL
    let runner: TachiyomiXSourceRunner?

    @Environment(\.dismiss) private var dismiss

    @State private var session: Session?
    @State private var cookies: [String: String] = [:]
    @State private var detailedCookies: [HTTPCookie] = []
    @State private var userAgent = ""
    @State private var currentURL: URL?
    @State private var reloadToggle = false
    @State private var goBackToggle = false
    @State private var goForwardToggle = false
    @State private var canGoBack = false
    @State private var canGoForward = false
    @State private var loading = true
    @State private var saving = false
    @State private var errorMessage: String?
    @StateObject private var webViewSession = WebViewSessionHandle()

    var body: some View {
        PlatformNavigationStack {
            Group {
                if let session {
                    WebView(
                        url,
                        cookies: $cookies,
                        detailedCookies: $detailedCookies,
                        userAgent: $userAgent,
                        preferredUserAgent: session.userAgent,
                        initialCookies: session.cookies,
                        sessionHandle: webViewSession,
                        reloadToggle: $reloadToggle,
                        goBackToggle: $goBackToggle,
                        goForwardToggle: $goForwardToggle,
                        canGoBack: $canGoBack,
                        canGoForward: $canGoForward,
                        currentURL: $currentURL
                    )
                    .edgesIgnoringSafeArea(.bottom)
                } else if loading {
                    ProgressView()
                } else {
                    VStack(spacing: 12) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.largeTitle)
                            .foregroundStyle(.secondary)
                        Text(errorMessage ?? "Unable to open website.")
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                    }
                    .padding()
                }
            }
            .navigationTitle(currentURL?.host ?? title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .topBarLeading) {
                    Button {
                        goBackToggle = true
                    } label: {
                        Image(systemName: "chevron.backward")
                    }
                    .disabled(!canGoBack)

                    Button {
                        goForwardToggle = true
                    } label: {
                        Image(systemName: "chevron.forward")
                    }
                    .disabled(!canGoForward)

                    Button {
                        reloadToggle = true
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .disabled(session == nil)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(NSLocalizedString("DONE")) {
                        closeAndSynchronize()
                    }
                    .disabled(saving)
                }
            }
        }
        .interactiveDismissDisabled()
        .task { await prepareSession() }
        .alert(
            NSLocalizedString("ERROR"),
            isPresented: Binding(
                get: { errorMessage != nil && session != nil },
                set: { if !$0 { errorMessage = nil } }
            )
        ) {
            Button(NSLocalizedString("OK"), role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func prepareSession() async {
        guard session == nil else { return }
        do {
            if let runner {
                async let resolvedUserAgent = runner.webLoginUserAgent()
                async let resolvedCookies = runner.webLoginCookies(for: url)
                let values = try await (resolvedUserAgent, resolvedCookies)
                userAgent = values.0
                detailedCookies = values.1
                session = Session(userAgent: values.0, cookies: values.1)
            } else {
                let resolvedUserAgent = await UserAgentProvider.shared
                    .getExtensionNetworkUserAgent()
                userAgent = resolvedUserAgent
                session = Session(
                    userAgent: resolvedUserAgent.isEmpty
                        ? nil
                        : resolvedUserAgent,
                    cookies: []
                )
            }
        } catch {
            errorMessage = error.localizedDescription
        }
        loading = false
    }

    private func closeAndSynchronize() {
        guard !saving else { return }
        saving = true
        Task {
            defer { saving = false }
            do {
                // localStorage-backed challenge tokens are not cookies. Take
                // an explicit snapshot before the visible WebView is dismissed
                // so a newly-created extension WebView cannot race WebKit's
                // cross-process persistence.
                _ = await webViewSession.captureLocalStorage()
                if let runner {
                    let resolvedUserAgent = userAgent.isEmpty
                        ? (try await runner.webLoginUserAgent())
                        : userAgent
                    try await runner.commitWebLogin(
                        cookies: detailedCookies,
                        userAgent: resolvedUserAgent
                    )
                }
                dismiss()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}

@MainActor
enum SourceWebBrowserPresenter {
    static func makeViewController(
        source: ExtensionRunner.Source?,
        url: URL,
        title: String
    ) -> UIViewController {
        let runner = source?.runner as? TachiyomiXSourceRunner
        let controller = UIHostingController(
            rootView: SourceWebBrowserView(
                title: title,
                url: url,
                runner: runner
            )
        )
        controller.modalPresentationStyle = .pageSheet
        return controller
    }

    static func present(
        from viewController: UIViewController,
        source: ExtensionRunner.Source?,
        url: URL,
        title: String
    ) {
        guard url.scheme == "http" || url.scheme == "https" else { return }
        viewController.present(
            makeViewController(source: source, url: url, title: title),
            animated: true
        )
    }
}
