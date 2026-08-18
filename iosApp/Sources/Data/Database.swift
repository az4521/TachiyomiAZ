import Foundation
import TachiyomiKit

/// The one open handle to the shared database.
///
/// `IosDatabaseHandler` is the portable half of Android's `DatabaseHelper`: the query mixins in
/// `:core-database` are default methods over tables generated from the same `.sq` files, so a query
/// issued here runs the identical SQL the Android app runs. Opening it twice would mean two
/// connections to one file, so every caller shares this handle.
enum Database {
    /// Application Support is where iOS expects non-user-facing app data.
    ///
    /// Returns the *directory*, not a path: sqliter rejects a name containing a separator, and the
    /// Kotlin factory is not `@Throws`, so that failure terminates the process instead of surfacing
    /// as a Swift error.
    private static func directory() -> String {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.path
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var stored: IosDatabaseHandler?

    static var handler: IosDatabaseHandler {
        lock.lock()
        defer { lock.unlock() }
        if let stored { return stored }
        let handler = IosDatabaseHandler.companion.open(
            name: "tachiyomi.db",
            directoryPath: directory()
        )
        stored = handler
        return handler
    }
}
