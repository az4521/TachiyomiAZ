import Foundation
import TachiyomiKit

/// The name the vendored UI calls to reach persistence, backed by the shared database.
///
/// Upstream this is CoreData. Here it is not: `:core-database` is already the store, and the Android
/// app reads it through the same generated queries, so keeping a second CoreData stack would give
/// the two apps separate schemas that drift apart -- the exact thing the shared modules exist to
/// prevent. The name and the method signatures are kept so the vendored views compile unmodified;
/// everything underneath is SQLDelight.
///
/// Two deliberate departures from upstream:
///
/// - There is no `container`. Every raw `container.performBackgroundTask` in the fork lives in the
///   manager layer, which is reimplemented over KMP rather than vendored, so nothing needs to reach
///   an `NSManagedObjectContext`. The `context:` parameters are kept only so call sites compile, and
///   are ignored.
/// - Filter groups are stored in `UserDefaults`, not the database. They are an Aidoku concept with
///   no column in the Android schema, and inventing one would put this app's writes out of step with
///   the shared database.
final class CoreDataManager {
    static let shared = CoreDataManager()

    private init() {}

    var handler: IosDatabaseHandler { Database.handler }

    /// SQLDelight writes on the calling statement, so there is no pending-changes buffer to flush.
    /// Kept because the vendored UI calls it after mutating.
    func save() {}
}
