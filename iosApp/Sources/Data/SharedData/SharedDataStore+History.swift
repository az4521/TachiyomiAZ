import Foundation
import TachiyomiKit

/// Reading history, over the shared `history` table.
///
/// The shared schema splits what upstream keeps in one place: *when* a chapter was last read is a
/// `history` row keyed by chapter id, while *how far* into it the reader got is `last_page_read` on
/// the chapter itself. This is Android's layout, so the methods below join the two rather than
/// introduce a third representation.
extension SharedDataStore {
    /// Chapter key to progress, as the reader expects it.
    func getReadingHistory(sourceId: String, mangaId: String) async -> [String: (page: Int, date: Int)] {
        historySnapshot(sourceId: sourceId, mangaId: mangaId)
    }

    func getProgress(
        sourceId: String,
        mangaId: String,
        chapterId: String,
        context: Any? = nil
    ) -> (completed: Bool, progress: Int?) {
        guard let source = SourceIdentity.numericId(sourceId) else { return (false, nil) }
        let value = historyRepository.progress(mangaUrl: mangaId, sourceId: source, chapterUrl: chapterId)
        return (value.completed, value.progress?.intValue)
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
        historyRepository.setProgress(
            chapter: chapter,
            progress: Int32(progress),
            completed: chapter.read,
            readAt: Int64((dateRead ?? Date()).timeIntervalSince1970 * 1000)
        )
    }

    func setCompleted(chapters: [DbChapter], date: Date = Date(), context: Any? = nil) {
        historyRepository.markCompleted(
            chapters: chapters,
            readAt: Int64(date.timeIntervalSince1970 * 1000)
        )
    }

    @discardableResult
    func setCompleted(
        sourceId: String,
        mangaId: String,
        chapterIds: [String],
        date: Date = Date(),
        context: Any? = nil
    ) -> Bool {
        guard let source = SourceIdentity.numericId(sourceId) else { return false }
        return historyRepository.markCompleted(
            mangaUrl: mangaId,
            sourceId: source,
            chapterUrls: chapterIds,
            readAt: Int64(date.timeIntervalSince1970 * 1000)
        )
    }

    func hasHistory(sourceId: String, mangaId: String, context: Any? = nil) -> Bool {
        guard let source = SourceIdentity.numericId(sourceId) else { return false }
        return historyRepository.hasHistory(mangaUrl: mangaId, sourceId: source, chapterUrl: nil)
    }

    func hasHistory(sourceId: String, mangaId: String, chapterId: String, context: Any? = nil) -> Bool {
        guard let source = SourceIdentity.numericId(sourceId) else { return false }
        return historyRepository.hasHistory(mangaUrl: mangaId, sourceId: source, chapterUrl: chapterId)
    }

    func removeHistory(sourceId: String, mangaId: String, context: Any? = nil) {
        guard let source = SourceIdentity.numericId(sourceId) else { return }
        historyRepository.remove(mangaUrl: mangaId, sourceId: source, chapterUrls: nil)
    }

    func removeHistory(sourceId: String, mangaId: String, chapterIds: [String]) async {
        guard let source = SourceIdentity.numericId(sourceId) else { return }
        historyRepository.remove(mangaUrl: mangaId, sourceId: source, chapterUrls: chapterIds)
    }

    func clearHistory(context: Any? = nil) {
        historyRepository.clear()
    }

    /// Upstream keeps library entries' history; the shared query that does this keeps rows with a
    /// `last_read`, which is the same set for anything the user has actually opened.
    func clearHistoryExcludingLibrary(context: Any? = nil) {
        historyRepository.clearWithoutLastRead()
    }

    func getEarliestReadDate(sourceId: String, mangaId: String, context: Any? = nil) -> Date? {
        guard let source = SourceIdentity.numericId(sourceId),
              let stamp = historyRepository.earliestRead(mangaUrl: mangaId, sourceId: source)
        else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(stamp.int64Value) / 1000)
    }
}

extension SharedDataStore {
    /// Accumulates time spent reading a chapter, in `history.time_read`.
    func addReadTime(seconds: Int64, sourceId: String, mangaId: String, chapterId: String) {
        guard let source = SourceIdentity.numericId(sourceId) else { return }
        historyRepository.addReadTime(
            mangaUrl: mangaId,
            sourceId: source,
            chapterUrl: chapterId,
            amount: seconds
        )
    }
}

extension SharedDataStore {
    private func historySnapshot(sourceId: String, mangaId: String) -> [String: (page: Int, date: Int)] {
        guard let source = SourceIdentity.numericId(sourceId) else { return [:] }
        return Dictionary(
            uniqueKeysWithValues: historyRepository.readingHistory(mangaUrl: mangaId, sourceId: source)
                .map { ($0.chapterUrl, (page: Int($0.page), date: Int($0.dateSeconds))) }
        )
    }
}

extension SharedDataStore {
    /// Every history row for a source, as the "source changed its ids" migration walks them.
    func getHistory(sourceId: String, context: Any? = nil) -> [ChapterIdentifier] {
        guard let source = SourceIdentity.numericId(sourceId) else { return [] }
        return historyRepository.identifiersForSource(sourceId: source).map {
            ChapterIdentifier(sourceKey: sourceId, mangaKey: $0.mangaUrl, chapterKey: $0.chapterUrl)
        }
    }
}
