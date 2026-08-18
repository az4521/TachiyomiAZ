import Foundation
import TachiyomiKit

/// Manga rows, over the shared `mangas` table.
///
/// The fork's `(sourceId, mangaId)` pair maps to the shared schema's `(source, url)`: `mangaId` is
/// the source-specific key, which is what Android stores in `url`.
extension CoreDataManager {
    /// The shared row, for the facade's own use.
    ///
    /// The public `getManga` below returns the UI's `Manga` because that is what upstream's
    /// `MangaObject` is to the vendored code -- a String `id`, a `cover`. The rest of the facade
    /// needs the actual row to query against, so it goes through this.
    func sharedManga(sourceId: String, mangaId: String) -> DbManga? {
        guard let source = SourceIdentity.numericId(sourceId) else { return nil }
        return handler.getManga(url: mangaId, sourceId: source)
    }

    func getManga(sourceId: String, mangaId: String, context: Any? = nil) -> Manga? {
        sharedManga(sourceId: sourceId, mangaId: mangaId)?.toLegacy()
    }

    func getManga(context: Any? = nil) -> [Manga] {
        handler.getMangas().map { $0.toLegacy() }
    }

    func hasManga(sourceId: String, mangaId: String, context: Any? = nil) -> Bool {
        sharedManga(sourceId: sourceId, mangaId: mangaId) != nil
    }

    func removeManga(sourceId: String, mangaId: String, context: Any? = nil) {
        guard let manga = sharedManga(sourceId: sourceId, mangaId: mangaId) else { return }
        handler.deleteManga(manga: manga)
    }

    /// Drops everything the library does not point at, matching upstream's cache-clear semantics.
    /// The favourites are the user's data; the rest is a cache of what sources returned.
    func clearManga(context: Any? = nil) {
        handler.deleteMangasNotInLibrary()
    }
}

extension CoreDataManager {
    /// The reading mode a source asked for, which the reader falls back to when the user has not
    /// chosen one. Android keeps this in the manga's `viewer` column.
    func getMangaSourceReadingMode(sourceId: String, mangaId: String, context: Any? = nil) -> Int {
        Int(sharedManga(sourceId: sourceId, mangaId: mangaId)?.viewer ?? 0)
    }
}

extension CoreDataManager {
    /// Writes back the details the user can edit on the manga screen -- the chapter filter and sort
    /// flags. Android packs those into `chapter_flags`; the language and scanlator filters upstream
    /// also stores have no column there, so they are not persisted.
    @discardableResult
    func updateMangaDetails(manga: Manga, override: Bool = false) async -> Manga? {
        guard let record = sharedManga(sourceId: manga.sourceId, mangaId: manga.id) else { return nil }
        record.chapter_flags = Int32(manga.chapterFlags)
        handler.updateFlags(manga: record)
        return record.toLegacy()
    }
}

extension CoreDataManager {
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
        let handler = self.handler

        handler.inTransaction {
            let mangas = handler.getMangasBySource(sourceId: source)

            for manga in mangas {
                for chapter in handler.getChapters(manga: manga) {
                    guard let newKey = chapterIds[chapter.url], newKey != chapter.url else { continue }
                    chapter.url = newKey
                    handler.insertChapter(chapter: chapter)
                }
            }

            let renamed = mangas.filter { mangaIds[$0.url] != nil }
            for manga in renamed {
                manga.url = mangaIds[manga.url] ?? manga.url
            }
            if !renamed.isEmpty {
                handler.updateMangaUrls(mangas: renamed)
            }
        }
    }
}
