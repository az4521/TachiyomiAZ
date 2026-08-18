import Foundation
import TachiyomiKit

/// Manga rows, over the shared `mangas` table.
///
/// The fork's `(sourceId, mangaId)` pair maps to the shared schema's `(source, url)`: `mangaId` is
/// the source-specific key, which is what Android stores in `url`.
extension CoreDataManager {
    func getManga(sourceId: String, mangaId: String, context: Any? = nil) -> DbManga? {
        guard let source = SourceIdentity.numericId(sourceId) else { return nil }
        return handler.getManga(url: mangaId, sourceId: source)
    }

    func getManga(context: Any? = nil) -> [DbManga] {
        handler.getMangas()
    }

    func hasManga(sourceId: String, mangaId: String, context: Any? = nil) -> Bool {
        getManga(sourceId: sourceId, mangaId: mangaId) != nil
    }

    func removeManga(sourceId: String, mangaId: String, context: Any? = nil) {
        guard let manga = getManga(sourceId: sourceId, mangaId: mangaId) else { return }
        handler.deleteManga(manga: manga)
    }

    /// Drops everything the library does not point at, matching upstream's cache-clear semantics.
    /// The favourites are the user's data; the rest is a cache of what sources returned.
    func clearManga(context: Any? = nil) {
        handler.deleteMangasNotInLibrary()
    }
}
