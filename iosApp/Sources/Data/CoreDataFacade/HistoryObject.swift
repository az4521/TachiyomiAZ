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
