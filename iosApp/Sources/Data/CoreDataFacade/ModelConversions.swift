import ExtensionRunner
import Foundation
import TachiyomiKit

/// Converts between the shared database's models and the vendored UI's.
///
/// Two model families meet here. `:core-model` defines what a row *is* -- Android's field names,
/// numeric source ids, `url` as the source-specific key -- and the vendored UI carries its own
/// `Manga`/`Chapter` classes with string source ids and its own spellings. Neither is wrong, and
/// neither can be made to be the other, so this is the single seam where one becomes the other.
///
/// Anything the shared schema does not record comes back empty rather than invented: Android folds
/// the volume into `chapter_number` and has no separate column, so `volumeNum` is nil.
extension DbChapter {
    /// - Parameters:
    ///   - sourceId: the owning manga's source, which the chapter row does not carry.
    ///   - mangaId: the owning manga's `url`, which is the UI's `mangaId`.
    func toLegacy(sourceId: String, mangaId: String) -> Chapter {
        Chapter(
            sourceId: sourceId,
            id: url,
            mangaId: mangaId,
            title: name,
            scanlator: scanlator,
            url: url,
            lang: "en",
            chapterNum: chapter_number < 0 ? nil : chapter_number,
            volumeNum: nil,
            dateUploaded: date_upload > 0
                ? Date(timeIntervalSince1970: TimeInterval(date_upload) / 1000)
                : nil,
            thumbnail: nil,
            locked: false,
            sourceOrder: Int(source_order)
        )
    }
}

extension DbManga {
    /// The UI's string source id for this row.
    var legacySourceId: String { SourceIdentity.key(for: source) }

    func toLegacy() -> Manga {
        Manga(
            sourceId: legacySourceId,
            id: url,
            title: title,
            author: author,
            artist: artist,
            description: description_,
            tags: genre?.components(separatedBy: ", "),
            coverUrl: thumbnail_url.flatMap { URL(string: $0) },
            url: URL(string: url),
            status: PublishingStatus(rawValue: Int(status)) ?? .unknown,
            nsfw: .safe,
            viewer: MangaViewer(rawValue: Int(viewer)) ?? .defaultViewer,
            chapterFlags: Int(chapter_flags),
            lastUpdated: last_update > 0
                ? Date(timeIntervalSince1970: TimeInterval(last_update) / 1000)
                : nil,
            dateAdded: date_added > 0
                ? Date(timeIntervalSince1970: TimeInterval(date_added) / 1000)
                : nil
        )
    }
}

extension CoreDataManager {
    /// Chapters as the vendored UI expects them.
    ///
    /// This is upstream's async `getChapters` overload; the synchronous one returns the shared rows,
    /// which is what the facade's own methods work with.
    func getChapters(sourceId: String, mangaId: String) async -> [Chapter] {
        getChapters(sourceId: sourceId, mangaId: mangaId, context: nil).map { $0.toChapter() }
    }
}

extension DbManga {
    /// The vendored UI reads `sourceId` off a manga; the shared row stores the numeric `source`.
    var sourceId: String { legacySourceId }
}

extension Manga {
    /// Upstream's `MangaObject` exposes the cover as `cover`; this model stores it as `coverUrl`.
    var cover: String? { coverUrl?.absoluteString }
}

extension ExtensionRunner.Manga {
    /// A shared database row for this source result.
    func toShared(source: Int64) -> DbManga {
        let record = MangaImpl()
        record.source = source
        record.url = key
        record.title = title
        record.author = authors?.joined(separator: ", ")
        record.artist = artists?.joined(separator: ", ")
        record.description_ = description
        record.genre = tags?.joined(separator: ", ")
        record.thumbnail_url = cover
        record.status = Int32(status.rawValue)
        return record
    }
}

extension Manga {
    /// The runner-facing model, for the vendored views that work in those terms.
    func toNewManga() -> ExtensionRunner.Manga {
        toNew()
    }
}

extension DbChapter {
    func toNewChapter() -> ExtensionRunner.Chapter {
        ExtensionRunner.Chapter(
            key: url,
            title: name,
            chapterNumber: chapter_number < 0 ? nil : chapter_number,
            scanlators: scanlator.map { [$0] },
            url: URL(string: url),
            thumbnail: nil,
            locked: false
        )
    }
}

extension Track {
    /// The tracker view model for this `manga_sync` row.
    func toItem() -> TrackItem {
        TrackItem(
            id: String(media_id),
            trackerId: TrackerService(rawValue: sync_id)?.title ?? String(sync_id),
            sourceId: "",
            mangaId: String(manga_id_),
            title: title,
            state: nil,
            chapterOffset: 0
        )
    }
}

extension LibraryManga {
    /// Upstream's library entity wraps a manga; the shared `LibraryManga` *is* the manga row, with
    /// the unread and last-read columns joined on.
    var manga: LibraryManga { self }
}
