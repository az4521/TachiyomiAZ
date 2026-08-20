import Foundation
import TachiyomiKit

/// The new-chapter feed.
///
/// Upstream keeps a `MangaUpdate` row per newly fetched chapter. The shared schema has no such
/// table, because Android derives the same feed from `chapters.date_fetch` -- which is what
/// `getRecentChapters` in `:core-database` returns, and what the Android updates screen shows. This
/// reads that, so both apps' update lists come from one query rather than two records of the same
/// event.
///
/// "Viewed" is not in the schema either; `MangaUpdateManager` keeps a per-title timestamp, and a
/// chapter fetched before it counts as seen.
/// One entry in the new-chapter feed, in the shape the updates screen reads.
final class MangaUpdateObject {
    let chapterObject: ChapterObject
    let viewed: Bool

    init(chapterObject: ChapterObject, viewed: Bool) {
        self.chapterObject = chapterObject
        self.viewed = viewed
    }

    /// Stable per chapter, which is what the feed is keyed by.
    var id: String { "\(chapterObject.sourceId)|\(chapterObject.mangaId)|\(chapterObject.id)" }

    var sourceId: String? { chapterObject.sourceId }
    var mangaId: String? { chapterObject.mangaId }
    var chapterId: String? { chapterObject.id }

    /// When the chapter was fetched -- the moment it became an update.
    var date: Date? {
        chapterObject.dateFetched
    }

    var chapter: ChapterObject? { chapterObject }

    func toItem() -> MangaUpdateItem {
        MangaUpdateItem(
            sourceId: sourceId,
            chapterId: chapterId,
            mangaId: mangaId,
            viewed: viewed
        )
    }
}

extension CoreDataManager {
    func getRecentMangaUpdates(limit: Int, offset: Int, context: Any? = nil) -> [MangaUpdateObject] {
        // A window wide enough to cover what the screen pages through; the query is ordered by
        // fetch date, so the slice below takes the newest.
        let since = Int64(Date().addingTimeInterval(-60 * 60 * 24 * 365).timeIntervalSince1970 * 1000)
        let recent = handler.getRecentChapters(date: since)

        let items: [MangaUpdateObject] = recent.map { entry in
            let manga = entry.manga
            let sourceId = manga.legacySourceId
            let viewedAt = MangaUpdateManager.shared.lastViewed(sourceId: sourceId, mangaId: manga.url)
            let fetched = Date(timeIntervalSince1970: TimeInterval(entry.chapter.date_fetch) / 1000)
            return MangaUpdateObject(
                chapterObject: ChapterObject(
                    row: entry.chapter,
                    sourceId: sourceId,
                    mangaId: manga.url
                ),
                viewed: viewedAt.map { fetched <= $0 } ?? false
            )
        }

        guard offset < items.count else { return [] }
        return Array(items[offset..<min(offset + limit, items.count)])
    }

    /// Upstream deletes the update rows; here there are none, so clearing means marking the titles
    /// they belong to as seen.
    func removeMangaUpdates(updates: [ChapterIdentifier], context: Any? = nil) {
        for identifier in Set(updates.map { MangaIdentifier(sourceKey: $0.sourceKey, mangaKey: $0.mangaKey) }) {
            UserDefaults.standard.set(
                Date().timeIntervalSince1970,
                forKey: "Library.updatesViewed.\(identifier.sourceKey).\(identifier.mangaKey)"
            )
        }
    }
}
