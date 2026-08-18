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

    // MARK: - Next chapter

    // Lifted verbatim from tachiyomiazios' MangaManager. That class is not vendored -- it drives
    // chapter syncing, which this port takes from :core-domain -- but this method is pure logic
    // over the chapter list and reading history, touching no persistence, so it comes across as-is
    // rather than being reimplemented and drifting from the behaviour the UI expects.
    nonisolated func getNextChapter(
        manga: ExtensionRunner.Manga,
        chapters: [ExtensionRunner.Chapter],
        readingHistory: [String: (page: Int, date: Int)],
        sortAscending: Bool,
        downloadStatuses: [String: DownloadStatus]? = nil
    ) -> ExtensionRunner.Chapter? {
        let resumeLastOpened = UserDefaults.standard.bool(forKey: "Library.resumeLastOpenedChapter")

        // 1. Resume Reading: Find the most recently read chapter that isn't
        // completed, unless the "resume last opened" option is enabled.
        var selectedChapter: ExtensionRunner.Chapter?
        var selectedDate: Int = -1

        for chapter in chapters {
            guard
                let history = readingHistory[chapter.id],
                resumeLastOpened || history.page != -1,
                history.date > selectedDate
            else { continue }

            if chapter.locked {
                let isDownloaded = if let downloadStatuses {
                    downloadStatuses[chapter.key] == .finished
                } else {
                    DownloadManager.shared.getDownloadStatus(
                        for: .init(
                            sourceKey: manga.sourceKey,
                            mangaKey: manga.key,
                            chapterKey: chapter.key
                        )
                    ) == .finished
                }
                guard isDownloaded else { continue }
            }

            selectedDate = history.date
            selectedChapter = chapter
        }

        if let selectedChapter {
            return selectedChapter
        }

        // 2. Fallback: Find first uncompleted chapter in sort order (Start Reading)
        let sorted = sortAscending ? chapters : chapters.reversed()

        return sorted.first(where: { chapter in
            let isDownloaded = if let downloadStatuses {
                downloadStatuses[chapter.key] == .finished
            } else {
                DownloadManager.shared.getDownloadStatus(
                    for: .init(
                        sourceKey: manga.sourceKey,
                        mangaKey: manga.key,
                        chapterKey: chapter.key
                    )
                ) == .finished
            }
            let isUnlocked = !chapter.locked || isDownloaded
            let history = readingHistory[chapter.id]
            let isCompleted = history?.page ?? 0 == -1

            return isUnlocked && !isCompleted
        })
    }
}
