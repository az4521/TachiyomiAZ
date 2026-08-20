import Foundation

/// Stands in for `NSPersistentContainer` so the vendored UI's background-write blocks compile.
///
/// Upstream wraps writes in managed-object-context background blocks. SQLDelight statements are
/// durable when they run, so this type preserves only the useful private-queue dispatch behavior.
///
/// This exists so the ~100 call sites that use this idiom need no edits. It is not a general
/// CoreData emulation: there is no object graph, no faulting, and no rollback.
final class DatabaseContainer: @unchecked Sendable {
    /// Compatibility token passed into vendored call sites; it intentionally carries no state.
    final class Context: @unchecked Sendable {}

    private let queue = DispatchQueue(label: "app.tachiyomiaz.database.write", qos: .userInitiated)

    func performBackgroundTask<T: Sendable>(_ block: @escaping @Sendable (Context) -> T) async -> T {
        await withCheckedContinuation { continuation in
            queue.async {
                continuation.resume(returning: block(Context()))
            }
        }
    }

    /// Fire-and-forget form, for the call sites that do not await the result.
    func performBackgroundTask(_ block: @escaping @Sendable (Context) -> Void) {
        queue.async { block(Context()) }
    }
}

extension DatabaseContainer {
    static let shared = DatabaseContainer()
}
