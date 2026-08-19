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
///
/// Every method that changes read state posts the notification upstream posts, with the same
/// payload. That is not decoration: the library screen's unread badges, its sort order and its
/// "started"/"unread" filters are all driven by these, and this class writing to the database
/// without announcing it is why a chapter could be read with the badge never changing.
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
        // The library's "started" filter and its last-read sort watch for this.
        NotificationCenter.default.post(name: .historySet, object: (chapter, progress))
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

    /// Marks chapters read.
    ///
    /// The tracker sync is part of finishing a chapter, not a separate feature: "update trackers
    /// after reading" is a setting the user can turn on, and with this method silent it did
    /// nothing at all.
    /// - Parameter defersDownloadCleanup: set by the reader, which queues its own deletions until
    ///   the chapter is off screen. Everywhere else marking a chapter read should drop its
    ///   download straight away, which is what the other app does from each of its mark-read
    ///   screens; this app only ever did it from the reader, so marking a title read from the
    ///   library left every chapter's pages on disk with the setting on.
    func addHistory(
        sourceId: String,
        mangaId: String,
        chapters: [ExtensionRunner.Chapter],
        date: Date = Date(),
        skipTracker: Tracker? = nil,
        defersDownloadCleanup: Bool = false
    ) async {
        let written = CoreDataManager.shared.setCompleted(
            sourceId: sourceId,
            mangaId: mangaId,
            chapterIds: chapters.map(\.key),
            date: date
        )
        guard written else { return }

        NotificationCenter.default.post(
            name: .historyAdded,
            object: chapters.map { $0.toOld(sourceId: sourceId, mangaId: mangaId) }
        )

        if !defersDownloadCleanup, DownloadPreferencesBridge.shared.removeAfterMarkedAsRead {
            await DownloadManager.shared.delete(
                chapters: chapters.map {
                    ChapterIdentifier(sourceKey: sourceId, mangaKey: mangaId, chapterKey: $0.key)
                }
            )
        }

        Task {
            if UserDefaults.standard.bool(forKey: "Tracking.updateAfterReading") {
                // Only the furthest chapter is reported: trackers store a high-water mark, so
                // sending each of a batch in turn would be several requests saying less.
                if let maxChapter = chapters.max(by: { $0.chapterNumber ?? 0 < $1.chapterNumber ?? 0 }) {
                    await TrackerManager.shared.setCompleted(
                        chapter: maxChapter.toOld(sourceId: sourceId, mangaId: mangaId),
                        skipTracker: skipTracker
                    )
                }
            }

            await TrackerManager.shared.setProgress(
                sourceKey: sourceId,
                mangaKey: mangaId,
                chapters: chapters,
                progress: .init(completed: true, page: 0)
            )
        }
    }

    func removeHistory(sourceId: String, mangaId: String, chapterIds: [String]) async {
        await CoreDataManager.shared.removeHistory(
            sourceId: sourceId,
            mangaId: mangaId,
            chapterIds: chapterIds
        )
        // The observers read only the source and manga off these, so the rest is left empty
        // rather than queried back out of the database purely to fill a notification.
        NotificationCenter.default.post(
            name: .historyRemoved,
            object: chapterIds.map {
                Chapter(sourceId: sourceId, id: $0, mangaId: mangaId, title: nil, sourceOrder: 0)
            }
        )
    }

    func removeHistory(sourceId: String, mangaId: String) async {
        CoreDataManager.shared.removeHistory(sourceId: sourceId, mangaId: mangaId)
        NotificationCenter.default.post(
            name: .historyRemoved,
            object: Manga(sourceId: sourceId, id: mangaId)
        )
    }
}
