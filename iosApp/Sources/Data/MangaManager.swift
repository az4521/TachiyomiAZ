import ExtensionRunner
import Foundation
import TachiyomiKit

/// Library operations for the vendored UI, backed by the shared KMP database.
///
/// The views taken from tachiyomiazios call `MangaManager.shared`, so this keeps that API. What it
/// does underneath is entirely different: upstream wrote to Core Data, this writes through
/// `IosDatabaseHandler` -- the same `:core-database` queries the Android app uses. That is the
/// whole point of the port, and it is why the UI could be taken while the data layer was not.
///
/// `sourceKey` is a String on the UI side and a Long `source` column in the database, so the two
/// are converted at this boundary rather than either side being bent to match.
@MainActor
final class MangaManager {
    static let shared = MangaManager()

    /// Assigned at launch. A singleton is upstream's shape, not a choice made here.
    weak var library: LibraryStore?
    weak var runtime: SourceRuntime?

    private init() {}

    func addToLibrary(
        manga: ExtensionRunner.Manga,
        chapters: [ExtensionRunner.Chapter] = [],
        fetchMangaDetails: Bool = false
    ) async {
        guard let library, let sourceId = Int64(manga.sourceKey) else { return }
        await library.addFromRunner(manga, sourceId: sourceId)
    }

    func removeFromLibrary(sourceId: String, mangaId: String) async {
        guard let library, let source = Int64(sourceId) else { return }
        await library.remove(url: mangaId, sourceId: source)
    }

    /// Re-fetches details purely to refresh a broken cover URL.
    func resetCover(manga: ExtensionRunner.Manga) async -> String? {
        guard let runtime, let sourceId = Int64(manga.sourceKey),
              let source = runtime.sources.first(where: { $0.id == sourceId }) else { return nil }
        let update = try? await runtime.mangaDetails(
            source,
            url: manga.key,
            title: manga.title,
            memo: nil
        )
        return update?.manga.thumbnailURL
    }

    /// Whether adding to the library should prompt for a category.
    ///
    /// Upstream read category titles from Core Data; this reads them from the shared
    /// `CategoryQueries`, so the answer matches what the library actually contains.
    static func shouldAskForCategories() -> Bool {
        guard let handler = MangaManager.shared.library?.handler else { return false }
        let categories = handler.getCategories()
        guard !categories.isEmpty else { return false }
        let defaultCategory = UserDefaults.standard.string(forKey: "Library.defaultCategory")
        if let defaultCategory, defaultCategory == "none" || categories.contains(where: { $0.name == defaultCategory }) {
            return false
        }
        return true
    }
}
