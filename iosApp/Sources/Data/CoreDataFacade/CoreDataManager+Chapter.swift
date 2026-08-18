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

    func getChapters(sourceId: String, context: Any? = nil) -> [DbChapter] {
        guard let source = SourceIdentity.numericId(sourceId) else { return [] }
        return handler.getMangasBySource(sourceId: source).flatMap { handler.getChapters(manga: $0) }
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
    /// Diffing is by chapter key, matching how `syncChaptersWithSource` in `:core-domain` decides
    /// what is new -- read state on an existing chapter is preserved rather than reset.
    @discardableResult
    func setChapters(
        _ chapters: [ExtensionRunner.Chapter],
        sourceId: String,
        mangaId: String,
        context: Any? = nil
    ) -> [ChapterObject] {
        guard let manga = sharedManga(sourceId: sourceId, mangaId: mangaId),
              let mangaRowId = manga.id?.int64Value
        else { return [] }

        let existing = Dictionary(
            sharedChapters(sourceId: sourceId, mangaId: mangaId).map { ($0.url, $0) },
            uniquingKeysWith: { first, _ in first }
        )

        var added: [ChapterObject] = []
        let handler = self.handler
        handler.inTransaction {
            for (order, chapter) in chapters.enumerated() {
                if let stored = existing[chapter.key] {
                    stored.name = chapter.title ?? stored.name
                    stored.scanlator = chapter.scanlators?.joined(separator: ", ") ?? stored.scanlator
                    stored.source_order = Int32(order)
                    handler.insertChapter(chapter: stored)
                } else {
                    let record = ChapterImpl()
                    record.manga_id = KotlinLong(value: mangaRowId)
                    record.url = chapter.key
                    record.name = chapter.title ?? chapter.key
                    record.scanlator = chapter.scanlators?.joined(separator: ", ")
                    record.chapter_number = chapter.chapterNumber ?? -1
                    record.date_upload = Int64((chapter.dateUploaded?.timeIntervalSince1970 ?? 0) * 1000)
                    record.date_fetch = Int64(Date().timeIntervalSince1970 * 1000)
                    record.source_order = Int32(order)
                    handler.insertChapter(chapter: record)
                    added.append(ChapterObject(row: record, sourceId: sourceId, mangaId: mangaId))
                }
            }

            // Chapters the source no longer lists are gone from it.
            let keys = Set(chapters.map(\.key))
            let removed = existing.values.filter { !keys.contains($0.url) }
            if !removed.isEmpty {
                handler.deleteChapters(chapters: removed)
            }
        }
        return added
    }

    /// Upstream records every new chapter as a `MangaUpdate` row for the updates badge. The shared
    /// schema has no such table -- `MangaUpdateManager` keeps a viewed timestamp instead -- so
    /// there is nothing to write here.
    func createMangaUpdate(sourceId: String, mangaId: String, chapterObject: ChapterObject, context: Any? = nil) {}
}
