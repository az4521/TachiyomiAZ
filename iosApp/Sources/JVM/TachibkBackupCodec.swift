import Foundation
import TachiJVMRunner

enum TachibkBackupCodec {
    enum CodecError: LocalizedError {
        case invalidBackup

        var errorDescription: String? {
            switch self {
                case .invalidBackup:
                    "The file is not a valid .tachibk backup."
            }
        }
    }

    // Unknown fields are retained as opaque data by protobuf readers. Mihon
    // ignores this field, while TachiyomiAZ iOS uses it to round-trip state
    // that has no Android backup equivalent (settings, updates, and sessions).
    private static let nativeBackupField = 1_000

    static func encode(_ backup: Backup) throws -> Data {
        var root = ProtobufWriter()
        let library = Dictionary(
            (backup.library ?? []).map { ($0.identifier, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        let favoriteIdentifiers = Set(library.keys)
        let manga = (backup.manga ?? []).filter {
            favoriteIdentifiers.contains(
                MangaIdentifier(sourceKey: $0.sourceId, mangaKey: $0.id)
            ) && numericSourceId($0.sourceId) != nil
        }
        let chapters = Dictionary(grouping: backup.chapters ?? []) {
            MangaIdentifier(sourceKey: $0.sourceId, mangaKey: $0.mangaId)
        }
        let histories = Dictionary(grouping: backup.history ?? []) {
            MangaIdentifier(sourceKey: $0.sourceId, mangaKey: $0.mangaId)
        }
        let trackers = Dictionary(grouping: backup.trackItems ?? []) {
            MangaIdentifier(sourceKey: $0.sourceId, mangaKey: $0.mangaId)
        }
        let durations = Dictionary(grouping: backup.readingSessions ?? []) {
            $0.identifier
        }.mapValues {
            $0.reduce(Int64(0)) { total, session in
                total + max(
                    0,
                    Int64(session.endDate.timeIntervalSince(session.startDate) * 1_000)
                )
            }
        }

        let categories = (backup.categories ?? []).sorted {
            ($0.sort ?? 0, $0.title ?? "") < ($1.sort ?? 0, $1.title ?? "")
        }
        let categoryIds = Dictionary(
            categories.enumerated().compactMap {
                index, category in category.title.map { ($0, Int64(index)) }
            },
            uniquingKeysWith: { first, _ in first }
        )

        for item in manga {
            let identifier = MangaIdentifier(
                sourceKey: item.sourceId,
                mangaKey: item.id
            )
            guard let source = numericSourceId(item.sourceId) else { continue }
            let libraryItem = library[identifier]
            root.message(1) { value in
                value.int64(1, source)
                value.string(2, item.url ?? item.id)
                value.string(3, item.title)
                value.optionalString(4, item.artist)
                value.optionalString(5, item.author)
                value.optionalString(6, item.desc)
                for genre in item.tags ?? [] { value.string(7, genre) }
                value.int(8, mihonStatus(item.status))
                value.optionalString(9, item.cover)
                value.int64(13, milliseconds(libraryItem?.dateAdded))

                let itemHistory = histories[identifier] ?? []
                let historyByChapter = Dictionary(
                    itemHistory.map { ($0.chapterId, $0) },
                    uniquingKeysWith: { first, _ in first }
                )
                for chapter in chapters[identifier] ?? [] {
                    let history = historyByChapter[chapter.id]
                    value.message(16) { chapterValue in
                        chapterValue.string(1, chapter.url ?? chapter.id)
                        chapterValue.string(2, chapter.title ?? "")
                        chapterValue.optionalString(3, chapter.scanlator)
                        chapterValue.bool(4, history?.completed ?? false)
                        chapterValue.bool(5, chapter.bookmarked ?? false)
                        chapterValue.int64(
                            6,
                            Int64(max(0, (history?.progress ?? 0) - 1))
                        )
                        chapterValue.int64(8, milliseconds(chapter.dateUploaded))
                        chapterValue.float(9, chapter.chapter ?? -1)
                        chapterValue.int64(10, Int64(chapter.sourceOrder))
                    }
                }

                let memberships = (libraryItem?.categories ?? []).compactMap {
                    categoryIds[$0]
                }
                value.packedInt64(17, memberships)
                for tracker in trackers[identifier] ?? [] {
                    guard
                        let syncId = mihonTrackerId(tracker.trackerId),
                        let mediaId = Int64(tracker.id)
                    else { continue }
                    value.message(18) { trackerValue in
                        trackerValue.int(1, syncId)
                        trackerValue.int64(2, 0)
                        if let legacyId = Int32(exactly: mediaId) {
                            trackerValue.int(3, Int(legacyId))
                        }
                        trackerValue.optionalString(5, tracker.title)
                        trackerValue.int64(100, mediaId)
                    }
                }
                value.bool(100, true)
                value.int(101, item.chapterFlags ?? 0)
                value.int(103, mihonViewer(item.viewer))
                for history in itemHistory {
                    let chapter = (chapters[identifier] ?? []).first {
                        $0.id == history.chapterId
                    }
                    value.message(104) { historyValue in
                        historyValue.string(1, chapter?.url ?? history.chapterId)
                        historyValue.int64(2, milliseconds(history.dateRead))
                        historyValue.int64(
                            3,
                            durations[
                                ChapterIdentifier(
                                    sourceKey: history.sourceId,
                                    mangaKey: history.mangaId,
                                    chapterKey: history.chapterId
                                )
                            ] ?? 0
                        )
                    }
                }
                value.int(105, item.neverUpdate == true ? 1 : 0)
                for scanlator in item.scanlatorFilter ?? [] {
                    value.string(108, scanlator)
                }
                value.bool(111, true)
            }
        }

        for (index, category) in categories.enumerated() {
            guard let name = category.title else { continue }
            root.message(2) { value in
                value.string(1, name)
                value.int64(2, Int64(index))
                // Matching id and order keeps memberships compatible with
                // both TachiyomiAZ's order-based and Mihon's id-based restore.
                value.int64(3, Int64(index))
            }
        }

        let sourceIds = Set(manga.map(\.sourceId))
        for sourceKey in sourceIds.sorted() {
            guard let sourceId = numericSourceId(sourceKey) else { continue }
            root.message(101) { value in
                value.string(
                    1,
                    SourceManager.shared.name(for: sourceKey)
                        ?? sourceKey
                )
                value.int64(2, sourceId)
            }
        }

        let nativeEncoder = JSONEncoder()
        nativeEncoder.dateEncodingStrategy = .secondsSince1970
        root.bytes(nativeBackupField, try nativeEncoder.encode(backup))
        return try TachiJVMCompression.gzip(root.data)
    }

    /// Decodes both Tachiyomi/Mihon protobuf backups and backups containing
    /// TachiyomiAZ iOS's optional native state. Backup decoding deliberately
    /// stays out of the Java extension host: `.tachibk` is an application data
    /// format, not an extension API.
    static func decode(from data: Data) throws -> Backup {
        let uncompressed = try uncompressed(data)
        if let native = try decodeNativeBackup(fromUncompressed: uncompressed) {
            return native
        }
        return MihonBackupImporter.convert(
            try decodeStandardPayload(from: uncompressed)
        )
    }

    static func decodeNativeBackup(from data: Data) throws -> Backup? {
        try decodeNativeBackup(fromUncompressed: uncompressed(data))
    }

    private static func uncompressed(_ data: Data) throws -> Data {
        if data.starts(with: [0x1f, 0x8b]) {
            return try TachiJVMCompression.gunzip(data)
        }
        return data
    }

    private static func decodeNativeBackup(
        fromUncompressed uncompressed: Data
    ) throws -> Backup? {
        var reader = ProtobufReader(data: uncompressed)
        while let tag = try reader.nextTag() {
            if tag.field == nativeBackupField, tag.wire == 2 {
                let payload = try reader.bytes()
                let decoder = JSONDecoder()
                decoder.dateDecodingStrategy = .secondsSince1970
                return try decoder.decode(Backup.self, from: payload)
            }
            try reader.skip(wire: tag.wire)
        }
        return nil
    }

    private static func decodeStandardPayload(
        from data: Data
    ) throws -> MihonBackupImporter.Payload {
        var reader = ProtobufReader(data: data)
        var manga: [MihonBackupImporter.Manga] = []
        var categories: [MihonBackupImporter.Category] = []
        var sources: [MihonBackupImporter.Source] = []

        while let tag = try reader.nextTag() {
            switch (tag.field, tag.wire) {
                case (1, 2):
                    manga.append(try decodeManga(from: reader.bytes()))
                case (2, 2):
                    categories.append(try decodeCategory(from: reader.bytes()))
                case (101, 2):
                    sources.append(try decodeSource(from: reader.bytes()))
                default:
                    try reader.skip(wire: tag.wire)
            }
        }

        guard !manga.isEmpty || !categories.isEmpty || !sources.isEmpty else {
            throw CodecError.invalidBackup
        }
        return .init(manga: manga, categories: categories, sources: sources)
    }

    private static func decodeManga(
        from data: Data
    ) throws -> MihonBackupImporter.Manga {
        var reader = ProtobufReader(data: data)
        var source: Int64 = 0
        var url = ""
        var title = ""
        var artist: String?
        var author: String?
        var description: String?
        var genre: [String] = []
        var status = 0
        var thumbnailURL: String?
        var dateAdded: Int64 = 0
        var viewer = 0
        var chapters: [MihonBackupImporter.Chapter] = []
        var tracking: [MihonBackupImporter.Tracking] = []
        var categories: [Int64] = []
        var favorite = true
        var chapterFlags = 0
        var viewerFlags: Int?
        var history: [MihonBackupImporter.History] = []
        var updateStrategy = 0
        var excludedScanlators: [String] = []
        var notes = ""
        var initialized = false

        while let tag = try reader.nextTag() {
            switch tag.field {
                case 1: source = try reader.int64(wire: tag.wire)
                case 2: url = try reader.string(wire: tag.wire)
                case 3: title = try reader.string(wire: tag.wire)
                case 4: artist = try reader.string(wire: tag.wire)
                case 5: author = try reader.string(wire: tag.wire)
                case 6: description = try reader.string(wire: tag.wire)
                case 7: genre.append(try reader.string(wire: tag.wire))
                case 8: status = try reader.int(wire: tag.wire)
                case 9: thumbnailURL = try reader.string(wire: tag.wire)
                case 13: dateAdded = try reader.int64(wire: tag.wire)
                case 14: viewer = try reader.int(wire: tag.wire)
                case 16:
                    try requireWire(tag.wire, 2)
                    chapters.append(try decodeChapter(from: reader.bytes()))
                case 17:
                    if tag.wire == 2 {
                        var packed = ProtobufReader(data: try reader.bytes())
                        while !packed.isAtEnd {
                            categories.append(try packed.int64(wire: 0))
                        }
                    } else {
                        categories.append(try reader.int64(wire: tag.wire))
                    }
                case 18:
                    try requireWire(tag.wire, 2)
                    tracking.append(try decodeTracking(from: reader.bytes()))
                case 100: favorite = try reader.bool(wire: tag.wire)
                case 101: chapterFlags = try reader.int(wire: tag.wire)
                case 103: viewerFlags = try reader.int(wire: tag.wire)
                case 104:
                    try requireWire(tag.wire, 2)
                    history.append(try decodeHistory(from: reader.bytes()))
                case 105: updateStrategy = try reader.int(wire: tag.wire)
                case 108:
                    excludedScanlators.append(
                        try reader.string(wire: tag.wire)
                    )
                case 110: notes = try reader.string(wire: tag.wire)
                case 111: initialized = try reader.bool(wire: tag.wire)
                default: try reader.skip(wire: tag.wire)
            }
        }

        return .init(
            source: String(source),
            url: url,
            title: title,
            artist: artist,
            author: author,
            description: description,
            genre: genre,
            status: status,
            thumbnailUrl: thumbnailURL,
            dateAdded: dateAdded,
            viewer: viewer,
            chapters: chapters,
            tracking: tracking,
            categories: categories,
            favorite: favorite,
            chapterFlags: chapterFlags,
            viewerFlags: viewerFlags,
            history: history,
            updateStrategy: updateStrategy,
            excludedScanlators: excludedScanlators,
            notes: notes,
            initialized: initialized
        )
    }

    private static func decodeChapter(
        from data: Data
    ) throws -> MihonBackupImporter.Chapter {
        var reader = ProtobufReader(data: data)
        var url = ""
        var name = ""
        var scanlator: String?
        var read = false
        var bookmark = false
        var lastPageRead: Int64 = 0
        var dateFetch: Int64 = 0
        var dateUpload: Int64 = 0
        var chapterNumber: Float = 0
        var sourceOrder: Int64 = 0

        while let tag = try reader.nextTag() {
            switch tag.field {
                case 1: url = try reader.string(wire: tag.wire)
                case 2: name = try reader.string(wire: tag.wire)
                case 3: scanlator = try reader.string(wire: tag.wire)
                case 4: read = try reader.bool(wire: tag.wire)
                case 5: bookmark = try reader.bool(wire: tag.wire)
                case 6: lastPageRead = try reader.int64(wire: tag.wire)
                case 7: dateFetch = try reader.int64(wire: tag.wire)
                case 8: dateUpload = try reader.int64(wire: tag.wire)
                case 9: chapterNumber = try reader.float(wire: tag.wire)
                case 10: sourceOrder = try reader.int64(wire: tag.wire)
                default: try reader.skip(wire: tag.wire)
            }
        }
        return .init(
            url: url,
            name: name,
            scanlator: scanlator,
            read: read,
            bookmark: bookmark,
            lastPageRead: lastPageRead,
            dateFetch: dateFetch,
            dateUpload: dateUpload,
            chapterNumber: chapterNumber,
            sourceOrder: sourceOrder
        )
    }

    private static func decodeHistory(
        from data: Data
    ) throws -> MihonBackupImporter.History {
        var reader = ProtobufReader(data: data)
        var url = ""
        var lastRead: Int64 = 0
        var readDuration: Int64 = 0
        while let tag = try reader.nextTag() {
            switch tag.field {
                case 1: url = try reader.string(wire: tag.wire)
                case 2: lastRead = try reader.int64(wire: tag.wire)
                case 3: readDuration = try reader.int64(wire: tag.wire)
                default: try reader.skip(wire: tag.wire)
            }
        }
        return .init(
            url: url,
            lastRead: lastRead,
            readDuration: readDuration
        )
    }

    private static func decodeTracking(
        from data: Data
    ) throws -> MihonBackupImporter.Tracking {
        var reader = ProtobufReader(data: data)
        var syncId = 0
        var mediaIdInt = 0
        var mediaId: Int64 = 0
        var title = ""
        while let tag = try reader.nextTag() {
            switch tag.field {
                case 1: syncId = try reader.int(wire: tag.wire)
                case 3: mediaIdInt = try reader.int(wire: tag.wire)
                case 5: title = try reader.string(wire: tag.wire)
                case 100: mediaId = try reader.int64(wire: tag.wire)
                default: try reader.skip(wire: tag.wire)
            }
        }
        return .init(
            syncId: syncId,
            mediaIdInt: mediaIdInt,
            mediaId: mediaId,
            title: title
        )
    }

    private static func decodeCategory(
        from data: Data
    ) throws -> MihonBackupImporter.Category {
        var reader = ProtobufReader(data: data)
        var name = ""
        var order: Int64 = 0
        var id: Int64 = 0
        var flags: Int64 = 0
        while let tag = try reader.nextTag() {
            switch tag.field {
                case 1: name = try reader.string(wire: tag.wire)
                case 2: order = try reader.int64(wire: tag.wire)
                case 3: id = try reader.int64(wire: tag.wire)
                case 100: flags = try reader.int64(wire: tag.wire)
                default: try reader.skip(wire: tag.wire)
            }
        }
        return .init(name: name, order: order, id: id, flags: flags)
    }

    private static func decodeSource(
        from data: Data
    ) throws -> MihonBackupImporter.Source {
        var reader = ProtobufReader(data: data)
        var name = ""
        var id: Int64 = 0
        while let tag = try reader.nextTag() {
            switch tag.field {
                case 1: name = try reader.string(wire: tag.wire)
                case 2: id = try reader.int64(wire: tag.wire)
                default: try reader.skip(wire: tag.wire)
            }
        }
        return .init(name: name, id: String(id))
    }

    private static func requireWire(_ actual: Int, _ expected: Int) throws {
        guard actual == expected else { throw CodecError.invalidBackup }
    }

    private static func numericSourceId(_ key: String) -> Int64? {
        let raw = key.hasPrefix("mihon.") ? String(key.dropFirst(6)) : key
        return Int64(raw)
    }

    private static func milliseconds(_ date: Date?) -> Int64 {
        guard let date, date != .distantPast else { return 0 }
        return Int64(date.timeIntervalSince1970 * 1_000)
    }

    private static func mihonStatus(_ status: Int) -> Int {
        switch status {
            case 1: 1
            case 2: 2
            case 3: 5
            case 4: 6
            default: 0
        }
    }

    private static func mihonViewer(_ viewer: Int) -> Int {
        switch viewer {
            case 1: 1
            case 2: 2
            case 3: 3
            case 4: 4
            default: 0
        }
    }

    private static func mihonTrackerId(_ tracker: String) -> Int? {
        switch tracker {
            case "myanimelist": 1
            case "anilist": 2
            case "shikimori": 4
            case "bangumi": 5
            default: nil
        }
    }
}

private struct ProtobufWriter {
    var data = Data()

    mutating func int(_ field: Int, _ value: Int) {
        int64(field, Int64(value))
    }

    mutating func int64(_ field: Int, _ value: Int64) {
        tag(field, wire: 0)
        varint(UInt64(bitPattern: value))
    }

    mutating func bool(_ field: Int, _ value: Bool) {
        int64(field, value ? 1 : 0)
    }

    mutating func float(_ field: Int, _ value: Float) {
        tag(field, wire: 5)
        var bits = value.bitPattern.littleEndian
        Swift.withUnsafeBytes(of: &bits) { data.append(contentsOf: $0) }
    }

    mutating func string(_ field: Int, _ value: String) {
        bytes(field, Data(value.utf8))
    }

    mutating func optionalString(_ field: Int, _ value: String?) {
        guard let value else { return }
        string(field, value)
    }

    mutating func bytes(_ field: Int, _ value: Data) {
        tag(field, wire: 2)
        varint(UInt64(value.count))
        data.append(value)
    }

    mutating func message(
        _ field: Int,
        _ body: (inout ProtobufWriter) -> Void
    ) {
        var value = ProtobufWriter()
        body(&value)
        bytes(field, value.data)
    }

    mutating func packedInt64(_ field: Int, _ values: [Int64]) {
        guard !values.isEmpty else { return }
        var packed = ProtobufWriter()
        for value in values { packed.varint(UInt64(bitPattern: value)) }
        bytes(field, packed.data)
    }

    private mutating func tag(_ field: Int, wire: Int) {
        varint(UInt64((field << 3) | wire))
    }

    private mutating func varint(_ value: UInt64) {
        var value = value
        while value >= 0x80 {
            data.append(UInt8(value & 0x7f) | 0x80)
            value >>= 7
        }
        data.append(UInt8(value))
    }
}

private struct ProtobufReader {
    let data: Data
    var index = 0

    var isAtEnd: Bool { index >= data.count }

    mutating func nextTag() throws -> (field: Int, wire: Int)? {
        guard index < data.count else { return nil }
        let value = try varint()
        guard value != 0 else { throw TachibkBackupCodec.CodecError.invalidBackup }
        return (Int(value >> 3), Int(value & 7))
    }

    mutating func bytes() throws -> Data {
        let rawCount = try varint()
        guard let count = Int(exactly: rawCount) else {
            throw TachibkBackupCodec.CodecError.invalidBackup
        }
        guard count >= 0, index + count <= data.count else {
            throw TachibkBackupCodec.CodecError.invalidBackup
        }
        defer { index += count }
        return data.subdata(in: index..<(index + count))
    }

    mutating func string(wire: Int) throws -> String {
        guard wire == 2, let value = String(data: try bytes(), encoding: .utf8)
        else {
            throw TachibkBackupCodec.CodecError.invalidBackup
        }
        return value
    }

    mutating func int64(wire: Int) throws -> Int64 {
        guard wire == 0 else {
            throw TachibkBackupCodec.CodecError.invalidBackup
        }
        return Int64(bitPattern: try varint())
    }

    mutating func int(wire: Int) throws -> Int {
        guard wire == 0 else {
            throw TachibkBackupCodec.CodecError.invalidBackup
        }
        return Int(Int32(truncatingIfNeeded: try varint()))
    }

    mutating func bool(wire: Int) throws -> Bool {
        guard wire == 0 else {
            throw TachibkBackupCodec.CodecError.invalidBackup
        }
        return try varint() != 0
    }

    mutating func float(wire: Int) throws -> Float {
        guard wire == 5, index + 4 <= data.count else {
            throw TachibkBackupCodec.CodecError.invalidBackup
        }
        let bits =
            UInt32(data[index]) |
            (UInt32(data[index + 1]) << 8) |
            (UInt32(data[index + 2]) << 16) |
            (UInt32(data[index + 3]) << 24)
        index += 4
        return Float(bitPattern: bits)
    }

    mutating func skip(wire: Int) throws {
        switch wire {
            case 0: _ = try varint()
            case 1: try advance(8)
            case 2:
                let rawCount = try varint()
                guard let count = Int(exactly: rawCount) else {
                    throw TachibkBackupCodec.CodecError.invalidBackup
                }
                try advance(count)
            case 5: try advance(4)
            default: throw TachibkBackupCodec.CodecError.invalidBackup
        }
    }

    private mutating func advance(_ count: Int) throws {
        guard count >= 0, index + count <= data.count else {
            throw TachibkBackupCodec.CodecError.invalidBackup
        }
        index += count
    }

    private mutating func varint() throws -> UInt64 {
        var value: UInt64 = 0
        for shift in stride(from: 0, to: 64, by: 7) {
            guard index < data.count else {
                throw TachibkBackupCodec.CodecError.invalidBackup
            }
            let byte = data[index]
            index += 1
            value |= UInt64(byte & 0x7f) << UInt64(shift)
            if byte & 0x80 == 0 { return value }
        }
        throw TachibkBackupCodec.CodecError.invalidBackup
    }
}
