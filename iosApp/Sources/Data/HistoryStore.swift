import Foundation
import TachiJVMRunner
import TachiyomiKit

/// A recently read chapter, as the History screen needs it.
/// One recently-read chapter, read straight from the shared `history` table.
///
/// Named apart from the vendored `HistoryEntry`: that one is the history screen's view model, this
/// is what the shared query returns. Both exist because the screen's shape is upstream's and the
/// data's shape is the database's.
struct RecentReadEntry: Identifiable, Hashable {
    let mangaId: Int64
    let mangaTitle: String
    let mangaUrl: String
    let sourceId: Int64
    let thumbnailUrl: String?
    let chapterId: Int64
    let chapterName: String
    let chapterUrl: String
    let lastRead: Int64

    var id: Int64 { chapterId }

    var lastReadDate: Date { Date(timeIntervalSince1970: Double(lastRead) / 1000) }
}

/// Reading history, stored through the shared `HistoryQueries`.
///
/// The queries, the tables and the `History` model are all `:core-database`, so a chapter read on
/// iOS lands in the same rows an Android backup would carry. Nothing about history is reimplemented
/// here -- this only turns rows into something a SwiftUI list can show.
@MainActor
final class HistoryStore: ObservableObject {
    @Published private(set) var entries: [RecentReadEntry] = []
    @Published private(set) var isLoading = false

    private unowned let library: LibraryStore
    private lazy var repository = HistoryRepository(db: library.handler)

    init(library: LibraryStore) {
        self.library = library
    }

    func load() async {
        isLoading = true
        defer { isLoading = false }

        // getRecentMangaLimit returns MangaChapterHistory rows: the manga, the chapter and when it
        // was last read, already joined and ordered by the shared query.
        let recent = repository.recent(limit: 300)
        entries = recent.map { row in
            let manga = row.manga
            let chapter = row.chapter
            let history = row.history
            return RecentReadEntry(
                mangaId: manga.id?.int64Value ?? 0,
                mangaTitle: manga.title,
                mangaUrl: manga.url,
                sourceId: manga.source,
                thumbnailUrl: manga.thumbnail_url,
                chapterId: chapter.id?.int64Value ?? 0,
                chapterName: chapter.name,
                chapterUrl: chapter.url,
                lastRead: history.last_read
            )
        }
    }

    /// Records a read. Written through the shared queries so it is indistinguishable from a read
    /// recorded by the Android app against the same database.
    func record(
        manga: TachiyomiXManga,
        chapter: TachiyomiXChapter,
        source: SourceDescriptor
    ) async {
        guard repository.record(
            mangaUrl: manga.url,
            sourceId: source.id,
            chapterUrl: chapter.url,
            readAt: Int64(Date().timeIntervalSince1970 * 1000)
        ) else { return }
        await load()
    }

    func clearAll() async {
        repository.clear()
        await load()
    }

    func remove(_ entry: RecentReadEntry) async {
        repository.remove(
            mangaUrl: entry.mangaUrl,
            sourceId: entry.sourceId,
            chapterUrl: entry.chapterUrl
        )
        await load()
    }
}
