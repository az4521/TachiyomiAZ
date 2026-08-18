import UIKit

/// Finds the view controller a Cloudflare challenge should be presented over.
///
/// The vendored `CloudflareHandler` reaches for `AppDelegate.visibleViewController`, which is how
/// the UIKit-based fork tracked this. This app is SwiftUI and has no AppDelegate, so the top
/// controller is walked from the active window scene instead.
final class AppDelegate {
    static let shared = AppDelegate()

    @MainActor
    var visibleViewController: UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
            ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard var top = scene?.windows.first(where: \.isKeyWindow)?.rootViewController else {
            return nil
        }
        while let presented = top.presentedViewController {
            top = presented
        }
        return top
    }

    @MainActor
    func presentAlert(
        title: String,
        message: String? = nil,
        actions: [UIAlertAction] = [],
        textFieldHandlers: [((UITextField) -> Void)] = [],
        textFieldDisablesLastActionWhenEmpty: Bool = false,
        completion: (() -> Void)? = nil
    ) {
        let alertController = UIAlertController(title: title, message: message, preferredStyle: .alert)

        for handler in textFieldHandlers {
            alertController.addTextField { textField in
                handler(textField)

                if textFieldDisablesLastActionWhenEmpty && textFieldHandlers.count == 1 {
                    actions.last?.isEnabled = !(textField.text?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)

                    NotificationCenter.default.addObserver(forName: UITextField.textDidChangeNotification, object: textField, queue: .main) { _ in
                        Task { @MainActor in
                            let text = textField.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                            actions.last?.isEnabled = !text.isEmpty
                        }
                    }
                }
            }
        }

        // if no actions are provided, add a default 'OK' action
        if actions.isEmpty {
            let okAction = UIAlertAction(title: NSLocalizedString("OK"), style: .cancel)
            alertController.addAction(okAction)
        } else {
            for action in actions {
                alertController.addAction(action)
            }
        }

        visibleViewController?.present(alertController, animated: true, completion: completion)
    }

    // MARK: - Loading indicator

    /// A modal, non-interactive progress alert, as the vendored settings pages expect.
    ///
    /// Ported from tachiyomiazios rather than copied: upstream builds the alert in stored `lazy`
    /// properties on a full `UIApplicationDelegate`, and this shim is not one. The views are built
    /// on first use here instead, which keeps the same behaviour without the delegate lifecycle.
    enum LoadingStyle {
        case indefinite
        case progress
    }

    @MainActor
    private static var loadingViews: (alert: UIAlertController, indicator: UIActivityIndicatorView, progress: UIProgressView)?

    @MainActor
    private var loadingViews: (alert: UIAlertController, indicator: UIActivityIndicatorView, progress: UIProgressView) {
        if let existing = Self.loadingViews { return existing }

        let alert = UIAlertController(
            title: nil,
            message: NSLocalizedString("LOADING_ELLIPSIS"),
            preferredStyle: .alert
        )
        let indicator = UIActivityIndicatorView(frame: .zero)
        indicator.style = .medium
        let progress = UIProgressView(frame: .zero)
        progress.progress = 0
        progress.tintColor = alert.view.tintColor

        alert.view.addSubview(progress)
        alert.view.addSubview(indicator)
        progress.translatesAutoresizingMaskIntoConstraints = false
        indicator.translatesAutoresizingMaskIntoConstraints = false

        NSLayoutConstraint.activate([
            progress.centerXAnchor.constraint(equalTo: alert.view.centerXAnchor),
            progress.bottomAnchor.constraint(equalTo: alert.view.bottomAnchor, constant: -8),
            progress.widthAnchor.constraint(equalTo: alert.view.widthAnchor, constant: -32),

            indicator.centerYAnchor.constraint(equalTo: alert.view.centerYAnchor),
            indicator.leadingAnchor.constraint(equalTo: alert.view.leadingAnchor, constant: 10),
            indicator.widthAnchor.constraint(equalToConstant: 50),
            indicator.heightAnchor.constraint(equalToConstant: 50)
        ])

        let views = (alert, indicator, progress)
        Self.loadingViews = views
        return views
    }

    @MainActor
    var indicatorProgress: Float {
        get { loadingViews.progress.progress }
        set { loadingViews.progress.progress = newValue }
    }

    @MainActor
    func showLoadingIndicator(style: LoadingStyle = .indefinite, completion: (() -> Void)? = nil) {
        let views = loadingViews
        switch style {
        case .indefinite:
            views.indicator.startAnimating()
            views.indicator.isHidden = false
            views.progress.isHidden = true
        case .progress:
            views.progress.progress = 0
            views.indicator.isHidden = true
            views.progress.isHidden = false
        }
        visibleViewController?.present(views.alert, animated: true, completion: completion)
    }

    @MainActor
    func hideLoadingIndicator(completion: (() -> Void)? = nil) async {
        let views = loadingViews
        await withCheckedContinuation { continuation in
            views.alert.dismiss(animated: true) {
                views.indicator.stopAnimating()
                completion?()
                continuation.resume()
            }
        }
    }

    // MARK: - Deep links

    /// Whether a URL was consumed by opening it inside the app.
    ///
    /// Upstream matches the URL against every loaded source's declared `urls` and pushes that
    /// source's manga page. This port's sources are JVM extensions reached through `SourceRuntime`,
    /// which does not expose per-source URL patterns yet, so nothing is claimed and the caller falls
    /// back to opening the link in a Safari view -- which is what upstream does for an unmatched
    /// link too.
    func handleDeepLink(url: URL) async -> Bool {
        false
    }
}
