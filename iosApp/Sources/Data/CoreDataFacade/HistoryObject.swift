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

    init(scrollPosition: NSNumber?, dateRead: Date?) {
        self.scrollPosition = scrollPosition
        self.dateRead = dateRead
    }
}

extension CoreDataManager {
    private static func scrollPositionKey(_ sourceId: String, _ mangaId: String, _ chapterId: String) -> String {
        "Reader.scrollPosition.\(sourceId).\(mangaId).\(chapterId)"
    }

    func getHistory(
        sourceId: String,
        mangaId: String,
        chapterId: String,
        context: Any? = nil
    ) -> HistoryObject? {
        guard let chapter = sharedChapter(sourceId: sourceId, mangaId: mangaId, chapterId: chapterId) else {
            return nil
        }
        let stored = handler.getHistoryByChapterUrl(chapterUrl: chapter.url)
        let key = Self.scrollPositionKey(sourceId, mangaId, chapterId)
        let scroll = UserDefaults.standard.object(forKey: key) as? NSNumber

        guard stored != nil || scroll != nil else { return nil }

        return HistoryObject(
            scrollPosition: scroll,
            dateRead: (stored?.last_read).flatMap { $0 > 0 ? Date(timeIntervalSince1970: TimeInterval($0) / 1000) : nil }
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

extension CoreDataManager {
    /// The reading history feed, newest first.
    ///
    /// Built from the shared `history` table joined to its chapters -- the same rows Android's
    /// history screen reads. Total page count is not stored, so it reports 0 and the screen falls
    /// back to showing progress alone.
    func getRecentHistory(limit: Int, offset: Int, context: Any? = nil) -> [RecentHistoryObject] {
        let handler = self.handler

        var chaptersById: [Int64: DbChapter] = [:]
        for chapter in handler.getAllChapters() {
            guard let id = chapter.id?.int64Value else { continue }
            chaptersById[id] = chapter
        }
        var mangaById: [Int64: DbManga] = [:]
        for manga in handler.getMangas() {
            guard let id = manga.id?.int64Value else { continue }
            mangaById[id] = manga
        }

        let entries = handler.getAllHistory()
            .filter { $0.last_read > 0 }
            .sorted { $0.last_read > $1.last_read }
            .compactMap { history -> RecentHistoryObject? in
                guard
                    let chapter = chaptersById[history.chapter_id],
                    let mangaId = chapter.manga_id?.int64Value,
                    let manga = mangaById[mangaId]
                else { return nil }
                return RecentHistoryObject(
                    sourceId: manga.legacySourceId,
                    mangaId: manga.url,
                    chapterId: chapter.url,
                    dateRead: Date(timeIntervalSince1970: TimeInterval(history.last_read) / 1000),
                    progress: Int16(clamping: chapter.last_page_read),
                    total: 0,
                    completed: chapter.read
                )
            }

        guard offset < entries.count else { return [] }
        return Array(entries[offset..<min(offset + limit, entries.count)])
    }
}
