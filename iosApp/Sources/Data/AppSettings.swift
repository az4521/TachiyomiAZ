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
        static let contentFilter = "extensions.contentFilter"
    }

    /// How much of a repository's content to show.
    ///
    /// Three levels rather than a single NSFW switch, because repositories mark extensions as
    /// SAFE, MIXED or NSFW and the middle case is the interesting one -- MangaDex and Weeb Central
    /// are both MIXED, so folding it into "adult" hides the two biggest sources in the repository.
    enum ContentFilter: String, CaseIterable, Identifiable {
        /// Hide only extensions marked 18+.
        case hideAdult
        /// Hide both 18+ and mixed-content extensions.
        case hideAdultAndMixed
        /// Show everything the repository offers.
        case showAll

        var id: String { rawValue }

        var title: String {
            switch self {
            case .hideAdult: return "Hide 18+"
            case .hideAdultAndMixed: return "Hide 18+ and mixed"
            case .showAll: return "Show all"
            }
        }
    }

    @Published var usesBottomTabs: Bool {
        didSet { UserDefaults.standard.set(usesBottomTabs, forKey: Key.bottomTabs) }
    }

    @Published var contentFilter: ContentFilter {
        didSet { UserDefaults.standard.set(contentFilter.rawValue, forKey: Key.contentFilter) }
    }

    init() {
        // Defaults to false: drawer navigation, matching Android.
        usesBottomTabs = UserDefaults.standard.bool(forKey: Key.bottomTabs)
        // Defaults to hiding only 18+, so mixed-content sources stay visible.
        contentFilter = UserDefaults.standard.string(forKey: Key.contentFilter)
            .flatMap(ContentFilter.init(rawValue:)) ?? .hideAdult
    }
}
