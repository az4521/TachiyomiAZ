import Foundation
import TachiJVMRunner
import TachiyomiKit

/// Reads and writes `.tachibk` backups.
///
/// Almost nothing happens here, which is the point. `:core-domain` owns the models, their field
/// numbers, the codec that turns them into bytes, and -- since `BackupBuilder` -- what goes into
/// one in the first place. Both apps go through all of it, so "the two apps agree on the format"
/// is structural rather than something kept true by hand.
///
/// What is left is gzip, and this app's own state riding alongside in the field the shared model
/// reserves for it.
///
/// Two implementations used to live here. First a hand-rolled protobuf reader and writer,
/// transcribed field by field from the shared models; then, after that went, a translation between
/// the shared models and a set of Swift ones shaped like Aidoku's. The second was worse than it
/// looked: `encode` wrote the shared protobuf *and* JSON-encoded the whole Aidoku backup into
/// `iosState`, and `decode` preferred that JSON whenever it was present. Every file this app wrote
/// contained the library twice, and a restore read the copy that the other app could not see -- so
/// a bug in either representation was invisible from the other side.
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

    static func encode(_ backup: TachiyomiKit.Backup, state: BackupState) throws -> Data {
        backup.iosState = try nativeEncoder.encode(state).kotlinByteArray
        return try TachiJVMCompression.gzip(BackupCodec.shared.encode(backup: backup).data)
    }

    // MARK: - Reading

    /// A backup's contents, and whatever of this app's state came with it.
    ///
    /// The state is absent for a backup written by the other app or by Mihon, which is not an
    /// error -- it means there are no local preferences to restore, not that the file is bad.
    /// Identity is the file it was read from, not its contents: two backups taken a second apart
    /// are equal field by field, and the list would treat them as one row.
    struct Decoded: Identifiable, Equatable {
        var id: URL
        var backup: TachiyomiKit.Backup
        var state: BackupState

        static func == (lhs: Decoded, rhs: Decoded) -> Bool { lhs.id == rhs.id }
    }

    static func decode(from data: Data, url: URL = URL(fileURLWithPath: "/")) throws -> Decoded {
        let backup = BackupCodec.shared.decode(bytes: try uncompressed(data).kotlinByteArray)

        guard !backup.backupManga.isEmpty || !backup.backupCategories.isEmpty else {
            throw CodecError.invalidBackup
        }

        // A file from before this app stopped writing its whole library into iosState still
        // decodes: the fields that are no longer here are ignored, and the name, date and settings
        // come back.
        let state = backup.iosState.map(\.data).flatMap { try? nativeDecoder.decode(BackupState.self, from: $0) }
        return Decoded(id: url, backup: backup, state: state ?? BackupState(date: Date()))
    }

    private static func uncompressed(_ data: Data) throws -> Data {
        if data.starts(with: [0x1f, 0x8b]) {
            return try TachiJVMCompression.gunzip(data)
        }
        return data
    }

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
