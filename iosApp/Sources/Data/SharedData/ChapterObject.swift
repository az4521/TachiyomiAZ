import ExtensionRunner
import Foundation
import TachiyomiKit

/// A chapter row in the shape the vendored UI expects.
///
/// Upstream's `ChapterObject` is a CoreData entity, and the views address a chapter by its
/// source-specific key (`id`) while the shared row stores that as `url` and keeps a numeric `id` of
/// its own. The two spellings cannot both be called `id`, so this wraps the row and presents
/// upstream's names.
///
/// Writes go straight through to the database: setting `bookmarked` updates the shared row, because
/// a bookmark is library data both apps read.
final class ChapterObject {
    let row: DbChapter
    let sourceId: String
    let mangaId: String

    init(row: DbChapter, sourceId: String, mangaId: String) {
        self.row = row
        self.sourceId = sourceId
        self.mangaId = mangaId
    }

    /// The chapter's source-specific key -- upstream's `id`, the shared row's `url`.
    var id: String { row.url }

    var title: String? { row.name }
    var scanlator: String? { row.scanlator }
    var sourceOrder: Int { Int(row.source_order) }
    var chapter: NSNumber? { row.chapter_number < 0 ? nil : NSNumber(value: row.chapter_number) }
    var volume: NSNumber? { nil }

    var dateUploaded: Date? {
        row.date_upload > 0 ? Date(timeIntervalSince1970: TimeInterval(row.date_upload) / 1000) : nil
    }

    var read: Bool {
        get { row.read }
        set {
            row.read = newValue
            ChapterRepository(db: Database.handler).saveProgress(chapter: row)
        }
    }

    var bookmarked: Bool {
        get { row.bookmark }
        set {
            row.bookmark = newValue
            ChapterRepository(db: Database.handler).saveProgress(chapter: row)
        }
    }

    func toNewChapter() -> ExtensionRunner.Chapter {
        row.toNewChapter()
    }
}

extension ChapterObject {
    /// A Tachiyomi source is single-language, so a chapter has no language of its own and the
    /// shared schema has no column for one. Reported as nil, which reads as "no language filter
    /// applies" at the call sites that check it.
    var lang: String? { nil }
}

extension ChapterObject {
    /// When this chapter was first seen, which is what dates an entry in the updates feed.
    var dateFetched: Date? {
        row.date_fetch > 0 ? Date(timeIntervalSince1970: TimeInterval(row.date_fetch) / 1000) : nil
    }
}
