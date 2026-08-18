import Foundation
import TachiyomiKit

/// The library, which in the shared schema is the `favorite` flag on a manga row rather than a
/// separate table. `LibraryManga` is the shared read model, carrying the unread/last-read columns.
extension CoreDataManager {
    func getLibraryManga(category: String? = nil, context: Any? = nil) -> [LibraryMangaObject] {
        let all = handler.getLibraryMangas().map {
            LibraryMangaObject(row: $0, sourceId: $0.legacySourceId, mangaId: $0.url)
        }
        guard let category, let stored = getCategory(title: category), let id = stored.id else { return all }
        // Category membership is a join, so the filter runs over the rows behind these objects.
        let inCategory = Set(
            handler.getMangas()
                .filter { manga in handler.getCategoriesForManga(manga: manga).contains { $0.id == id } }
                .compactMap { $0.id?.int64Value }
        )
        return all.filter { object in
            object.manga.flatMap { $0.row.id?.int64Value }.map { inCategory.contains($0) } ?? false
        }
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

extension CoreDataManager {
    /// Stamps when a title was last read, which is what "recently read" ordering sorts on.
    ///
    /// Android's schema has no `last_read` column on the manga row -- reading times live in the
    /// history table, per chapter -- so this is kept alongside, like the other library timestamps.
    func setRead(sourceId: String, mangaId: String, date: Date? = nil, context: Any? = nil) {
        getLibraryManga(sourceId: sourceId, mangaId: mangaId)?.lastRead = date ?? Date()
    }
}
