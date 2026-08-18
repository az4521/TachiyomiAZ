import ExtensionRunner
import Foundation
import TachiyomiKit

/// Caching a source's manga into the shared database, and the per-manga chapter filters.
///
/// Android keeps chapter filter state packed into `chapter_flags` on the manga row, which is what
/// `ChapterFilters.flags` carries. It has no per-manga language or scanlator filter columns -- a
/// Tachiyomi source is single-language -- so those come back nil rather than being invented.
extension CoreDataManager {
    struct ChapterFilters {
        let flags: Int
        let language: String?
        let scanlators: [String]?
    }

    func getMangaChapterFilters(sourceId: String, mangaId: String, context: Any? = nil) -> ChapterFilters {
        ChapterFilters(
            flags: Int(sharedManga(sourceId: sourceId, mangaId: mangaId)?.chapter_flags ?? 0),
            language: nil,
            scanlators: nil
        )
    }

    /// Records the titles a browse request returned, so they are known rows before the user opens
    /// one. Existing rows are left alone: a summary carries less than the details already stored.
    func cacheMangaSummaries(_ manga: [ExtensionRunner.Manga]) async {
        let handler = self.handler
        handler.inTransaction {
            for item in manga {
                guard
                    let source = SourceIdentity.numericId(item.sourceKey),
                    handler.getManga(url: item.key, sourceId: source) == nil
                else { continue }
                handler.insertManga(manga: item.toShared(source: source))
            }
        }
    }

    /// Writes the full details a source returned over the stored row, keeping the flags and
    /// favourite state the user owns.
    func cacheMangaDetails(_ manga: ExtensionRunner.Manga, includeChapters: Bool) async {
        guard let source = SourceIdentity.numericId(manga.sourceKey) else { return }
        let record = manga.toShared(source: source)
        if let existing = handler.getManga(url: manga.key, sourceId: source) {
            record.id = existing.id
            record.favorite = existing.favorite
            record.chapter_flags = existing.chapter_flags
            record.viewer = existing.viewer
            record.date_added = existing.date_added
        }
        record.initialized = true
        handler.insertManga(manga: record)
    }

    /// Upstream stamps a `lastOpened` column on its library entity. Android's schema has none, so
    /// this is kept in `UserDefaults` -- it drives "recently opened" ordering in the UI and is not
    /// data the two apps need to agree on.
    func setOpened(sourceId: String, mangaId: String) async {
        UserDefaults.standard.set(
            Date().timeIntervalSince1970,
            forKey: "Library.lastOpened.\(sourceId).\(mangaId)"
        )
    }

    func lastOpened(sourceId: String, mangaId: String) -> Date? {
        let stamp = UserDefaults.standard.double(forKey: "Library.lastOpened.\(sourceId).\(mangaId)")
        return stamp > 0 ? Date(timeIntervalSince1970: stamp) : nil
    }
}

/// Fields the user has overridden on a title, and the cover they chose.
///
/// Android's schema records neither: it has no "edited" bitmask and no user-cover column, because
/// the Android app does not offer per-title editing. Both live in `UserDefaults` here so the shared
/// rows stay exactly what both apps expect.
extension CoreDataManager {
    private static func editedKeysKey(_ sourceId: String, _ mangaId: String) -> String {
        "Manga.editedKeys.\(sourceId).\(mangaId)"
    }

    func hasEditedKey(sourceId: String, mangaId: String, key: EditedKeys, context: Any? = nil) -> Bool {
        let stored = UserDefaults.standard.integer(forKey: Self.editedKeysKey(sourceId, mangaId))
        return EditedKeys(rawValue: Int32(stored)).contains(key)
    }

    /// Sets a title's cover, returning the previous one so the caller can offer to restore it.
    @discardableResult
    func setCover(sourceId: String, mangaId: String, coverUrl: String?, original: Bool = false) async -> String? {
        guard let record = sharedManga(sourceId: sourceId, mangaId: mangaId) else { return nil }
        let previous = record.thumbnail_url
        record.thumbnail_url = coverUrl
        handler.insertManga(manga: record)

        let key = Self.editedKeysKey(sourceId, mangaId)
        var edited = EditedKeys(rawValue: Int32(UserDefaults.standard.integer(forKey: key)))
        if original {
            edited.remove(.cover)
        } else {
            edited.insert(.cover)
        }
        UserDefaults.standard.set(Int(edited.rawValue), forKey: key)

        return previous
    }
}
