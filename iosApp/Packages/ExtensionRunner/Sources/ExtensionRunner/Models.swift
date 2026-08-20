import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

#if canImport(UIKit)
import UIKit
public typealias PlatformImage = UIImage
public extension UIImage {
    var image: UIImage { self }
}
#elseif canImport(AppKit)
import AppKit
public struct PlatformImage: @unchecked Sendable, Hashable {
    public let image: NSImage
    public var size: NSSize { image.size }
    public init(_ image: NSImage) { self.image = image }
    public init?(data: Data) {
        guard let image = NSImage(data: data) else { return nil }
        self.image = image
    }
    public func pngData() -> Data? {
        guard let data = image.tiffRepresentation,
              let bitmap = NSBitmapImageRep(data: data) else { return nil }
        return bitmap.representation(using: .png, properties: [:])
    }
}
#else
/// Non-Apple placeholder used only to compile and test the model package.
public struct PlatformImage: Sendable, Hashable {
    public init() {}
}
#endif

public enum PublishingStatus: UInt8, Sendable, Codable, CaseIterable {
    case unknown, ongoing, completed, cancelled, hiatus
}

public enum ContentRating: UInt8, Sendable, Codable, CaseIterable {
    case unknown, safe, suggestive, nsfw
}

public enum SourceContentRating: Int, Sendable, Codable, CaseIterable {
    case safe, containsNsfw, primarilyNsfw
}

public enum Viewer: UInt8, Sendable, Codable, CaseIterable {
    case unknown, leftToRight, rightToLeft, vertical, webtoon
}

public enum UpdateStrategy: UInt8, Sendable, Codable {
    case always, never
}

public struct Chapter: Sendable, Hashable, Codable, Identifiable {
    public var key: String
    public var title: String?
    public var chapterNumber: Float?
    public var volumeNumber: Float?
    public var dateUploaded: Date?
    public var scanlators: [String]?
    public var url: URL?
    public var language: String?
    public var thumbnail: String?
    public var locked: Bool
    /// Opaque extension-owned JSON state that must survive persistence.
    public var memo: String?
    public var id: String { key }

    public init(
        key: String,
        title: String? = nil,
        chapterNumber: Float? = nil,
        volumeNumber: Float? = nil,
        dateUploaded: Date? = nil,
        scanlators: [String]? = nil,
        url: URL? = nil,
        language: String? = nil,
        thumbnail: String? = nil,
        locked: Bool = false,
        memo: String? = nil
    ) {
        self.key = key
        self.title = title
        self.chapterNumber = chapterNumber
        self.volumeNumber = volumeNumber
        self.dateUploaded = dateUploaded
        self.scanlators = scanlators
        self.url = url
        self.language = language
        self.thumbnail = thumbnail
        self.locked = locked
        self.memo = memo
    }
}

public struct Manga: Sendable, Hashable, Codable {
    public var sourceKey: String
    public let key: String
    public var title: String
    public var cover: String?
    public var artists: [String]?
    public var authors: [String]?
    public var description: String?
    public var url: URL?
    public var tags: [String]?
    public var status: PublishingStatus
    public var contentRating: ContentRating
    public var viewer: Viewer
    public var updateStrategy: UpdateStrategy
    public var nextUpdateTime: Int?
    public var chapters: [Chapter]?
    /// Opaque extension-owned JSON state that must survive persistence.
    public var memo: String?

    public init(
        sourceKey: String,
        key: String,
        title: String,
        cover: String? = nil,
        artists: [String]? = nil,
        authors: [String]? = nil,
        description: String? = nil,
        url: URL? = nil,
        tags: [String]? = nil,
        status: PublishingStatus = .unknown,
        contentRating: ContentRating = .unknown,
        viewer: Viewer = .unknown,
        updateStrategy: UpdateStrategy = .always,
        nextUpdateTime: Int? = nil,
        chapters: [Chapter]? = nil,
        memo: String? = nil
    ) {
        self.sourceKey = sourceKey
        self.key = key
        self.title = title
        self.cover = cover
        self.artists = artists
        self.authors = authors
        self.description = description
        self.url = url
        self.tags = tags
        self.status = status
        self.contentRating = contentRating
        self.viewer = viewer
        self.updateStrategy = updateStrategy
        self.nextUpdateTime = nextUpdateTime
        self.chapters = chapters
        self.memo = memo
    }

    public func copy(from other: Self) -> Self {
        .init(
            sourceKey: other.sourceKey.isEmpty ? sourceKey : other.sourceKey,
            key: other.key.isEmpty ? key : other.key,
            title: other.title.isEmpty ? title : other.title,
            cover: other.cover ?? cover,
            artists: other.artists ?? artists,
            authors: other.authors ?? authors,
            description: other.description ?? description,
            url: other.url ?? url,
            tags: other.tags ?? tags,
            status: other.status,
            contentRating: other.contentRating,
            viewer: other.viewer,
            updateStrategy: other.updateStrategy,
            nextUpdateTime: other.nextUpdateTime,
            chapters: other.chapters ?? chapters,
            memo: other.memo ?? memo
        )
    }

}

public struct Listing: Sendable, Hashable, Codable {
    public var id: String
    public var name: String
    public var kind: ListingKind
    public init(id: String, name: String, kind: ListingKind = .default) {
        self.id = id
        self.name = name
        self.kind = kind
    }
}

public enum ListingKind: UInt8, Sendable, Codable {
    case `default`, list
}

public struct MangaPageResult: Sendable, Codable {
    public var entries: [Manga]
    public var hasNextPage: Bool
    public init(entries: [Manga], hasNextPage: Bool) {
        self.entries = entries
        self.hasNextPage = hasNextPage
    }
    public mutating func setSourceKey(_ key: String) {
        for index in entries.indices {
            entries[index].sourceKey = key
        }
    }
}

public struct MangaWithChapter: Sendable, Codable, Hashable {
    public var manga: Manga
    public var chapter: Chapter
    public init(manga: Manga, chapter: Chapter) {
        self.manga = manga
        self.chapter = chapter
    }
}

public typealias PageContext = [String: String]
public typealias ImageRef = Int32

public struct Page: @unchecked Sendable, Hashable {
    public var content: PageContent
    public var thumbnail: URL?
    public var hasDescription: Bool
    public var description: String?
    public init(
        content: PageContent,
        thumbnail: URL? = nil,
        hasDescription: Bool = false,
        description: String? = nil
    ) {
        self.content = content
        self.thumbnail = thumbnail
        self.hasDescription = hasDescription
        self.description = description
    }
}

public enum PageContent: @unchecked Sendable, Hashable {
    case url(url: URL, context: PageContext? = nil)
    case text(String)
    case image(PlatformImage)
    case zipFile(url: URL, filePath: String)
}

public struct Request: Sendable {
    public let url: URL?
    public let headers: [String: String]
    public init(url: URL?, headers: [String: String]) {
        self.url = url
        self.headers = headers
    }
}

public struct Response: Sendable {
    public let code: UInt16
    public let headers: [String: String]
    public let request: Request
    public let image: ImageRef
    public init(code: Int, headers: [String: String], request: Request, image: ImageRef) {
        self.code = UInt16(clamping: code)
        self.headers = headers
        self.request = request
        self.image = image
    }
}

public struct DeepLinkResult: Sendable, Codable {
    public var mangaKey: String?
    public var chapterKey: String?
    public var listing: Listing?
    public init(mangaKey: String? = nil, chapterKey: String? = nil, listing: Listing? = nil) {
        self.mangaKey = mangaKey
        self.chapterKey = chapterKey
        self.listing = listing
    }
}

public enum KeyKind: UInt8, Sendable, Codable {
    case manga, chapter
}

public enum SourceError: LocalizedError, Equatable {
    case missingResult
    case unimplemented
    case networkError
    case message(String)

    public var errorDescription: String? {
        switch self {
            case .missingResult:
                "No result was returned by the source."
            case .unimplemented:
                "This operation is not implemented by the source."
            case .networkError:
                "The source request failed because of a network error."
            case .message(let message):
                message
        }
    }
}

public struct SourceFeatures: Sendable {
    public let providesListings: Bool
    public let dynamicFilters: Bool
    public let dynamicSettings: Bool
    public let dynamicListings: Bool
    public let processesPages: Bool
    public let providesImageRequests: Bool
    public let providesPageDescriptions: Bool
    public let providesAlternateCovers: Bool
    public let providesBaseUrl: Bool
    public let handlesNotifications: Bool
    public let handlesDeepLinks: Bool
    public let handlesBasicLogin: Bool
    public let handlesWebLogin: Bool
    public let handlesMigration: Bool

    public init(
        providesListings: Bool = false,
        dynamicFilters: Bool = false,
        dynamicSettings: Bool = false,
        dynamicListings: Bool = false,
        processesPages: Bool = false,
        providesImageRequests: Bool = false,
        providesPageDescriptions: Bool = false,
        providesAlternateCovers: Bool = false,
        providesBaseUrl: Bool = false,
        handlesNotifications: Bool = false,
        handlesDeepLinks: Bool = false,
        handlesBasicLogin: Bool = false,
        handlesWebLogin: Bool = false,
        handlesMigration: Bool = false
    ) {
        self.providesListings = providesListings
        self.dynamicFilters = dynamicFilters
        self.dynamicSettings = dynamicSettings
        self.dynamicListings = dynamicListings
        self.processesPages = processesPages
        self.providesImageRequests = providesImageRequests
        self.providesPageDescriptions = providesPageDescriptions
        self.providesAlternateCovers = providesAlternateCovers
        self.providesBaseUrl = providesBaseUrl
        self.handlesNotifications = handlesNotifications
        self.handlesDeepLinks = handlesDeepLinks
        self.handlesBasicLogin = handlesBasicLogin
        self.handlesWebLogin = handlesWebLogin
        self.handlesMigration = handlesMigration
    }
}

public enum LanguageSelectType: String, Sendable, Codable {
    case single, multiple
}

public struct SourceInfo: Sendable, Codable {
    public struct Configuration: Sendable, Codable {
        public var languageSelectType: LanguageSelectType?
        public var supportsArtistSearch: Bool?
        public var supportsAuthorSearch: Bool?
        public var supportsTagSearch: Bool?
        public var allowsBaseUrlSelect: Bool?
        public var breakingChangeVersion: Int?
        public var hidesFiltersWhileSearching: Bool?
        public var maximumParallelRequests: Int?
        public init(
            languageSelectType: LanguageSelectType? = nil,
            supportsArtistSearch: Bool? = nil,
            supportsAuthorSearch: Bool? = nil,
            supportsTagSearch: Bool? = nil,
            allowsBaseUrlSelect: Bool? = nil,
            breakingChangeVersion: Int? = nil,
            hidesFiltersWhileSearching: Bool? = nil,
            maximumParallelRequests: Int? = nil
        ) {
            self.languageSelectType = languageSelectType
            self.supportsArtistSearch = supportsArtistSearch
            self.supportsAuthorSearch = supportsAuthorSearch
            self.supportsTagSearch = supportsTagSearch
            self.allowsBaseUrlSelect = allowsBaseUrlSelect
            self.breakingChangeVersion = breakingChangeVersion
            self.hidesFiltersWhileSearching = hidesFiltersWhileSearching
            self.maximumParallelRequests = maximumParallelRequests
        }
    }
}

public actor SinglePublisher<Value: Sendable> {
    private var receiver: (@Sendable (Value) -> Void)?
    public init() {}
    public func send(_ value: Value) { receiver?(value) }
    public func sink(to receiver: @escaping @Sendable (Value) -> Void) {
        self.receiver = receiver
    }
    public func removeSink() { receiver = nil }
}
