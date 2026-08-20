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

    func loadBackup(from url: URL) -> TachibkBackupCodec.Decoded? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? TachibkBackupCodec.decode(from: data, url: url)
    }

    // MARK: - Writing

    /// What a backup includes, as the create screen offers it.
    ///
    /// The four that describe library contents are handed to the shared builder as its bitmask, so
    /// the two apps' backup screens offer the same choices rather than each naming them their own
    /// way. The rest are this app's own and travel in `BackupState`.
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

        /// - Note: `libraryEntries` has no bit. A backup *is* the library, so switching it off
        ///   leaves nothing for the other options to apply to; the builder always walks favourites.
        var flags: Int32 {
            var value: Int32 = 0
            if categories { value |= TachiyomiKit.BackupOptions.shared.CATEGORY }
            if chapters { value |= TachiyomiKit.BackupOptions.shared.CHAPTER }
            if history { value |= TachiyomiKit.BackupOptions.shared.HISTORY }
            if tracking { value |= TachiyomiKit.BackupOptions.shared.TRACK }
            return value
        }
    }

    /// Snapshots the library, its chapters, history, categories and tracks.
    @discardableResult
    func saveNewBackup(name: String = "", options: BackupOptions = .init()) -> Bool {
        let backup = makeBackup(options: options)
        let state = BackupState(name: name.isEmpty ? nil : name, date: Date(), automatic: options.automatic)
        guard let data = try? TachibkBackupCodec.encode(backup, state: state) else { return false }

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

    /// Builds the backup's contents.
    ///
    /// This is `BackupBuilder` in `:core-domain` -- the same function the other app builds its
    /// backups with -- so a backup taken here contains what one taken there would, from the same
    /// library. What used to be here was a second implementation that walked the same tables by
    /// hand, and it was quietly lossy: it filled seven fields of each entry, so every backup this
    /// app wrote carried no description, no artist, status 0, reading mode 0 and chapter_flags 0.
    /// The shared builder copies whole rows, so a column added to the schema travels without
    /// anyone remembering to add it here.
    private func makeBackup(options: BackupOptions) -> TachiyomiKit.Backup {
        BackupBuilder.shared.build(
            db: Database.handler,
            flags: options.flags,
            // Source names come from the installed extensions, which is this app's to answer. The
            // database stores the numeric id; the extensions are keyed by string.
            sources: { mangas in
                var ids = Set<Int64>()
                for manga in mangas {
                    ids.insert(manga.source)
                }
                return ids.map { id -> BackupSource in
                    let key = SourceIdentity.key(for: id)
                    return BackupSource(
                        name: SourceManager.shared.name(for: key) ?? "",
                        sourceId: id
                    )
                }
            },
            savedSearches: [],
            flatMetadata: { _ in nil }
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
    func restore(from backup: TachiyomiKit.Backup) async -> Bool {
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
        _ backup: TachiyomiKit.Backup,
        installedSources: Set<String>,
        progress: RestoreProgress
    ) -> RestoreOutcome {
        let handler = Database.handler
        var outcome = RestoreOutcome()

        handler.inTransaction {
            restoreCategories(backup.backupCategories, handler: handler)

            var restored: [Int64] = []
            for entry in backup.backupManga {
                progress.advance()
                guard let manga = restoreManga(entry, handler: handler) else { continue }
                restored.append(entry.source)

                restoreChapters(entry, manga: manga, handler: handler)
                restoreCategoryMembership(
                    entry,
                    manga: manga,
                    backupCategories: backup.backupCategories,
                    handler: handler
                )
                restoreTracks(entry, manga: manga, handler: handler)
                progress.advance()
            }

            outcome.missingSources = missingSourceNames(
                backup,
                restored: restored,
                installedSources: installedSources
            )

            // A backup with titles in it that produced no rows at all did not partly work -- every
            // one had a source id this app could not read, which means the file is not what it
            // claims to be. Reported rather than passed off as a restore that changed nothing.
            if !backup.backupManga.isEmpty && restored.isEmpty {
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
        _ categories: [TachiyomiKit.BackupCategory],
        handler: IosDatabaseHandler
    ) {
        BackupRestorer.shared.restoreCategories(db: handler, backupCategories: categories)
    }

    /// Writes one backup entry, merged onto the stored row if there is one.
    ///
    /// The row is returned so the chapter, category and tracker passes can address it without
    /// looking it up again. `getMangaImpl` is the shared model's own conversion -- the columns are
    /// no longer copied across by hand here, which is what used to lose `viewer` and
    /// `chapter_flags` on the way in.
    nonisolated private static func restoreManga(
        _ entry: TachiyomiKit.BackupManga,
        handler: IosDatabaseHandler
    ) -> DbManga? {
        guard SourceIdentity.key(for: entry.source) != nil || entry.source != 0 else { return nil }

        // `restoreMangaNoFetch` is the other app's no-fetch restore path, which is what this is:
        // nothing here asks the source for anything. `initialized` used to be forced false here so
        // the next open would refetch details; that is not what the other app does, and a
        // difference in a shared table is worth less than the two agreeing.
        let record = entry.getMangaImpl()
        let stored = handler.getManga(url: record.url, sourceId: record.source) ?? MangaImpl()
        BackupRestorer.shared.restoreMangaNoFetch(db: handler, manga: record, dbManga: stored)
        return handler.getManga(url: record.url, sourceId: record.source)
    }

    /// Restores an entry's chapters and the read state that goes with them.
    ///
    /// Read state arrives in two places -- the chapter carries its bookmark and page, the history
    /// list carries when it was last read -- and they are put back together before
    /// `mergeBackupChapters` sees them, because that is the shape it and the database expect.
    nonisolated private static func restoreChapters(
        _ entry: TachiyomiKit.BackupManga,
        manga: DbManga,
        handler: IosDatabaseHandler
    ) {
        let chapters = entry.getChaptersImpl()
        if !chapters.isEmpty {
            BackupRestorer.shared.restoreChaptersForManga(db: handler, manga: manga, chapters: chapters)
        }

        // Runs even with no chapters in the backup: the title's chapters may already be stored from
        // a library refresh, and the timestamps still belong on them.
        BackupRestorer.shared.restoreHistoryForManga(db: handler, history: entry.history)
    }

    /// Puts the entry back in its categories, through the shared rule.
    ///
    /// Resolving membership takes two hops -- the stored order names a category *in the backup*,
    /// and that category's name finds the one in this database -- and the one-hop version that
    /// looks equivalent files titles under the wrong categories as soon as a restore adds one.
    /// That is `BackupRestorer`'s to know, not this file's.
    nonisolated private static func restoreCategoryMembership(
        _ entry: TachiyomiKit.BackupManga,
        manga: DbManga,
        backupCategories: [TachiyomiKit.BackupCategory],
        handler: IosDatabaseHandler
    ) {
        BackupRestorer.shared.restoreCategoriesForManga(
            db: handler,
            manga: manga,
            categories: entry.categories,
            backupCategories: backupCategories
        )
    }

    /// Relinks tracked titles.
    ///
    /// A link that is already there is left alone rather than rewritten: the stored one carries the
    /// progress this device has synced, and the backup's carries what the other device had.
    nonisolated private static func restoreTracks(
        _ entry: TachiyomiKit.BackupManga,
        manga: DbManga,
        handler: IosDatabaseHandler
    ) {
        guard !entry.tracking.isEmpty else { return }

        // The other app's behaviour, which this now follows in two ways it did not: a link already
        // here takes the backup's ids rather than being skipped, and its progress moves to
        // whichever is further along. Links for services that are not signed in are left out --
        // this app used to restore them all, which put rows in the database pointing at accounts
        // it could not reach.
        BackupRestorer.shared.restoreTrackForManga(
            db: handler,
            manga: manga,
            tracks: entry.getTrackingImpl(),
            // Kotlin's `Boolean` comes back boxed through a lambda.
            isLogged: { syncId in
                guard let id = TrackerSyncId.trackerId(for: syncId.int32Value) else {
                    return KotlinBoolean(bool: false)
                }
                return KotlinBoolean(bool: TrackerManager.getTracker(id: id)?.isLoggedIn ?? false)
            }
        )
    }

    /// How many steps a restore of this backup takes.
    ///
    /// Each entry is counted twice because it is walked twice -- once to write the title, once for
    /// everything hanging off it. Counting individual chapters instead would be more precise and
    /// less useful: the bar would sit at a few percent through every large title and then jump.
    nonisolated private static func workUnits(in backup: TachiyomiKit.Backup) -> Int {
        backup.backupManga.count * 2
    }

    nonisolated private static func missingSourceNames(
        _ backup: TachiyomiKit.Backup,
        restored: [Int64],
        installedSources: Set<String>
    ) -> [String] {
        let declared = backup.backupSources.map(\.sourceId)
        return Set(declared + restored)
            .map { SourceIdentity.key(for: $0) }
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
        guard var decoded = loadBackup(from: url) else { return }
        decoded.state.name = name
        guard let data = try? TachibkBackupCodec.encode(decoded.backup, state: decoded.state) else { return }
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
