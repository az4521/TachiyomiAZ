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

    func isChapterDownloaded(chapter: any DbChapter, manga: any DbManga) -> Bool { false }

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
    func addFromRunner(_ manga: ExtensionRunner.Manga, sourceId: Int64) async {
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
        await reload()
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
    func refresh(
        categoryId: Int32?,
        runtime: SourceRuntime,
        settings: AppSettings,
        onProgress: ((Int, Int) -> Void)? = nil
    ) async {
        lastError = nil

        let selection = LibraryUpdateSelectionKt.selectLibraryMangaToUpdate(
            library: handler.getLibraryMangas(),
            categoryId: categoryId ?? LibraryUpdateSelectionKt.ALL_CATEGORIES,
            categoriesToUpdate: settings.updateCategories.map { KotlinInt(int: $0) },
            excludeCompleted: settings.skipCompleted
        )

        // Only pay for the chapter lookup when a read-state filter is actually on.
        let targets: [LibraryManga]
        if settings.needsReadState {
            targets = selection.filter { entry in
                let hasStarted = handler.getChapters(manga: entry).contains { $0.read }
                return settings.shouldRefresh(unread: Int(entry.unread), hasStarted: hasStarted)
            }
        } else {
            targets = selection
        }
        guard !targets.isEmpty else { return }

        refreshProgress = (0, targets.count)
        defer { refreshProgress = nil }

        for (index, entry) in targets.enumerated() {
            refreshProgress = (index, targets.count)
            onProgress?(index, targets.count)
            guard let source = runtime.sources.first(where: { $0.id == entry.source }) else { continue }
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
                // Shared diffing: which chapters are new, deleted, or silently renamed.
                _ = ChapterSyncKt.syncChaptersWithSource(
                    db: handler,
                    rawSourceChapters: sourceChapters,
                    manga: entry,
                    platform: syncPlatform
                )
            } catch {
                lastError = "\(entry.title): \(error.localizedDescription)"
            }
        }

        await reload()
    }

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
