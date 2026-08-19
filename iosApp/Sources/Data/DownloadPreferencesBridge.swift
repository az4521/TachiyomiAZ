import Foundation
import TachiyomiKit

/// Supplies the auto-download settings to the shared rule that reads them.
///
/// `shouldDownloadNewChapters` in `:core-domain` decides whether a chapter a refresh just found
/// should download: it checks the title is in the library, that auto-download is on, and that the
/// title is in one of the chosen categories -- treating an empty choice as every category, and a
/// title in no category as category 0. The Android app has answered that question through this
/// interface since before the port; this app was not asking it at all, and had no such feature.
///
/// Preference storage is platform work, so the shared side sees only resolved values.
final class DownloadPreferencesBridge: NSObject, DownloadPreferences {
    static let shared = DownloadPreferencesBridge()

    private enum Key {
        static let enabled = "Downloads.downloadNewChapters"
        static let categories = "Downloads.downloadNewCategories"
    }

    /// Read on access rather than cached: the settings screens write these keys directly.
    var downloadNewChapters: Bool {
        UserDefaults.standard.bool(forKey: Key.enabled)
    }

    /// The setting stores category names, because that is what the picker offers and what a name
    /// survives being renamed on the other device as; the rule wants ids.
    var downloadNewCategories: [KotlinInt] {
        let chosen = Set(UserDefaults.standard.stringArray(forKey: Key.categories) ?? [])
        guard !chosen.isEmpty else { return [] }
        return Database.handler.getCategories()
            .filter { chosen.contains($0.name) }
            .compactMap { $0.id?.int32Value }
            .map { KotlinInt(int: $0) }
    }
}
