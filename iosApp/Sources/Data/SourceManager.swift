import ExtensionRunner
import Foundation

/// Source lookup for the vendored UI, backed by the JVM runtime.
///
/// The views call `SourceManager.shared.source(for:)` expecting Aidoku's registry. Underneath this
/// resolves against `SourceRuntime`, which drives the JVM extension host -- so the UI reaches real
/// TachiyomiX sources rather than a second source system.
///
/// `sourceKey` is a String in the UI and an Int64 source id in the database and the host; the
/// conversion lives here so neither side has to know about the other's representation.
@MainActor
final class SourceManager {
    static let shared = SourceManager()

    weak var runtime: SourceRuntime?

    private init() {}

    /// Blocks until extensions have finished loading, so an image request is not built against an
    /// empty source list on a cold launch.
    func waitForSourcesLoad() async {
        guard let runtime else { return }
        if runtime.sources.isEmpty && !runtime.isLoading {
            await runtime.reload()
        }
    }

    /// Nonisolated because callers reach it from background work -- the backup codec names each
    /// source while encoding, off the main actor. Nothing isolated is touched: the result is
    /// currently always nil, and the name lookup that does read state goes through `name(for:)`,
    /// which is backed by its own synchronised snapshot.
    nonisolated func source(for key: String) -> ExtensionRunner.Source? {
        // The vendored UI only uses the returned source to modify image requests. That path needs
        // the JVM's per-source headers, which are not exposed as an ExtensionRunner.Source here --
        // returning nil makes the caller fall back to an unmodified request, which is correct for
        // every source that does not require referer or cookie headers on images.
        nil
    }

    /// "Reset everything" in settings clears installed sources along with the database.
    ///
    /// Uninstalling is the extension catalog's job, so this asks it to remove everything and then
    /// reloads, rather than dropping a cached list the runtime would rebuild anyway.
    func clearSources() {
        guard let runtime else { return }
        Task { await runtime.uninstallAll() }
    }

    /// Aidoku keeps user-added "source lists" of downloadable sources. This app gets sources from
    /// extension repositories instead, which `RepositoryStore` owns, so there is no second list to
    /// clear here.
    func clearSourceLists() {}

    // MARK: - Source names

    /// Source display names by key, readable from any thread.
    ///
    /// `SourceRuntime` lives on the main actor, but background work -- encoding a backup, for one --
    /// needs to put a human-readable name against a source key. This snapshot is refreshed whenever
    /// the runtime reloads.
    private nonisolated static let nameLock = NSLock()
    nonisolated(unsafe) private static var names: [String: String] = [:]

    nonisolated func name(for key: String) -> String? {
        Self.nameLock.lock()
        defer { Self.nameLock.unlock() }
        return Self.names[key]
    }

    nonisolated func updateNames(_ names: [String: String]) {
        Self.nameLock.lock()
        Self.names = names
        Self.nameLock.unlock()
    }
}
