import ExtensionRunner
import Foundation
import TachiyomiKit

/// Chapters, over the shared `chapters` table.
///
/// Read state lives on the chapter row here (`read`, `last_page_read`), as on Android, rather than
/// being split across a separate history entity.
extension CoreDataManager {
    /// The shared rows, for the facade's own queries.
    func sharedChapters(sourceId: String, mangaId: String) -> [DbChapter] {
        guard let manga = sharedManga(sourceId: sourceId, mangaId: mangaId) else { return [] }
        return handler.getChapters(manga: manga)
    }

    /// `context` is required here, unlike its siblings: the async overload in ModelConversions
    /// shares these argument labels, and a default would make every call ambiguous.
    func getChapters(sourceId: String, mangaId: String, context: Any?) -> [ChapterObject] {
        sharedChapters(sourceId: sourceId, mangaId: mangaId)
            .map { ChapterObject(row: $0, sourceId: sourceId, mangaId: mangaId) }
    }

    func getChapters(sourceId: String, context: Any? = nil) -> [ChapterObject] {
        guard let source = SourceIdentity.numericId(sourceId) else { return [] }
        return handler.getMangasBySource(sourceId: source).flatMap { manga in
            handler.getChapters(manga: manga).map {
                ChapterObject(row: $0, sourceId: sourceId, mangaId: manga.url)
            }
        }
    }

    func getChapters(context: Any? = nil) -> [DbChapter] {
        handler.getAllChapters()
    }

    func getChapter(sourceId: String, mangaId: String, chapterId: String, context: Any? = nil) -> ChapterObject? {
        sharedChapters(sourceId: sourceId, mangaId: mangaId)
            .first { $0.url == chapterId }
            .map { ChapterObject(row: $0, sourceId: sourceId, mangaId: mangaId) }
    }

    /// The shared row for one chapter.
    func sharedChapter(sourceId: String, mangaId: String, chapterId: String) -> DbChapter? {
        sharedChapters(sourceId: sourceId, mangaId: mangaId).first { $0.url == chapterId }
    }

    func hasChapter(sourceId: String, mangaId: String, chapterId: String, context: Any? = nil) -> Bool {
        getChapter(sourceId: sourceId, mangaId: mangaId, chapterId: chapterId) != nil
    }

    func removeChapters(sourceId: String, mangaId: String, context: Any? = nil) {
        handler.deleteChapters(chapters: sharedChapters(sourceId: sourceId, mangaId: mangaId))
    }

    func clearChapters(context: Any? = nil) {
        handler.deleteChapters(chapters: handler.getAllChapters())
    }

    /// `lang` is accepted for signature compatibility and ignored: the shared schema has no
    /// per-chapter language column, because a Tachiyomi source is already single-language.
    func unreadCount(
        sourceId: String,
        mangaId: String,
        lang: String? = nil,
        scanlators: [String]? = nil,
        context: Any? = nil
    ) -> Int {
        matching(sourceId: sourceId, mangaId: mangaId, scanlators: scanlators).filter { !$0.read }.count
    }

    func readCount(
        sourceId: String,
        mangaId: String,
        lang: String? = nil,
        scanlators: [String]? = nil,
        context: Any? = nil
    ) -> Int {
        matching(sourceId: sourceId, mangaId: mangaId, scanlators: scanlators).filter(\.read).count
    }

    /// Started, not finished -- a chapter with progress that is still unread.
    func startedCount(
        sourceId: String,
        mangaId: String,
        lang: String? = nil,
        scanlators: [String]? = nil,
        context: Any? = nil
    ) -> Int {
        matching(sourceId: sourceId, mangaId: mangaId, scanlators: scanlators)
            .filter { !$0.read && $0.last_page_read > 0 }
            .count
    }

    func getHighestReadNumber(sourceId: String, mangaId: String, context: Any? = nil) -> Float? {
        sharedChapters(sourceId: sourceId, mangaId: mangaId)
            .filter(\.read)
            .map(\.chapter_number)
            .max()
    }

    private func matching(sourceId: String, mangaId: String, scanlators: [String]?) -> [DbChapter] {
        let chapters = sharedChapters(sourceId: sourceId, mangaId: mangaId)
        guard let scanlators, !scanlators.isEmpty else { return chapters }
        return chapters.filter { chapter in
            guard let scanlator = chapter.scanlator else { return false }
            return scanlators.contains(scanlator)
        }
    }
}

extension CoreDataManager {
    /// Replaces a title's chapter list with what the source returned, and reports which are new.
    ///
    /// The diff is `syncChaptersWithSource` from `:core-domain` -- the same call `LibraryStore`
    /// makes on a library refresh, and the same one the Android app makes. It was hand-rolled here
    /// first, which would have meant two implementations of "which chapters are new" writing to one
    /// database; they would have drifted on exactly the cases that matter, like a silently renamed
    /// chapter keeping its read state.
    @discardableResult
    func setChapters(
        _ chapters: [ExtensionRunner.Chapter],
        sourceId: String,
        mangaId: String,
        context: Any? = nil
    ) -> [ChapterObject] {
        guard
            !chapters.isEmpty,
            let manga = sharedManga(sourceId: sourceId, mangaId: mangaId)
        else { return [] }

        let sourceChapters: [SChapter] = chapters.map { chapter in
            let s = SChapterImpl()
            s.url = chapter.key
            s.name = chapter.title ?? chapter.key
            s.scanlator = chapter.scanlators?.joined(separator: ", ")
            s.date_upload = Int64((chapter.dateUploaded?.timeIntervalSince1970 ?? 0) * 1000)
            if let number = chapter.chapterNumber { s.chapter_number = number }
            MemoJsonKt.setChapterMemoJson(s, memoJson: chapter.memo)
            return s
        }

        // Throws only for an empty source list, which the guard above already excludes -- but it
        // is a thrown error now rather than a process abort, so the boundary is worth respecting.
        guard
            let result = try? ChapterSyncKt.syncChaptersWithSource(
                db: handler,
                rawSourceChapters: sourceChapters,
                manga: manga,
                platform: Self.syncPlatform
            )
        else { return [] }

        let added = (result.first as? [DbChapter]) ?? []
        return added.map { ChapterObject(row: $0, sourceId: sourceId, mangaId: mangaId) }
    }

    /// Shared with `LibraryStore`: the platform hooks `syncChaptersWithSource` delegates.
    private static let syncPlatform = IOSChapterSyncPlatform()

    /// Upstream records every new chapter as a `MangaUpdate` row for the updates badge. The shared
    /// schema has no such table -- `MangaUpdateManager` keeps a viewed timestamp instead -- so
    /// there is nothing to write here.
    func createMangaUpdate(sourceId: String, mangaId: String, chapterObject: ChapterObject, context: Any? = nil) {}
}

extension CoreDataManager {
    /// Unread counts for the whole library in one pass, which is what the library grid badges.
    ///
    /// Upstream runs a single aggregate fetch; here the chapters are read once and tallied, rather
    /// than issuing one count query per entry.
    func libraryUnreadCounts(context: Any? = nil) -> [MangaIdentifier: Int] {
        let handler = self.handler
        var byMangaId: [Int64: Int] = [:]
        for chapter in handler.getAllChapters() where !chapter.read {
            guard let mangaId = chapter.manga_id?.int64Value else { continue }
            byMangaId[mangaId, default: 0] += 1
        }

        var counts: [MangaIdentifier: Int] = [:]
        for manga in handler.getLibraryMangas() {
            guard let id = manga.id?.int64Value else { continue }
            counts[MangaIdentifier(sourceKey: manga.legacySourceId, mangaKey: manga.url)] =
                byMangaId[id] ?? 0
        }
        return counts
    }
}
