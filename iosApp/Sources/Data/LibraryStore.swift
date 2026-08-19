import ExtensionRunner
import Foundation
import TachiJVMRunner
import TachiyomiKit

/// `Category` alone is ambiguous -- the name exists in more than one module in scope -- so the
/// shared one is aliased once here and used everywhere in the app.
typealias MangaCategory = TachiyomiKit.Category

/// `Manga` and `Chapter` exist in both TachiyomiKit (the shared database models) and
/// ExtensionRunner (the vendored UI's view models), so anything naming them has to say which. The
/// shared ones are the database's; the runner's are what the UI renders.
typealias DbManga = TachiyomiKit.Manga
typealias DbChapter = TachiyomiKit.Chapter

/// Supplies the platform work `syncChaptersWithSource` delegates.
///
/// Kotlin gives every member except `now()` a default, but those defaults do not survive into the
/// Objective-C protocol -- all five arrive `@required` -- so each is implemented here, matching the
/// Kotlin default in every case but the clock. Downloads are not built yet, so the two
/// download-related members are honest no-ops rather than guesses.
final class IOSChapterSyncPlatform: NSObject, ChapterSyncPlatform {
    func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    func prepareNewChapter(chapter: any SChapter, manga: any DbManga) {
        // HttpSource does this on Android to strip the manga title out of chapter names. On iOS
        // the extension has already normalised the name before it crossed the JVM boundary.
    }

    func isChapterDownloaded(chapter: any DbChapter, manga: any DbManga) -> Bool {
        DownloadManager.shared.getDownloadStatus(
            for: ChapterIdentifier(
                sourceKey: manga.legacySourceId,
                mangaKey: manga.url,
                chapterKey: chapter.url
            )
        ) == .finished
    }

    /// Nothing to move.
    ///
    /// On Android a download lives in a directory named after the chapter, so a source renaming a
    /// chapter orphans it and the sync has to move it. Here `DownloadCache` keys directories by
    /// the chapter's key -- its url -- which a rename does not touch, so the download stays
    /// attached on its own. Empty because there is genuinely no work, not because it is unfinished.
    func renameDownloadedChapter(manga: any DbManga, from: any DbChapter, to: any DbChapter) {}

    /// Only the EH/EXH sources carry progress over, and those are deliberately out of scope.
    func carriesOverReadingProgress(manga: any DbManga) -> Bool { false }
}

/// The app's view of the shared database.
///
/// Every query goes through `IosDatabaseHandler`, which is the same surface the Android app uses --
/// the mixins in `:core-database` are default methods, so `getLibraryMangas()` here runs the
/// identical SQL against tables generated from the identical `.sq` files.
///
/// Rules are not reimplemented here. Which entries a refresh touches comes from
/// `selectLibraryMangaToUpdate`, and chapter diffing from `syncChaptersWithSource`, both in
/// `:core-domain`. Duplicating either would let the two apps disagree about a shared database.
@MainActor
final class LibraryStore: ObservableObject {
    @Published private(set) var categories: [MangaCategory] = []
    @Published private(set) var manga: [LibraryManga] = []
    @Published private(set) var isLoading = true
    @Published private(set) var refreshProgress: (done: Int, total: Int)?
    @Published private(set) var lastError: String?

    /// Non-optional now that the handle is shared, so the `guard let handler` dance below is gone.
    var handler: IosDatabaseHandler { Database.handler }
    private let syncPlatform = IOSChapterSyncPlatform()

    func load() async {
        isLoading = true
        defer { isLoading = false }

        // No category is seeded. "Uncategorized" is not a row -- it is the library screen's name
        // for entries belonging to no category -- so creating a "Default" row alongside it produced
        // two tabs meaning the same thing, one of which nothing was ever filed under.
        categories = handler.getCategories()
        manga = handler.getLibraryMangas()
    }

    func reload() async {
        categories = handler.getCategories()
        manga = handler.getLibraryMangas()
    }

    // MARK: - Membership

    func contains(url: String, sourceId: Int64) -> Bool {
        return handler.getManga(url: url, sourceId: sourceId)?.favorite == true
    }

    func add(manga sourceManga: TachiyomiXManga, source: SourceDescriptor) async {

        let existing = handler.getManga(url: sourceManga.url, sourceId: source.id)
        let record = existing ?? MangaImpl()
        record.url = sourceManga.url
        record.title = sourceManga.title
        record.source = source.id
        record.thumbnail_url = sourceManga.thumbnailURL
        record.author = sourceManga.author
        record.artist = sourceManga.artist
        record.description_ = sourceManga.description
        record.genre = sourceManga.genre
        record.status = Int32(sourceManga.status)
        record.favorite = true
        record.initialized = true
        if existing == nil {
            record.date_added = Int64(Date().timeIntervalSince1970 * 1000)
            handler.insertManga(manga: record)
        } else {
            handler.updateMangaFavorite(manga: record)
        }
        await reload()
    }

    /// Adds a manga described by the vendored UI's model type.
    ///
    /// Separate from `add(manga:source:)` because that one takes the JVM client's TachiyomiXManga.
    /// Both funnel into the same shared insert, so there is one write path regardless of which
    /// model the caller happens to hold.
    /// Adds a title to the library.
    ///
    /// `reload` re-runs the whole library query, which aggregates over every chapter row, so a
    /// caller adding many titles at once should pass `false` and reload once at the end -- the
    /// same reason `remove(urls:)` exists.
    func addFromRunner(_ manga: ExtensionRunner.Manga, sourceId: Int64, reload: Bool = true) async {
        let existing = handler.getManga(url: manga.key, sourceId: sourceId)
        let record = existing ?? MangaImpl()
        record.url = manga.key
        record.title = manga.title
        record.source = sourceId
        record.thumbnail_url = manga.cover
        record.author = manga.authors?.joined(separator: ", ")
        record.artist = manga.artists?.joined(separator: ", ")
        record.description_ = manga.description
        record.genre = manga.tags?.joined(separator: ", ")
        record.favorite = true
        record.initialized = true
        if existing == nil {
            record.date_added = Int64(Date().timeIntervalSince1970 * 1000)
            handler.insertManga(manga: record)
        } else {
            handler.updateMangaFavorite(manga: record)
        }
        if reload {
            await self.reload()
        }
    }

    func remove(url: String, sourceId: Int64) async {
        guard let record = handler.getManga(url: url, sourceId: sourceId) else { return }
        record.favorite = false
        handler.updateMangaFavorite(manga: record)
        await reload()
    }

    func remove(_ entry: LibraryManga) async {
        await remove(url: entry.url, sourceId: entry.source)
    }

    /// Drops many titles from the library at once.
    ///
    /// Removing a thousand titles by calling `remove` a thousand times meant a thousand separate
    /// write transactions and, worse, a thousand `reload()` calls -- and a reload re-runs the whole
    /// library query, which aggregates over every chapter row in the database. That is quadratic
    /// work on the main actor, which is why a large selection froze the app until the watchdog
    /// killed it. One transaction, one reload.
    func remove(urls: [(url: String, sourceId: Int64)]) async {
        guard !urls.isEmpty else { return }
        let handler = self.handler
        handler.inTransaction {
            for entry in urls {
                guard let record = handler.getManga(url: entry.url, sourceId: entry.sourceId) else {
                    continue
                }
                record.favorite = false
                handler.updateMangaFavorite(manga: record)
            }
        }
        await reload()
    }

    // MARK: - Categories

    func addCategory(named name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        // Category.create is the shared factory; it assigns the next order itself.
        let category = CategoryCompanion.shared.create(name: trimmed)
        category.order = Int32(handler.getCategories().count)
        handler.insertCategory(category: category)
        await reload()
    }

    func renameCategory(_ category: MangaCategory, to name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        category.name = trimmed
        // insertCategory upserts on the primary key, so this is the rename path too.
        handler.insertCategory(category: category)
        await reload()
    }

    func deleteCategory(_ category: MangaCategory) async {
        handler.deleteCategory(category: category)
        await reload()
    }

    // MARK: - Refresh

    /// Refreshes one category, or the whole library when `categoryId` is nil.
    ///
    /// Which entries are touched is decided by `selectLibraryMangaToUpdate` in `:core-domain`,
    /// given the user's settings -- so "excluded categories" and "skip completed" mean exactly what
    /// they mean on Android.
    /// - Parameter onProgress: called as each entry finishes, so the caller can drive a progress
    ///   view. Reported here rather than only published, because the refresh runs off the screen
    ///   that shows it.
    /// The library entries a refresh would touch.
    ///
    /// Shared with the "would be refreshed" library filter, so what the filter shows and what a
    /// refresh does cannot drift apart -- the point of the filter is to be believable.
    ///
    /// All three skip filters are the shared rule's, so this app and the Android one select the
    /// same set. The unread and not-started filters used to be applied afterwards, with a chapter
    /// query per library entry to find out what had been read; the library query counts both now.
    func refreshTargets(categoryId: Int32? = nil, settings: AppSettings) -> [LibraryManga] {
        LibraryUpdateSelectionKt.selectLibraryMangaToUpdate(
            library: handler.getLibraryMangas(),
            categoryId: categoryId ?? LibraryUpdateSelectionKt.ALL_CATEGORIES,
            categoriesToUpdate: settings.updateCategories.map { KotlinInt(int: $0) },
            excludeCompleted: settings.skipCompleted,
            excludeUnread: settings.skipUnread,
            excludeNotStarted: settings.skipNotStarted
        )
    }

    func refresh(
        categoryId: Int32?,
        runtime: SourceRuntime,
        settings: AppSettings,
        onProgress: ((Int, Int) -> Void)? = nil
    ) async {
        lastError = nil

        let targets = refreshTargets(categoryId: categoryId, settings: settings)
        guard !targets.isEmpty else { return }

        refreshProgress = (0, targets.count)
        defer { refreshProgress = nil }

        // A refresh that skips everything looks exactly like a refresh that worked: the bar runs to
        // the end in a second and nothing changes. These count what actually happened so the
        // outcome can be stated rather than guessed at.
        var fetched = 0
        var missingSource = 0
        var failed = 0
        var firstFailure: String?
        // Which sources, not just how many. Knowing "188 skipped" says something is wrong; knowing
        // they are all source 2499283573021220255 says what.
        var missingSourceIds = Set<Int64>()
        var newChapters: [NotificationManager.NewChaptersSummary] = []
        var newlyDownloadable: [(manga: LibraryManga, chapters: [DbChapter])] = []

        // One lookup for the whole run. `sources.first(where:)` inside the loop is a linear scan
        // per entry, which on a thousand-title library is a scan per title.
        let sourcesById = Dictionary(
            runtime.sources.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )

        for (index, entry) in targets.enumerated() {
            refreshProgress = (index, targets.count)
            onProgress?(index, targets.count)
            guard let source = sourcesById[entry.source] else {
                // The extension providing this title is not installed, so there is nothing to ask.
                missingSource += 1
                missingSourceIds.insert(entry.source)
                continue
            }
            do {
                let update = try await runtime.mangaDetails(
                    source,
                    url: entry.url,
                    title: entry.title,
                    // memo is the extension's own opaque state and is a Kotlin JsonObject here,
                    // not a string. Sources treat it as optional, so a refresh sends none.
                    memo: nil
                )
                let sourceChapters: [SChapter] = update.chapters.map { chapter in
                    let s = SChapterImpl()
                    s.url = chapter.url
                    s.name = chapter.name
                    s.scanlator = chapter.scanlator
                    s.date_upload = chapter.dateUpload
                    if let number = chapter.chapterNumber { s.chapter_number = number }
                    return s
                }
                // A source that returned nothing has nothing to diff. Counted as a failure rather
                // than passed on: the shared sync treats an empty list as an error, and calling it
                // anyway is what crashed a whole refresh the moment one dead source came up.
                guard !sourceChapters.isEmpty else {
                    failed += 1
                    if firstFailure == nil {
                        firstFailure = "\(entry.title): the source returned no chapters"
                    }
                    continue
                }

                // Shared diffing: which chapters are new, deleted, or silently renamed.
                let result = try ChapterSyncKt.syncChaptersWithSource(
                    db: handler,
                    rawSourceChapters: sourceChapters,
                    manga: entry,
                    platform: syncPlatform
                )
                fetched += 1

                // What the shared diff considered genuinely new, which is what a "new chapters"
                // notification should count. A chapter the source renamed, or deleted and re-added
                // under another url, is not in here -- nothing changed for the reader.
                let added = (result.first as? [DbChapter]) ?? []

                // Whether these should download is the shared rule's answer, not this app's: it
                // weighs the library membership, the setting and the chosen categories the same
                // way the Android app does. This app asked nobody and downloaded nothing.
                if !added.isEmpty,
                   MangaExtensionsKt.shouldDownloadNewChapters(
                       entry,
                       db: handler,
                       prefs: DownloadPreferencesBridge.shared
                   ) {
                    newlyDownloadable.append((entry, added))
                }

                if !added.isEmpty {
                    newChapters.append(
                        NotificationManager.NewChaptersSummary(
                            mangaIdentifier: MangaIdentifier(
                                sourceKey: entry.legacySourceId,
                                mangaKey: entry.url
                            ),
                            mangaTitle: entry.title,
                            chapterCount: added.count
                        )
                    )
                }
            } catch {
                failed += 1
                // Keep the first, not the last: a run where every request fails the same way was
                // being reported by whichever title happened to come last.
                if firstFailure == nil {
                    firstFailure = "\(entry.title): \(error.localizedDescription)"
                }
            }
        }

        let summary = RefreshSummary(
            selected: targets.count,
            fetched: fetched,
            missingSource: missingSource,
            failed: failed,
            firstFailure: firstFailure,
            missingSourceIds: missingSourceIds.sorted(),
            newChapters: newChapters
        )
        lastSummary = summary
        lastError = summary.isComplete ? nil : summary.description

        // Queued after the loop rather than inside it, so a refresh is not interleaving network
        // requests for pages with the ones it is still making for chapter lists.
        for entry in newlyDownloadable {
            await DownloadManager.shared.download(
                manga: entry.manga.toNewManga(),
                chapters: entry.chapters.map { $0.toNewChapter() }
            )
        }

        // Always logged, so Settings -> Logs has a record even when the run looked fine. A refresh
        // that skips everything takes about as long as one that has nothing to do, and the two are
        // indistinguishable from the outside.
        LogManager.logger.log("Library refresh: \(summary.description)")
        if !summary.missingSourceIds.isEmpty {
            let loaded = sourcesById.keys.sorted().map(String.init).joined(separator: ", ")
            LogManager.logger.log(
                "Library refresh: no loaded source for ids "
                    + summary.missingSourceIds.map(String.init).joined(separator: ", ")
                    + " -- loaded ids are [\(loaded)]"
            )
        }

        await reload()
    }

    /// What a refresh run actually did.
    ///
    /// Every count is here rather than a single error string, because the interesting cases are
    /// partial: a refresh that fetched 12 of 200 has a problem, and reporting only the last error
    /// hides that it happened 188 times.
    struct RefreshSummary {
        let selected: Int
        let fetched: Int
        let missingSource: Int
        let failed: Int
        let firstFailure: String?
        let missingSourceIds: [Int64]

        /// Titles that gained chapters, for the new-chapter notifications.
        let newChapters: [NotificationManager.NewChaptersSummary]

        /// Whether every selected title was actually fetched.
        var isComplete: Bool { fetched == selected }

        var newChapterCount: Int { newChapters.reduce(0) { $0 + $1.chapterCount } }

        /// The line a finished background refresh shows in its completion notification.
        var completionSummary: String {
            if newChapterCount > 0 {
                return String(
                    format: NSLocalizedString("LIBRARY_UPDATE_NEW_CHAPTERS_FORMAT"),
                    newChapterCount,
                    fetched,
                    selected
                )
            }
            if failed > 0 || missingSource > 0 {
                return String(
                    format: NSLocalizedString("LIBRARY_UPDATE_FAILURES_FORMAT"),
                    fetched,
                    selected,
                    failed + missingSource
                )
            }
            return String(format: NSLocalizedString("LIBRARY_UPDATE_COMPLETE_FORMAT"), selected)
        }

        var description: String {
            var parts = ["\(fetched)/\(selected) fetched"]
            if missingSource > 0 {
                var line = "\(missingSource) with no loaded source"
                // Named, up to a point -- a library spanning thirty dead sources should not put
                // thirty ids in an alert.
                if !missingSourceIds.isEmpty {
                    let shown = missingSourceIds.prefix(3).map(String.init).joined(separator: ", ")
                    let more = missingSourceIds.count > 3 ? " and \(missingSourceIds.count - 3) more" : ""
                    line += " (\(shown)\(more))"
                }
                parts.append(line)
            }
            if failed > 0 { parts.append("\(failed) failed") }
            if let firstFailure { parts.append("first error -- \(firstFailure)") }
            return parts.joined(separator: ", ")
        }
    }

    /// Set after every refresh, so a run that did nothing can say why.
    @Published private(set) var lastSummary: RefreshSummary?

    func clearLibrary() async {
        let handler = self.handler
        handler.inTransaction {
            for m in handler.getFavoriteMangas() {
                handler.deleteManga(manga: m)
            }
        }
        await reload()
    }
}
