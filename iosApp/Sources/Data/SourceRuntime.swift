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
        guard let runtime = jvm.runtime else { return }
        isLoading = true
        defer { isLoading = false }

        // The Suwayomi/AndroidCompat surface has to be initialised before any extension class is
        // touched, or the first source that reaches for an Android API dies instead of working.
        if !compatibilityReady {
            do {
                _ = try await Task.detached { try runtime.initializeExtensionCompatibility() }.value
                compatibilityReady = true
            } catch {
                loadErrors["*"] = "Compatibility layer failed: \(error.localizedDescription)"
                return
            }
        }

        var descriptors: [SourceDescriptor] = []
        for installed in catalog.installed {
            do {
                if !loadedExtensions.contains(installed.packageName) {
                    // Resolved against the current container, not a path stored at install time.
                    let url = try ExtensionCatalog.jarURL(for: installed)
                    let id = installed.packageName
                    let entry = installed.entryClass
                    _ = try await Task.detached {
                        try runtime.loadTachiyomiXExtension(id: id, jarURL: url, entryClass: entry)
                    }.value
                    loadedExtensions.insert(installed.packageName)
                }

                let id = installed.packageName
                let listed = try await Task.detached {
                    try runtime.sources(extensionId: id)
                }.value

                descriptors.append(contentsOf: listed.map {
                    SourceDescriptor(
                        id: $0.id,
                        extensionId: installed.packageName,
                        extensionName: installed.name,
                        name: $0.name,
                        lang: $0.lang,
                        supportsLatest: $0.supportsLatest
                    )
                })
                loadErrors.removeValue(forKey: installed.packageName)
            } catch {
                loadErrors[installed.packageName] = error.localizedDescription
            }
        }

        sources = descriptors.sorted {
            $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    /// Forgets a loaded extension so the next reload picks up a new version of it.
    func forget(_ packageName: String) {
        loadedExtensions.remove(packageName)
        sources.removeAll { $0.extensionId == packageName }
    }

    // MARK: - Browsing

    func popular(_ source: SourceDescriptor, page: Int) async throws -> TachiyomiXMangaPage {
        try await withRuntime { runtime in
            try runtime.popularManga(extensionId: source.extensionId, sourceId: source.id, page: page)
        }
    }

    func latest(_ source: SourceDescriptor, page: Int) async throws -> TachiyomiXMangaPage {
        try await withRuntime { runtime in
            try runtime.latestManga(extensionId: source.extensionId, sourceId: source.id, page: page)
        }
    }

    func search(
        _ source: SourceDescriptor,
        query: String,
        page: Int,
        filterStates: String? = nil
    ) async throws -> TachiyomiXMangaPage {
        // The package's searchManga rejects an empty query, but a filter-only search is legitimate
        // -- browsing by genre with no text is the normal way to use most sources -- so the
        // request is built directly when filters are supplied.
        if let filterStates, !filterStates.isEmpty {
            return try await withRuntime { runtime in
                let request = ExtensionHostRequest(
                    operation: "searchManga",
                    extensionId: source.extensionId,
                    sourceId: String(source.id),
                    argument: String(page),
                    query: query,
                    filterStates: filterStates
                )
                let response: ExtensionHostResponse = try runtime.dispatch(request)
                guard response.success, let result = response.result else {
                    throw JVMRuntimeError.decodingFailed(response.error ?? "Search failed")
                }
                return try JSONDecoder().decode(TachiyomiXMangaPage.self, from: Data(result.utf8))
            }
        }
        return try await withRuntime { runtime in
            try runtime.searchManga(
                extensionId: source.extensionId,
                sourceId: source.id,
                query: query,
                page: page
            )
        }
    }

    func filters(_ source: SourceDescriptor) async throws -> [SourceFilter] {
        let json = try await withRuntime { runtime -> String in
            let request = ExtensionHostRequest(
                operation: "getSearchFilters",
                extensionId: source.extensionId,
                sourceId: String(source.id)
            )
            let response: ExtensionHostResponse = try runtime.dispatch(request)
            guard response.success, let result = response.result else {
                throw JVMRuntimeError.decodingFailed(response.error ?? "Filters unavailable")
            }
            return result
        }
        return SourceFilterDecoder.decode(json)
    }

    func mangaDetails(
        _ source: SourceDescriptor,
        url: String,
        title: String,
        memo: String?
    ) async throws -> TachiyomiXMangaUpdate {
        try await withRuntime { runtime in
            try runtime.mangaUpdate(
                extensionId: source.extensionId,
                sourceId: source.id,
                mangaURL: url,
                mangaTitle: title,
                mangaMemo: memo
            )
        }
    }

    func pages(
        _ source: SourceDescriptor,
        chapterURL: String,
        chapterName: String,
        memo: String?
    ) async throws -> [TachiyomiXPage] {
        try await withRuntime { runtime in
            try runtime.pages(
                extensionId: source.extensionId,
                sourceId: source.id,
                chapterURL: chapterURL,
                chapterName: chapterName,
                chapterMemo: memo
            )
        }
    }

    /// Runs work on the VM off the main actor. Every host call is synchronous and can block for as
    /// long as the source's network does.
    private func withRuntime<T: Sendable>(
        _ body: @escaping @Sendable (JVMRuntime) throws -> T
    ) async throws -> T {
        guard let runtime = jvm.runtime else {
            throw JVMRuntimeError.invalidConfiguration("The JVM is not running.")
        }
        return try await Task.detached(priority: .userInitiated) { try body(runtime) }.value
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
