import ExtensionRunner
import Foundation

/// Aidoku's pre-runner source model.
///
/// Upstream keeps a `LegacySourceRunner` wrapping its old WASM `Source` class, and asks for it here
/// to special-case those. This port has only JVM extensions -- there is no legacy runner to unwrap
/// -- so nothing is a legacy source and the checks that gate on it correctly do nothing.
extension ExtensionRunner.Source {
    var legacySource: ExtensionRunner.Source? { nil }
}

extension ExtensionRunner.Source {
    /// Aidoku's WASM sources expose named actions a settings change can fire. JVM extensions have
    /// no equivalent hook, so the notification is delivered through NotificationCenter alone --
    /// which is what these settings cells already do alongside this call.
    func performAction(key: String) {}
}
