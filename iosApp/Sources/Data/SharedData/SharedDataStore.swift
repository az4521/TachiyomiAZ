import Foundation
import TachiyomiKit

/// Adapts KMP repositories and SQLDelight models to the Aidoku-derived UI's model shapes.
///
/// Persistence and platform-neutral workflows live in shared Kotlin. This type is intentionally
/// limited to source-key translation and Swift UI model construction; it owns no database state.
///
/// Two deliberate departures from upstream:
///
/// - Background dispatch is provided independently by `DatabaseContainer`; there is no managed
///   object context. Remaining `context:` parameters are compatibility arguments and are ignored.
/// - Filter groups are stored in `UserDefaults`, not the database. They are an Aidoku concept with
///   no column in the Android schema, and inventing one would put this app's writes out of step with
///   the shared database.
final class SharedDataStore {
    static let shared = SharedDataStore()

    private init() {}

    var handler: IosDatabaseHandler { Database.handler }
    var historyRepository: HistoryRepository { HistoryRepository(db: handler) }
    var libraryRepository: LibraryRepository { LibraryRepository(db: handler) }
    var trackRepository: TrackRepository { TrackRepository(db: handler) }
}
