import Foundation
import TachiyomiKit

enum MihonBackupImporter {
    static func load(from url: URL) async throws -> Backup {
        let secured = url.startAccessingSecurityScopedResource()
        defer {
            if secured { url.stopAccessingSecurityScopedResource() }
        }
        let fileData = try Data(contentsOf: url)
        return try await Task.detached(priority: .userInitiated) {
            try TachibkBackupCodec.decode(from: fileData)
        }.value
    }

    static func convert(_ payload: TachiyomiKit.Backup) -> Backup {
        // Membership refers to a category's `order`. The shared model carries no separate id --
        // TachiyomiAZ categories predate Mihon's explicit id field, and every backup this app or
        // the Android one writes numbers them by position.
        let categoryNamesByOrder = Dictionary(
            payload.backupCategories.map { (Int64($0.order), $0.name) },
            uniquingKeysWith: { first, _ in first }
        )

        var backupManga: [BackupManga] = []
        var library: [BackupLibraryManga] = []
        var chapters: [BackupChapter] = []
        var history: [BackupHistory] = []
        var trackItems: [BackupTrackItem] = []

        for manga in payload.backupManga {
            let sourceId = tachiyomixSourceKey(String(manga.source))
            let mangaId = manga.url
            let dateAdded = date(milliseconds: manga.dateAdded)
            let categoryNames = manga.categories.compactMap {
                categoryNamesByOrder[Int64($0.int32Value)]
            }
            let histories = Dictionary(
                manga.history.map { ($0.url, $0) },
                uniquingKeysWith: { first, _ in first }
            )
            for tracking in manga.tracking {
                guard
                    let trackerId = aidokuTrackerId(mihonId: Int(tracking.syncId))
                else {
                    continue
                }
                let remoteId = tracking.mediaId
                guard remoteId != 0 else {
                    continue
                }
                trackItems.append(
                    BackupTrackItem(
                        id: String(remoteId),
                        trackerId: trackerId,
                        mangaId: mangaId,
                        sourceId: sourceId,
                        title: tracking.title
                    )
                )
            }

            backupManga.append(
                BackupManga(
                    id: mangaId,
                    sourceId: sourceId,
                    title: manga.title,
                    author: manga.author,
                    artist: manga.artist,
                    desc: manga.description,
                    tags: manga.genre,
                    cover: manga.thumbnailUrl,
                    url: manga.url,
                    status: aidokuStatus(mihonStatus: Int(manga.status)),
                    viewer: aidokuViewer(
                        mihonFlags: Int(manga.viewerFlags?.int32Value ?? manga.viewer)
                    ),
                    neverUpdate: manga.updateStrategy == .onlyFetchOnce,
                    chapterFlags: Int(manga.chapterFlags),
                    scanlatorFilter: manga.excludedScanlators
                )
            )

            if manga.favorite {
                let lastRead = manga.history
                    .map(\.lastRead)
                    .max()
                    .map { date(milliseconds: $0) }
                library.append(
                    BackupLibraryManga(
                        lastRead: lastRead,
                        dateAdded: dateAdded,
                        categories: categoryNames,
                        mangaId: mangaId,
                        sourceId: sourceId
                    )
                )
            }

            for chapter in manga.chapters {
                chapters.append(
                    BackupChapter(
                        sourceId: sourceId,
                        mangaId: mangaId,
                        id: chapter.url,
                        title: chapter.name,
                        scanlator: chapter.scanlator,
                        url: chapter.url,
                        chapter: chapter.chapterNumber,
                        dateUploaded: optionalDate(
                            milliseconds: chapter.dateUpload
                        ),
                        bookmarked: chapter.bookmark,
                        sourceOrder: Int(chapter.sourceOrder)
                    )
                )

                let matchingHistory = histories[chapter.url]
                if
                    chapter.read ||
                    chapter.lastPageRead > 0 ||
                    matchingHistory != nil
                {
                    let lastRead = matchingHistory?.lastRead ?? max(
                        chapter.dateUpload,
                        chapter.dateFetch
                    )
                    history.append(
                        BackupHistory(
                            dateRead: date(milliseconds: lastRead),
                            sourceId: sourceId,
                            chapterId: chapter.url,
                            mangaId: mangaId,
                            progress: aidokuProgress(
                                mihonLastPageRead: Int64(chapter.lastPageRead),
                                hasHistory: matchingHistory != nil
                            ),
                            completed: chapter.read
                        )
                    )
                }
            }
        }

        return Backup(
            library: library,
            history: history,
            manga: backupManga,
            chapters: chapters,
            trackItems: trackItems,
            readingSessions: [],
            updates: [],
            categories: payload.backupCategories
                .sorted { $0.order < $1.order }
                .map { BackupCategory(title: $0.name, sort: Int($0.order)) },
            sources: payload.backupSources.map {
                BackupSource(id: tachiyomixSourceKey(String($0.sourceId)))
            },
            sourceLists: [],
            settings: nil,
            date: .now,
            name: "Imported Mihon backup",
            automatic: false,
            version: "Mihon/TachiyomiAZ"
        )
    }

    private static func date(milliseconds: Int64) -> Date {
        guard milliseconds > 0 else { return .distantPast }
        return Date(
            timeIntervalSince1970: TimeInterval(milliseconds) / 1_000
        )
    }

    private static func optionalDate(milliseconds: Int64) -> Date? {
        guard milliseconds > 0 else { return nil }
        return date(milliseconds: milliseconds)
    }

    private static func tachiyomixSourceKey(_ sourceId: String) -> String {
        guard !sourceId.hasPrefix("mihon.") else {
            return sourceId
        }
        return "mihon.\(sourceId)"
    }

    static func aidokuViewer(mihonFlags: Int) -> Int {
        // Mihon stores the reading mode in the low three bits. Its standard
        // modes use the same raw ordering as Aidoku. TachiyomiAZ additionally
        // has horizontal-continuous modes, which degrade to the matching
        // paged direction because Aidoku has no direct equivalent.
        switch mihonFlags & 0x7 {
            case 1, 6: 1 // left-to-right
            case 2, 7: 2 // right-to-left
            case 3: 3 // vertical pager
            case 4, 5: 4 // webtoon and continuous vertical
            default: 0
        }
    }

    static func aidokuStatus(mihonStatus: Int) -> Int {
        // Mihon has extra "licensed" and "publishing finished" states between
        // completed and cancelled. Translate explicitly instead of treating
        // the Android raw value as an Aidoku enum raw value.
        switch mihonStatus {
            case 1: 1 // ongoing
            case 2, 4: 2 // completed / publishing finished
            case 5: 3 // cancelled
            case 6: 4 // hiatus
            default: 0 // unknown / licensed
        }
    }

    static func aidokuProgress(
        mihonLastPageRead: Int64,
        hasHistory: Bool
    ) -> Int {
        // Mihon persists Page.index (zero based). Aidoku persists the visible
        // page number (one based), as confirmed by its Suwayomi tracker doing
        // the inverse subtraction when exporting progress.
        guard mihonLastPageRead > 0 || hasHistory else {
            return 0
        }
        guard mihonLastPageRead < Int64.max else {
            return Int.max
        }
        return Int(clamping: mihonLastPageRead + 1)
    }

    /// The tracker a `sync_id` names.
    ///
    /// The inverse of the codec's `mihonTrackerId`, and for the same reason it reads from
    /// `TrackerSyncId`: this was a four-entry list that quietly discarded any tracked title from a
    /// service outside it while importing.
    private static func aidokuTrackerId(mihonId: Int) -> String? {
        TrackerSyncId.trackerId(for: Int32(mihonId))
    }
}
