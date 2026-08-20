import Foundation

public struct TachiyomiXManga: Codable, Sendable, Equatable {
    public let url: String
    public let title: String
    public let thumbnailURL: String?
    public let artist: String?
    public let author: String?
    public let status: Int
    public let description: String?
    public let genre: String?
    public let updateStrategy: String?
    public let initialized: Bool?
    /// Opaque JSON object owned by the extension library.
    public let memo: String?
}

public struct TachiyomiXMangaPage: Codable, Sendable, Equatable {
    public let mangas: [TachiyomiXManga]
    public let hasNextPage: Bool
}

public struct TachiyomiXChapter: Codable, Sendable, Equatable {
    public let url: String
    public let name: String
    public let chapterNumber: Float?
    public let scanlator: String?
    public let dateUpload: Int64
    /// Opaque JSON object owned by the extension library.
    public let memo: String?

    public init(
        url: String,
        name: String,
        chapterNumber: Float?,
        scanlator: String?,
        dateUpload: Int64,
        memo: String?
    ) {
        self.url = url
        self.name = name
        self.chapterNumber = chapterNumber
        self.scanlator = scanlator
        self.dateUpload = dateUpload
        self.memo = memo
    }
}

public struct TachiyomiXMangaUpdate: Codable, Sendable, Equatable {
    public let manga: TachiyomiXManga
    public let chapters: [TachiyomiXChapter]
}

public struct TachiyomiXPage: Codable, Sendable, Equatable {
    public let index: Int
    public let url: String
    public let imageURL: String?
    public let uri: String?
}

public struct TachiyomiXSourceDescriptor: Codable, Sendable, Equatable {
    public let id: Int64
    public let name: String
    public let lang: String
    public let supportsLatest: Bool
    public let baseURL: String?
    public let homeURL: String?

    public init(
        id: Int64,
        name: String,
        lang: String,
        supportsLatest: Bool,
        baseURL: String? = nil,
        homeURL: String? = nil
    ) {
        self.id = id
        self.name = name
        self.lang = lang
        self.supportsLatest = supportsLatest
        self.baseURL = baseURL
        self.homeURL = homeURL
    }
}

public extension JVMRuntime {
    @discardableResult
    func initializeExtensionCompatibility() throws -> ExtensionHostResponse {
        try checkedDispatch(
            ExtensionHostRequest(operation: "initializeCompatibility")
        )
    }

    @discardableResult
    func loadTachiyomiXExtension(
        id: String,
        jarURL: URL,
        entryClass: String? = nil
    ) throws -> ExtensionHostResponse {
        try checkedDispatch(
            ExtensionHostRequest(
                operation: "loadExtension",
                extensionId: id,
                jarPath: jarURL.path,
                entryClass: entryClass
            )
        )
    }

    func popularManga(
        extensionId: String,
        sourceId: Int64? = nil,
        page: Int
    ) throws -> TachiyomiXMangaPage {
        try pagedManga(
            operation: "getPopularManga",
            extensionId: extensionId,
            sourceId: sourceId,
            page: page
        )
    }

    func latestManga(
        extensionId: String,
        sourceId: Int64? = nil,
        page: Int
    ) throws -> TachiyomiXMangaPage {
        try pagedManga(
            operation: "getLatestUpdates",
            extensionId: extensionId,
            sourceId: sourceId,
            page: page
        )
    }

    func searchManga(
        extensionId: String,
        sourceId: Int64? = nil,
        query: String,
        page: Int
    ) throws -> TachiyomiXMangaPage {
        guard !query.isEmpty else {
            throw JVMRuntimeError.invalidConfiguration(
                "Search query must not be empty"
            )
        }
        return try pagedManga(
            operation: "searchManga",
            extensionId: extensionId,
            sourceId: sourceId,
            page: page,
            query: query
        )
    }

    private func pagedManga(
        operation: String,
        extensionId: String,
        sourceId: Int64?,
        page: Int,
        query: String? = nil
    ) throws -> TachiyomiXMangaPage {
        guard page > 0 else {
            throw JVMRuntimeError.invalidConfiguration(
                "Manga page must be at least 1"
            )
        }
        let response = try checkedDispatch(
            ExtensionHostRequest(
                operation: operation,
                extensionId: extensionId,
                sourceId: sourceId.map(String.init),
                argument: String(page),
                query: query
            )
        )
        guard let result = response.result else {
            throw JVMRuntimeError.decodingFailed(
                "Manga-page response has no result"
            )
        }
        do {
            return try JSONDecoder().decode(
                TachiyomiXMangaPage.self,
                from: Data(result.utf8)
            )
        } catch {
            throw JVMRuntimeError.decodingFailed(
                error.localizedDescription
            )
        }
    }

    func sources(
        extensionId: String
    ) throws -> [TachiyomiXSourceDescriptor] {
        let response = try checkedDispatch(
            ExtensionHostRequest(
                operation: "listSources",
                extensionId: extensionId
            )
        )
        guard let result = response.result else {
            throw JVMRuntimeError.decodingFailed(
                "Source list response has no result"
            )
        }
        do {
            return try JSONDecoder().decode(
                [TachiyomiXSourceDescriptor].self,
                from: Data(result.utf8)
            )
        } catch {
            throw JVMRuntimeError.decodingFailed(
                error.localizedDescription
            )
        }
    }

    func mangaUpdate(
        extensionId: String,
        sourceId: Int64? = nil,
        mangaURL: String,
        mangaTitle: String,
        mangaThumbnailURL: String? = nil,
        mangaArtist: String? = nil,
        mangaAuthor: String? = nil,
        mangaStatus: Int = 0,
        mangaDescription: String? = nil,
        mangaGenre: String? = nil,
        mangaUpdateStrategy: String = "ALWAYS_UPDATE",
        mangaInitialized: Bool = false,
        mangaMemo: String? = nil,
        mangaChapters: [TachiyomiXChapter] = [],
        fetchDetails: Bool = true,
        fetchChapters: Bool = true
    ) throws -> TachiyomiXMangaUpdate {
        let encodedChapters = String(
            decoding: try JSONEncoder().encode(mangaChapters),
            as: UTF8.self
        )
        let response = try checkedDispatch(
            ExtensionHostRequest(
                operation: "getMangaUpdate",
                extensionId: extensionId,
                sourceId: sourceId.map(String.init),
                mangaURL: mangaURL,
                mangaTitle: mangaTitle,
                mangaThumbnailURL: mangaThumbnailURL,
                mangaArtist: mangaArtist,
                mangaAuthor: mangaAuthor,
                mangaStatus: String(mangaStatus),
                mangaDescription: mangaDescription,
                mangaGenre: mangaGenre,
                mangaUpdateStrategy: mangaUpdateStrategy,
                mangaInitialized: String(mangaInitialized),
                mangaMemo: mangaMemo,
                mangaChapters: encodedChapters,
                fetchDetails: String(fetchDetails),
                fetchChapters: String(fetchChapters)
            )
        )
        return try decodeResult(response, as: TachiyomiXMangaUpdate.self)
    }

    func pages(
        extensionId: String,
        sourceId: Int64? = nil,
        chapterURL: String,
        chapterName: String,
        chapterNumber: Float? = nil,
        chapterScanlator: String? = nil,
        chapterDateUpload: Int64 = 0,
        chapterMemo: String? = nil
    ) throws -> [TachiyomiXPage] {
        let response = try checkedDispatch(
            ExtensionHostRequest(
                operation: "getPageList",
                extensionId: extensionId,
                sourceId: sourceId.map(String.init),
                chapterURL: chapterURL,
                chapterName: chapterName,
                chapterNumber: chapterNumber.map { String($0) },
                chapterScanlator: chapterScanlator,
                chapterDateUpload: String(chapterDateUpload),
                chapterMemo: chapterMemo
            )
        )
        return try decodeResult(response, as: [TachiyomiXPage].self)
    }

    private func decodeResult<Value: Decodable>(
        _ response: ExtensionHostResponse,
        as type: Value.Type
    ) throws -> Value {
        guard let result = response.result else {
            throw JVMRuntimeError.decodingFailed(
                "Extension response has no result"
            )
        }
        do {
            return try JSONDecoder().decode(
                type,
                from: Data(result.utf8)
            )
        } catch {
            throw JVMRuntimeError.decodingFailed(
                error.localizedDescription
            )
        }
    }

    private func checkedDispatch(
        _ request: ExtensionHostRequest
    ) throws -> ExtensionHostResponse {
        let response: ExtensionHostResponse = try dispatch(request)
        guard response.success else {
            throw JVMRuntimeError.dispatchFailed(
                status: -1,
                message: response.error ?? "Unknown extension host error"
            )
        }
        return response
    }
}
