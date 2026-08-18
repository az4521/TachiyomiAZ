import Foundation
import TachiJVMRunner
import TachiyomiKit

/// A recently read chapter, as the History screen needs it.
struct HistoryEntry: Identifiable, Hashable {
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
    @Published private(set) var entries: [HistoryEntry] = []
    @Published private(set) var isLoading = false

    private unowned let library: LibraryStore

    init(library: LibraryStore) {
        self.library = library
    }

    func load() async {
        let handler = library.handler
        isLoading = true
        defer { isLoading = false }

        // getRecentMangaLimit returns MangaChapterHistory rows: the manga, the chapter and when it
        // was last read, already joined and ordered by the shared query.
        let recent = handler.getRecentMangaLimit(date: 0, limit: 300, search: "")
        entries = recent.map { row in
            let manga = row.manga
            let chapter = row.chapter
            let history = row.history
            return HistoryEntry(
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
        let handler = library.handler

        guard let stored = handler.getManga(url: manga.url, sourceId: source.id) else { return }

        let existingChapters = handler.getChapters(manga: stored)
        guard let storedChapter = existingChapters.first(where: { $0.url == chapter.url }),
              let chapterId = storedChapter.id else { return }

        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if let existing = handler.getHistoryByChapterUrl(chapterUrl: chapter.url) {
            existing.last_read = now
            handler.updateHistoryLastRead(history: existing)
        } else {
            // History links to the chapter, not the manga -- the manga is reached through it.
            let entry = HistoryImpl()
            entry.chapter_id = chapterId.int64Value
            entry.last_read = now
            handler.insertHistory(history: entry)
        }
        await load()
    }

    func clearAll() async {
        let handler = library.handler
        handler.deleteHistory()
        await load()
    }

    func remove(_ entry: HistoryEntry) async {
        let handler = library.handler
        if let history = handler.getHistoryByChapterUrl(chapterUrl: entry.chapterUrl) {
            history.last_read = 0
            handler.updateHistoryLastRead(history: history)
        }
        await load()
    }
}
