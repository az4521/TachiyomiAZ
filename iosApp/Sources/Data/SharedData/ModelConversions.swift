import ExtensionRunner
import Foundation
import TachiyomiKit

/// Converts shared database rows into the models the rest of the app works in.
///
/// Two families meet here, not three. `:core-model` defines what a row *is* -- Android's field
/// names, numeric source ids, `url` as the source-specific key -- and `ExtensionRunner` defines what
/// a source returns. The vendored UI used to carry a third set of its own, and everything crossed
/// twice to reach it; those are gone, and the views hold the runner models directly, which is the
/// same two-model shape the Android app has.
///
/// Anything the shared schema does not record comes back empty rather than invented.

extension SharedDataStore {
    /// A title's chapters, in the terms the extension boundary uses.
    ///
    /// This returned the Aidoku-shaped model until every caller converted it straight back with
    /// `toNew()`. The synchronous overload returns the shared rows, which is what the facade's own
    /// methods work with.
    func getChapters(sourceId: String, mangaId: String) async -> [ExtensionRunner.Chapter] {
        getChapters(sourceId: sourceId, mangaId: mangaId, context: nil).map { $0.toNewChapter() }
    }
}

extension DbManga {
    /// The string source id, from the numeric one the row stores.
    var legacySourceId: String { SourceIdentity.key(for: source) }

    /// Callers that read `sourceId` off a manga, as a source result carries it.
    var sourceId: String { legacySourceId }
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
        record.status = status.tachiyomiXValue
        record.update_strategy = updateStrategy == .never
            ? UpdateStrategy.onlyFetchOnce
            : UpdateStrategy.alwaysUpdate
        MemoJsonKt.setMangaMemoJson(manga: record, memoJson: memo)
        return record
    }
}

extension ExtensionRunner.PublishingStatus {
    var tachiyomiXValue: Int32 {
        switch self {
            case .ongoing: 1
            case .completed: 2
            case .cancelled: 5
            case .hiatus: 6
            case .unknown: 0
        }
    }
}

extension DbManga {
    /// A shared row in the terms the extension boundary uses.
    ///
    /// Direct, rather than going through the Aidoku-shaped model on the way. That hop was doing no
    /// work: every field here comes off the row, and routing them through a third representation
    /// only created somewhere for them to be dropped.
    func toNewManga() -> ExtensionRunner.Manga {
        ExtensionRunner.Manga(
            sourceKey: legacySourceId,
            key: url,
            title: title,
            cover: thumbnail_url,
            artists: artist.map { [$0] },
            authors: author.map { [$0] },
            description: description_,
            url: URL(string: url),
            tags: genre?.components(separatedBy: ", "),
            status: PublishingStatus(rawValue: Int(status))?.toNew() ?? .unknown,
            contentRating: .safe,
            viewer: (MangaViewer(rawValue: Int(viewer)) ?? .defaultViewer).toNew(),
            updateStrategy: update_strategy == UpdateStrategy.onlyFetchOnce ? .never : .always,
            nextUpdateTime: nil,
            chapters: nil,
            memo: memoJson
        )
    }
}

extension DbChapter {
    func toNewChapter() -> ExtensionRunner.Chapter {
        ExtensionRunner.Chapter(
            key: url,
            title: name,
            chapterNumber: chapter_number < 0 ? nil : chapter_number,
            dateUploaded: date_upload > 0
                ? Date(timeIntervalSince1970: Double(date_upload) / 1_000)
                : nil,
            scanlators: scanlator.map { [$0] },
            url: URL(string: url),
            thumbnail: nil,
            locked: false,
            memo: memoJson
        )
    }
}

extension Track {
    /// The tracker view model for this `manga_sync` row.
    ///
    /// Komga, Kavita and Suwayomi identify a title by a composite string rather than a number, so
    /// theirs is read back from `tracking_url` -- see `TrackerSyncId.usesTrackingUrlAsId`.
    func toItem() -> TrackItem {
        TrackItem(
            id: TrackerSyncId.usesTrackingUrlAsId(syncId: sync_id) ? tracking_url : String(media_id),
            trackerId: TrackerSyncId.trackerId(for: sync_id) ?? String(sync_id),
            sourceId: "",
            mangaId: String(manga_id_),
            title: title,
            state: nil,
            chapterOffset: 0
        )
    }
}


// A library row is a MangaImpl, so it takes `toNewManga()` from the extension above -- it does not
// need one of its own, and having one meant the library screens converted by a different route
// than everything else.
