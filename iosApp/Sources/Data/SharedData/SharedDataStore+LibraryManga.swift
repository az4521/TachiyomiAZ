import Foundation
import TachiyomiKit

/// Counts for the library's category tabs, with the membership map retained so an optimistic
/// removal can update the labels before the database-refresh notification arrives.
struct LibraryCategoryEntryCounts {
    private(set) var total = 0
    private(set) var uncategorized = 0
    private var identifiersByCategory: [String: Set<MangaIdentifier>] = [:]
    private var categoriesByIdentifier: [MangaIdentifier: Set<String>] = [:]
    private var allIdentifiers: Set<MangaIdentifier> = []

    init(
        allIdentifiers: Set<MangaIdentifier> = [],
        categoriesByIdentifier: [MangaIdentifier: Set<String>] = [:]
    ) {
        self.allIdentifiers = allIdentifiers
        self.categoriesByIdentifier = categoriesByIdentifier
        total = allIdentifiers.count
        uncategorized = allIdentifiers.subtracting(Set(categoriesByIdentifier.keys)).count
        for (identifier, categories) in categoriesByIdentifier {
            for category in categories {
                identifiersByCategory[category, default: []].insert(identifier)
            }
        }
    }

    func count(for category: String) -> Int {
        identifiersByCategory[category]?.count ?? 0
    }

    mutating func remove(_ identifiers: Set<MangaIdentifier>) {
        for identifier in identifiers where allIdentifiers.remove(identifier) != nil {
            total -= 1
            guard let categories = categoriesByIdentifier.removeValue(forKey: identifier) else {
                uncategorized -= 1
                continue
            }
            for category in categories {
                identifiersByCategory[category]?.remove(identifier)
            }
        }
    }
}

/// The library, which in the shared schema is the `favorite` flag on a manga row rather than a
/// separate table. `LibraryManga` is the shared read model, carrying the unread/last-read columns.
extension SharedDataStore {
    /// Counts every title once in each category it belongs to. A title with multiple categories is
    /// intentionally counted in each of those tabs, but only once in the All tab.
    func libraryCategoryEntryCounts(context: Any? = nil) -> LibraryCategoryEntryCounts {
        let namesById = Dictionary(
            libraryRepository.categories().compactMap { category in
                category.id.map { ($0.int32Value, category.name) }
            },
            uniquingKeysWith: { first, _ in first }
        )
        var allIdentifiers = Set<MangaIdentifier>()
        var categoriesByIdentifier: [MangaIdentifier: Set<String>] = [:]

        for row in libraryRepository.entries() {
            let identifier = MangaIdentifier(
                sourceKey: SourceIdentity.key(for: row.source),
                mangaKey: row.url
            )
            allIdentifiers.insert(identifier)
            guard row.category != 0, let name = namesById[row.category] else { continue }
            categoriesByIdentifier[identifier, default: []].insert(name)
        }

        return LibraryCategoryEntryCounts(
            allIdentifiers: allIdentifiers,
            categoriesByIdentifier: categoriesByIdentifier
        )
    }

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
        let rows = libraryRepository.entries()
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
        libraryRepository.uncategorizedEntries().map(Self.libraryObject)
    }

    /// Whether anything in the library belongs to no category.
    ///
    /// Drives whether the library shows an "Uncategorized" tab at all, so it runs on every load.
    /// `contains` stops at the first hit rather than building the whole list.
    func hasUncategorizedLibraryManga(context: Any? = nil) -> Bool {
        libraryRepository.hasUncategorizedEntries()
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
        var result: [MangaIdentifier: [String]] = [:]
        for membership in libraryRepository.categoryMemberships() {
            let identifier = MangaIdentifier(
                sourceKey: SourceIdentity.key(for: membership.sourceId),
                mangaKey: membership.url
            )
            result[identifier] = membership.names
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
        return libraryRepository.entries()
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
        libraryRepository.clear()
    }
}
