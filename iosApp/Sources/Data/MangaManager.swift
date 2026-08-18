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

    /// Sets which categories a title belongs to, and tells the library to refresh.
    func setCategories(sourceId: String, mangaId: String, categories: [String]) async {
        await CoreDataManager.shared.setMangaCategories(
            sourceId: sourceId,
            mangaId: mangaId,
            categories: categories
        )
        NotificationCenter.default.post(
            name: Notification.Name("updateMangaCategories"),
            object: MangaInfo(mangaId: mangaId, sourceId: sourceId)
        )
    }

    // MARK: - Cover

    // Lifted from tachiyomiazios: writing a user-chosen cover into Documents/Covers and recording
    // the URL is filesystem work with no persistence decisions in it, so it comes across unchanged.
    // The CoreDataManager.setCover it calls is this port's own, backed by the shared row.
    // sets uploaded cover image and returns the new cover url
    func setCover(manga: ExtensionRunner.Manga, cover: PlatformImage) async -> String? {
        if manga.isLocal() {
            return await LocalFileManager.shared.setCover(for: manga.key, image: cover)
        }

        // upload cover image to Documents/Covers/id.png
        let documentsDirectory = FileManager.default.documentDirectory
        let targetDirectory = documentsDirectory.appendingPathComponent("Covers")
        let ext = if #available(iOS 17.0, *) {
            "heic"
        } else {
            "png"
        }
        var targetUrl = targetDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension(ext)
        while targetUrl.exists {
            targetUrl = targetDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension(ext)
        }
        targetDirectory.createDirectory()
        do {
            let data = if #available(iOS 17.0, *) {
#if !os(macOS)
                cover.heicData()
#else
                cover.pngData()
#endif
            } else {
                cover.pngData()
            }
            try data?.write(to: targetUrl)
        } catch {
            LogManager.logger.error("MangaManager.setMangaCover: \(error.localizedDescription)")
            return nil
        }

        // set cover in coredata
        let coverUrl = "aidoku-image:///Covers/\(targetUrl.lastPathComponent)"
        await CoreDataManager.shared.setCover(
            sourceId: manga.sourceKey,
            mangaId: manga.key,
            coverUrl: coverUrl
        )

        return coverUrl
    }

    // MARK: - Migration

    /// Moves (or copies) library entries from one source to another.
    ///
    /// Upstream's version threads through its CoreData object graph. This works in the same terms
    /// the shared database does: the destination becomes a favourite carrying the source's details
    /// and chapters, category membership follows it, and unless `copy` is set the original stops
    /// being a favourite. Read state is not carried across -- the two sources number chapters
    /// differently, and guessing an alignment would silently mark things read.
    func migrate(
        copy: Bool,
        fromSeries: [ExtensionRunner.Manga],
        toSeries: [MangaIdentifier: ExtensionRunner.Manga?],
        withChapters: [MangaIdentifier: [ExtensionRunner.Chapter]] = [:],
        progressReport: ((Float) -> Void)? = nil
    ) async {
        let total = Float(max(fromSeries.count, 1))
        for (index, from) in fromSeries.enumerated() {
            defer { progressReport?(Float(index + 1) / total) }

            let identifier = MangaIdentifier(sourceKey: from.sourceKey, mangaKey: from.key)
            guard let destination = toSeries[identifier] ?? nil else { continue }

            await CoreDataManager.shared.cacheMangaDetails(destination, includeChapters: false)

            if let chapters = withChapters[identifier] {
                CoreDataManager.shared.setChapters(
                    chapters,
                    sourceId: destination.sourceKey,
                    mangaId: destination.key
                )
            }

            let categories = CoreDataManager.shared
                .getCategories(sourceId: from.sourceKey, mangaId: from.key)
                .map(\.name)

            await library?.addFromRunner(destination, sourceId: SourceIdentity.numericId(destination.sourceKey) ?? 0)

            if !categories.isEmpty {
                await CoreDataManager.shared.setMangaCategories(
                    sourceId: destination.sourceKey,
                    mangaId: destination.key,
                    categories: categories
                )
            }

            if !copy {
                await library?.remove(
                    url: from.key,
                    sourceId: SourceIdentity.numericId(from.sourceKey) ?? 0
                )
            }
        }

        NotificationCenter.default.post(name: Notification.Name("updateLibrary"), object: nil)
    }
}
