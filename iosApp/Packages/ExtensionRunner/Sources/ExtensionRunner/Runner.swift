import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public protocol Runner: Sendable {
    var features: SourceFeatures { get }
    var partialMangaPublisher: SinglePublisher<Manga>? { get }

    func getSearchMangaList(query: String?, page: Int, filters: [FilterValue]) async throws -> MangaPageResult
    func getMangaUpdate(manga: Manga, needsDetails: Bool, needsChapters: Bool) async throws -> Manga
    func getPageList(manga: Manga, chapter: Chapter) async throws -> [Page]
    func getMangaList(listing: Listing, page: Int) async throws -> MangaPageResult
    func processPageImage(response: Response, context: PageContext?) async throws -> PlatformImage?
    func getSearchFilters() async throws -> [Filter]
    func getSettings() async throws -> [Setting]
    func getListings() async throws -> [Listing]
    func getImageRequest(url: String, context: PageContext?) async throws -> URLRequest
    func getPageDescription(page: Page) async throws -> String?
    func getAlternateCovers(manga: Manga) async throws -> [String]
    func getBaseUrl() async throws -> URL?
    func handleNotification(notification: String) async throws
    func handleDeepLink(url: String) async throws -> DeepLinkResult?
    func handleBasicLogin(key: String, username: String, password: String) async throws -> Bool
    func handleWebLogin(key: String, cookies: [String: String]) async throws -> Bool
    func handleMigration(kind: KeyKind, mangaKey: String, chapterKey: String?) async throws -> String
    func store<T: Sendable>(value: T) async throws -> Int32
    func remove(value: Int32) async throws
}

public extension Runner {
    var partialMangaPublisher: SinglePublisher<Manga>? { nil }
    func getMangaList(listing: Listing, page: Int) async throws -> MangaPageResult { throw SourceError.unimplemented }
    func processPageImage(response: Response, context: PageContext?) async throws -> PlatformImage? { throw SourceError.unimplemented }
    func getSearchFilters() async throws -> [Filter] { throw SourceError.unimplemented }
    func getSettings() async throws -> [Setting] { throw SourceError.unimplemented }
    func getListings() async throws -> [Listing] { throw SourceError.unimplemented }
    func getImageRequest(url: String, context: PageContext?) async throws -> URLRequest { throw SourceError.unimplemented }
    func getPageDescription(page: Page) async throws -> String? { throw SourceError.unimplemented }
    func getAlternateCovers(manga: Manga) async throws -> [String] { throw SourceError.unimplemented }
    func getBaseUrl() async throws -> URL? { throw SourceError.unimplemented }
    func handleNotification(notification: String) async throws { throw SourceError.unimplemented }
    func handleDeepLink(url: String) async throws -> DeepLinkResult? { throw SourceError.unimplemented }
    func handleBasicLogin(key: String, username: String, password: String) async throws -> Bool { throw SourceError.unimplemented }
    func handleWebLogin(key: String, cookies: [String: String]) async throws -> Bool { throw SourceError.unimplemented }
    func handleMigration(kind: KeyKind, mangaKey: String, chapterKey: String?) async throws -> String { throw SourceError.unimplemented }
    func store<T: Sendable>(value: T) async throws -> Int32 { throw SourceError.unimplemented }
    func remove(value: Int32) async throws { throw SourceError.unimplemented }
}

public struct InterpreterConfiguration: Sendable {
    public typealias PrintHandler = @Sendable (String) -> Void
    public typealias RequestHandler = @Sendable (URLRequest) async throws -> (Data, URLResponse)
    public let printHandler: PrintHandler?
    public let requestHandler: RequestHandler?
    public init(printHandler: PrintHandler? = nil, requestHandler: RequestHandler? = nil) {
        self.printHandler = printHandler
        self.requestHandler = requestHandler
    }
}

/// Marker retained only so legacy Aidoku UI checks compile. This local module
/// never constructs an interpreter and contains no Wasm3 implementation.
public final class Interpreter: Runner, @unchecked Sendable {
    public let features = SourceFeatures()
    public init() {}
    public func getSearchMangaList(query: String?, page: Int, filters: [FilterValue]) async throws -> MangaPageResult {
        throw SourceError.unimplemented
    }
    public func getMangaUpdate(manga: Manga, needsDetails: Bool, needsChapters: Bool) async throws -> Manga {
        throw SourceError.unimplemented
    }
    public func getPageList(manga: Manga, chapter: Chapter) async throws -> [Page] {
        throw SourceError.unimplemented
    }
}
