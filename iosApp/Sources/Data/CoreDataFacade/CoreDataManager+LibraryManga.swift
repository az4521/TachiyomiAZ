import Foundation
import TachiyomiKit

/// The library, which in the shared schema is the `favorite` flag on a manga row rather than a
/// separate table. `LibraryManga` is the shared read model, carrying the unread/last-read columns.
extension CoreDataManager {
    /// Library entries, optionally limited to one category.
    ///
    /// The category filter is free. `getLibraryMangas` returns one row per manga-category pairing
    /// and each row carries its `category`, so membership is already in hand -- filtering the rows
    /// is the whole job.
    ///
    /// It used to be answered by loading *every* manga row in the database, favourite or not, and
    /// running a `getCategoriesForManga` query against each one. That is one query per row per
    /// category switch, and it is why switching tabs on a large library stalled.
    func getLibraryManga(category: String? = nil, context: Any? = nil) -> [LibraryMangaObject] {
        let rows = handler.getLibraryMangas()
        guard let category else { return rows.map(Self.libraryObject) }
        guard let id = getCategory(title: category)?.id?.int32Value else { return [] }
        return rows.filter { $0.category == id }.map(Self.libraryObject)
    }

    /// Library entries belonging to no category.
    ///
    /// `getLibraryMangas` left-joins the membership table and coalesces a miss to 0, and no real
    /// category ever has that id -- SQLite assigns row ids from 1, and Tachiyomi's "Default" is a
    /// name the UI gives to this case rather than a row. So an uncategorised entry is exactly a
    /// row whose `category` is 0, with no per-manga query needed to find out.
    func getUncategorizedLibraryManga(context: Any? = nil) -> [LibraryMangaObject] {
        handler.getLibraryMangas().filter { $0.category == 0 }.map(Self.libraryObject)
    }

    /// Whether anything in the library belongs to no category.
    ///
    /// Drives whether the library shows an "Uncategorized" tab at all, so it runs on every load.
    /// `contains` stops at the first hit rather than building the whole list.
    func hasUncategorizedLibraryManga(context: Any? = nil) -> Bool {
        handler.getLibraryMangas().contains { $0.category == 0 }
    }

    /// Every library title's category names, in two queries for the whole library.
    ///
    /// The alternative is `getCategories(sourceId:mangaId:)` per title, which is two queries each
    /// -- and the library screen was doing exactly that for every entry on every load, which is
    /// what made switching category tabs slow. The rows already carry their category id, so the
    /// names only need looking up once.
    ///
    /// Titles in no category are absent rather than present with an empty list; callers asking for
    /// one get `nil` and can treat it as empty.
    func libraryCategoryNames(context: Any? = nil) -> [MangaIdentifier: [String]] {
        let namesById = Dictionary(
            handler.getCategories().compactMap { category in
                category.id.map { (Int32($0.int32Value), category.name) }
            },
            uniquingKeysWith: { first, _ in first }
        )

        var result: [MangaIdentifier: [String]] = [:]
        // One row per manga-category pairing, so a title in three categories arrives three times.
        for row in handler.getLibraryMangas() where row.category != 0 {
            guard let name = namesById[row.category] else { continue }
            let identifier = MangaIdentifier(sourceKey: row.legacySourceId, mangaKey: row.url)
            result[identifier, default: []].append(name)
        }
        return result
    }

    private static func libraryObject(_ row: LibraryManga) -> LibraryMangaObject {
        LibraryMangaObject(
            row: row,
            sourceId: row.legacySourceId,
            mangaId: row.url,
            totalChapters: Int(row.unread) + Int(row.read)
        )
    }

    func getLibraryManga(sourceId: String, context: Any? = nil) -> [LibraryMangaObject] {
        guard let source = SourceIdentity.numericId(sourceId) else { return [] }
        return handler.getLibraryMangas()
            .filter { $0.source == source }
            .map { LibraryMangaObject(row: $0, sourceId: sourceId, mangaId: $0.url) }
    }

    /// Upstream returns its `LibraryMangaObject`; this returns the same shape over the shared row.
    func getLibraryManga(sourceId: String, mangaId: String, context: Any? = nil) -> LibraryMangaObject? {
        guard let manga = sharedManga(sourceId: sourceId, mangaId: mangaId), manga.favorite else {
            return nil
        }
        return LibraryMangaObject(row: manga, sourceId: sourceId, mangaId: mangaId)
    }

    func hasLibraryManga(sourceId: String, mangaId: String, context: Any? = nil) -> Bool {
        sharedManga(sourceId: sourceId, mangaId: mangaId)?.favorite == true
    }

    func clearLibrary(context: Any? = nil) {
        let handler = self.handler
        handler.inTransaction {
            for manga in handler.getFavoriteMangas() {
                manga.favorite = false
                handler.updateMangaFavorite(manga: manga)
            }
        }
    }
}
