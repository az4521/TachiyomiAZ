import Foundation
import TachiyomiKit

/// Chapters, over the shared `chapters` table.
///
/// Read state lives on the chapter row here (`read`, `last_page_read`), as on Android, rather than
/// being split across a separate history entity.
extension CoreDataManager {
    func getChapters(sourceId: String, mangaId: String, context: Any? = nil) -> [DbChapter] {
        guard let manga = getManga(sourceId: sourceId, mangaId: mangaId) else { return [] }
        return handler.getChapters(manga: manga)
    }

    func getChapters(sourceId: String, mangaId: String) async -> [DbChapter] {
        getChapters(sourceId: sourceId, mangaId: mangaId, context: nil)
    }

    func getChapters(sourceId: String, context: Any? = nil) -> [DbChapter] {
        guard let source = SourceIdentity.numericId(sourceId) else { return [] }
        return handler.getMangasBySource(sourceId: source).flatMap { handler.getChapters(manga: $0) }
    }

    func getChapters(context: Any? = nil) -> [DbChapter] {
        handler.getAllChapters()
    }

    func getChapter(sourceId: String, mangaId: String, chapterId: String, context: Any? = nil) -> DbChapter? {
        getChapters(sourceId: sourceId, mangaId: mangaId).first { $0.url == chapterId }
    }

    func hasChapter(sourceId: String, mangaId: String, chapterId: String, context: Any? = nil) -> Bool {
        getChapter(sourceId: sourceId, mangaId: mangaId, chapterId: chapterId) != nil
    }

    func removeChapters(sourceId: String, mangaId: String, context: Any? = nil) {
        handler.deleteChapters(chapters: getChapters(sourceId: sourceId, mangaId: mangaId))
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
        getChapters(sourceId: sourceId, mangaId: mangaId)
            .filter(\.read)
            .map(\.chapter_number)
            .max()
    }

    private func matching(sourceId: String, mangaId: String, scanlators: [String]?) -> [DbChapter] {
        let chapters = getChapters(sourceId: sourceId, mangaId: mangaId)
        guard let scanlators, !scanlators.isEmpty else { return chapters }
        return chapters.filter { chapter in
            guard let scanlator = chapter.scanlator else { return false }
            return scanlators.contains(scanlator)
        }
    }
}
