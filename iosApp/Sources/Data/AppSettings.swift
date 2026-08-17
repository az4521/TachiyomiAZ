import SwiftUI

/// User-facing app preferences.
///
/// Navigation style is one of them because the two platforms disagree about it: a drawer is how
/// the Android app works and what someone coming from it expects, while a bottom bar is the iOS
/// convention. Rather than pick for the user, the drawer is the default and the bar is available.
@MainActor
final class AppSettings: ObservableObject {
    private enum Key {
        static let bottomTabs = "navigation.usesBottomTabs"
    }

    @Published var usesBottomTabs: Bool {
        didSet { UserDefaults.standard.set(usesBottomTabs, forKey: Key.bottomTabs) }
    }

    init() {
        // Defaults to false: drawer navigation, matching Android.
        usesBottomTabs = UserDefaults.standard.bool(forKey: Key.bottomTabs)
    }
}
