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

    /// - Parameter chapter: identified rather than passed as a model. Recording progress needs the
    ///   three keys and nothing else, and taking a whole chapter meant the reader built one in the
    ///   shape the vendored UI kept.
    func setProgress(
        chapter: ChapterIdentifier,
        progress: Int,
        totalPages: Int? = nil,
        scrollPosition: Double? = nil,
        completed: Bool
    ) async {
        CoreDataManager.shared.setProgress(
            progress,
            sourceId: chapter.sourceKey,
            mangaId: chapter.mangaKey,
            chapterId: chapter.chapterKey,
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

        // Identifiers rather than chapters: every observer of this only reads the source, manga
        // and chapter keys off it, and carrying a whole chapter model meant converting one into
        // the shape the vendored UI used to hold.
        NotificationCenter.default.post(
            name: .historyAdded,
            object: chapters.map {
                ChapterIdentifier(sourceKey: sourceId, mangaKey: mangaId, chapterKey: $0.key)
            }
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
                        chapter: maxChapter,
                        manga: MangaIdentifier(sourceKey: sourceId, mangaKey: mangaId),
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
        NotificationCenter.default.post(
            name: .historyRemoved,
            object: chapterIds.map {
                ChapterIdentifier(sourceKey: sourceId, mangaKey: mangaId, chapterKey: $0)
            }
        )
    }

    func removeHistory(sourceId: String, mangaId: String) async {
        CoreDataManager.shared.removeHistory(sourceId: sourceId, mangaId: mangaId)
        NotificationCenter.default.post(
            name: .historyRemoved,
            object: MangaIdentifier(sourceKey: sourceId, mangaKey: mangaId)
        )
    }
}
