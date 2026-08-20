import UIKit
import WebKit

/// Hosts the WKWebView a Cloudflare challenge is solved in.
///
/// Adapted from tachiyomiazios rather than copied: upstream subclasses `BaseViewController`, which
/// extends AsyncDisplayKit's `ASDKViewController`. That would drag a large UI framework in for a
/// 39-line controller, so this subclasses `UIViewController` directly. `configure()` was only ever
/// a hook called from `viewDidLoad`, so it is inlined.
class WebViewViewController: UIViewController, WKNavigationDelegate {
    let request: URLRequest
    var handler: PopupWebViewHandler?

    init(request: URLRequest, handler: PopupWebViewHandler? = nil) {
        self.request = request
        self.handler = handler
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        handler?.navigated(webView: webView, for: request)
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationResponse: WKNavigationResponse
    ) async -> WKNavigationResponsePolicy {
        await handler?.handle(response: navigationResponse)
        return .allow
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        handler?.canceled(request: request)
        handler = nil
    }
}
