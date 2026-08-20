import Foundation

/// Stands in for `NSPersistentContainer` so the vendored UI's background-write blocks compile.
///
/// Upstream wraps every write in `container.performBackgroundTask { context in ...; context.save() }`
/// -- CoreData's way of batching changes on a private queue and flushing them at the end. SQLDelight
/// has no such buffer: a statement is written when it runs. So the shape is preserved and the
/// meaning is not: the block runs off the main thread, and `save()` is a no-op because the work is
/// already durable by the time it is called.
///
/// This exists so the ~100 call sites that use this idiom need no edits. It is not a general
/// CoreData emulation: there is no object graph, no faulting, and no rollback.
final class DatabaseContainer: @unchecked Sendable {
    /// The `context` handed to the block. Carries no state -- it is passed back into facade methods,
    /// which ignore it -- and exists to satisfy the `try context.save()` the blocks end with.
    final class Context: @unchecked Sendable {
        func save() throws {}
    }

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

extension CoreDataManager {
    var container: DatabaseContainer { DatabaseContainer.shared }
}

extension DatabaseContainer {
    static let shared = DatabaseContainer()
}
