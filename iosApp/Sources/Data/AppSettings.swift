import SwiftUI
import TachiyomiKit

/// User-facing app preferences.
///
/// Navigation style is one of them because the two platforms disagree about it: a drawer is how
/// the Android app works and what someone coming from it expects, while a bottom bar is the iOS
/// convention. Rather than pick for the user, the drawer is the default and the bar is available.
@MainActor
final class AppSettings: ObservableObject {
    /// The keys the settings screens actually write.
    ///
    /// These were this app's own names (`library.skipCompleted` and so on), written by a settings
    /// UI that no longer exists. The vendored screens write the fork's keys, so the library-update
    /// rules were reading preferences nothing set: every refresh ran with the defaults no matter
    /// what the user chose. Reading the same keys the UI writes is the whole point.
    private enum Key {
        static let contentFilter = "extensions.contentFilter"
        static let excludedUpdateCategories = "Library.excludedUpdateCategories"
        static let skipTitles = "Library.skipTitles"
        static let updateOnlyOnWifi = "Library.updateOnlyOnWifi"
        static let refreshMetadata = "Library.refreshMetadata"
    }

    /// Values the "skip titles" multi-select stores, as declared in Settings.swift.
    private enum SkipTitle {
        static let hasUnread = "hasUnread"
        static let completed = "completed"
        static let notStarted = "notStarted"
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

    @Published var contentFilter: ContentFilter {
        didSet { UserDefaults.standard.set(contentFilter.rawValue, forKey: Key.contentFilter) }
    }

    /// Read fresh each time rather than cached at init: the vendored settings screens write these
    /// keys directly, so a value cached here would go stale the moment the user changed one.
    private static var defaults: UserDefaults { .standard }

    /// Categories a library update is limited to.
    ///
    /// The setting is *excluded* categories, so this inverts it into the "categories to update"
    /// list the shared `selectLibraryMangaToUpdate` takes. Empty means every category, which is
    /// also what the shared rule treats as no restriction.
    var updateCategories: [Int32] {
        let excluded = Set(Self.defaults.stringArray(forKey: Key.excludedUpdateCategories) ?? [])
        guard !excluded.isEmpty else { return [] }
        guard let handler = MangaManager.shared.library?.handler else { return [] }
        return handler.getCategories()
            .filter { !excluded.contains($0.name) }
            .compactMap { $0.id?.int32Value }
    }

    private var skipTitles: Set<String> {
        Set(Self.defaults.stringArray(forKey: Key.skipTitles) ?? [])
    }

    /// Handled by the shared `selectLibraryMangaToUpdate`.
    var skipCompleted: Bool { skipTitles.contains(SkipTitle.completed) }

    /// Applied on top of the shared selection, because they depend on read counts the shared rule
    /// does not take. Kept as plain filters so the shared rule stays the single source of truth for
    /// everything it does cover.
    var skipUnread: Bool { skipTitles.contains(SkipTitle.hasUnread) }

    var skipNotStarted: Bool { skipTitles.contains(SkipTitle.notStarted) }

    /// Not offered by the settings screens; the fork's "skip titles" has no fully-read option.
    var skipFullyRead: Bool { false }

    /// Whether a refresh should re-read details as well as chapters.
    var refreshMetadata: Bool { Self.defaults.bool(forKey: Key.refreshMetadata) }

    var updateOnlyOnWifi: Bool { Self.defaults.bool(forKey: Key.updateOnlyOnWifi) }

    init() {
        // Defaults to hiding only 18+, so mixed-content sources stay visible.
        contentFilter = UserDefaults.standard.string(forKey: Key.contentFilter)
            .flatMap(ContentFilter.init(rawValue:)) ?? .hideAdult
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
