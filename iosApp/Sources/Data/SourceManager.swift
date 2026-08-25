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

    /// Language ordering for the browse and migration lists, in tachiyomiazios's order so the two
    /// apps present sources the same way.
    static let languageCodes = [
        "multi", "en", "ca", "de", "es", "fr", "id", "it", "pl", "pt-br",
        "vi", "tr", "ru", "ar", "zh", "zh-hans", "ja", "ko"
    ]

    weak var runtime: SourceRuntime?
    weak var catalog: ExtensionCatalog?

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
    /// source while encoding, off the main actor. The backing snapshot is refreshed by
    /// `SourceRuntime.reload()` and guarded by its own lock.
    nonisolated func source(for key: String) -> ExtensionRunner.Source? {
        Self.sourceLock.lock()
        defer { Self.sourceLock.unlock() }
        return Self.loadedSources.first { $0.key == key }
            ?? (key == MergedSourceSupport.sourceKey ? MergedSourceSupport.source : nil)
    }

    /// The descriptors behind the loaded sources, which carry the extension each came from.
    nonisolated var descriptors: [SourceDescriptor] {
        Self.descriptorLock.lock()
        defer { Self.descriptorLock.unlock() }
        return Self.loadedDescriptors
    }

    nonisolated func updateDescriptors(_ descriptors: [SourceDescriptor]) {
        Self.descriptorLock.lock()
        Self.loadedDescriptors = descriptors
        Self.descriptorLock.unlock()
    }

    private nonisolated static let descriptorLock = NSLock()
    nonisolated(unsafe) private static var loadedDescriptors: [SourceDescriptor] = []

    /// Every loaded source, as the vendored UI addresses them.
    nonisolated var sources: [ExtensionRunner.Source] {
        Self.sourceLock.lock()
        defer { Self.sourceLock.unlock() }
        return Self.loadedSources
    }

    /// Refreshed by `SourceRuntime.reload()`, which builds these from the loaded JVM extensions.
    nonisolated func updateSources(_ sources: [ExtensionRunner.Source]) {
        Self.sourceLock.lock()
        Self.loadedSources = sources
        Self.sourceLock.unlock()
    }

    /// Whether a source key resolves to something installed -- the library uses this to grey out
    /// entries whose source has been removed.
    nonisolated func hasSourceInstalled(id: String) -> Bool {
        source(for: id) != nil
    }

    /// Sources the user has pinned to the top of Browse.
    nonisolated func getPinned() -> [ExtensionRunner.Source] {
        let sourcesByKey = Dictionary(uniqueKeysWithValues: sources.map { ($0.key, $0) })
        return pinnedSourceKeys().compactMap { sourcesByKey[$0] }
    }

    private nonisolated static let sourceLock = NSLock()
    nonisolated(unsafe) private static var loadedSources: [ExtensionRunner.Source] = []

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
        return Self.names[key] ?? (key == MergedSourceSupport.sourceKey ? MergedSourceSupport.source.name : nil)
    }

    nonisolated func updateNames(_ names: [String: String]) {
        Self.nameLock.lock()
        Self.names = names
        Self.nameLock.unlock()
    }

    /// Clears a source's stored settings.
    ///
    /// Lifted from tachiyomiazios: source settings are UserDefaults keys namespaced by the source
    /// key, so removing a source means dropping its key and anything prefixed with it.
    nonisolated func removeSettings(from source: ExtensionRunner.Source) {
        let userDefaults = UserDefaults.standard
        let keys = userDefaults.dictionaryRepresentation().keys

        for key in keys where key == source.key || key.hasPrefix(source.key + ".") {
            userDefaults.removeObject(forKey: key)
        }
    }

    // MARK: - Pinning

    /// Browse has always persisted both its pins and their drag-reorder order under this key.
    /// Keeping a second key here made context-menu pinning appear to do nothing.
    private static let pinnedKey = "Browse.pinnedList"
    private static let legacyPinnedKey = "Browse.pinnedSources"

    /// Reads the one canonical list, migrating pins written by builds that used the old key.
    /// The array, rather than a Set, is intentional: its order is the order shown in Browse.
    /// The canonical ordered pin list. Reads made by Browse also pass through here so old
    /// `Browse.pinnedSources` values are migrated before the UI decides which section to show.
    nonisolated func pinnedSourceKeys() -> [String] {
        let defaults = UserDefaults.standard
        var current = defaults.stringArray(forKey: Self.pinnedKey) ?? []
        if let legacy = defaults.stringArray(forKey: Self.legacyPinnedKey) {
            current.append(contentsOf: legacy.filter { !current.contains($0) })
            defaults.set(current, forKey: Self.pinnedKey)
            defaults.removeObject(forKey: Self.legacyPinnedKey)
        }
        return current
    }

    nonisolated func pin(source: ExtensionRunner.Source) {
        var pinned = pinnedSourceKeys()
        guard !pinned.contains(source.key) else { return }
        pinned.append(source.key)
        UserDefaults.standard.set(pinned, forKey: Self.pinnedKey)
    }

    nonisolated func unpin(source: ExtensionRunner.Source) {
        var pinned = pinnedSourceKeys()
        pinned.removeAll { $0 == source.key }
        UserDefaults.standard.set(pinned, forKey: Self.pinnedKey)
    }

    // MARK: - Installing and removing

    /// Removes the extension providing this source.
    ///
    /// A JVM extension can expose many sources -- MangaDex is one per language -- so removing a
    /// source means uninstalling its extension, which is what the catalog owns.
    func remove(source: ExtensionRunner.Source) {
        removeSettings(from: source)
        guard
            let id = SourceIdentity.numericId(source.key),
            let descriptor = runtime?.sources.first(where: { $0.id == id }),
            let installed = catalog?.installed.first(where: { $0.packageName == descriptor.extensionId })
        else { return }
        catalog?.uninstall(installed)
        Task { await runtime?.reload() }
    }

    /// Installs an extension JAR the user opened from Files, then returns the source it provides.
    func importSource(from url: URL) async -> ExtensionRunner.Source? {
        guard let catalog else { return nil }
        await catalog.installLocalJar(at: url)
        await runtime?.reload()
        return sources.last
    }

    /// Languages offered by the add-source filter. Extensions declare their own, so the list is
    /// built from what the repositories actually carry.
    nonisolated var sourceListLanguages: [String] {
        Array(Set(sources.flatMap { $0.languages })).sorted()
    }

    /// Installs a JVM extension from a repository catalog entry.
    ///
    /// Goes straight to `JVMSourceRuntime`, which downloads, verifies the checksum and writes the
    /// layout it loads from -- the same path the add-source screen uses upstream.
    @discardableResult
    func installTachiyomiXExtension(
        _ entry: TachiyomiXJarRepository.Catalog.Extension
    ) async throws -> JVMExtensionManifest {
        let manifest = try await JVMSourceRuntime.shared.install(catalogEntry: entry)
        await runtime?.reload()
        return manifest
    }

    /// Removes an installed extension.
    ///
    /// The runtime owns the copy that actually loads, so it does the uninstalling; the catalog's
    /// own record is cleared alongside. Going only through the catalog left the runtime's copy in
    /// place, which is why uninstalling appeared to do nothing.
    func uninstallTachiyomiXExtension(id extensionId: String) async throws {
        try await JVMSourceRuntime.shared.uninstall(extensionId: extensionId)
        if let catalog, let item = catalog.installed.first(where: { $0.packageName == extensionId }) {
            catalog.uninstall(item)
        }
        await runtime?.reload()
    }

    /// Aidoku's user-added lists of downloadable sources.
    ///
    /// Empty here: this port installs extensions from repositories, which `RepositoryStore` and the
    /// add-source screen own. The Browse screen still renders its installed-sources section; the
    /// external-list section simply has nothing to show.
    nonisolated var sourceLists: [SourceList] { [] }

    /// Aidoku refreshes its source lists here. This port has none -- see `sourceLists`.
    func loadSourceLists(reload: Bool = false) async {}
}
