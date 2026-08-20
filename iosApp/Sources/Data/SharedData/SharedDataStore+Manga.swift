import Foundation
import ExtensionRunner
import TachiyomiKit

/// Manga rows, over the shared `mangas` table.
///
/// The fork's `(sourceId, mangaId)` pair maps to the shared schema's `(source, url)`: `mangaId` is
/// the source-specific key, which is what Android stores in `url`.
extension SharedDataStore {
    /// Kept computed so the facade remains a thin adapter over the shared repository rather than
    /// owning a second persistence service.
    var mangaRepository: MangaRepository { MangaRepository(db: handler) }

    /// The shared row, for the facade's own use.
    ///
    /// The public `getManga` below returns the UI's `Manga` because that is what upstream's
    /// `MangaObject` is to the vendored code -- a String `id`, a `cover`. The rest of the facade
    /// needs the actual row to query against, so it goes through this.
    func sharedManga(sourceId: String, mangaId: String) -> DbManga? {
        guard let source = SourceIdentity.numericId(sourceId) else { return nil }
        return mangaRepository.manga(url: mangaId, sourceId: source)
    }

    func getManga(sourceId: String, mangaId: String, context: Any? = nil) -> ExtensionRunner.Manga? {
        sharedManga(sourceId: sourceId, mangaId: mangaId)?.toNewManga()
    }

    func getManga(context: Any? = nil) -> [ExtensionRunner.Manga] {
        mangaRepository.mangas().map { $0.toNewManga() }
    }

    func hasManga(sourceId: String, mangaId: String, context: Any? = nil) -> Bool {
        guard let source = SourceIdentity.numericId(sourceId) else { return false }
        return mangaRepository.contains(url: mangaId, sourceId: source)
    }

    /// Drops everything the library does not point at, matching upstream's cache-clear semantics.
    /// The favourites are the user's data; the rest is a cache of what sources returned.
    func clearManga(context: Any? = nil) {
        mangaRepository.clearCache()
    }
}

extension SharedDataStore {
    /// The reading mode a source asked for, which the reader falls back to when the user has not
    /// chosen one. Android keeps this in the manga's `viewer` column.
    func getMangaSourceReadingMode(sourceId: String, mangaId: String, context: Any? = nil) -> Int {
        guard let source = SourceIdentity.numericId(sourceId) else { return 0 }
        return Int(mangaRepository.sourceReadingMode(url: mangaId, sourceId: source))
    }
}

extension SharedDataStore {
    /// Writes back the details the user can edit on the manga screen -- the chapter filter and sort
    /// flags. Android packs those into `chapter_flags`; the language and scanlator filters upstream
    /// also stores have no column there, so they are not persisted.
    @discardableResult
    func updateMangaDetails(manga: ExtensionRunner.Manga, chapterFlags: Int, override: Bool = false) async -> ExtensionRunner.Manga? {
        guard let source = SourceIdentity.numericId(manga.sourceKey) else { return nil }
        guard let record = mangaRepository.updateChapterFlags(
            url: manga.key,
            sourceId: source,
            flags: Int32(chapterFlags)
        ) else { return nil }
        return record.toNewManga()
    }
}

extension SharedDataStore {
    /// Rewrites a source's manga and chapter keys after it changes its id scheme.
    ///
    /// The manga side goes through `updateMangaUrls` in `:core-database` -- the same call the
    /// Android app makes for this -- rather than reinserting rows, so ids and relationships survive.
    func migrateSourceIds(
        sourceId: String,
        mangaIds: [String: String],
        chapterIds: [String: String]
    ) {
        guard let source = SourceIdentity.numericId(sourceId) else { return }
        let oldMangaIds = Array(mangaIds.keys)
        let newMangaIds = oldMangaIds.compactMap { mangaIds[$0] }
        let oldChapterIds = Array(chapterIds.keys)
        let newChapterIds = oldChapterIds.compactMap { chapterIds[$0] }
        mangaRepository.migrateSourceIds(
            sourceId: source,
            oldMangaUrls: oldMangaIds,
            newMangaUrls: newMangaIds,
            oldChapterUrls: oldChapterIds,
            newChapterUrls: newChapterIds
        )
    }
}
