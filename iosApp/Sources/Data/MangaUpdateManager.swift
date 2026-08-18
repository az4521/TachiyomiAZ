import ExtensionRunner
import Foundation

/// New-chapter badges on library entries.
///
/// Upstream keeps a `MangaUpdate` entity recording every newly fetched chapter and whether the user
/// has seen it. The shared schema has no such table -- Android derives "new" from a chapter's
/// `date_fetch` against when the series was last opened -- so the viewed mark is a timestamp here
/// rather than a row per chapter. Nothing else needs to agree on it, so it stays local.
final class MangaUpdateManager {
    static let shared = MangaUpdateManager()

    private init() {}

    private static func key(_ sourceId: String, _ mangaId: String) -> String {
        "Library.updatesViewed.\(sourceId).\(mangaId)"
    }

    func viewAllUpdates(of manga: ExtensionRunner.Manga) async {
        UserDefaults.standard.set(
            Date().timeIntervalSince1970,
            forKey: Self.key(manga.sourceKey, manga.key)
        )
        NotificationCenter.default.post(name: NSNotification.Name("mangaUpdatesViewed"), object: nil)
    }

    /// When the user last cleared this series' new-chapter badge.
    func lastViewed(sourceId: String, mangaId: String) -> Date? {
        let stamp = UserDefaults.standard.double(forKey: Self.key(sourceId, mangaId))
        return stamp > 0 ? Date(timeIntervalSince1970: stamp) : nil
    }
}
