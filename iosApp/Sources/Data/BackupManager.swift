import BackgroundTasks
import ExtensionRunner
import Foundation
import TachiyomiKit
import UIKit

/// Backups, written as Tachiyomi's own `.tachibk` protobuf.
///
/// Format is the point: a backup taken here restores on TachiyomiAZ for Android and vice versa,
/// which an app-specific JSON blob would not. `TachibkBackupCodec` does the encoding, and every
/// read and write of the data itself goes through `SharedDataStore` -- so the tables backed up are
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
            LibraryBadgeCache.save(SharedDataStore.shared.libraryUnreadCounts(), kind: .unread)
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
        let report = BackupRestorer.shared.restoreBackupNoFetch(
            db: handler,
            backup: backup,
            canRestoreSource: { sourceId in
                KotlinBoolean(bool: sourceId.int64Value != 0)
            },
            isLogged: { syncId in
                guard let id = TrackerSyncId.trackerId(for: syncId.int32Value) else {
                    return KotlinBoolean(bool: false)
                }
                return KotlinBoolean(bool: TrackerManager.getTracker(id: id)?.isLoggedIn ?? false)
            },
            onProgress: { completed, total in
                progress.update(completed: Int(completed.int32Value), total: Int(total.int32Value))
            }
        )

        let restored = report.restoredSourceIds.map(\.int64Value)
        outcome.missingSources = missingSourceNames(
            backup,
            restored: restored,
            installedSources: installedSources
        )

        if !backup.backupManga.isEmpty && report.restoredCount == 0 {
            outcome.error = NSLocalizedString("BACKUP_NO_RESTORABLE_ENTRIES")
        }
        return outcome
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
            .filter { $0 != MergedSourceSupport.sourceKey }
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

    func update(completed: Int, total: Int) {
        guard total > 0, completed >= 0 else { return }
        done = min(completed, total)
        let percent = done * 100 / total
        guard percent != lastPercent else { return }
        lastPercent = percent
        report(Float(done) / Float(total))
    }
}
