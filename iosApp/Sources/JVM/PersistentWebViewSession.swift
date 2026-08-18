import Foundation
import WebKit

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
