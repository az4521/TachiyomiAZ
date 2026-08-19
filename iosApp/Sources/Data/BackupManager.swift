import BackgroundTasks
import ExtensionRunner
import Foundation
import TachiyomiKit
import UIKit

/// Backups, written as Tachiyomi's own `.tachibk` protobuf.
///
/// Format is the point: a backup taken here restores on TachiyomiAZ for Android and vice versa,
/// which an app-specific JSON blob would not. `TachibkBackupCodec` does the encoding, and every
/// read and write of the data itself goes through `CoreDataManager` -- so the tables backed up are
/// the shared ones both apps use.
///
/// Restore merges rather than replaces, and what "merge" means is not decided here: `:core-domain`
/// owns it, in `mergeBackupCategories`, `mergeBackupManga` and `mergeBackupChapters`. Sharing the
/// wire format only guarantees both apps *parse* a backup the same way -- those three functions are
/// where read state actually survives a restore, so a second implementation on this side would show
/// up as the two apps disagreeing about lost progress.
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
                        trackerId: TrackerSyncId.trackerId(for: track.sync_id) ?? String(track.sync_id),
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

    /// What a finished restore has to tell the user.
    private struct RestoreOutcome {
        var error: String?
        var missingSources: [String] = []
    }

    /// Restores a backup, merging it onto what is already stored.
    ///
    /// Nothing is cleared first, and every conflict is decided by `:core-domain` rather than here:
    /// stored metadata wins over the backup's, a title already in the library stays in it, and a
    /// chapter already read is never marked unread by an older backup.
    @discardableResult
    func restore(from backup: Backup) async -> Bool {
        let delegate = UIApplication.shared.delegate as? AppDelegate
        // A restore of a full library is long enough that a spinner says nothing useful. The
        // loading alert already carries a determinate bar; this is what drives it.
        delegate?.showLoadingIndicator(style: .progress)
        UIApplication.shared.isIdleTimerDisabled = true

        // The delegate is not Sendable, so the hop re-reads it on the main actor rather than
        // capturing it in a closure that leaves this one.
        let progress = RestoreProgress(total: Self.workUnits(in: backup)) { fraction in
            Task { @MainActor in
                (UIApplication.shared.delegate as? AppDelegate)?.indicatorProgress = fraction
            }
        }

        // The restore is one long run of synchronous SQLite writes. Off the main thread, or the
        // loading indicator just put up would never draw a frame.
        let installed = Set(SourceManager.shared.sources.map(\.key))
        let outcome = await Task.detached(priority: .userInitiated) {
            Self.performRestore(backup, installedSources: installed, progress: progress)
        }.value

        if outcome.error == nil {
            // Unread badges are cached, and a restore changes read state under them.
            LibraryBadgeCache.save(CoreDataManager.shared.libraryUnreadCounts(), kind: .unread)
        }

        NotificationCenter.default.post(name: .updateHistory, object: "backupRestore")
        NotificationCenter.default.post(name: .updateTrackers, object: nil)
        NotificationCenter.default.post(name: .updateCategories, object: nil)
        NotificationCenter.default.post(name: .updateLibrary, object: nil)

        await delegate?.hideLoadingIndicator()
        UIApplication.shared.isIdleTimerDisabled = false

        if let error = outcome.error {
            delegate?.presentAlert(
                title: NSLocalizedString("BACKUP_ERROR"),
                message: String(format: NSLocalizedString("BACKUP_ERROR_TEXT"), error)
            )
            return false
        }

        var message = NSLocalizedString("BACKUP_RESTORE_COMPLETE_TEXT")
        if !outcome.missingSources.isEmpty {
            message += "\n\n" + NSLocalizedString("MISSING_SOURCES_TEXT")
                + outcome.missingSources.map { "\n- \($0)" }.joined()
        }
        delegate?.presentAlert(
            title: NSLocalizedString("BACKUP_RESTORE_COMPLETE"),
            message: message
        )
        return true
    }

    /// Writes a backup into the shared database.
    ///
    /// One transaction for the whole thing: a restore that stopped halfway would leave library
    /// entries whose chapters never arrived, which is worse than not having restored at all.
    ///
    /// - Parameter installedSources: source keys that resolve to a loaded extension, passed in
    ///   because `SourceManager` is reachable from here but the answer must be sampled before the
    ///   work moves off the main thread.
    nonisolated private static func performRestore(
        _ backup: Backup,
        installedSources: Set<String>,
        progress: RestoreProgress
    ) -> RestoreOutcome {
        let handler = Database.handler
        var outcome = RestoreOutcome()

        handler.inTransaction {
            restoreCategories(backup.categories ?? [], handler: handler)

            // Library membership is a separate list from the manga themselves, and a backup carries
            // manga that are not in it -- anything with history but since removed from the library.
            // Those are restored too, as rows that are simply not favourite, which is what keeps
            // the history tab intact across a restore.
            let inLibrary = Set(
                (backup.library ?? []).map {
                    MangaIdentifier(sourceKey: $0.sourceId, mangaKey: $0.mangaId)
                }
            )
            let addedDates = Dictionary(
                (backup.library ?? []).map {
                    (MangaIdentifier(sourceKey: $0.sourceId, mangaKey: $0.mangaId), $0.dateAdded)
                },
                uniquingKeysWith: { first, _ in first }
            )

            let backupManga = backup.manga ?? []
            var rows: [MangaIdentifier: DbManga] = [:]
            for item in backupManga {
                let identifier = MangaIdentifier(sourceKey: item.sourceId, mangaKey: item.id)
                progress.advance()
                guard
                    let row = restoreManga(
                        item,
                        favorite: inLibrary.contains(identifier),
                        dateAdded: addedDates[identifier],
                        handler: handler
                    )
                else { continue }
                rows[identifier] = row
            }

            restoreChapters(backup, rows: rows, handler: handler, progress: progress)
            // The chapter pass is budgeted one unit per backup manga; anything skipped for an
            // uninstallable source never got one, so the bar catches up here rather than stalling.
            progress.advance(backupManga.count - rows.count)

            restoreCategoryMembership(
                backup.library ?? [],
                rows: rows,
                handler: handler,
                progress: progress
            )
            restoreTracks(backup.trackItems ?? [], rows: rows, progress: progress)

            outcome.missingSources = missingSourceNames(
                backup,
                restored: rows.keys,
                installedSources: installedSources
            )

            // A backup with titles in it that produced no rows at all did not partly work -- every
            // one had a source id this app could not read, which means the file is not what it
            // claims to be. Reported rather than passed off as a restore that changed nothing.
            if !backupManga.isEmpty && rows.isEmpty {
                outcome.error = NSLocalizedString("BACKUP_NO_RESTORABLE_ENTRIES")
            }
        }

        return outcome
    }

    /// Adds the categories the backup has and this database does not.
    ///
    /// Which ones those are comes from `mergeBackupCategories`, which matches by name: ids are
    /// assigned per database, so the same "Reading" category has a different id on every device and
    /// matching on id would duplicate every category on every restore.
    nonisolated private static func restoreCategories(
        _ categories: [BackupCategory],
        handler: IosDatabaseHandler
    ) {
        let backupCategories = categories.compactMap { item -> MangaCategory? in
            guard let title = item.title, !title.isEmpty else { return nil }
            return CategoryCompanion.shared.create(name: title)
        }
        guard !backupCategories.isEmpty else { return }

        let merge = BackupMangaMergeKt.mergeBackupCategories(
            backupCategories: backupCategories,
            dbCategories: handler.getCategories()
        )

        // `order` is a plain sort key, so new categories go after the existing ones rather than
        // taking the position they held on the other device and colliding with what is here.
        var order = Int32(handler.getCategories().count)
        for category in merge.toInsert {
            category.order = order
            order += 1
            handler.insertCategory(category: category)
        }
    }

    /// Writes one backup manga, merged onto the stored row if there is one.
    ///
    /// Returns the row so the chapter, category and tracker passes can address it by id without
    /// looking it up again.
    nonisolated private static func restoreManga(
        _ item: BackupManga,
        favorite: Bool,
        dateAdded: Date?,
        handler: IosDatabaseHandler
    ) -> DbManga? {
        guard let source = SourceIdentity.numericId(item.sourceId) else { return nil }

        let record = MangaImpl()
        record.source = source
        record.url = item.url ?? item.id
        record.title = item.title
        record.author = item.author
        record.artist = item.artist
        record.description_ = item.desc
        record.genre = item.tags?.joined(separator: ", ")
        record.thumbnail_url = item.cover
        record.status = Int32(item.status)
        record.viewer = Int32(item.viewer)
        record.chapter_flags = Int32(item.chapterFlags ?? 0)
        record.favorite = favorite
        record.update_strategy = item.neverUpdate == true
            ? UpdateStrategy.onlyFetchOnce
            : UpdateStrategy.alwaysUpdate
        record.date_added = dateAdded.map { Int64($0.timeIntervalSince1970 * 1000) } ?? 0
        // Left false deliberately: `initialized` means "the source has been asked for the full
        // details", and a backup is not that -- it carries whatever the other device happened to
        // have. False makes the next open fetch them, which costs one request and cannot lose
        // anything. `mergeBackupManga` ORs it, so a row already initialised here stays initialised.
        record.initialized = false

        if let stored = handler.getManga(url: record.url, sourceId: source) {
            BackupMangaMergeKt.mergeBackupManga(manga: record, dbManga: stored)
        }
        handler.insertManga(manga: record)
        return record
    }

    /// Restores chapters and the read state that goes with them.
    ///
    /// Read state is split across two lists in this format: the chapter carries its bookmark, and
    /// whether it was read and how far lives in `history`. They are put back together here before
    /// `mergeBackupChapters` sees them, because that is the shape it -- and the database -- expects.
    nonisolated private static func restoreChapters(
        _ backup: Backup,
        rows: [MangaIdentifier: DbManga],
        handler: IosDatabaseHandler,
        progress: RestoreProgress
    ) {
        let chaptersByManga = Dictionary(grouping: backup.chapters ?? []) {
            MangaIdentifier(sourceKey: $0.sourceId, mangaKey: $0.mangaId)
        }
        let historyByManga = Dictionary(grouping: backup.history ?? []) {
            MangaIdentifier(sourceKey: $0.sourceId, mangaKey: $0.mangaId)
        }

        for (identifier, manga) in rows {
            progress.advance()

            let backupChapters = chaptersByManga[identifier] ?? []
            let history = Dictionary(
                (historyByManga[identifier] ?? []).map { ($0.chapterId, $0) },
                uniquingKeysWith: { first, _ in first }
            )

            let chapters: [DbChapter] = backupChapters.map { item in
                let chapter = ChapterImpl()
                chapter.url = item.url ?? item.id
                chapter.name = item.title ?? item.id
                chapter.scanlator = item.scanlator
                chapter.chapter_number = item.chapter ?? -1
                chapter.source_order = Int32(item.sourceOrder)
                chapter.date_upload = item.dateUploaded
                    .map { Int64($0.timeIntervalSince1970 * 1000) } ?? 0
                // This format has no fetch date. Reusing the upload date keeps restored chapters
                // out of the updates feed; stamping them with now would announce a whole library
                // as brand new the moment a backup was restored.
                chapter.date_fetch = chapter.date_upload
                chapter.bookmark = item.bookmarked ?? false

                if let entry = history[item.id] {
                    chapter.read = entry.completed
                    // The backup stores the page the reader was showing, one-based; the column is
                    // the index it resumes from. `MihonBackupImporter` adds the one, this takes it
                    // back off.
                    chapter.last_page_read = Int32(max(0, (entry.progress ?? 0) - 1))
                }
                return chapter
            }

            if !chapters.isEmpty {
                // Which of these are new, which are updates, and which read state survives: all
                // three are `:core-domain`'s answer, not this file's.
                let merge = BackupChapterMergeKt.mergeBackupChapters(
                    manga: manga,
                    backupChapters: chapters,
                    dbChapters: handler.getChapters(manga: manga)
                )
                handler.insertChapters(chapters: merge.toInsert)
                // Writes read, bookmark and last_page_read only -- the same columns Android's
                // backup restore touches, so a restore cannot rewrite chapter metadata the source
                // owns.
                handler.updateChaptersBackup(chapters: merge.toUpdate)
            }

            // Runs even with no chapters in the backup: the title's chapters may already be stored
            // from a library refresh, and the timestamps still belong on them.
            restoreHistory(
                historyByManga[identifier] ?? [],
                manga: manga,
                handler: handler
            )
        }
    }

    /// Writes the read timestamps, once the chapters they point at exist.
    ///
    /// Rows are keyed by chapter id, so this runs after the chapter pass has given every new
    /// chapter one. The later of the two dates wins: the backup may be older than this device.
    nonisolated private static func restoreHistory(
        _ entries: [BackupHistory],
        manga: DbManga,
        handler: IosDatabaseHandler
    ) {
        guard !entries.isEmpty, let mangaId = manga.id?.int64Value else { return }

        let chapterIds = Dictionary(
            handler.getChapters(manga: manga).compactMap { chapter in
                chapter.id.map { (chapter.url, $0.int64Value) }
            },
            uniquingKeysWith: { first, _ in first }
        )
        let stored = Dictionary(
            handler.getHistoryByMangaId(mangaId: mangaId).map { ($0.chapter_id, $0) },
            uniquingKeysWith: { first, _ in first }
        )

        for entry in entries {
            guard let chapterId = chapterIds[entry.chapterId] else { continue }
            let read = Int64(entry.dateRead.timeIntervalSince1970 * 1000)
            let history = stored[chapterId] ?? HistoryImpl()
            history.chapter_id = chapterId
            history.last_read = max(history.last_read, read)
            // Upserts: updates the row when one exists for this chapter, inserts when it does not.
            handler.updateHistoryLastRead(history: history)
        }
    }

    /// Files restored titles into their categories.
    ///
    /// Additive, like the fork's restore: a title already filed under something keeps it. The
    /// backup names categories by title, and `restoreCategories` has already made sure each one
    /// exists, so this resolves them by name.
    nonisolated private static func restoreCategoryMembership(
        _ library: [BackupLibraryManga],
        rows: [MangaIdentifier: DbManga],
        handler: IosDatabaseHandler,
        progress: RestoreProgress
    ) {
        let categoriesByName = Dictionary(
            handler.getCategories().map { ($0.name, $0) },
            uniquingKeysWith: { first, _ in first }
        )

        for entry in library {
            progress.advance()
            let identifier = MangaIdentifier(sourceKey: entry.sourceId, mangaKey: entry.mangaId)
            guard
                let manga = rows[identifier],
                let wanted = entry.categories,
                !wanted.isEmpty
            else { continue }

            let existing = Set(handler.getCategoriesForManga(manga: manga).map(\.name))
            let links = wanted
                .filter { !existing.contains($0) }
                .compactMap { categoriesByName[$0] }
                .map { TachiyomiKit.MangaCategory.companion.create(manga: manga, category: $0) }
            guard !links.isEmpty else { continue }

            // Replaces this manga's memberships, so the existing ones have to be passed back in
            // alongside the new -- the shared call clears before it inserts.
            let keeping = handler.getCategoriesForManga(manga: manga)
                .map { TachiyomiKit.MangaCategory.companion.create(manga: manga, category: $0) }
            handler.setMangaCategories(mangasCategories: keeping + links, mangas: [manga])
        }
    }

    /// Relinks tracked titles.
    ///
    /// A link that is already there is left alone rather than rewritten: the stored one carries the
    /// progress this device has synced, and the backup's carries none.
    nonisolated private static func restoreTracks(
        _ items: [BackupTrackItem],
        rows: [MangaIdentifier: DbManga],
        progress: RestoreProgress
    ) {
        let manager = CoreDataManager.shared
        for item in items {
            progress.advance()
            let identifier = MangaIdentifier(sourceKey: item.sourceId, mangaKey: item.mangaId)
            guard
                rows[identifier] != nil,
                !manager.hasTrack(
                    trackerId: item.trackerId,
                    sourceId: item.sourceId,
                    mangaId: item.mangaId
                )
            else { continue }

            manager.createTrack(
                id: item.id,
                trackerId: item.trackerId,
                sourceId: item.sourceId,
                mangaId: item.mangaId,
                title: item.title,
                chapterOffset: item.chapterOffset ?? 0
            )
        }
    }

    /// How many steps a restore of this backup takes.
    ///
    /// Each manga is counted twice because it is walked twice -- once to write the title, once for
    /// its chapters and history -- and the chapter pass is much the slower of the two. Counting
    /// individual chapters instead would be more precise and less useful: the bar would sit at a
    /// few percent through every large title and then jump.
    nonisolated private static func workUnits(in backup: Backup) -> Int {
        (backup.manga?.count ?? 0) * 2
            + (backup.library?.count ?? 0)
            + (backup.trackItems?.count ?? 0)
    }

    /// Sources the restored titles need and this device does not have installed.
    ///
    /// Taken from the manga that were actually restored rather than only the backup's source list,
    /// because a backup written by this app carries no such list -- and a title whose source is
    /// missing is exactly the one the user needs to be told about.
    nonisolated private static func missingSourceNames(
        _ backup: Backup,
        restored: some Sequence<MangaIdentifier>,
        installedSources: Set<String>
    ) -> [String] {
        let declared = (backup.sources ?? []).map(\.id)
        let used = restored.map(\.sourceKey)
        return Set(declared + used)
            .filter { !installedSources.contains($0) }
            .map { SourceManager.shared.name(for: $0) ?? $0 }
            .sorted()
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

/// Counts a restore's steps and reports them to the progress bar.
///
/// Declared outside `BackupManager` so it does not inherit that type's main-actor isolation: the
/// restore counts from the background thread doing the writes, and only the report crosses back.
///
/// Reports are throttled to whole percentage points. A restore steps through every title in a
/// library, and a main-actor hop per step would cost more than the database writes do -- and the
/// bar cannot draw more than a hundred distinct positions anyway.
private final class RestoreProgress: @unchecked Sendable {
    private let total: Int
    private let report: @Sendable (Float) -> Void
    private var done = 0
    private var lastPercent = -1

    init(total: Int, report: @escaping @Sendable (Float) -> Void) {
        self.total = total
        self.report = report
    }

    func advance(_ steps: Int = 1) {
        guard total > 0, steps > 0 else { return }
        done = min(done + steps, total)
        let percent = done * 100 / total
        guard percent != lastPercent else { return }
        lastPercent = percent
        report(Float(done) / Float(total))
    }
}
