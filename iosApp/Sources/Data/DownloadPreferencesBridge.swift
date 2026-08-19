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
        /// The key this app already had for "delete a chapter's download once it is read". It is
        /// reused rather than replaced with a `Downloads.`-prefixed one: a second key meaning the
        /// same thing would leave the existing setting stranded, with the toggle the user can see
        /// no longer the one the rule reads.
        static let removeAfterMarkedAsRead = "Library.deleteDownloadAfterReading"
        static let removeAfterReadSlots = "Downloads.removeAfterReadSlots"
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

    var removeAfterMarkedAsRead: Bool {
        UserDefaults.standard.bool(forKey: Key.removeAfterMarkedAsRead)
    }

    /// Defaults to keeping everything, as it does on the other app -- reading a chapter should not
    /// start deleting pages until the user asks for it.
    ///
    /// Stored as a string because the settings list writes its selections that way, and with the
    /// same values the other app uses ("-1", "0", "1"...) so the two settings screens describe one
    /// rule rather than two.
    var removeAfterReadSlots: Int32 {
        guard
            let raw = UserDefaults.standard.string(forKey: Key.removeAfterReadSlots),
            let slots = Int32(raw)
        else {
            return DownloadCleanup.shared.KEEP_ALL
        }
        return slots
    }
}
