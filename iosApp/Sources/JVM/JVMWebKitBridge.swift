import Darwin
import Foundation
import UIKit
import WebKit

@_silgen_name("tachiyomiaz_jvm_webkit_event")
private func tachiyomiazJVMWebKitEvent(
    _ handle: Int64,
    _ event: UnsafePointer<CChar>,
    _ argument1: UnsafePointer<CChar>,
    _ argument2: UnsafePointer<CChar>
) -> UnsafeMutablePointer<CChar>?

/// Synchronous JNI command entry point. Commands that start navigation only
/// enqueue work in WKWebView; navigation and resource callbacks travel back to
/// Java through `tachiyomiaz_jvm_webkit_event`.
@_cdecl("tachiyomiaz_webkit_command")
func tachiyomiazWebKitCommand(
    _ operationPointer: UnsafePointer<CChar>?,
    _ handle: Int64,
    _ argument1Pointer: UnsafePointer<CChar>?,
    _ argument2Pointer: UnsafePointer<CChar>?
) -> UnsafeMutablePointer<CChar>? {
    guard !Thread.isMainThread else {
        return copiedCString("__UNAVAILABLE__Android WebView command arrived on the iOS main thread")
    }
    let operation = operationPointer.map { String(cString: $0) } ?? ""
    let argument1 = argument1Pointer.map { String(cString: $0) }
    let argument2 = argument2Pointer.map { String(cString: $0) }
    let result = JVMWebKitCommandResult()
    let semaphore = DispatchSemaphore(value: 0)
    Task { @MainActor in
        result.value = await JVMWebKitBridge.shared.command(
            operation: operation,
            handle: handle,
            argument1: argument1,
            argument2: argument2
        )
        semaphore.signal()
    }
    guard semaphore.wait(timeout: .now() + 65) == .success else {
        return copiedCString("__ERROR__WKWebView command timed out")
    }
    return copiedCString(result.value)
}

private func copiedCString(_ value: String) -> UnsafeMutablePointer<CChar>? {
    value.withCString { strdup($0) }
}

private func sendJVMWebKitEvent(
    handle: Int64,
    event: String,
    argument1: String = "",
    argument2: String = ""
) -> String {
    event.withCString { eventPointer in
        argument1.withCString { argument1Pointer in
            argument2.withCString { argument2Pointer in
                guard let result = tachiyomiazJVMWebKitEvent(
                    handle,
                    eventPointer,
                    argument1Pointer,
                    argument2Pointer
                ) else { return "" }
                defer { free(result) }
                return String(cString: result)
            }
        }
    }
}

private final class JVMWebKitCommandResult: @unchecked Sendable {
    var value = ""
}

@MainActor
final class JVMWebKitBridge {
    static let shared = JVMWebKitBridge()

    private var nextHandle: Int64 = 1
    private var contexts: [Int64: Context] = [:]

    func command(
        operation: String,
        handle: Int64,
        argument1: String?,
        argument2: String?
    ) async -> String {
        switch operation {
            case "create":
                let configuration: WKWebViewConfiguration
                if argument1 == "true" {
                    configuration = WKWebViewConfiguration()
                    configuration.websiteDataStore = .nonPersistent()
                } else {
                    // Android WebViews in one app share their browser profile.
                    // Use the same persistent WebKit process pool as the
                    // visible source WebView so DOM storage (including auth
                    // tokens) is immediately available to extension-created
                    // headless WebViews.
                    configuration = PersistentWebViewSession.configuration()
                }
                let newHandle = nextHandle
                nextHandle += 1
                contexts[newHandle] = Context(handle: newHandle, configuration: configuration)
                return String(newHandle)
            case "cookieSet": return await setCookie(urlString: argument1, header: argument2)
            case "cookieGet": return await cookies(for: argument1)
            case "cookieRemoveSession": return await removeCookies(sessionOnly: true)
            case "cookieRemoveAll": return await removeCookies(sessionOnly: false)
            case "cookieHas": return String(!(await allCookies()).isEmpty)
            case "cookieFlush":
                await copyWebKitCookiesToHTTPStorage(
                    store: WKWebsiteDataStore.default().httpCookieStore
                )
                return "true"
            default: break
        }

        guard let context = contexts[handle] else {
            return "__ERROR__Unknown WKWebView handle"
        }
        switch operation {
            case "destroy":
                context.destroy()
                contexts[handle] = nil
                return "true"
            case "userAgent":
                context.webView.customUserAgent = argument1
                return "true"
            case "settings":
                await context.applySettings(argument1 ?? "")
                return "true"
            case "addJSInterface":
                context.addJavaScriptInterface(
                    named: argument1 ?? "",
                    asynchronousMethods: argument2 ?? ""
                )
                return "true"
            case "removeJSInterface":
                context.removeJavaScriptInterface(named: argument1 ?? "")
                return "true"
            case "load":
                guard let value = argument1, let url = URL(string: value) else {
                    return "__ERROR__Invalid WebView URL"
                }
                context.prepareLocalStorage(for: url)
                await copyHTTPCookiesToWebKit(store: context.cookieStore, for: url)
                var request = URLRequest(url: url)
                for (key, value) in decodeHeaders(argument2) {
                    request.setValue(value, forHTTPHeaderField: key)
                }
                context.load(request)
                return "true"
            case "post":
                guard let value = argument1, let url = URL(string: value) else {
                    return "__ERROR__Invalid WebView URL"
                }
                context.prepareLocalStorage(for: url)
                await copyHTTPCookiesToWebKit(store: context.cookieStore, for: url)
                var request = URLRequest(url: url)
                request.httpMethod = "POST"
                request.httpBody = argument2.flatMap { Data(base64Encoded: $0) }
                context.load(request)
                return "true"
            case "loadHTML":
                guard
                    let encoded = argument2,
                    let data = Data(base64Encoded: encoded),
                    let html = String(data: data, encoding: .utf8)
                else { return "__ERROR__Invalid WebView HTML" }
                let baseURL = argument1.flatMap(URL.init(string:))
                if let baseURL {
                    await copyHTTPCookiesToWebKit(store: context.cookieStore, for: baseURL)
                }
                context.load(html: html, baseURL: baseURL)
                return "true"
            case "evaluate":
                do {
                    return Self.jsonString(
                        try await context.webView.evaluateJavaScript(argument1 ?? "")
                    )
                } catch {
                    context.reportJavaScriptEvaluationError(error)
                    return "null"
                }
            case "stop": context.webView.stopLoading(); return "true"
            case "reload": context.webView.reload(); return "true"
            case "goBack": context.webView.goBack(); return "true"
            case "goForward": context.webView.goForward(); return "true"
            case "go":
                let offset = Int(argument1 ?? "") ?? 0
                let list = context.webView.backForwardList
                let item = offset < 0
                    ? list.backList[safe: max(0, list.backList.count + offset)]
                    : list.forwardList[safe: max(0, offset - 1)]
                if let item { context.webView.go(to: item) }
                return String(item != nil)
            case "canGoBack": return String(context.webView.canGoBack)
            case "canGoForward": return String(context.webView.canGoForward)
            case "canGo":
                let offset = Int(argument1 ?? "") ?? 0
                return String(offset < 0
                    ? context.webView.backForwardList.backList.count >= -offset
                    : context.webView.backForwardList.forwardList.count >= offset)
            case "url": return context.webView.url?.absoluteString ?? ""
            case "originalUrl": return context.originalURL?.absoluteString ?? ""
            case "title": return context.webView.title ?? ""
            case "progress": return String(Int((context.webView.estimatedProgress * 100).rounded()))
            case "contentHeight": return await dimension(context, "document.documentElement.scrollHeight")
            case "contentWidth": return await dimension(context, "document.documentElement.scrollWidth")
            case "clearHistory": return "true"
            case "clearCache":
                await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                    context.webView.configuration.websiteDataStore.removeData(
                        ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(),
                        modifiedSince: .distantPast
                    ) { continuation.resume() }
                }
                return "true"
            default: return "__ERROR__Unsupported WKWebView command: \(operation)"
        }
    }

    private func dimension(_ context: Context, _ expression: String) async -> String {
        let value = try? await context.webView.evaluateJavaScript(expression)
        return String((value as? NSNumber)?.intValue ?? 0)
    }

    private func setCookie(urlString: String?, header: String?) async -> String {
        guard let urlString, let url = URL(string: urlString), let header else { return "false" }
        let cookies = HTTPCookie.cookies(
            withResponseHeaderFields: ["Set-Cookie": header],
            for: url
        )
        guard !cookies.isEmpty else { return "false" }
        for cookie in cookies {
            HTTPCookieStorage.shared.setCookie(cookie)
            await set(cookie, in: WKWebsiteDataStore.default().httpCookieStore)
        }
        return "true"
    }

    private func cookies(for urlString: String?) async -> String {
        guard let urlString, let url = URL(string: urlString) else { return "" }
        return (await allCookies())
            .filter { Self.cookie($0, matches: url) }
            .map { "\($0.name)=\($0.value)" }
            .joined(separator: "; ")
    }

    private func allCookies() async -> [HTTPCookie] {
        await WKWebsiteDataStore.default().httpCookieStore.allCookies()
    }

    private func removeCookies(sessionOnly: Bool) async -> String {
        let store = WKWebsiteDataStore.default().httpCookieStore
        let cookies = await store.allCookies()
        let removing = sessionOnly ? cookies.filter { $0.expiresDate == nil } : cookies
        for cookie in removing {
            await delete(cookie, from: store)
            HTTPCookieStorage.shared.deleteCookie(cookie)
        }
        return String(!removing.isEmpty)
    }

    private func copyHTTPCookiesToWebKit(store: WKHTTPCookieStore, for url: URL) async {
        for cookie in HTTPCookieStorage.shared.cookies(for: url) ?? [] {
            await set(cookie, in: store)
        }
    }

    fileprivate func copyWebKitCookiesToHTTPStorage(store: WKHTTPCookieStore) async {
        for cookie in await store.allCookies() {
            HTTPCookieStorage.shared.setCookie(cookie)
        }
    }

    private func set(_ cookie: HTTPCookie, in store: WKHTTPCookieStore) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            store.setCookie(cookie) { continuation.resume() }
        }
    }

    private func delete(_ cookie: HTTPCookie, from store: WKHTTPCookieStore) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            store.delete(cookie) { continuation.resume() }
        }
    }

    private static func cookie(_ cookie: HTTPCookie, matches url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        let domain = cookie.domain.trimmingCharacters(in: CharacterSet(charactersIn: ".")).lowercased()
        guard host == domain || host.hasSuffix("." + domain) else { return false }
        guard url.path.hasPrefix(cookie.path) else { return false }
        guard !cookie.isSecure || url.scheme?.lowercased() == "https" else { return false }
        return cookie.expiresDate.map { $0 > Date() } ?? true
    }

    private static func jsonString(_ value: Any?) -> String {
        guard let value, JSONSerialization.isValidJSONObject([value]) else { return "null" }
        guard
            let data = try? JSONSerialization.data(withJSONObject: value, options: .fragmentsAllowed),
            let string = String(data: data, encoding: .utf8)
        else { return "null" }
        return string
    }

    fileprivate static func encode(_ value: String) -> String {
        Data(value.utf8).base64EncodedString()
    }

    fileprivate static func decode(_ value: String) -> String {
        Data(base64Encoded: value).flatMap { String(data: $0, encoding: .utf8) } ?? ""
    }

    fileprivate static func encodeHeaders(_ headers: [String: String]) -> String {
        headers.map { "\(encode($0.key)):\(encode($0.value))" }.joined(separator: "\n")
    }

    private func decodeHeaders(_ value: String?) -> [(String, String)] {
        guard let value, !value.isEmpty else { return [] }
        return value.split(separator: "\n").compactMap { line in
            let pieces = line.split(separator: ":", maxSplits: 1)
            guard pieces.count == 2 else { return nil }
            return (Self.decode(String(pieces[0])), Self.decode(String(pieces[1])))
        }
    }

    final class Context: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler,
        WKScriptMessageHandlerWithReply {
        private static let consoleHandler = "tachiyomiaz_console"
        private static let networkHandler = "tachiyomiaz_network"
        private static let sessionReadyHandler = "tachiyomiaz_session_ready"

        let handle: Int64
        let webView: WKWebView
        var originalURL: URL?
        private var javaScriptHandlers: [String: String] = [:]
        private var asynchronousJavaScriptMethods: [String: Set<String>] = [:]
        private var bypassInterception = false
        private var blockImages = false
        private var wideViewport = false
        private var overviewMode = false
        private var reportedJavaScriptErrors: Set<String> = []
        private var localStorageSnapshot: [String: String] = [:]
        private var localStorageOrigin: String?
        private var pageFinishedEmitted = false

        var cookieStore: WKHTTPCookieStore {
            webView.configuration.websiteDataStore.httpCookieStore
        }

        init(handle: Int64, configuration: WKWebViewConfiguration) {
            self.handle = handle
            // Android's helper measures its headless WebView to the display.
            // A zero-sized WKWebView changes responsive/lazy-loading behavior.
            webView = WKWebView(
                frame: UIScreen.main.bounds,
                configuration: configuration
            )
            super.init()
            webView.navigationDelegate = self
            webView.uiDelegate = self
            let controller = webView.configuration.userContentController
            controller.add(self, name: Self.consoleHandler)
            controller.add(self, name: Self.sessionReadyHandler)
            controller.addScriptMessageHandler(
                self,
                contentWorld: .page,
                name: Self.networkHandler
            )
            rebuildScripts()
        }

        func destroy() {
            webView.stopLoading()
            webView.removeFromSuperview()
            webView.navigationDelegate = nil
            webView.uiDelegate = nil
            let controller = webView.configuration.userContentController
            controller.removeScriptMessageHandler(forName: Self.consoleHandler)
            controller.removeScriptMessageHandler(forName: Self.sessionReadyHandler)
            controller.removeScriptMessageHandler(forName: Self.networkHandler, contentWorld: .page)
            for handler in javaScriptHandlers.values {
                controller.removeScriptMessageHandler(forName: handler)
            }
            javaScriptHandlers.removeAll()
            asynchronousJavaScriptMethods.removeAll()
            controller.removeAllUserScripts()
        }

        func load(_ request: URLRequest) {
            originalURL = request.url
            pageFinishedEmitted = false
            attachToWindowIfNeeded()
            webView.load(request)
        }

        func load(html: String, baseURL: URL?) {
            originalURL = baseURL
            pageFinishedEmitted = false
            attachToWindowIfNeeded()
            if
                let baseURL,
                baseURL.scheme == "http" || baseURL.scheme == "https"
            {
                // Android's loadDataWithBaseURL treats the base URL as the
                // document URL/security origin. loadHTMLString only uses it to
                // resolve relative links, which breaks same-origin storage,
                // cookies, modules, and API calls in SPAs such as Comix.
                webView.loadSimulatedRequest(
                    URLRequest(url: baseURL),
                    responseHTML: html
                )
            } else {
                webView.loadHTMLString(html, baseURL: baseURL)
            }
        }

        /// WKWebView aggressively deprioritizes detached views. Android WebView
        /// continues running a measured, headless instance, so keep this view in
        /// an active window behind the app's content to preserve equivalent page
        /// lifecycle behavior without displaying or intercepting the page.
        private func attachToWindowIfNeeded() {
            guard webView.window == nil else { return }
            let windows = UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap { $0.windows }
            guard let window = windows.first(where: { $0.isKeyWindow })
                ?? windows.first(where: { !$0.isHidden })
            else { return }
            webView.frame = window.bounds
            webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            webView.isUserInteractionEnabled = false
            window.insertSubview(webView, at: 0)
        }

        func applySettings(_ payload: String) async {
            let values = payload.split(separator: "\n", omittingEmptySubsequences: false)
                .map { $0 == "true" }
            webView.configuration.defaultWebpagePreferences.allowsContentJavaScript =
                values[safe: 0] ?? true
            let newBlockImages = values[safe: 2] ?? false
            wideViewport = values[safe: 3] ?? false
            overviewMode = values[safe: 4] ?? false
            if blockImages != newBlockImages {
                blockImages = newBlockImages
                await updateImageBlocking()
            }
            rebuildScripts()
        }

        func addJavaScriptInterface(named name: String, asynchronousMethods: String) {
            guard !name.isEmpty, javaScriptHandlers[name] == nil else { return }
            let safeName = "tachiyomiaz_js_" + Data(name.utf8).base64EncodedString()
                .replacingOccurrences(of: "+", with: "_")
                .replacingOccurrences(of: "/", with: "-")
                .replacingOccurrences(of: "=", with: "")
            javaScriptHandlers[name] = safeName
            asynchronousJavaScriptMethods[name] = Set(
                asynchronousMethods.split(separator: "\n").map(String.init)
            )
            webView.configuration.userContentController.add(self, name: safeName)
            rebuildScripts()
        }

        func prepareLocalStorage(for url: URL) {
            localStorageSnapshot = PersistentWebViewSession.localStorage(for: url)
            localStorageOrigin = localStorageSnapshot.isEmpty
                ? nil
                : PersistentWebViewSession.origin(for: url)
            rebuildScripts()
        }

        func removeJavaScriptInterface(named name: String) {
            guard let handler = javaScriptHandlers.removeValue(forKey: name) else { return }
            asynchronousJavaScriptMethods[name] = nil
            webView.configuration.userContentController.removeScriptMessageHandler(forName: handler)
            rebuildScripts()
        }

        private func rebuildScripts() {
            let controller = webView.configuration.userContentController
            controller.removeAllUserScripts()
            controller.addUserScript(WKUserScript(
                source: PersistentWebViewSession.browserCompatibilityScript,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: false
            ))
            controller.addUserScript(WKUserScript(
                source: Self.consoleScript,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: false
            ))
            controller.addUserScript(WKUserScript(
                source: Self.networkScript,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: false
            ))
            if
                !localStorageSnapshot.isEmpty,
                let data = try? JSONSerialization.data(
                    withJSONObject: localStorageSnapshot,
                    options: [.sortedKeys]
                ),
                let snapshot = String(data: data, encoding: .utf8),
                let localStorageOrigin
            {
                // The visible source browser and Android-compatible WebViews
                // share WKWebsiteDataStore.default(), but a token written just
                // before dismissal may not yet be visible to a newly-spawned
                // WebContent process. Seed the freshly captured values before
                // any page script can validate or invalidate them.
                controller.addUserScript(WKUserScript(
                    source: """
                    (() => {
                      try {
                        if (window.location.origin !== \(Self.jsonLiteral(localStorageOrigin))) return;
                        const snapshot = \(snapshot);
                        for (const [key, value] of Object.entries(snapshot)) {
                          window.localStorage.setItem(key, String(value));
                        }
                        if (Object.prototype.hasOwnProperty.call(snapshot, 'clearance')) {
                          window.webkit.messageHandlers.tachiyomiaz_session_ready.postMessage(
                            window.location.href
                          );
                        }
                      } catch (_) {}
                    })();
                    """,
                    injectionTime: .atDocumentStart,
                    forMainFrameOnly: true
                ))
            }
            if wideViewport || overviewMode {
                controller.addUserScript(WKUserScript(
                    source: Self.viewportScript,
                    injectionTime: .atDocumentEnd,
                    forMainFrameOnly: true
                ))
            }
            for (name, handler) in javaScriptHandlers {
                let nameJSON = Self.jsonLiteral(name)
                let handlerJSON = Self.jsonLiteral(handler)
                let asynchronousMethodsJSON = "[" +
                    (asynchronousJavaScriptMethods[name] ?? [])
                        .sorted()
                        .map(Self.jsonLiteral)
                        .joined(separator: ",") +
                    "]"
                let source = """
                (() => {
                  const name = \(nameJSON);
                  const handler = \(handlerJSON);
                  const asynchronousMethods = new Set(\(asynchronousMethodsJSON));
                  const encode = value => btoa(unescape(encodeURIComponent(String(value))));
                  const invoke = (method, args) => {
                    const payload = String(method) + '\\n' + args.length + '\\n' +
                      args.map(encode).join('\\t');
                    if (asynchronousMethods.has(String(method) + '\\t' + args.length)) {
                      try {
                        window.webkit.messageHandlers[handler].postMessage(payload);
                      } catch (_) {}
                      return null;
                    }
                    const result = window.prompt('__tachiyomiaz_jsbridge__' + handler, payload);
                    try { return result == null ? null : JSON.parse(result); } catch (_) { return null; }
                  };
                  window[name] = new Proxy(window[name] || {}, {
                    get(target, property) {
                      if (property in target) return target[property];
                      return (...args) => invoke(property, args);
                    },
                  });
                })();
                """
                controller.addUserScript(WKUserScript(
                    source: source,
                    injectionTime: .atDocumentStart,
                    forMainFrameOnly: false
                ))
            }
        }

        private func updateImageBlocking() async {
            let controller = webView.configuration.userContentController
            controller.removeAllContentRuleLists()
            guard blockImages else { return }
            let rules = "[{\"trigger\":{\"url-filter\":\".*\",\"resource-type\":[\"image\"]},\"action\":{\"type\":\"block\"}}]"
            if let list = try? await WKContentRuleListStore.default().compileContentRuleList(
                forIdentifier: "tachiyomiaz-block-images",
                encodedContentRuleList: rules
            ) {
                controller.add(list)
            }
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction
        ) async -> WKNavigationActionPolicy {
            if bypassInterception {
                bypassInterception = false
                return .allow
            }
            let payload = requestPayload(
                navigationAction.request,
                mainFrame: navigationAction.targetFrame?.isMainFrame ?? false,
                redirect: false,
                gesture: navigationAction.navigationType == .linkActivated
            )
            let shouldOverride = await detachedEvent("shouldOverride", payload) == "true"
            if shouldOverride { return .cancel }
            let responsePayload = await detachedEvent("intercept", payload)
            guard
                !responsePayload.isEmpty,
                !responsePayload.hasPrefix("__ERROR__"),
                let response = Self.decodeResponse(responsePayload)
            else { return .allow }

            bypassInterception = true
            webView.load(
                response.data,
                mimeType: response.mimeType,
                characterEncodingName: response.encoding,
                baseURL: navigationAction.request.url ?? URL(string: "about:blank")!
            )
            return .cancel
        }

        func webView(
            _ webView: WKWebView,
            runJavaScriptTextInputPanelWithPrompt prompt: String,
            defaultText: String?,
            initiatedByFrame frame: WKFrameInfo,
            completionHandler: @escaping (String?) -> Void
        ) {
            let prefix = "__tachiyomiaz_jsbridge__"
            guard
                prompt.hasPrefix(prefix),
                let name = javaScriptHandlers.first(where: {
                    $0.value == String(prompt.dropFirst(prefix.count))
                })?.key
            else {
                completionHandler(nil)
                return
            }
            completionHandler(sendJVMWebKitEvent(
                handle: handle,
                event: "jsBridgeSync",
                argument1: name,
                argument2: defaultText ?? ""
            ))
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            emit("progress", "0")
        }

        func webView(
            _ webView: WKWebView,
            didReceiveServerRedirectForProvisionalNavigation navigation: WKNavigation!
        ) {
            emit("progress", "0")
        }

        func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
            emit("pageStarted", webView.url?.absoluteString ?? originalURL?.absoluteString ?? "")
            emit("progress", "60")
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            Task { @MainActor in
                await JVMWebKitBridge.shared.copyWebKitCookiesToHTTPStorage(store: cookieStore)
            }
            let url = webView.url?.absoluteString ?? originalURL?.absoluteString ?? ""
            emit("title", webView.title ?? "")
            emit("progress", "100")
            if !pageFinishedEmitted {
                pageFinishedEmitted = true
                emit("pageFinished", url)
            }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            emitError(error, url: webView.url ?? originalURL)
        }

        func webView(
            _ webView: WKWebView,
            didFailProvisionalNavigation navigation: WKNavigation!,
            withError error: Error
        ) {
            emitError(error, url: webView.url ?? originalURL)
        }

        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            emit("renderGone", "true")
        }

        func userContentController(
            _ userContentController: WKUserContentController,
            didReceive message: WKScriptMessage
        ) {
            if message.name == Self.consoleHandler {
                guard let value = message.body as? [String: Any] else { return }
                let level = String(describing: value["level"] ?? "LOG").uppercased()
                let text = String(describing: value["message"] ?? "")
                let source = String(describing: value["source"] ?? "")
                let line = String(describing: value["line"] ?? 0)
                emit(
                    "console",
                    [level, JVMWebKitBridge.encode(text), JVMWebKitBridge.encode(source), line]
                        .joined(separator: "\n")
                )
                return
            }
            if
                message.name == Self.sessionReadyHandler,
                message.frameInfo.isMainFrame,
                !pageFinishedEmitted,
                blockImages,
                localStorageSnapshot["clearance"] != nil
            {
                // A source such as SchaleNetwork only opens this headless view
                // to read a token that was just captured by the visible shared
                // browser. Do not make its hard-coded 10-second latch wait for
                // unrelated page resources or a third-party challenge script.
                pageFinishedEmitted = true
                emit("progress", "100")
                emit(
                    "pageFinished",
                    webView.url?.absoluteString ?? originalURL?.absoluteString ?? ""
                )
                return
            }
            guard let name = javaScriptHandlers.first(where: { $0.value == message.name })?.key else {
                return
            }
            emit("jsBridge", name, String(describing: message.body))
        }

        func userContentController(
            _ userContentController: WKUserContentController,
            didReceive message: WKScriptMessage,
            replyHandler: @escaping (Any?, String?) -> Void
        ) {
            guard
                message.name == Self.networkHandler,
                let value = message.body as? [String: Any],
                let url = value["url"] as? String
            else {
                replyHandler(nil, nil)
                return
            }
            let method = value["method"] as? String ?? "GET"
            let headers = (value["headers"] as? [String: Any] ?? [:])
                .mapValues { String(describing: $0) }
            let payload = requestPayload(
                URLRequest(url: URL(string: url) ?? URL(string: "about:blank")!),
                method: method,
                headers: headers,
                mainFrame: false,
                redirect: false,
                gesture: false
            )
            let handle = self.handle
            Task { @MainActor in
                let result = await Task.detached {
                    sendJVMWebKitEvent(
                        handle: handle,
                        event: "intercept",
                        argument1: payload
                    )
                }.value
                guard let response = Self.decodeResponse(result) else {
                    replyHandler(nil, nil)
                    return
                }
                replyHandler([
                    "status": response.status,
                    "reason": response.reason,
                    "mime": response.mimeType,
                    "encoding": response.encoding,
                    "headers": response.headers,
                    "body": response.data.base64EncodedString()
                ], nil)
            }
        }

        private func detachedEvent(_ event: String, _ argument1: String) async -> String {
            let handle = self.handle
            return await Task.detached {
                sendJVMWebKitEvent(handle: handle, event: event, argument1: argument1)
            }.value
        }

        private func emit(_ event: String, _ argument1: String = "", _ argument2: String = "") {
            _ = sendJVMWebKitEvent(
                handle: handle,
                event: event,
                argument1: argument1,
                argument2: argument2
            )
        }

        private func emitError(_ error: Error, url: URL?) {
            var request = URLRequest(url: url ?? URL(string: "about:blank")!)
            request.httpMethod = "GET"
            let payload = requestPayload(
                request,
                mainFrame: true,
                redirect: false,
                gesture: false
            )
            let nsError = error as NSError
            if nsError.domain != NSURLErrorDomain || nsError.code != NSURLErrorCancelled {
                LogManager.logger.error(
                    "Compatibility WebView navigation failed " +
                        "url=\(url?.absoluteString ?? "unknown") " +
                        "error=\(nsError.domain):\(nsError.code) " +
                        error.localizedDescription
                )
            }
            let detail = "\(nsError.code)\n\(JVMWebKitBridge.encode(error.localizedDescription))"
            emit("error", payload, detail)
        }

        func reportJavaScriptEvaluationError(_ error: Error) {
            let nsError = error as NSError
            let detail = "\(nsError.domain):\(nsError.code):\(error.localizedDescription)"
            guard reportedJavaScriptErrors.insert(detail).inserted else { return }
            LogManager.logger.error(
                "Compatibility WebView JavaScript evaluation failed " +
                    "url=\(webView.url?.absoluteString ?? originalURL?.absoluteString ?? "unknown") " +
                    "error=\(detail)"
            )
        }

        private func requestPayload(
            _ request: URLRequest,
            method: String? = nil,
            headers: [String: String]? = nil,
            mainFrame: Bool,
            redirect: Bool,
            gesture: Bool
        ) -> String {
            let headerValues = headers ?? request.allHTTPHeaderFields ?? [:]
            return [
                JVMWebKitBridge.encode(request.url?.absoluteString ?? "about:blank"),
                JVMWebKitBridge.encode(method ?? request.httpMethod ?? "GET"),
                String(mainFrame),
                String(redirect),
                String(gesture),
                JVMWebKitBridge.encode(JVMWebKitBridge.encodeHeaders(headerValues))
            ].joined(separator: "\n")
        }

        private struct InterceptedResponse {
            let status: Int
            let reason: String
            let mimeType: String
            let encoding: String
            let headers: [String: String]
            let data: Data
        }

        private static func decodeResponse(_ payload: String) -> InterceptedResponse? {
            guard !payload.isEmpty, !payload.hasPrefix("__ERROR__") else { return nil }
            let fields = payload.split(separator: "\n", omittingEmptySubsequences: false)
                .map(String.init)
            guard fields.count >= 6 else { return nil }
            let headerPayload = JVMWebKitBridge.decode(fields[4])
            var headers: [String: String] = [:]
            for line in headerPayload.split(separator: "\n") {
                let parts = line.split(separator: ":", maxSplits: 1)
                if parts.count == 2 {
                    headers[JVMWebKitBridge.decode(String(parts[0]))] =
                        JVMWebKitBridge.decode(String(parts[1]))
                }
            }
            return InterceptedResponse(
                status: Int(fields[0]) ?? 200,
                reason: JVMWebKitBridge.decode(fields[1]),
                mimeType: JVMWebKitBridge.decode(fields[2]).isEmpty
                    ? "application/octet-stream"
                    : JVMWebKitBridge.decode(fields[2]),
                encoding: JVMWebKitBridge.decode(fields[3]).isEmpty
                    ? "UTF-8"
                    : JVMWebKitBridge.decode(fields[3]),
                headers: headers,
                data: Data(base64Encoded: fields[5]) ?? Data()
            )
        }

        private static func jsonLiteral(_ value: String) -> String {
            guard
                let data = try? JSONSerialization.data(withJSONObject: value, options: .fragmentsAllowed),
                let result = String(data: data, encoding: .utf8)
            else { return "\"\"" }
            return result
        }

        private static let consoleScript = """
        (() => {
          if (window.__tachiyomiazConsoleInstalled) return;
          window.__tachiyomiazConsoleInstalled = true;
          for (const level of ['log', 'info', 'warn', 'error', 'debug']) {
            const original = console[level];
            console[level] = function(...values) {
              try {
                window.webkit.messageHandlers.tachiyomiaz_console.postMessage({
                  level: level === 'warn' ? 'WARNING' : level.toUpperCase(),
                  message: values.map(value => typeof value === 'string' ? value : JSON.stringify(value)).join(' '),
                  source: document.currentScript?.src || location.href,
                  line: 0
                });
              } catch (_) {}
              return original.apply(console, values);
            };
          }
        })();
        """

        private static let networkScript = """
        (() => {
          if (window.__tachiyomiazFetchInstalled || !window.fetch) return;
          window.__tachiyomiazFetchInstalled = true;
          const originalFetch = window.fetch.bind(window);
          window.fetch = async function(input, init) {
            try {
              const request = new Request(input, init);
              const headers = {};
              request.headers.forEach((value, key) => headers[key] = value);
              const intercepted = await window.webkit.messageHandlers.tachiyomiaz_network.postMessage({
                url: request.url,
                method: request.method,
                headers: headers
              });
              if (intercepted && intercepted.body !== undefined) {
                const binary = atob(intercepted.body);
                const bytes = new Uint8Array(binary.length);
                for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
                return new Response(bytes, {
                  status: intercepted.status || 200,
                  statusText: intercepted.reason || 'OK',
                  headers: intercepted.headers || {}
                });
              }
            } catch (_) {}
            return originalFetch(input, init);
          };
        })();
        """

        private static let viewportScript = """
        (() => {
          if (document.querySelector('meta[name="viewport"]')) return;
          const viewport = document.createElement('meta');
          viewport.name = 'viewport';
          viewport.content = 'width=device-width, initial-scale=1.0';
          document.head?.appendChild(viewport);
        })();
        """
    }
}
