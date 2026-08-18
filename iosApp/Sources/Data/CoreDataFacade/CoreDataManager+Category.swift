import Foundation
import TachiyomiKit

/// Categories, over the shared `categories` table.
///
/// Upstream returns `CategoryObject` (an `NSManagedObject`); this returns the shared `Category`,
/// which carries the same title/sort information under Android's names -- `name` for `title`,
/// `order` for `sort`.
extension CoreDataManager {
    func getCategories(sorted: Bool = true, groupsOnly: Bool = false, context: Any? = nil) -> [MangaCategory] {
        // Filter groups are not rows in the shared schema, so a groups-only query has nothing to
        // return; `getFilterGroups()` reads them from their own store instead.
        if groupsOnly { return [] }
        let categories = handler.getCategories()
        return sorted ? categories.sorted { $0.order < $1.order } : categories
    }

    func getCategory(title: String, context: Any? = nil) -> MangaCategory? {
        handler.getCategories().first { $0.name == title }
    }

    func getCategoryTitles(sorted: Bool = true, excludeFilterGroups: Bool = true, context: Any? = nil) -> [String] {
        getCategories(sorted: sorted).map(\.name)
    }

    func hasCategory(title: String, context: Any? = nil) -> Bool {
        getCategory(title: title) != nil
    }

    @discardableResult
    func createCategory(title: String, group: Bool = false, context: Any? = nil) -> MangaCategory {
        let category = CategoryCompanion.shared.create(name: title)
        category.order = Int32(handler.getCategories().count)
        handler.insertCategory(category: category)
        return category
    }

    func removeCategory(title: String, context: Any? = nil) {
        guard let category = getCategory(title: title) else { return }
        handler.deleteCategory(category: category)
    }

    @discardableResult
    func renameCategory(title: String, newTitle: String, context: Any? = nil) -> Bool {
        guard let category = getCategory(title: title) else { return false }
        category.name = newTitle
        handler.insertCategory(category: category)
        return true
    }

    /// Reorders by rewriting every `order` in one pass -- the column is a plain sort key with no
    /// uniqueness constraint, so moving one row means renumbering the rest.
    func moveCategory(title: String, toPosition: Int, context: Any? = nil) {
        var categories = getCategories()
        guard let from = categories.firstIndex(where: { $0.name == title }) else { return }
        let moved = categories.remove(at: from)
        categories.insert(moved, at: min(max(toPosition, 0), categories.count))
        for (index, category) in categories.enumerated() where category.order != Int32(index) {
            category.order = Int32(index)
            handler.insertCategory(category: category)
        }
    }

    func clearCategories(context: Any? = nil) {
        handler.deleteCategories(categories: handler.getCategories())
    }
}
