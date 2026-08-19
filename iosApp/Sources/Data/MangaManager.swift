import BackgroundTasks
import UIKit
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
        // A source key is `mihon.<id>`, so parsing it as a number always failed and these three
        // methods silently did nothing -- adding to the library from the manga screen included.
        guard let library, let sourceId = SourceIdentity.numericId(manga.sourceKey) else { return }
        await library.addFromRunner(manga, sourceId: sourceId)
        // Browse and search grids swap a cell's library badge on this. Without it the cell kept
        // saying the title was not in the library until the whole screen was rebuilt.
        NotificationCenter.default.post(name: .addToLibrary, object: manga)
    }

    func removeFromLibrary(sourceId: String, mangaId: String) async {
        guard let library, let source = SourceIdentity.numericId(sourceId) else { return }
        await library.remove(url: mangaId, sourceId: source)
        NotificationCenter.default.post(
            name: .removeFromLibrary,
            object: ExtensionRunner.Manga(sourceKey: sourceId, key: mangaId, title: "")
        )
    }

    /// Re-fetches details purely to refresh a broken cover URL.
    func resetCover(manga: ExtensionRunner.Manga) async -> String? {
        guard let runtime, let sourceId = SourceIdentity.numericId(manga.sourceKey),
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

    /// Refreshes the library without blocking the UI, as the pull-to-refresh and the scheduled
    /// update both do. The rules -- which entries are eligible, how chapters are diffed -- come
    /// from :core-domain via LibraryStore, so this only decides when to run.
    /// New-chapter notifications are decided by whether the app is on screen when the run
    /// *finishes*, not by how it started. The fork gates them on having been launched by the
    /// system, which misses the common case: starting a refresh by hand and then switching away.
    /// Nothing is worth notifying about while the user is watching the library update in front of
    /// them; everything is, the moment they are not.
    func backgroundRefreshLibrary(
        category: String? = nil,
        skipReachabilityCheck: Bool = false
    ) async {
        guard let library, let runtime else { return }
        let categoryId = category.flatMap { title in
            CoreDataManager.shared.getCategory(title: title)?.id?.int32Value
        }

        // The tab bar shows the progress accessory at the bottom of the screen. Nothing was
        // driving it, so a refresh ran silently -- indistinguishable from not running at all.
        let tabBarController = (UIApplication.shared.delegate as? AppDelegate)?
            .window?.rootViewController as? TabBarController
        tabBarController?.showLibraryRefreshView()

        // Ask for the short allowance that lets a refresh keep running after the user switches
        // away. Without it iOS suspends the app on backgrounding and the run simply stops partway
        // -- no chapters, no notification, no indication it did not finish. The download queue
        // already does this for the same reason; the library refresh never did.
        //
        // "Background library refresh" is the setting that governs it, and it only covers a few
        // minutes: a long refresh can still be cut short, which is why the scheduled
        // BGProcessingTask exists alongside it.
        var backgroundTask = UIBackgroundTaskIdentifier.invalid
        if UserDefaults.standard.bool(forKey: "Library.backgroundRefresh") {
            backgroundTask = UIApplication.shared.beginBackgroundTask(
                withName: "TachiyomiAZ Library Refresh"
            )
        }
        defer {
            if backgroundTask != .invalid {
                UIApplication.shared.endBackgroundTask(backgroundTask)
            }
        }

        // Zero total with a detail line, because how many titles are eligible is not known until
        // the selection rule has run -- the notification says "calculating" rather than "0 of 0".
        await NotificationManager.shared.beginProgress(
            .libraryUpdate,
            total: 0,
            detail: NotificationManager.calculatingLibraryRefreshDetail
        )

        await library.refresh(
            categoryId: categoryId,
            runtime: runtime,
            settings: AppEnvironment.shared.settings
        ) { completed, total in
            tabBarController?.setLibraryRefreshProgress(
                LibraryRefreshProgress(completed: completed, total: total)
            )
            // The notification is throttled inside NotificationManager -- to whole percent and at
            // most once a second -- so this can be called per title without flooding.
            Task {
                await NotificationManager.shared.updateProgress(
                    .libraryUpdate,
                    completed: Double(completed),
                    total: total
                )
            }
        }

        tabBarController?.hideAccessoryView()
        NotificationCenter.default.post(name: Notification.Name("updateLibrary"), object: nil)

        let summary = library.lastSummary

        await NotificationManager.shared.finishProgress(
            .libraryUpdate,
            success: summary.map { $0.failed == 0 && $0.missingSource == 0 } ?? true,
            summary: summary?.completionSummary
        )

        // Read here rather than at the start: a refresh takes minutes, and what matters is where
        // the user is now. Starting one by hand and switching away should still notify.
        let isActive = UIApplication.shared.applicationState == .active

        if !isActive, let summary, !summary.newChapters.isEmpty {
            await NotificationManager.shared.notifyNewChapters(summary.newChapters)
        }

        // Say so when a refresh did not do what it looked like it was doing. The counts were being
        // written to a property with no reader, so a run that fetched nothing at all was reported
        // exactly the same as one that worked: the progress bar filled and the accessory vanished.
        //
        // Only while the app is on screen: an alert raised behind a backgrounded refresh would
        // ambush the user on next launch, and the completion notification carries the same counts.
        if isActive, let summary, !summary.isComplete {
            (UIApplication.shared.delegate as? AppDelegate)?.presentAlert(
                title: NSLocalizedString("REFRESH_INCOMPLETE"),
                message: summary.description
            )
        }
    }

    /// Drops library membership for a set of titles, returning what was dropped so the caller can
    /// offer an undo.
    ///
    /// Everything else the title owns -- chapters, history, tracks, downloads -- is left alone;
    /// only the `favorite` flag and category links change.
    /// Everything else the title owns -- chapters, history, tracks, downloads -- is left alone;
    /// only the `favorite` flag and category links change.
    ///
    /// The snapshots are taken in one pass and the removal happens in one transaction. Doing both
    /// per title meant a full library reload for each one, which froze the app on a large
    /// selection; see `LibraryStore.remove(urls:)`.
    @discardableResult
    func removeFromLibrary(manga identifiers: [MangaIdentifier]) async -> [LibraryMembershipSnapshot] {
        // Both maps cover the whole library in a handful of queries, rather than the three per
        // title this used to run. `libraryCategoryNames` is the same lookup the library screen
        // uses, so the two cannot disagree about what a title is filed under.
        let handler = Database.handler
        let categoriesFor = CoreDataManager.shared.libraryCategoryNames()
        var objects: [MangaIdentifier: LibraryMangaObject] = [:]
        for row in handler.getLibraryMangas() {
            let identifier = MangaIdentifier(sourceKey: row.legacySourceId, mangaKey: row.url)
            objects[identifier] = LibraryMangaObject(
                row: row,
                sourceId: identifier.sourceKey,
                mangaId: identifier.mangaKey
            )
        }

        var snapshots: [LibraryMembershipSnapshot] = []
        var removals: [(url: String, sourceId: Int64)] = []

        for identifier in identifiers {
            guard let object = objects[identifier] else { continue }

            snapshots.append(
                LibraryMembershipSnapshot(
                    identifier: identifier,
                    categories: categoriesFor[identifier] ?? [],
                    lastOpened: object.lastOpened ?? .distantPast,
                    lastUpdated: object.lastUpdated ?? .distantPast,
                    lastUpdatedChapters: object.lastUpdatedChapters ?? .distantPast,
                    lastChapter: object.lastChapter,
                    lastRead: object.lastRead,
                    dateAdded: object.dateAdded ?? .distantPast
                )
            )

            if let source = SourceIdentity.numericId(identifier.sourceKey) {
                removals.append((url: identifier.mangaKey, sourceId: source))
            }
        }

        await library?.remove(urls: removals)
        NotificationCenter.default.post(name: Notification.Name("updateLibrary"), object: nil)
        return snapshots
    }

    /// Puts back what `removeFromLibrary` took, for the undo action.
    /// Undoing a thousand-title removal is the same amount of work as the removal, so it gets the
    /// same treatment: one transaction rather than one per title.
    func restoreLibraryMembership(_ snapshots: [LibraryMembershipSnapshot]) async {
        guard !snapshots.isEmpty else { return }
        let handler = Database.handler
        let categoriesByName = Dictionary(
            handler.getCategories().map { ($0.name, $0) },
            uniquingKeysWith: { first, _ in first }
        )

        handler.inTransaction {
            for snapshot in snapshots {
                guard
                    let source = SourceIdentity.numericId(snapshot.identifier.sourceKey),
                    let manga = handler.getManga(
                        url: snapshot.identifier.mangaKey,
                        sourceId: source
                    )
                else { continue }

                manga.favorite = true
                handler.updateMangaFavorite(manga: manga)

                guard !snapshot.categories.isEmpty else { continue }
                let links = snapshot.categories
                    .compactMap { categoriesByName[$0] }
                    .map { TachiyomiKit.MangaCategory.companion.create(manga: manga, category: $0) }
                handler.setMangaCategories(mangasCategories: links, mangas: [manga])
            }
        }

        await library?.reload()
        NotificationCenter.default.post(name: Notification.Name("updateLibrary"), object: nil)
    }

    // MARK: - Scheduled refresh

    static let refreshTaskIdentifier = "app.tachiyomiaz.library-refresh"

    /// Registers the background task the automatic library update setting drives.
    func register() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.refreshTaskIdentifier,
            using: nil
        ) { task in
            Task { @MainActor in
                await self.backgroundRefreshLibrary()
                self.scheduleLibraryRefresh()
                task.setTaskCompleted(success: true)
            }
        }
    }

    /// Asks the system to run a library refresh no sooner than the configured interval.
    func scheduleLibraryRefresh() {
        let interval = UserDefaults.standard.double(forKey: "Library.updateInterval")
        guard interval > 0 else { return }
        let request = BGAppRefreshTaskRequest(identifier: Self.refreshTaskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: interval)
        try? BGTaskScheduler.shared.submit(request)
    }
}
