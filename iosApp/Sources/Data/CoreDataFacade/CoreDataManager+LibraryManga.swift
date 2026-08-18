import Foundation
import TachiyomiKit

/// The library, which in the shared schema is the `favorite` flag on a manga row rather than a
/// separate table. `LibraryManga` is the shared read model, carrying the unread/last-read columns.
extension CoreDataManager {
    func getLibraryManga(category: String? = nil, context: Any? = nil) -> [LibraryManga] {
        let all = handler.getLibraryMangas()
        guard let category, let stored = getCategory(title: category), let id = stored.id else { return all }
        let inCategory = Set(
            handler.getMangas()
                .filter { manga in handler.getCategoriesForManga(manga: manga).contains { $0.id == id } }
                .compactMap { $0.id?.int64Value }
        )
        return all.filter { manga in manga.id.map { inCategory.contains($0.int64Value) } ?? false }
    }

    func getLibraryManga(sourceId: String, context: Any? = nil) -> [LibraryManga] {
        guard let source = SourceIdentity.numericId(sourceId) else { return [] }
        return handler.getLibraryMangas().filter { $0.source == source }
    }

    func getLibraryManga(sourceId: String, mangaId: String, context: Any? = nil) -> LibraryManga? {
        guard let source = SourceIdentity.numericId(sourceId) else { return nil }
        return handler.getLibraryMangas().first { $0.source == source && $0.url == mangaId }
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
