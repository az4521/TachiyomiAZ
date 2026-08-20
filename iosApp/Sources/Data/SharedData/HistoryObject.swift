import Foundation
import TachiyomiKit

/// A history row in the shape the vendored reader expects.
///
/// Upstream's `HistoryObject` is a CoreData entity. This carries the two things the text readers
/// read off it, from two different places: `dateRead` from the shared `history` table, and
/// `scrollPosition` from `UserDefaults`.
///
/// The split is deliberate. Android's schema has no scroll-position column -- it is a text-reader
/// detail, not library data, and adding a column for it would put this app's writes out of step
/// with the database both apps share.
final class HistoryObject {
    let scrollPosition: NSNumber?
    let dateRead: Date?
    let isCompleted: Bool

    init(scrollPosition: NSNumber?, dateRead: Date?, isCompleted: Bool = false) {
        self.scrollPosition = scrollPosition
        self.dateRead = dateRead
        self.isCompleted = isCompleted
    }
}

extension SharedDataStore {
    private static func scrollPositionKey(_ sourceId: String, _ mangaId: String, _ chapterId: String) -> String {
        "Reader.scrollPosition.\(sourceId).\(mangaId).\(chapterId)"
    }

    func getHistory(
        sourceId: String,
        mangaId: String,
        chapterId: String,
        context: Any? = nil
    ) -> HistoryObject? {
        guard let source = SourceIdentity.numericId(sourceId),
              let chapter = sharedChapter(sourceId: sourceId, mangaId: mangaId, chapterId: chapterId) else {
            return nil
        }
        let stored = historyRepository.detail(mangaUrl: mangaId, sourceId: source, chapterUrl: chapter.url)
        let key = Self.scrollPositionKey(sourceId, mangaId, chapterId)
        let scroll = UserDefaults.standard.object(forKey: key) as? NSNumber

        guard stored != nil || scroll != nil else { return nil }

        return HistoryObject(
            scrollPosition: scroll,
            dateRead: stored?.readAt.map { Date(timeIntervalSince1970: TimeInterval($0.int64Value) / 1000) },
            isCompleted: chapter.read
        )
    }

    func setScrollPosition(_ position: Double?, sourceId: String, mangaId: String, chapterId: String) {
        let key = Self.scrollPositionKey(sourceId, mangaId, chapterId)
        if let position {
            UserDefaults.standard.set(position, forKey: key)
        } else {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }
}

/// A recently-read chapter, in the shape the history screen reads.
final class RecentHistoryObject {
    let sourceId: String
    let mangaId: String
    let chapterId: String
    let dateRead: Date?
    let progress: Int16
    let total: Int16
    let completed: Bool

    init(
        sourceId: String,
        mangaId: String,
        chapterId: String,
        dateRead: Date?,
        progress: Int16,
        total: Int16,
        completed: Bool
    ) {
        self.sourceId = sourceId
        self.mangaId = mangaId
        self.chapterId = chapterId
        self.dateRead = dateRead
        self.progress = progress
        self.total = total
        self.completed = completed
    }
}

extension SharedDataStore {
    /// The reading history feed, newest first.
    ///
    /// Built from the shared `history` table joined to its chapters -- the same rows Android's
    /// history screen reads. Total page count is not stored, so it reports 0 and the screen falls
    /// back to showing progress alone.
    func getRecentHistory(limit: Int, offset: Int, context: Any? = nil) -> [RecentHistoryObject] {
        historyRepository.recentEntries(limit: Int32(limit), offset: Int32(offset)).map { entry in
                RecentHistoryObject(
                    sourceId: SourceIdentity.key(for: entry.sourceId),
                    mangaId: entry.mangaUrl,
                    chapterId: entry.chapterUrl,
                    dateRead: Date(timeIntervalSince1970: TimeInterval(entry.readAt) / 1000),
                    progress: Int16(clamping: entry.progress),
                    total: 0,
                    completed: entry.completed
                )
            }
    }
}

extension HistoryObject {
    /// Whether the chapter was finished, as opposed to left partway through.
    ///
    /// The shared schema keeps this on the chapter (`read`), not the history row, so it is resolved
    /// from there rather than stored twice.
    var completed: Bool { isCompleted }
}
