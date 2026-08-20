//
//  BackupState.swift
//  TachiyomiAZ
//

import Foundation

/// The part of a backup that only this app has.
///
/// A `.tachibk` carries the library, its chapters, history, tracks and categories in the shared
/// model, and both apps read and write those through `:core-domain`. This is what is left over --
/// things the format has no field for, and the other app has no concept of.
///
/// It replaces a set of Swift structs that mirrored the shared models field for field. Those were
/// not only duplication: the codec wrote *both* representations into every file, the shared
/// protobuf and this app's entire library JSON-encoded into `iosState`, and then preferred the
/// JSON when reading one back. Every backup held the library twice, and the two copies could
/// disagree.
///
/// The keys are the ones the old whole-backup JSON used, so a backup written before this change
/// still yields its name, date and settings: decoding ignores the fields that are no longer here.
struct BackupState: Codable, Sendable {
    var name: String?
    var date: Date
    var automatic: Bool?
    var version: String?

    /// Source list urls the user has added.
    var sourceLists: [String]?

    /// This app's preferences. The other app's settings live in its own store and are not
    /// interchangeable, so these are carried as an opaque bag rather than mapped.
    var settings: [String: JsonAnyValue]?

    var extensionRepositories: Data?

    /// Per-session reading times. The shared format keeps a total per chapter, in
    /// `BackupHistory.readDuration`, but not the sessions that made it up.
    var readingSessions: [BackupReadingSession]?

    /// The new-chapter feed, which the other app does not keep.
    var updates: [BackupUpdate]?

    init(
        name: String? = nil,
        date: Date = Date(),
        automatic: Bool? = nil,
        version: String? = nil,
        sourceLists: [String]? = nil,
        settings: [String: JsonAnyValue]? = nil,
        extensionRepositories: Data? = nil,
        readingSessions: [BackupReadingSession]? = nil,
        updates: [BackupUpdate]? = nil
    ) {
        self.name = name
        self.date = date
        self.automatic = automatic
        self.version = version
        self.sourceLists = sourceLists
        self.settings = settings
        self.extensionRepositories = extensionRepositories
        self.readingSessions = readingSessions
        self.updates = updates
    }
}
