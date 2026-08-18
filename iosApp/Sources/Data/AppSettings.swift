import SwiftUI
import TachiyomiKit

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
        static let updateCategories = "library.updateCategories"
        static let skipCompleted = "library.skipCompleted"
        static let skipFullyRead = "library.skipFullyRead"
        static let skipUnread = "library.skipUnread"
        static let skipNotStarted = "library.skipNotStarted"
        static let libraryColumns = "library.columns"
        static let colorScheme = "appearance.colorScheme"
        static let accent = "appearance.accent"
    }

    /// How much of a repository's content to show.
    ///
    /// Three levels rather than a single NSFW switch, because repositories mark extensions as
    /// SAFE, MIXED or NSFW and the middle case is the interesting one -- MangaDex and Weeb Central
    /// are both MIXED, so folding it into "adult" hides the two biggest sources in the repository.
    enum ContentFilter: String, CaseIterable, Identifiable {
        case hideAdult
        case hideAdultAndMixed
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

    /// Categories a library update is limited to. Empty means every category.
    @Published var updateCategories: [Int32] {
        didSet {
            UserDefaults.standard.set(updateCategories.map(Int.init), forKey: Key.updateCategories)
        }
    }

    /// Handled by the shared `selectLibraryMangaToUpdate`.
    @Published var skipCompleted: Bool {
        didSet { UserDefaults.standard.set(skipCompleted, forKey: Key.skipCompleted) }
    }

    /// The three below are applied on top of the shared selection, because they depend on read
    /// counts the shared rule does not take. Kept as plain filters so the shared rule stays the
    /// single source of truth for everything it does cover.
    @Published var skipFullyRead: Bool {
        didSet { UserDefaults.standard.set(skipFullyRead, forKey: Key.skipFullyRead) }
    }

    @Published var skipUnread: Bool {
        didSet { UserDefaults.standard.set(skipUnread, forKey: Key.skipUnread) }
    }

    @Published var skipNotStarted: Bool {
        didSet { UserDefaults.standard.set(skipNotStarted, forKey: Key.skipNotStarted) }
    }

    @Published var libraryColumns: Int {
        didSet { UserDefaults.standard.set(libraryColumns, forKey: Key.libraryColumns) }
    }

    @Published var colorScheme: AppColorScheme {
        didSet { UserDefaults.standard.set(colorScheme.rawValue, forKey: Key.colorScheme) }
    }

    @Published var accent: AppAccent {
        didSet { UserDefaults.standard.set(accent.rawValue, forKey: Key.accent) }
    }

    init() {
        let defaults = UserDefaults.standard
        // Defaults to false: drawer navigation, matching Android.
        usesBottomTabs = defaults.bool(forKey: Key.bottomTabs)
        // Defaults to hiding only 18+, so mixed-content sources stay visible.
        contentFilter = defaults.string(forKey: Key.contentFilter)
            .flatMap(ContentFilter.init(rawValue:)) ?? .hideAdult
        updateCategories = (defaults.array(forKey: Key.updateCategories) as? [Int] ?? []).map(Int32.init)
        skipCompleted = defaults.bool(forKey: Key.skipCompleted)
        skipFullyRead = defaults.bool(forKey: Key.skipFullyRead)
        skipUnread = defaults.bool(forKey: Key.skipUnread)
        skipNotStarted = defaults.bool(forKey: Key.skipNotStarted)
        let columns = defaults.integer(forKey: Key.libraryColumns)
        libraryColumns = columns == 0 ? 3 : columns
        colorScheme = defaults.string(forKey: Key.colorScheme)
            .flatMap(AppColorScheme.init(rawValue:)) ?? .system
        accent = defaults.string(forKey: Key.accent)
            .flatMap(AppAccent.init(rawValue:)) ?? .blue
    }

    /// The read-state filters, applied after the shared selection rule.
    ///
    /// `hasStarted` is passed in rather than read off LibraryManga, which exposes only `category`
    /// and `unread` -- whether anything was actually read has to come from the chapters table.
    func shouldRefresh(unread: Int, hasStarted: Bool) -> Bool {
        if skipUnread && unread > 0 { return false }
        // "Fully read" means started and nothing left unread.
        if skipFullyRead && hasStarted && unread == 0 { return false }
        if skipNotStarted && !hasStarted { return false }
        return true
    }

    /// True when any of the read-state filters is on, so callers can skip the per-manga chapter
    /// lookup entirely in the common case where none is.
    var needsReadState: Bool { skipFullyRead || skipUnread || skipNotStarted }
}
