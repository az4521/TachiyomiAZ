import Foundation
import TachiyomiKit

/// Categories, over the shared `categories` table.
///
/// Upstream returns `CategoryObject` (an `NSManagedObject`); this returns the shared `Category`,
/// which carries the same title/sort information under Android's names -- `name` for `title`,
/// `order` for `sort`.
extension SharedDataStore {
    func getCategories(sorted: Bool = true, groupsOnly: Bool = false, context: Any? = nil) -> [MangaCategory] {
        // Filter groups are not rows in the shared schema, so a groups-only query has nothing to
        // return; `getFilterGroups()` reads them from their own store instead.
        if groupsOnly { return [] }
        let categories = libraryRepository.categories()
        return sorted ? categories.sorted { $0.order < $1.order } : categories
    }

    func getCategory(title: String, context: Any? = nil) -> MangaCategory? {
        libraryRepository.category(name: title)
    }

    func getCategoryTitles(sorted: Bool = true, excludeFilterGroups: Bool = true, context: Any? = nil) -> [String] {
        getCategories(sorted: sorted).map(\.name)
    }

    @discardableResult
    func createCategory(title: String, group: Bool = false, context: Any? = nil) -> MangaCategory {
        libraryRepository.addCategory(name: title) ?? CategoryCompanion.shared.create(name: title)
    }

    func removeCategory(title: String, context: Any? = nil) {
        guard let category = getCategory(title: title) else { return }
        libraryRepository.deleteCategory(category: category)
    }

    @discardableResult
    func renameCategory(title: String, newTitle: String, context: Any? = nil) -> Bool {
        guard let category = getCategory(title: title) else { return false }
        return libraryRepository.renameCategory(category: category, name: newTitle)
    }

    /// Reorders by rewriting every `order` in one pass -- the column is a plain sort key with no
    /// uniqueness constraint, so moving one row means renumbering the rest.
    func moveCategory(title: String, toPosition: Int, context: Any? = nil) {
        libraryRepository.moveCategory(name: title, toPosition: Int32(toPosition))
    }

    func clearCategories(context: Any? = nil) {
        libraryRepository.clearCategories()
    }
}

/// Which categories a manga belongs to, over the shared `mangas_categories` join table.
extension SharedDataStore {
    func getCategories(sourceId: String, mangaId: String, context: Any? = nil) -> [MangaCategory] {
        guard let source = SourceIdentity.numericId(sourceId) else { return [] }
        let wanted = Set(libraryRepository.categoryNames(url: mangaId, sourceId: source))
        return libraryRepository.categories().filter { wanted.contains($0.name) }
    }

    /// Replaces a manga's category memberships with exactly the titles given.
    ///
    /// `setMangaCategories(mangasCategories:mangas:)` is a `:core-database` default method that
    /// clears and reinserts in one transaction -- the same call the Android app makes -- so the
    /// replacement is not reimplemented here.
    func setMangaCategories(sourceId: String, mangaId: String, categories: [String]) async {
        guard let source = SourceIdentity.numericId(sourceId) else { return }
        libraryRepository.setCategories(url: mangaId, sourceId: source, names: categories)
    }

    func addCategoriesToManga(sourceId: String, mangaId: String, categories: [String]) async {
        guard let source = SourceIdentity.numericId(sourceId) else { return }
        libraryRepository.addCategories(url: mangaId, sourceId: source, names: categories)
    }

    func removeCategoriesFromManga(sourceId: String, mangaId: String, categories: [String]) async {
        guard let source = SourceIdentity.numericId(sourceId) else { return }
        libraryRepository.removeCategories(url: mangaId, sourceId: source, names: categories)
    }
}

/// Bulk category edits, as the library's selection mode makes them.
extension SharedDataStore {
    func addCategoriesToManga(_ identifiers: [MangaIdentifier], categories: [String]) async {
        for identifier in identifiers {
            await addCategoriesToManga(
                sourceId: identifier.sourceKey,
                mangaId: identifier.mangaKey,
                categories: categories
            )
        }
    }

    func removeCategoriesFromManga(_ identifiers: [MangaIdentifier], categories: [String]) async {
        for identifier in identifiers {
            await removeCategoriesFromManga(
                sourceId: identifier.sourceKey,
                mangaId: identifier.mangaKey,
                categories: categories
            )
        }
    }
}
