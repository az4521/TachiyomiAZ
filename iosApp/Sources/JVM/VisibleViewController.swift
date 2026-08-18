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
}
