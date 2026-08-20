import Foundation

public struct ExtensionHostRequest: Codable, Sendable {
    public let operation: String
    public let extensionId: String?
    public let sourceId: String?
    public let jarPath: String?
    public let entryClass: String?
    public let method: String?
    public let argument: String?
    public var userAgent: String?
    public let query: String?
    public let filterStates: String?
    public let settingKey: String?
    public let settingType: String?
    public let settingValue: String?
    public let mangaURL: String?
    public let mangaTitle: String?
    public let mangaThumbnailURL: String?
    public let mangaArtist: String?
    public let mangaAuthor: String?
    public let mangaStatus: String?
    public let mangaDescription: String?
    public let mangaGenre: String?
    public let mangaUpdateStrategy: String?
    public let mangaInitialized: String?
    public let mangaMemo: String?
    /// JSON-encoded TachiyomiXChapter array, in the order returned by the source.
    public let mangaChapters: String?
    public let chapterURL: String?
    public let chapterName: String?
    public let chapterNumber: String?
    public let chapterScanlator: String?
    public let chapterDateUpload: String?
    public let chapterMemo: String?
    public let fetchDetails: String?
    public let fetchChapters: String?
    public let imageURL: String?
    public let pageURL: String?
    public let destinationPath: String?

    public init(
        operation: String,
        extensionId: String? = nil,
        sourceId: String? = nil,
        jarPath: String? = nil,
        entryClass: String? = nil,
        method: String? = nil,
        argument: String? = nil,
        userAgent: String? = nil,
        query: String? = nil,
        filterStates: String? = nil,
        settingKey: String? = nil,
        settingType: String? = nil,
        settingValue: String? = nil,
        mangaURL: String? = nil,
        mangaTitle: String? = nil,
        mangaThumbnailURL: String? = nil,
        mangaArtist: String? = nil,
        mangaAuthor: String? = nil,
        mangaStatus: String? = nil,
        mangaDescription: String? = nil,
        mangaGenre: String? = nil,
        mangaUpdateStrategy: String? = nil,
        mangaInitialized: String? = nil,
        mangaMemo: String? = nil,
        mangaChapters: String? = nil,
        chapterURL: String? = nil,
        chapterName: String? = nil,
        chapterNumber: String? = nil,
        chapterScanlator: String? = nil,
        chapterDateUpload: String? = nil,
        chapterMemo: String? = nil,
        fetchDetails: String? = nil,
        fetchChapters: String? = nil,
        imageURL: String? = nil,
        pageURL: String? = nil,
        destinationPath: String? = nil
    ) {
        self.operation = operation
        self.extensionId = extensionId
        self.sourceId = sourceId
        self.jarPath = jarPath
        self.entryClass = entryClass
        self.method = method
        self.argument = argument
        self.userAgent = userAgent
        self.query = query
        self.filterStates = filterStates
        self.settingKey = settingKey
        self.settingType = settingType
        self.settingValue = settingValue
        self.mangaURL = mangaURL
        self.mangaTitle = mangaTitle
        self.mangaThumbnailURL = mangaThumbnailURL
        self.mangaArtist = mangaArtist
        self.mangaAuthor = mangaAuthor
        self.mangaStatus = mangaStatus
        self.mangaDescription = mangaDescription
        self.mangaGenre = mangaGenre
        self.mangaUpdateStrategy = mangaUpdateStrategy
        self.mangaInitialized = mangaInitialized
        self.mangaMemo = mangaMemo
        self.mangaChapters = mangaChapters
        self.chapterURL = chapterURL
        self.chapterName = chapterName
        self.chapterNumber = chapterNumber
        self.chapterScanlator = chapterScanlator
        self.chapterDateUpload = chapterDateUpload
        self.chapterMemo = chapterMemo
        self.fetchDetails = fetchDetails
        self.fetchChapters = fetchChapters
        self.imageURL = imageURL
        self.pageURL = pageURL
        self.destinationPath = destinationPath
    }
}

public struct ExtensionHostResponse: Codable, Sendable {
    public let success: Bool
    public let result: String?
    public let error: String?
    public let runtime: String?
    public let javaVersion: String?
    public let packageName: String?
    public let name: String?
    public let version: String?
    public let versionCode: String?
    public let entryClass: String?
    public let minimumSdk: String?
    public let targetSdk: String?
    public let nsfw: String?
    public let extensionLibrary: String?
    public let sourceCount: String?
    public let classCount: String?
    public let maximumClassVersion: String?
    public let requiredJavaVersion: String?
    public let runtimeCompatible: String?
}
