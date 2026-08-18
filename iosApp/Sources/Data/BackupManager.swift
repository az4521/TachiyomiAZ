import BackgroundTasks
import ExtensionRunner
import Foundation

/// Backups, written as Tachiyomi's own `.tachibk` protobuf.
///
/// Format is the point: a backup taken here restores on TachiyomiAZ for Android and vice versa,
/// which an app-specific JSON blob would not. `TachibkBackupCodec` does the encoding, and every
/// read and write of the data itself goes through `CoreDataManager` -- so the tables backed up are
/// the shared ones both apps use.
///
/// Restore is deliberately additive: it inserts what is missing and leaves existing rows alone,
/// rather than clearing the database first. `:core-domain` has `mergeBackupManga` and
/// `mergeBackupChapters` for the merge Android performs; wiring those in is the next step, and is
/// what would let a restore reconcile read state instead of skipping known titles.
@MainActor
final class BackupManager {
    static let shared = BackupManager()

    private init() {}

    static let directory: URL = {
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let directory = base.appendingPathComponent("Backups", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }()

    /// Every stored backup, newest first.
    static var backupUrls: [URL] {
        let contents = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.contentModificationDateKey]
        )) ?? []
        return contents
            .filter { $0.pathExtension == "tachibk" || $0.pathExtension == "json" }
            .sorted { lhs, rhs in
                let l = (try? lhs.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
                let r = (try? rhs.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
                return l > r
            }
    }

    // MARK: - Reading

    func loadBackup(from url: URL) -> Backup? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? TachibkBackupCodec.decode(from: data)
    }

    // MARK: - Writing

    /// What a backup includes, as the create screen offers it.
    struct BackupOptions {
        var automatic: Bool = false
        var libraryEntries: Bool = true
        var history: Bool = true
        var chapters: Bool = true
        var tracking: Bool = true
        var readingSessions: Bool = false
        var updates: Bool = false
        var categories: Bool = true
        var settings: Bool = false
        var sourceLists: Bool = false
        var sensitiveSettings: Bool = false
    }

    /// Snapshots the library, its chapters, history, categories and tracks.
    @discardableResult
    func saveNewBackup(name: String = "", options: BackupOptions = .init()) -> Bool {
        let backup = makeBackup(name: name.isEmpty ? nil : name, options: options)
        guard let data = try? TachibkBackupCodec.encode(backup) else { return false }

        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd_HH-mm-ss"
        let filename = "backup_\(formatter.string(from: Date())).tachibk"
        let url = Self.directory.appendingPathComponent(filename)
        do {
            try data.write(to: url)
            return true
        } catch {
            LogManager.logger.error("Failed to write backup: \(error)")
            return false
        }
    }

    private func makeBackup(name: String?, options: BackupOptions) -> Backup {
        let coreData = CoreDataManager.shared
        var library: [BackupLibraryManga] = []
        var manga: [BackupManga] = []
        var chapters: [BackupChapter] = []
        var history: [BackupHistory] = []
        var tracks: [BackupTrackItem] = []

        for object in (options.libraryEntries ? coreData.getLibraryManga() : []) {
            guard let entry = object.manga else { continue }
            let sourceId = entry.sourceId
            let mangaId = entry.id

            library.append(
                BackupLibraryManga(
                    lastOpened: object.lastOpened ?? .distantPast,
                    lastUpdated: object.lastUpdated ?? .distantPast,
                    lastUpdatedChapters: object.lastUpdatedChapters,
                    lastChapter: object.lastChapter,
                    lastRead: object.lastRead,
                    dateAdded: object.dateAdded ?? .distantPast,
                    categories: coreData.getCategories(sourceId: sourceId, mangaId: mangaId).map(\.name),
                    mangaId: mangaId,
                    sourceId: sourceId
                )
            )
            manga.append(
                BackupManga(
                    id: mangaId,
                    sourceId: sourceId,
                    title: entry.title ?? mangaId,
                    author: entry.author,
                    tags: entry.tags,
                    cover: entry.cover,
                    url: entry.url
                )
            )
            for chapter in (options.chapters ? coreData.getChapters(sourceId: sourceId, mangaId: mangaId, context: nil) : []) {
                chapters.append(
                    BackupChapter(
                        sourceId: sourceId,
                        mangaId: mangaId,
                        id: chapter.id,
                        title: chapter.title,
                        scanlator: chapter.scanlator,
                        dateUploaded: chapter.dateUploaded,
                        bookmarked: chapter.bookmarked,
                        sourceOrder: chapter.sourceOrder
                    )
                )
            }
            for (chapterId, progress) in (options.history ? coreData.readingHistorySnapshot(sourceId: sourceId, mangaId: mangaId) : [:]) {
                history.append(
                    BackupHistory(
                        dateRead: Date(timeIntervalSince1970: TimeInterval(progress.date)),
                        sourceId: sourceId,
                        chapterId: chapterId,
                        mangaId: mangaId,
                        progress: progress.page < 0 ? nil : progress.page,
                        completed: progress.page < 0
                    )
                )
            }
            for track in (options.tracking ? coreData.getTracks(sourceId: sourceId, mangaId: mangaId) : []) {
                tracks.append(
                    BackupTrackItem(
                        id: String(track.media_id),
                        trackerId: TrackerService(rawValue: track.sync_id)?.title ?? String(track.sync_id),
                        mangaId: mangaId,
                        sourceId: sourceId,
                        title: track.title,
                        chapterOffset: 0
                    )
                )
            }
        }

        return Backup(
            library: library,
            history: history,
            manga: manga,
            chapters: chapters,
            trackItems: tracks,
            categories: options.categories
                ? coreData.getCategoryTitles().map { BackupCategory(title: $0) }
                : [],
            date: Date(),
            name: name
        )
    }

    // MARK: - Restoring

    func restore(from backup: Backup) async {
        let coreData = CoreDataManager.shared

        for category in backup.categories ?? [] where category.title != nil {
            if !coreData.hasCategory(title: category.title!) {
                coreData.createCategory(title: category.title!)
            }
        }

        for entry in backup.library ?? [] {
            guard let stored = backup.manga?.first(where: {
                $0.id == entry.mangaId && $0.sourceId == entry.sourceId
            }) else { continue }

            let runnerManga = ExtensionRunner.Manga(
                sourceKey: entry.sourceId,
                key: entry.mangaId,
                title: stored.title ?? entry.mangaId,
                cover: stored.cover
            )
            await coreData.cacheMangaDetails(runnerManga, includeChapters: false)

            if let manga = coreData.sharedManga(sourceId: entry.sourceId, mangaId: entry.mangaId) {
                manga.favorite = true
                Database.handler.updateMangaFavorite(manga: manga)
            }

            if let categories = entry.categories, !categories.isEmpty {
                await coreData.setMangaCategories(
                    sourceId: entry.sourceId,
                    mangaId: entry.mangaId,
                    categories: categories
                )
            }
        }

        NotificationCenter.default.post(name: Notification.Name("updateLibrary"), object: nil)
    }

    // MARK: - Managing files

    func importBackup(from url: URL) -> Bool {
        let secured = url.startAccessingSecurityScopedResource()
        defer { if secured { url.stopAccessingSecurityScopedResource() } }
        let destination = Self.directory.appendingPathComponent(url.lastPathComponent)
        do {
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.copyItem(at: url, to: destination)
            return true
        } catch {
            LogManager.logger.error("Failed to import backup: \(error)")
            return false
        }
    }

    func removeBackup(url: URL) {
        try? FileManager.default.removeItem(at: url)
    }

    func renameBackup(url: URL, name: String) {
        guard var backup = loadBackup(from: url) else { return }
        backup.name = name
        guard let data = try? TachibkBackupCodec.encode(backup) else { return }
        try? data.write(to: url)
    }

    // MARK: - Scheduling

    static let taskIdentifier = "app.tachiyomiaz.backup"

    /// Registers the background task the automatic-backup setting drives.
    func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.taskIdentifier, using: nil) { task in
            Task { @MainActor in
                    _ = self.saveNewBackup(
                    name: NSLocalizedString("AUTOMATIC"),
                    options: .init(automatic: true)
                )
                self.scheduleAutoBackup()
                task.setTaskCompleted(success: true)
            }
        }
    }

    func scheduleAutoBackup() {
        let interval = UserDefaults.standard.double(forKey: "Backups.automaticInterval")
        guard interval > 0 else { return }
        let request = BGProcessingTaskRequest(identifier: Self.taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: interval)
        try? BGTaskScheduler.shared.submit(request)
    }
}
