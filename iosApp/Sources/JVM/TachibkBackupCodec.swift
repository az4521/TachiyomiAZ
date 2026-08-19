import Foundation
import TachiJVMRunner
import TachiyomiKit

/// Reads and writes `.tachibk` backups.
///
/// The protobuf itself is not handled here. `:core-domain` owns the models, their field numbers
/// and the codec, and the Android app encodes through the same ones -- so "both apps agree on the
/// format" is structural rather than something kept true by hand. This file used to carry its own
/// protobuf reader and writer, transcribed field by field from those models; that was two
/// implementations of one format, and the only thing keeping them aligned was care.
///
/// What is left is the part that genuinely differs: gzip, and translating between the shared
/// backup model and the Aidoku-shaped one the vendored UI works in.
enum TachibkBackupCodec {
    enum CodecError: LocalizedError {
        case invalidBackup

        var errorDescription: String? {
            switch self {
                case .invalidBackup:
                    NSLocalizedString("BACKUP_ERROR")
            }
        }
    }

    // MARK: - Writing

    static func encode(_ backup: Backup) throws -> Data {
        let library = Dictionary(
            (backup.library ?? []).map { ($0.identifier, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        let favourites = Set(library.keys)
        let chapters = Dictionary(grouping: backup.chapters ?? []) {
            MangaIdentifier(sourceKey: $0.sourceId, mangaKey: $0.mangaId)
        }
        let histories = Dictionary(grouping: backup.history ?? []) {
            MangaIdentifier(sourceKey: $0.sourceId, mangaKey: $0.mangaId)
        }
        let trackers = Dictionary(grouping: backup.trackItems ?? []) {
            MangaIdentifier(sourceKey: $0.sourceId, mangaKey: $0.mangaId)
        }
        // Time spent reading is per-session here and per-chapter in the shared format, so the
        // sessions for a chapter are summed into its history entry.
        let durations = Dictionary(grouping: backup.readingSessions ?? []) { $0.identifier }
            .mapValues { sessions in
                sessions.reduce(Int64(0)) { total, session in
                    total + max(0, Int64(session.endDate.timeIntervalSince(session.startDate) * 1000))
                }
            }

        // Category membership is by name here and by index there, so the order fixes the ids.
        let categories = (backup.categories ?? []).sorted {
            ($0.sort ?? 0, $0.title ?? "") < ($1.sort ?? 0, $1.title ?? "")
        }
        let categoryIds = Dictionary(
            categories.enumerated().compactMap { index, category in
                category.title.map { ($0, Int32(index)) }
            },
            uniquingKeysWith: { first, _ in first }
        )

        let backupManga: [TachiyomiKit.BackupManga] = (backup.manga ?? []).compactMap { item -> TachiyomiKit.BackupManga? in
            let identifier = MangaIdentifier(sourceKey: item.sourceId, mangaKey: item.id)
            guard
                favourites.contains(identifier),
                let source = SourceIdentity.numericId(item.sourceId)
            else { return nil }

            let itemChapters = chapters[identifier] ?? []
            let itemHistory = histories[identifier] ?? []
            let progressByChapter = Dictionary(
                itemHistory.map { ($0.chapterId, $0) },
                uniquingKeysWith: { first, _ in first }
            )

            let shared = TachiyomiKit.BackupManga(
                source: source,
                url: item.url ?? item.id,
                title: item.title,
                artist: item.artist,
                author: item.author,
                description: item.desc,
                genre: item.tags ?? [],
                status: Int32(mihonStatus(item.status)),
                thumbnailUrl: item.cover,
                dateAdded: milliseconds(library[identifier]?.dateAdded),
                viewer: Int32(mihonViewer(item.viewer)),
                chapters: itemChapters.map { chapter in
                    let progress = progressByChapter[chapter.id]
                    return TachiyomiKit.BackupChapter(
                        url: chapter.url ?? chapter.id,
                        name: chapter.title ?? chapter.id,
                        scanlator: chapter.scanlator,
                        read: progress?.completed ?? false,
                        bookmark: chapter.bookmarked ?? false,
                        // Stored one-based here and as a resume index there.
                        lastPageRead: Int32(max(0, (progress?.progress ?? 0) - 1)),
                        dateFetch: milliseconds(chapter.dateUploaded),
                        dateUpload: milliseconds(chapter.dateUploaded),
                        chapterNumber: chapter.chapter ?? -1,
                        sourceOrder: Int32(chapter.sourceOrder),
                        memo: Data("{}".utf8).kotlinByteArray
                    )
                },
                categories: (library[identifier]?.categories ?? [])
                    .compactMap { categoryIds[$0] }
                    .map { KotlinInt(int: $0) },
                tracking: (trackers[identifier] ?? []).compactMap { tracker -> TachiyomiKit.BackupTracking? in
                    guard
                        let sync = TrackerSyncId.syncId(for: tracker.trackerId),
                        let mediaId = Int64(tracker.id)
                    else { return nil }
                    return TachiyomiKit.BackupTracking(
                        syncId: sync,
                        libraryId: 0,
                        mediaId: mediaId,
                        trackingUrl: "",
                        title: tracker.title ?? "",
                        lastChapterRead: 0,
                        totalChapters: 0,
                        score: 0,
                        status: 0,
                        startedReadingDate: 0,
                        finishedReadingDate: 0,
                        private: false
                    )
                },
                favorite: true,
                chapterFlags: Int32(item.chapterFlags ?? 0),
                brokenHistory: [],
                history: itemHistory.compactMap { entry in
                    let chapter = itemChapters.first { $0.id == entry.chapterId }
                    return TachiyomiKit.BackupHistory(
                        url: chapter?.url ?? entry.chapterId,
                        lastRead: milliseconds(entry.dateRead),
                        readDuration: durations[
                            ChapterIdentifier(
                                sourceKey: entry.sourceId,
                                mangaKey: entry.mangaId,
                                chapterKey: entry.chapterId
                            )
                        ] ?? 0
                    )
                },
                updateStrategy: item.neverUpdate == true ? .onlyFetchOnce : .alwaysUpdate,
                // Left unset. `viewer_flags` is Mihon's, where the reading mode occupies the low
                // three bits alongside other flags; TachiyomiAZ has no such column -- `mangas.sq`
                // declares `viewer` and nothing else -- so a bare mode written here would be a
                // value with the wrong meaning in a field this app never reads.
                //
                // The previous encoder wrote the mode to `viewer_flags` and left `viewer` empty,
                // which is the wrong way round: a backup taken on iOS and restored on Android lost
                // its reading mode, because Android reads `viewer`. Reading 103 on import stays --
                // Mihon writes it, and `aidokuViewer` masks the mode out of the flags.
                viewerFlags: nil,
                excludedScanlators: item.scanlatorFilter ?? [],
                memo: Data("{}".utf8).kotlinByteArray,
                flatMetadata: nil
            )
            return shared
        }

        let shared = TachiyomiKit.Backup(
            backupManga: backupManga,
            backupCategories: categories.enumerated().compactMap { index, category in
                category.title.map {
                    TachiyomiKit.BackupCategory(name: $0, order: Int32(index), flags: 0, mangaOrder: [])
                }
            },
            backupBrokenSources: [],
            backupSources: Set((backup.manga ?? []).map(\.sourceId)).sorted().compactMap { key in
                SourceIdentity.numericId(key).map {
                    TachiyomiKit.BackupSource(name: SourceManager.shared.name(for: key) ?? key, sourceId: $0)
                }
            },
            backupSavedSearches: [],
            // This app's own state, in the field the shared model declares for it.
            iosState: try nativeEncoder.encode(backup).kotlinByteArray
        )

        return try TachiJVMCompression.gzip(BackupCodec.shared.encode(backup: shared).data)
    }

    // MARK: - Reading

    static func decode(from data: Data) throws -> Backup {
        let shared = try decodeShared(from: data)

        // A backup this app wrote carries its own state verbatim; anything else -- Mihon, the
        // Android app -- is converted from the shared model.
        if let state = shared.iosState?.data, let native = try? nativeDecoder.decode(Backup.self, from: state) {
            return native
        }
        guard !shared.backupManga.isEmpty || !shared.backupCategories.isEmpty else {
            throw CodecError.invalidBackup
        }
        return MihonBackupImporter.convert(shared)
    }

    /// The shared model, for callers that want it before it is reshaped.
    static func decodeShared(from data: Data) throws -> TachiyomiKit.Backup {
        BackupCodec.shared.decode(bytes: try uncompressed(data).kotlinByteArray)
    }

    private static func uncompressed(_ data: Data) throws -> Data {
        if data.starts(with: [0x1f, 0x8b]) {
            return try TachiJVMCompression.gunzip(data)
        }
        return data
    }

    // MARK: - Vocabularies

    private static var nativeEncoder: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .secondsSince1970
        return encoder
    }

    private static var nativeDecoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        return decoder
    }

    private static func milliseconds(_ date: Date?) -> Int64 {
        guard let date, date != .distantPast else { return 0 }
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    /// Mihon has extra states between completed and cancelled, so this is an explicit translation
    /// rather than passing a raw value through as if the two enums agreed.
    private static func mihonStatus(_ status: Int) -> Int {
        switch status {
            case 1: 1 // ongoing
            case 2: 2 // completed
            case 3: 5 // cancelled
            case 4: 6 // hiatus
            default: 0
        }
    }

    private static func mihonViewer(_ viewer: Int) -> Int {
        switch viewer {
            case 1: 1 // left-to-right
            case 2: 2 // right-to-left
            case 3: 3 // vertical
            case 4: 4 // webtoon
            default: 0
        }
    }
}

extension Data {
    /// Kotlin/Native exposes `ByteArray` as `KotlinByteArray`, which has no bulk initialiser.
    var kotlinByteArray: KotlinByteArray {
        let array = KotlinByteArray(size: Int32(count))
        withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            guard let base = raw.bindMemory(to: Int8.self).baseAddress else { return }
            for index in 0..<count {
                array.set(index: Int32(index), value: base[index])
            }
        }
        return array
    }
}

extension KotlinByteArray {
    var data: Data {
        var bytes = [UInt8]()
        bytes.reserveCapacity(Int(size))
        for index in 0..<size {
            bytes.append(UInt8(bitPattern: get(index: index)))
        }
        return Data(bytes)
    }
}
