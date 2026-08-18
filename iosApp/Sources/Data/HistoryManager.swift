import ExtensionRunner
import Foundation
import TachiyomiKit

/// Reading progress, as the vendored reader records it.
///
/// Upstream's version writes CoreData directly and keeps a separate `ReadingSession` entity. This
/// one goes through `CoreDataManager`, so progress lands in the shared `chapters` and `history`
/// tables that the Android app reads.
///
/// Sessions have no table of their own here. Android records time spent per chapter in
/// `history.time_read`, which is what a session's duration *is*, so a finished session accumulates
/// onto that column rather than creating a parallel history of its own.
final class HistoryManager {
    static let shared = HistoryManager()

    private init() {}

    struct ReadingSessionData {
        let startDate: Date
        let endDate: Date
        let pagesRead: Int
    }

    func setProgress(
        chapter: Chapter,
        progress: Int,
        totalPages: Int? = nil,
        scrollPosition: Double? = nil,
        completed: Bool
    ) async {
        CoreDataManager.shared.setProgress(
            progress,
            sourceId: chapter.sourceId,
            mangaId: chapter.mangaId,
            chapterId: chapter.id,
            totalPages: totalPages,
            scrollPosition: scrollPosition,
            completed: completed
        )
    }

    /// Adds a finished session's duration to the chapter's accumulated read time.
    func addSession(chapterIdentifier: ChapterIdentifier, data: ReadingSessionData) async {
        let seconds = data.endDate.timeIntervalSince(data.startDate)
        guard seconds > 0 else { return }
        CoreDataManager.shared.addReadTime(
            seconds: Int64(seconds),
            sourceId: chapterIdentifier.sourceKey,
            mangaId: chapterIdentifier.mangaKey,
            chapterId: chapterIdentifier.chapterKey
        )
    }

    func addHistory(
        sourceId: String,
        mangaId: String,
        chapters: [ExtensionRunner.Chapter],
        date: Date = Date(),
        skipTracker: Tracker? = nil
    ) async {
        CoreDataManager.shared.setCompleted(
            sourceId: sourceId,
            mangaId: mangaId,
            chapterIds: chapters.map(\.key),
            date: date
        )
    }

    func removeHistory(sourceId: String, mangaId: String, chapterIds: [String]) async {
        await CoreDataManager.shared.removeHistory(
            sourceId: sourceId,
            mangaId: mangaId,
            chapterIds: chapterIds
        )
    }

    func removeHistory(sourceId: String, mangaId: String) async {
        CoreDataManager.shared.removeHistory(sourceId: sourceId, mangaId: mangaId)
    }
}
