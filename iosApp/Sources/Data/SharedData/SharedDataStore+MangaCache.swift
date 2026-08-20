import ExtensionRunner
import Foundation
import TachiyomiKit

/// Caching a source's manga into the shared database, and the per-manga chapter filters.
///
/// Android keeps chapter filter state packed into `chapter_flags` on the manga row, which is what
/// `ChapterFilters.flags` carries. It has no per-manga language or scanlator filter columns -- a
/// Tachiyomi source is single-language -- so those come back nil rather than being invented.
extension SharedDataStore {
    struct ChapterFilters {
        let flags: Int
        let language: String?
        let scanlators: [String]?
    }

    func getMangaChapterFilters(sourceId: String, mangaId: String, context: Any? = nil) -> ChapterFilters {
        let flags: Int
        if let source = SourceIdentity.numericId(sourceId) {
            flags = Int(mangaRepository.chapterFlags(url: mangaId, sourceId: source))
        } else {
            flags = 0
        }
        return ChapterFilters(
            flags: flags,
            language: nil,
            scanlators: nil
        )
    }

    /// Records the titles a browse request returned, so they are known rows before the user opens
    /// one. Existing rows are left alone: a summary carries less than the details already stored.
    func cacheMangaSummaries(_ manga: [ExtensionRunner.Manga]) async {
        let records = manga.compactMap { item -> DbManga? in
            guard let source = SourceIdentity.numericId(item.sourceKey) else { return nil }
            return item.toShared(source: source)
        }
        mangaRepository.cacheSummaries(mangas: records)
    }

    /// Writes the full details a source returned over the stored row, keeping the flags and
    /// favourite state the user owns.
    func cacheMangaDetails(_ manga: ExtensionRunner.Manga, includeChapters: Bool) async {
        guard let source = SourceIdentity.numericId(manga.sourceKey) else { return }
        _ = mangaRepository.cacheDetails(manga: manga.toShared(source: source))

        // `includeChapters` was accepted and ignored, so opening a title stored the manga row and
        // dropped its chapters. Nothing else writes them outside a library refresh, which left
        // every entry with no chapters -- and therefore no unread count and nothing to read.
        if includeChapters, let chapters = manga.chapters, !chapters.isEmpty {
            setChapters(chapters, sourceId: manga.sourceKey, mangaId: manga.key)
        }
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

}

/// Fields the user has overridden on a title, and the cover they chose.
///
/// Android's schema records neither: it has no "edited" bitmask and no user-cover column, because
/// the Android app does not offer per-title editing. Both live in `UserDefaults` here so the shared
/// rows stay exactly what both apps expect.
extension SharedDataStore {
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
        guard let source = SourceIdentity.numericId(sourceId) else { return nil }
        guard let update = mangaRepository.updateCover(
            url: mangaId,
            sourceId: source,
            coverUrl: coverUrl
        ) else { return nil }

        let key = Self.editedKeysKey(sourceId, mangaId)
        var edited = EditedKeys(rawValue: Int32(UserDefaults.standard.integer(forKey: key)))
        if original {
            edited.remove(.cover)
        } else {
            edited.insert(.cover)
        }
        UserDefaults.standard.set(Int(edited.rawValue), forKey: key)

        return update.previousCoverUrl
    }
}
