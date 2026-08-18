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
}
