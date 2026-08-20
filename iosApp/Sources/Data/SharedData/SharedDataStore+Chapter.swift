import ExtensionRunner
import Foundation
import TachiyomiKit

/// Chapters, over the shared `chapters` table.
///
/// Read state lives on the chapter row here (`read`, `last_page_read`), as on Android, rather than
/// being split across a separate history entity.
extension SharedDataStore {
    /// The shared repository owns chapter selection and counting; this facade only translates the
    /// string source identity and wraps rows for the legacy UI.
    private var chapterRepository: ChapterRepository { ChapterRepository(db: handler) }

    /// The shared rows, for the facade's own queries.
    func sharedChapters(sourceId: String, mangaId: String) -> [DbChapter] {
        guard let source = SourceIdentity.numericId(sourceId) else { return [] }
        return chapterRepository.getChapters(sourceId: source, mangaUrl: mangaId)
    }

    /// `context` is required here, unlike its siblings: the async overload in ModelConversions
    /// shares these argument labels, and a default would make every call ambiguous.
    func getChapters(sourceId: String, mangaId: String, context: Any?) -> [ChapterObject] {
        sharedChapters(sourceId: sourceId, mangaId: mangaId)
            .map { ChapterObject(row: $0, sourceId: sourceId, mangaId: mangaId) }
    }

    func getChapters(sourceId: String, context: Any? = nil) -> [ChapterObject] {
        guard let source = SourceIdentity.numericId(sourceId) else { return [] }
        return chapterRepository.chaptersWithMangaBySource(sourceId: source).map { item in
            ChapterObject(row: item.chapter, sourceId: sourceId, mangaId: item.mangaUrl)
        }
    }

    func getChapters(context: Any? = nil) -> [DbChapter] {
        chapterRepository.getAllChapters()
    }

    func getChapter(sourceId: String, mangaId: String, chapterId: String, context: Any? = nil) -> ChapterObject? {
        guard let source = SourceIdentity.numericId(sourceId),
              let chapter = chapterRepository.getChapter(
                sourceId: source,
                mangaUrl: mangaId,
                chapterUrl: chapterId
              ) else { return nil }
        return ChapterObject(row: chapter, sourceId: sourceId, mangaId: mangaId)
    }

    /// The shared row for one chapter.
    func sharedChapter(sourceId: String, mangaId: String, chapterId: String) -> DbChapter? {
        guard let source = SourceIdentity.numericId(sourceId) else { return nil }
        return chapterRepository.getChapter(sourceId: source, mangaUrl: mangaId, chapterUrl: chapterId)
    }

    func clearChapters(context: Any? = nil) {
        chapterRepository.clearChapters()
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
        guard let source = SourceIdentity.numericId(sourceId) else { return 0 }
        return Int(chapterRepository.unreadCount(
            sourceId: source,
            mangaUrl: mangaId,
            scanlators: scanlators
        ))
    }

    func getHighestReadNumber(sourceId: String, mangaId: String, context: Any? = nil) -> Float? {
        guard let source = SourceIdentity.numericId(sourceId) else { return nil }
        return chapterRepository.highestReadNumber(sourceId: source, mangaUrl: mangaId)?.floatValue
    }
}

extension SharedDataStore {
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
            MemoJsonKt.setChapterMemoJson(chapter: s, memoJson: chapter.memo)
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

}

extension SharedDataStore {
    /// Unread counts for the whole library in one pass, which is what the library grid badges.
    ///
    /// Upstream runs a single aggregate fetch; here the chapters are read once and tallied, rather
    /// than issuing one count query per entry.
    func libraryUnreadCounts(context: Any? = nil) -> [MangaIdentifier: Int] {
        Dictionary(uniqueKeysWithValues: chapterRepository.libraryUnreadCounts().map {
            (
                MangaIdentifier(sourceKey: SourceIdentity.key(for: $0.sourceId), mangaKey: $0.mangaUrl),
                Int($0.count)
            )
        })
    }
}
