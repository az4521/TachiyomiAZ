import ExtensionRunner
import Foundation
import TachiyomiKit

/// A library entry in the shape the vendored UI expects.
///
/// Upstream's `LibraryMangaObject` is a CoreData entity wrapping a `MangaObject` and carrying five
/// timestamps. The shared schema has two of them -- `last_update` and `date_added` on the manga row
/// -- because Android's library screen sorts by those. The rest (`lastOpened`,
/// `lastUpdatedChapters`, `lastChapter`) drive this app's own ordering and have no column, so they
/// are kept alongside rather than bolted onto a schema both apps share.
///
/// There was a `lastRead` here too. "Recently read" ordering comes from the history table now, the
/// way Android does it, so the field had no reader -- and having one invited the assumption that
/// something maintained it.
///
/// Writes go through immediately, matching the rest of the facade.
final class LibraryMangaObject {
    private let row: DbManga
    private let sourceId: String
    private let mangaId: String

    /// How many chapters this title has, for the "total chapters" library sort.
    ///
    /// Upstream sorts on a `chapterCount` attribute of its CoreData entity. There is no such
    /// column here, but the library query already returns the read and unread counts, and their
    /// sum is the same number -- so it is carried in rather than counted again.
    ///
    /// Zero when built from a plain manga row, which has neither count.
    let totalChapters: Int

    init(row: DbManga, sourceId: String, mangaId: String, totalChapters: Int = 0) {
        self.row = row
        self.sourceId = sourceId
        self.mangaId = mangaId
        self.totalChapters = totalChapters
    }

    /// Upstream's entity points at a manga; here the row *is* the manga, presented under the name
    /// the vendored code uses. Optional to match `MangaObject?`.
    var manga: MangaObjectRef? { MangaObjectRef(row: row, sourceId: sourceId, mangaId: mangaId) }

    /// The shared row's id, for orderings the database answers rather than the row itself.
    var rowId: Int64? { row.id?.int64Value }

    private func key(_ name: String) -> String { "Library.\(name).\(sourceId).\(mangaId)" }

    private func date(_ name: String) -> Date? {
        let stamp = UserDefaults.standard.double(forKey: key(name))
        return stamp > 0 ? Date(timeIntervalSince1970: stamp) : nil
    }

    private func setDate(_ name: String, _ value: Date?) {
        if let value {
            UserDefaults.standard.set(value.timeIntervalSince1970, forKey: key(name))
        } else {
            UserDefaults.standard.removeObject(forKey: key(name))
        }
    }

    /// When the library last refreshed this entry. Backed by the shared `last_update` column, which
    /// is what the Android app reads.
    var lastUpdated: Date? {
        get { row.last_update > 0 ? Date(timeIntervalSince1970: TimeInterval(row.last_update) / 1000) : nil }
        set {
            row.last_update = Int64((newValue?.timeIntervalSince1970 ?? 0) * 1000)
            Database.handler.updateLastUpdated(manga: row)
        }
    }

    var lastOpened: Date? {
        get { date("lastOpened") }
        set { setDate("lastOpened", newValue) }
    }

    /// When new chapters were last found for this entry -- what the "updated" badge keys off.
    var lastUpdatedChapters: Date? {
        get { date("lastUpdatedChapters") }
        set { setDate("lastUpdatedChapters", newValue) }
    }

    /// The upload date of the newest chapter seen.
    var lastChapter: Date? {
        get { date("lastChapter") }
        set { setDate("lastChapter", newValue) }
    }

    var dateAdded: Date? {
        get { row.date_added > 0 ? Date(timeIntervalSince1970: TimeInterval(row.date_added) / 1000) : nil }
        set { row.date_added = Int64((newValue?.timeIntervalSince1970 ?? 0) * 1000) }
    }
}

/// The manga a library entry points at, under upstream's names.
final class MangaObjectRef {
    let row: DbManga
    let sourceId: String
    let mangaId: String

    init(row: DbManga, sourceId: String, mangaId: String) {
        self.row = row
        self.sourceId = sourceId
        self.mangaId = mangaId
    }

    var id: String { row.url }
    var title: String? { row.title }
    var cover: String? { row.thumbnail_url }

    /// Copies a source's fresh details over the stored row.
    func load(from manga: ExtensionRunner.Manga) {
        row.title = manga.title
        row.author = manga.authors?.joined(separator: ", ")
        row.artist = manga.artists?.joined(separator: ", ")
        row.description_ = manga.description
        row.genre = manga.tags?.joined(separator: ", ")
        if let cover = manga.cover { row.thumbnail_url = cover }
        row.status = Int32(manga.status.rawValue)
        row.initialized = true
        Database.handler.insertManga(manga: row)
    }
}

extension MangaObjectRef {
    /// The runner-facing model for this row, which is how the vendored screens carry a manga.
    func toNewManga() -> ExtensionRunner.Manga {
        row.toNewManga()
    }
}

extension MangaObjectRef {
    var author: String? { row.author }
    var artist: String? { row.artist }
    var tags: [String]? { row.genre?.components(separatedBy: ", ") }
    var url: String? { row.url }
    var contentRating: Int16 { 0 }
    var status: Int16 { Int16(row.status) }

    /// Android's schema has no content-rating column -- a Tachiyomi source declares NSFW at the
    /// source level, not per title -- so nothing here is rated.
    var nsfw: Int16 { 0 }
}
