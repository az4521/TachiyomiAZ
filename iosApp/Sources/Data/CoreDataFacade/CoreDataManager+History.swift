import Foundation
import TachiyomiKit

/// Reading history, over the shared `history` table.
///
/// The shared schema splits what upstream keeps in one place: *when* a chapter was last read is a
/// `history` row keyed by chapter id, while *how far* into it the reader got is `last_page_read` on
/// the chapter itself. This is Android's layout, so the methods below join the two rather than
/// introduce a third representation.
extension CoreDataManager {
    /// Chapter key to progress, as the reader expects it.
    func getReadingHistory(sourceId: String, mangaId: String) async -> [String: (page: Int, date: Int)] {
        let chapters = sharedChapters(sourceId: sourceId, mangaId: mangaId)
        var result: [String: (page: Int, date: Int)] = [:]
        for chapter in chapters {
            guard let history = handler.getHistoryByChapterUrl(chapterUrl: chapter.url) else { continue }
            // A finished chapter reports -1, which is upstream's marker for "completed" as opposed
            // to a page offset partway through.
            let page = chapter.read ? -1 : Int(chapter.last_page_read)
            result[chapter.url] = (page: page, date: Int(history.last_read / 1000))
        }
        return result
    }

    func getProgress(
        sourceId: String,
        mangaId: String,
        chapterId: String,
        context: Any? = nil
    ) -> (completed: Bool, progress: Int?) {
        guard let chapter = sharedChapter(sourceId: sourceId, mangaId: mangaId, chapterId: chapterId) else {
            return (false, nil)
        }
        return (chapter.read, chapter.last_page_read == 0 ? nil : Int(chapter.last_page_read))
    }

    func setProgress(
        _ progress: Int,
        sourceId: String,
        mangaId: String,
        chapterId: String,
        totalPages: Int? = nil,
        scrollPosition: Double? = nil,
        dateRead: Date? = nil,
        completed: Bool? = nil,
        context: Any? = nil
    ) {
        guard let chapter = sharedChapter(sourceId: sourceId, mangaId: mangaId, chapterId: chapterId) else { return }
        chapter.last_page_read = Int32(progress)
        if let completed { chapter.read = completed }
        if let scrollPosition {
            setScrollPosition(scrollPosition, sourceId: sourceId, mangaId: mangaId, chapterId: chapterId)
        }
        handler.updateChapterProgress(chapter: chapter)
        touchHistory(chapter: chapter, date: dateRead ?? Date())
    }

    func setCompleted(chapters: [DbChapter], date: Date = Date(), context: Any? = nil) {
        let handler = self.handler
        handler.inTransaction {
            for chapter in chapters {
                chapter.read = true
                handler.updateChapterProgress(chapter: chapter)
                self.touchHistory(chapter: chapter, date: date)
            }
        }
    }

    @discardableResult
    func setCompleted(
        sourceId: String,
        mangaId: String,
        chapterIds: [String],
        date: Date = Date(),
        context: Any? = nil
    ) -> Bool {
        let wanted = Set(chapterIds)
        let chapters = sharedChapters(sourceId: sourceId, mangaId: mangaId).filter { wanted.contains($0.url) }
        guard !chapters.isEmpty else { return false }
        setCompleted(chapters: chapters, date: date)
        return true
    }

    func hasHistory(sourceId: String, mangaId: String, context: Any? = nil) -> Bool {
        sharedChapters(sourceId: sourceId, mangaId: mangaId)
            .contains { handler.getHistoryByChapterUrl(chapterUrl: $0.url) != nil }
    }

    func hasHistory(sourceId: String, mangaId: String, chapterId: String, context: Any? = nil) -> Bool {
        handler.getHistoryByChapterUrl(chapterUrl: chapterId) != nil
    }

    func removeHistory(sourceId: String, mangaId: String, context: Any? = nil) {
        let handler = self.handler
        handler.inTransaction {
            for chapter in self.sharedChapters(sourceId: sourceId, mangaId: mangaId) {
                guard let history = handler.getHistoryByChapterUrl(chapterUrl: chapter.url) else { continue }
                history.last_read = 0
                handler.updateHistoryLastRead(history: history)
            }
        }
    }

    func removeHistory(sourceId: String, mangaId: String, chapterIds: [String]) async {
        let wanted = Set(chapterIds)
        let handler = self.handler
        handler.inTransaction {
            for chapter in self.sharedChapters(sourceId: sourceId, mangaId: mangaId)
                where wanted.contains(chapter.url) {
                guard let history = handler.getHistoryByChapterUrl(chapterUrl: chapter.url) else { continue }
                history.last_read = 0
                handler.updateHistoryLastRead(history: history)
            }
        }
    }

    func clearHistory(context: Any? = nil) {
        handler.deleteHistory()
    }

    /// Upstream keeps library entries' history; the shared query that does this keeps rows with a
    /// `last_read`, which is the same set for anything the user has actually opened.
    func clearHistoryExcludingLibrary(context: Any? = nil) {
        handler.deleteHistoryNoLastRead()
    }

    func getEarliestReadDate(sourceId: String, mangaId: String, context: Any? = nil) -> Date? {
        sharedChapters(sourceId: sourceId, mangaId: mangaId)
            .compactMap { handler.getHistoryByChapterUrl(chapterUrl: $0.url)?.last_read }
            .filter { $0 > 0 }
            .min()
            .map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
    }

    private func touchHistory(chapter: DbChapter, date: Date) {
        let stamp = Int64(date.timeIntervalSince1970 * 1000)
        if let history = handler.getHistoryByChapterUrl(chapterUrl: chapter.url) {
            history.last_read = stamp
            handler.updateHistoryLastRead(history: history)
        } else if let chapterId = chapter.id?.int64Value {
            let history = HistoryImpl()
            history.chapter_id = chapterId
            history.last_read = stamp
            handler.insertHistory(history: history)
        }
    }
}

extension CoreDataManager {
    /// Accumulates time spent reading a chapter, in `history.time_read`.
    func addReadTime(seconds: Int64, sourceId: String, mangaId: String, chapterId: String) {
        guard
            let chapter = sharedChapter(sourceId: sourceId, mangaId: mangaId, chapterId: chapterId),
            let history = handler.getHistoryByChapterUrl(chapterUrl: chapter.url)
        else { return }
        history.time_read += seconds
        handler.updateHistoryLastRead(history: history)
    }
}
