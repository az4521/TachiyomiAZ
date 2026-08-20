import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public final class Source: @unchecked Sendable, Identifiable {
    public let url: URL?
    public let key: String
    public let name: String
    public let version: Int
    public let languages: [String]
    public let urls: [URL]
    public let contentRating: SourceContentRating
    public let imageUrl: URL?
    public let config: SourceInfo.Configuration?
    public let staticSettings: [Setting]
    public let runner: any Runner
    private let staticListings: [Listing]
    private let staticFilters: [Filter]

    public var id: String { key }
    public var apiVersion: String { "tachiyomiaz-1" }
    public var features: SourceFeatures { runner.features }
    public var partialMangaPublisher: SinglePublisher<Manga>? { runner.partialMangaPublisher }
    public var hasListings: Bool { features.dynamicListings || !staticListings.isEmpty }
    public var onlySearch: Bool { !hasListings }
    public var supportsArtistSearch: Bool {
        config?.supportsArtistSearch ?? staticFilters.contains {
            if case .text = $0.value { $0.id == "artist" } else { false }
        }
    }
    public var supportsAuthorSearch: Bool {
        config?.supportsAuthorSearch ?? staticFilters.contains {
            if case .text = $0.value { $0.id == "author" } else { false }
        }
    }

    public init(
        url: URL? = nil,
        key: String,
        name: String,
        version: Int,
        languages: [String] = [],
        urls: [URL] = [],
        contentRating: SourceContentRating,
        imageUrl: URL? = nil,
        config: SourceInfo.Configuration? = nil,
        staticListings: [Listing] = [],
        staticFilters: [Filter] = [],
        staticSettings: [Setting] = [],
        runner: any Runner
    ) {
        self.url = url
        self.key = key
        self.name = name
        self.version = version
        self.languages = languages
        self.urls = urls
        self.contentRating = contentRating
        self.imageUrl = imageUrl
        self.config = config
        self.staticListings = staticListings
        self.staticFilters = staticFilters
        self.staticSettings = staticSettings
        self.runner = runner
        registerDefaults(in: staticSettings)
    }

    public convenience init(url: URL, interpreterConfig: InterpreterConfiguration = .init()) async throws {
        self.init(
            url: url,
            key: "unsupported.interpreter",
            name: "Unsupported Interpreter Source",
            version: 0,
            contentRating: .safe,
            runner: Interpreter()
        )
        throw SourceError.message("AIX/WASM sources are not supported by TachiyomiAZ.")
    }

    private func registerDefaults(in settings: [Setting]) {
        for setting in settings {
            let key = "\(self.key).\(setting.key)"
            switch setting.value {
                case .select(let value):
                    if let value = value.defaultValue ?? value.values.first {
                        UserDefaults.standard.register(defaults: [key: value])
                    }
                case .multiselect(let value):
                    if let value = value.defaultValue { UserDefaults.standard.register(defaults: [key: value]) }
                case .toggle(let value):
                    if let value = value.defaultValue { UserDefaults.standard.register(defaults: [key: value]) }
                case .stepper(let value):
                    if let value = value.defaultValue { UserDefaults.standard.register(defaults: [key: value]) }
                case .segment(let value):
                    if let value = value.defaultValue { UserDefaults.standard.register(defaults: [key: value]) }
                case .text(let value):
                    if let value = value.defaultValue { UserDefaults.standard.register(defaults: [key: value]) }
                case .editableList(let value):
                    if let value = value.defaultValue { UserDefaults.standard.register(defaults: [key: value]) }
                case .picker(let value):
                    if let value = value.defaultValue ?? value.values.first {
                        UserDefaults.standard.register(defaults: [key: value])
                    }
                case .group(let value):
                    registerDefaults(in: value.items)
                case .page(let value):
                    registerDefaults(in: value.items)
                default:
                    break
            }
        }
    }

    public func matchingGenreFilter(for tag: String) -> FilterValue? {
        if config?.supportsTagSearch == true {
            return .select(id: "genre", value: tag)
        }
        for filter in staticFilters {
            switch filter.value {
                case .select(let value) where value.isGenre:
                    guard let index = value.options.firstIndex(of: tag) else { continue }
                    return .select(id: filter.id, value: (value.ids ?? value.options)[index])
                case .multiselect(let value) where value.isGenre:
                    guard let index = value.options.firstIndex(of: tag) else { continue }
                    return .multiselect(id: filter.id, included: [(value.ids ?? value.options)[index]], excluded: [])
                default:
                    continue
            }
        }
        return nil
    }

    public func getSearchMangaList(query: String?, page: Int, filters: [FilterValue]) async throws -> MangaPageResult {
        let applied = query?.isEmpty == false && config?.hidesFiltersWhileSearching == true ? [] : filters
        var result = try await runner.getSearchMangaList(query: query, page: page, filters: applied)
        result.setSourceKey(key)
        return result
    }

    public func getMangaUpdate(manga: Manga, needsDetails: Bool, needsChapters: Bool) async throws -> Manga {
        var result = try await runner.getMangaUpdate(manga: manga, needsDetails: needsDetails, needsChapters: needsChapters)
        result.sourceKey = key
        if languages.count == 1, let language = languages.first, var chapters = result.chapters {
            for index in chapters.indices {
                chapters[index].language = chapters[index].language ?? language
            }
            result.chapters = chapters
        }
        return result
    }

    public func getPageList(manga: Manga, chapter: Chapter) async throws -> [Page] {
        try await runner.getPageList(manga: manga, chapter: chapter)
    }
    public func getMangaList(listing: Listing, page: Int) async throws -> MangaPageResult {
        guard features.providesListings else { throw SourceError.unimplemented }
        var result = try await runner.getMangaList(listing: listing, page: page)
        result.setSourceKey(key)
        return result
    }
    public func processPageImage(response: Response, context: PageContext?) async throws -> PlatformImage? {
        guard features.processesPages else { return nil }
        return try await runner.processPageImage(response: response, context: context)
    }
    public func getListings() async throws -> [Listing] {
        features.dynamicListings ? staticListings + (try await runner.getListings()) : staticListings
    }
    public func getSearchFilters() async throws -> [Filter] {
        features.dynamicFilters ? staticFilters + (try await runner.getSearchFilters()) : staticFilters
    }
    public func getSettings() async throws -> [Setting] {
        guard features.dynamicSettings else { return staticSettings }
        let dynamic = try await runner.getSettings()
        registerDefaults(in: dynamic)
        return staticSettings + dynamic
    }
    public func getImageRequest(url: String, context: PageContext?) async throws -> URLRequest {
        guard features.providesImageRequests else { throw SourceError.unimplemented }
        return try await runner.getImageRequest(url: url, context: context)
    }
    public func getPageDescription(page: Page) async throws -> String? {
        if let description = page.description { return description }
        guard features.providesPageDescriptions else { return nil }
        return try await runner.getPageDescription(page: page)
    }
    public func getAlternateCovers(manga: Manga) async throws -> [String] {
        features.providesAlternateCovers ? try await runner.getAlternateCovers(manga: manga) : []
    }
    public func getBaseUrl() async throws -> URL? {
        features.providesBaseUrl ? try await runner.getBaseUrl() : nil
    }
    public func handleNotification(notification: String) async throws {
        if features.handlesNotifications { try await runner.handleNotification(notification: notification) }
    }
    public func handleDeepLink(url: String) async throws -> DeepLinkResult? {
        features.handlesDeepLinks ? try await runner.handleDeepLink(url: url) : nil
    }
    public func handleBasicLogin(key: String, username: String, password: String) async throws -> Bool {
        features.handlesBasicLogin ? try await runner.handleBasicLogin(key: key, username: username, password: password) : true
    }
    public func handleWebLogin(key: String, cookies: [String: String]) async throws -> Bool {
        features.handlesWebLogin ? try await runner.handleWebLogin(key: key, cookies: cookies) : true
    }
    public func handleMigration(kind: KeyKind, mangaKey: String, chapterKey: String?) async throws -> String? {
        features.handlesMigration ? try await runner.handleMigration(kind: kind, mangaKey: mangaKey, chapterKey: chapterKey) : nil
    }
    public func store<T: Sendable>(value: T) async throws -> Int32 { try await runner.store(value: value) }
    public func remove(value: Int32) async throws { try await runner.remove(value: value) }
}
