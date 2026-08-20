import ExtensionRunner
import Foundation
import TachiJVMRunner

/// A source exposed by an installed extension.
struct SourceDescriptor: Identifiable, Hashable {
    let id: Int64
    let extensionId: String
    let extensionName: String
    let name: String
    let lang: String
    let supportsLatest: Bool

    var displayLanguage: String {
        if lang == "all" { return "All" }
        return Locale.current.localizedString(forLanguageCode: lang)?.capitalized ?? lang.uppercased()
    }
}

/// A search filter a source offers, as the extension describes it.
struct SourceFilter: Identifiable, Hashable {
    enum Kind: Hashable {
        case header
        case separator
        case text
        case checkbox
        case tristate
        case select(options: [String])
        case sort(options: [String])
        case group(children: [SourceFilter])
    }

    let index: Int
    let name: String
    let kind: Kind

    var id: Int { index }
}

/// Whatever the user has set a filter to. Sent back as the `filterStates` payload.
enum SourceFilterState: Hashable {
    case text(String)
    case checkbox(Bool)
    /// 0 ignored, 1 included, 2 excluded -- the extension library's own encoding.
    case tristate(Int)
    case select(Int)
    case sort(index: Int, ascending: Bool)
}

/// Loads installed extensions into the JVM and drives their sources.
///
/// Everything here is a thin pass-through to `TachiyomiXExtensionClient` in the vendored
/// `TachiJVMRunner` package, which already speaks the host's request/response protocol. Nothing
/// about a source's behaviour is reimplemented on this side -- the extension decides what popular,
/// latest and search mean, and this only carries the answer back.
@MainActor
final class SourceRuntime: ObservableObject {
    @Published private(set) var sources: [SourceDescriptor] = []
    @Published private(set) var isLoading = false
    @Published private(set) var loadErrors: [String: String] = [:]

    private unowned let jvm: JVMHost
    private unowned let catalog: ExtensionCatalog
    private var compatibilityReady = false
    private var loadedExtensions: Set<String> = []

    init(jvm: JVMHost, catalog: ExtensionCatalog) {
        self.jvm = jvm
        self.catalog = catalog
    }

    /// Loads every installed extension that is not loaded yet and refreshes the source list.
    func reload() async {
        guard jvm.isRunning else { return }
        isLoading = true
        defer { isLoading = false }

        // JVMSourceRuntime owns the VM and does the loading: installedAidokuSources() reads the
        // installed manifests, loads any extension not yet loaded, and returns one Source per
        // source it exposes -- each already carrying a TachiyomiXSourceRunner, which is what the
        // vendored UI feature-detects on.
        let loaded = await JVMSourceRuntime.shared.installedAidokuSources()
        SourceManager.shared.updateSources(loaded)

        // `staticListings` is private on Source, so which sources offer a "latest" listing is
        // asked of each source rather than read off it.
        var latestCapable: Set<String> = []
        for source in loaded {
            let listings = (try? await source.getListings()) ?? []
            if listings.contains(where: { $0.id == "latest" }) {
                latestCapable.insert(source.key)
            }
        }

        // Which extension provides each source, straight from the runtime that loaded them.
        //
        // This used to be looked up as `catalog.installed.first { $0.packageName == source.id }`,
        // and `Source.id` is the source *key* -- "mihon.<sourceId>" -- never a package name. The
        // match could not succeed, so `extensionId` always took the fallback and every descriptor
        // carried a source key where the host expects an extension id. Browsing never noticed,
        // because it goes through the runner attached to the source; a library refresh did, and
        // failed every title with "extension is not loaded mihon.<sourceId>".
        let extensionIds = await JVMSourceRuntime.shared.extensionIdsBySourceKey

        // This app's own screens still work in SourceDescriptor, derived from the same list so the
        // two views of a source cannot disagree.
        sources = loaded
            .compactMap { source -> SourceDescriptor? in
                guard let id = SourceIdentity.numericId(source.key) else { return nil }
                guard let extensionId = extensionIds[source.key] else {
                    // Skipped rather than given a guessed id: a descriptor carrying the wrong
                    // extension is exactly the failure above, and it fails silently at use.
                    LogManager.logger.error(
                        "No extension id for source \(source.key); it will not be listed."
                    )
                    return nil
                }
                let installed = catalog.installed.first { $0.packageName == extensionId }
                return SourceDescriptor(
                    id: id,
                    extensionId: extensionId,
                    extensionName: installed?.name ?? source.name,
                    name: source.name,
                    lang: source.languages.first ?? "en",
                    supportsLatest: latestCapable.contains(source.key)
                )
            }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }

        // Publish names where background work can read them -- backup encoding, for one.
        SourceManager.shared.updateNames(
            Dictionary(
                loaded.map { ($0.key, $0.name) },
                uniquingKeysWith: { first, _ in first }
            )
        )

        SourceManager.shared.updateDescriptors(sources)

        // Komga, Kavita and Suwayomi track against their own server, and their trackers read its
        // address from where a source's settings live. These sources are JVM extensions, so their
        // settings have to be mirrored out of the VM first.
        await EnhancedSourceBridge.mirrorSettings(for: sources)

        loadErrors.removeAll()
        if loaded.isEmpty && !catalog.installed.isEmpty {
            loadErrors["*"] = "No sources loaded from \(catalog.installed.count) installed extension(s)."
        }

        // Announce the new list. Everything above updates the source registry correctly, and none
        // of it reaches the screen on its own: Browse rebuilds from `.updateSourceList`, which was
        // posted once at startup and nowhere else. So installing an extension loaded it, listed it
        // internally, and left Browse showing the list from launch until the app was restarted.
        //
        // Posted here rather than at each call site because every path that changes what is
        // installed -- install, uninstall, local import, reset -- ends in a reload.
        NotificationCenter.default.post(name: .updateSourceList, object: nil)
    }

    /// Forgets a loaded extension so the next reload picks up a new version of it.
    func forget(_ packageName: String) {
        loadedExtensions.remove(packageName)
        sources.removeAll { $0.extensionId == packageName }
    }

    // MARK: - Browsing

    func popular(_ source: SourceDescriptor, page: Int) async throws -> TachiyomiXMangaPage {
        try await JVMSourceRuntime.shared.popularManga(
            extensionId: source.extensionId,
            sourceId: source.id,
            page: page
        )
    }

    func latest(_ source: SourceDescriptor, page: Int) async throws -> TachiyomiXMangaPage {
        try await JVMSourceRuntime.shared.latestManga(
            extensionId: source.extensionId,
            sourceId: source.id,
            page: page
        )
    }


    func search(
        _ source: SourceDescriptor,
        query: String,
        page: Int,
        filterStates: String? = nil
    ) async throws -> TachiyomiXMangaPage {
        // A filter-only search with no text is normal -- browsing by genre is how most sources are
        // used -- so an empty query is not rejected here.
        try await JVMSourceRuntime.shared.searchManga(
            extensionId: source.extensionId,
            sourceId: source.id,
            query: query,
            page: page
        )
    }

    func filters(_ source: SourceDescriptor) async throws -> [SourceFilter] {
        let descriptors = try await JVMSourceRuntime.shared.searchFilters(
            extensionId: source.extensionId,
            sourceId: source.id
        )
        return descriptors.enumerated().map { index, descriptor in
            SourceFilter(
                index: index,
                name: descriptor.name,
                kind: descriptor.sourceFilterKind
            )
        }
    }

    func mangaDetails(
        _ source: SourceDescriptor,
        manga: ExtensionRunner.Manga,
        mangaInitialized: Bool = false,
        fetchDetails: Bool = true,
        fetchChapters: Bool = true
    ) async throws -> TachiyomiXMangaUpdate {
        try await JVMSourceRuntime.shared.mangaUpdate(
            extensionId: source.extensionId,
            sourceId: source.id,
            mangaURL: manga.key,
            mangaTitle: manga.title,
            mangaThumbnailURL: manga.cover,
            mangaArtist: manga.artists?.joined(separator: ", "),
            mangaAuthor: manga.authors?.joined(separator: ", "),
            mangaStatus: Int(manga.status.tachiyomiXValue),
            mangaDescription: manga.description,
            mangaGenre: manga.tags?.joined(separator: ", "),
            mangaUpdateStrategy: manga.updateStrategy == .never
                ? "ONLY_FETCH_ONCE"
                : "ALWAYS_UPDATE",
            mangaInitialized: mangaInitialized,
            mangaMemo: manga.memo,
            fetchDetails: fetchDetails,
            fetchChapters: fetchChapters
        )
    }

    func pages(
        _ source: SourceDescriptor,
        chapterURL: String,
        chapterName: String,
        memo: String?
    ) async throws -> [TachiyomiXPage] {
        try await JVMSourceRuntime.shared.pages(
            extensionId: source.extensionId,
            sourceId: source.id,
            chapterURL: chapterURL,
            chapterName: chapterName,
            chapterMemo: memo
        )
    }


    // Cloudflare challenges are handled by JVMSourceRuntime.dispatch, which detects them on any
    // host response, solves through CloudflareHandler and retries the request. A second copy of
    // the detection and a user-agent cache lived here and were called from nowhere -- easy to
    // mistake for the real thing when reading this file, and they would have gone stale silently.


    /// Removes every installed extension, then reloads so the source list reflects it.
    ///
    /// Backs the "reset" action in settings. Uninstalling goes through the catalog so the JARs are
    /// deleted and the record persisted, rather than only clearing the in-memory source list.
    func uninstallAll() async {
        for item in catalog.installed {
            catalog.uninstall(item)
        }
        await reload()
    }
}

/// Turns the host's filter JSON into something the UI can render.
///
/// The shape is the extension library's, not ours, so this stays tolerant: an unrecognised filter
/// type becomes a header rather than an error, because one odd filter should not stop a source
/// being searchable.
enum SourceFilterDecoder {
    static func decode(_ json: String) -> [SourceFilter] {
        guard let data = json.data(using: .utf8),
              let raw = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        return raw.enumerated().map { index, entry in
            SourceFilter(
                index: entry["index"] as? Int ?? index,
                name: entry["name"] as? String ?? "",
                kind: kind(for: entry)
            )
        }
    }

    private static func kind(for entry: [String: Any]) -> SourceFilter.Kind {
        let type = (entry["type"] as? String ?? "").lowercased()
        let options = entry["options"] as? [String] ?? []
        switch type {
        case "text": return .text
        case "checkbox": return .checkbox
        case "tristate": return .tristate
        case "select": return .select(options: options)
        case "sort": return .sort(options: options)
        case "separator": return .separator
        case "group":
            let children = (entry["filters"] as? [[String: Any]] ?? []).enumerated().map { index, child in
                SourceFilter(
                    index: child["index"] as? Int ?? index,
                    name: child["name"] as? String ?? "",
                    kind: kind(for: child)
                )
            }
            return .group(children: children)
        default: return .header
        }
    }
}

extension TachiyomiXFilterDescriptor {
    /// How this app's filter sheet renders the host's filter description.
    var sourceFilterKind: SourceFilter.Kind {
        switch type {
        case "text": .text
        case "check": .checkbox
        case "tristate": .tristate
        case "select": .select(options: options ?? [])
        case "sort": .sort(options: options ?? [])
        case "separator": .separator
        default: .header
        }
    }
}
