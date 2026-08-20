import Foundation

/// Saved library filter groups.
///
/// Upstream stores these as categories carrying a JSON blob in a `data` column. The shared schema's
/// `categories` table has no such column -- it is Android's, and Android has no filter groups -- so
/// adding one would put this app's writes out of step with the database both apps share. They live
/// in `UserDefaults` instead, which is where a UI-only preference belongs.
extension CoreDataManager {
    private static let filterGroupsKey = "Library.filterGroups"

    private struct StoredFilterGroup: Codable {
        let title: String
        let filters: [LibraryFilter]
    }

    func getFilterGroups(context: Any? = nil) -> [FilterGroup] {
        guard
            let data = UserDefaults.standard.data(forKey: Self.filterGroupsKey),
            let stored = try? JSONDecoder().decode([StoredFilterGroup].self, from: data)
        else { return [] }
        return stored.map { FilterGroup(title: $0.title, filters: $0.filters) }
    }

    func setFilterGroups(_ groups: [FilterGroup]) {
        let stored = groups.map { StoredFilterGroup(title: $0.title, filters: $0.filters) }
        guard let data = try? JSONEncoder().encode(stored) else { return }
        UserDefaults.standard.set(data, forKey: Self.filterGroupsKey)
    }
}
