import Foundation
import TachiJVMRunner

enum TachiyomiXJarRepository {
    struct Repository: Codable, Identifiable, Sendable, Hashable {
        let name: String
        let catalogURL: URL

        var id: String { catalogURL.absoluteString }
    }

    struct Catalog: Decodable, Sendable {
        let name: String
        let extensionList: ExtensionList

        struct ExtensionList: Decodable, Sendable {
            let extensions: [Extension]
        }

        struct Extension: Decodable, Identifiable, Sendable {
            let name: String
            let packageName: String
            let resources: Resources
            let extensionLib: String
            let versionCode: String
            let versionName: String
            let contentWarning: String?
            let sources: [Source]

            var id: String { packageName }
            var isNsfw: Bool {
                contentWarning == "CONTENT_WARNING_NSFW"
            }

            var usesSupportedExtensionLibrary: Bool {
                let components = extensionLib.split(separator: ".")
                guard
                    components.count >= 2,
                    components[0] == "1",
                    let minor = Int(components[1])
                else {
                    return false
                }
                return (4...6).contains(minor)
            }
        }

        struct Resources: Decodable, Sendable {
            let apkUrl: URL
            let iconUrl: URL?
            let jarUrl: URL
        }

        struct Source: Decodable, Identifiable, Sendable {
            let id: String
            let name: String
            let language: String
            let homeUrl: URL?
        }
    }

    enum RepositoryError: LocalizedError {
        case invalidURL
        case unsupportedScheme
        case duplicateRepository
        case missingDeepLinkURL
        case malformedProtobuf
        case noCompatibleJars
        case repositoryTooLarge
        case invalidArtifactURL

        var errorDescription: String? {
            switch self {
                case .invalidURL:
                    "Enter a valid extension repository URL."
                case .unsupportedScheme:
                    "Extension repository URLs must use HTTP or HTTPS."
                case .duplicateRepository:
                    "That extension repository is already added."
                case .missingDeepLinkURL:
                    "The extension repository link has no URL."
                case .malformedProtobuf:
                    "The extension repository contains malformed protobuf data."
                case .noCompatibleJars:
                    "The extension repository does not provide compatible JAR artifacts."
                case .repositoryTooLarge:
                    "The extension repository exceeds the 16 MB size limit."
                case .invalidArtifactURL:
                    "The extension repository contains an invalid JAR URL."
            }
        }
    }

    private static let defaultsKey = "extensionRepositories"
    private static let maximumCatalogSize: Int64 = 16 * 1_048_576

    static func repositories(
        defaults: UserDefaults = .standard
    ) -> [Repository] {
        guard
            let data = defaults.data(forKey: defaultsKey),
            let repositories = try? JSONDecoder().decode(
                [Repository].self,
                from: data
            )
        else {
            return []
        }
        return repositories
    }

    static func save(
        repositories: [Repository],
        defaults: UserDefaults = .standard
    ) throws {
        let data = try JSONEncoder().encode(repositories)
        defaults.set(data, forKey: defaultsKey)
    }

    static func catalogURL(from input: String) throws -> URL {
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard
            !value.isEmpty,
            var components = URLComponents(string: value),
            let scheme = components.scheme?.lowercased(),
            components.host != nil
        else {
            throw RepositoryError.invalidURL
        }
        guard scheme == "https" || scheme == "http" else {
            throw RepositoryError.unsupportedScheme
        }
        components.fragment = nil
        guard var url = components.url else {
            throw RepositoryError.invalidURL
        }
        if !["json", "pb"].contains(url.pathExtension.lowercased()) {
            url.appendPathComponent("index.pb", isDirectory: false)
        }
        return url
    }

    static func repositoryURL(fromDeepLink url: URL) throws -> String? {
        guard let scheme = url.scheme?.lowercased() else { return nil }
        let host = url.host?.lowercased()
        let isStoreLink =
            (
                ["tachiyomiaz", "tachiaz", "mihon"].contains(scheme) &&
                    host == "extension-store"
            ) ||
            (scheme == "tachiyomi" && host == "add-repo")
        guard isStoreLink else { return nil }
        let components = URLComponents(
            url: url,
            resolvingAgainstBaseURL: false
        )
        guard
            let value = components?.queryItems?.first(where: {
                $0.name == "url"
            })?.value,
            !value.isEmpty
        else {
            throw RepositoryError.missingDeepLinkURL
        }
        return value
    }

    static func addRepository(
        from input: String,
        using session: URLSession = .shared,
        defaults: UserDefaults = .standard
    ) async throws -> Repository {
        let url = try catalogURL(from: input)
        let current = repositories(defaults: defaults)
        guard !current.contains(where: { $0.catalogURL == url }) else {
            throw RepositoryError.duplicateRepository
        }
        let catalog = try await fetchCatalog(from: url, using: session)
        let repository = Repository(name: catalog.name, catalogURL: url)
        try save(repositories: current + [repository], defaults: defaults)
        return repository
    }

    static func fetchCatalog(
        from catalogURL: URL,
        using session: URLSession = .shared
    ) async throws -> Catalog {
        let data = try await fetchData(from: catalogURL, using: session)
        return try await decodeCatalogData(
            data,
            catalogURL: catalogURL,
            using: session
        )
    }

    static func decodeCatalogData(
        _ data: Data,
        catalogURL: URL,
        using session: URLSession = .shared
    ) async throws -> Catalog {
        let catalog: Catalog
        if firstMeaningfulByte(in: data) == UInt8(ascii: "{") {
            catalog = try JSONDecoder().decode(Catalog.self, from: data)
        } else {
            catalog = try await decodeProtobufCatalog(
                data,
                catalogURL: catalogURL,
                using: session
            )
        }
        let baseURL = catalogURL.deletingLastPathComponent()
        let resolved = Catalog(
            name: catalog.name,
            extensionList: .init(
                extensions: catalog.extensionList.extensions.map { entry in
                    .init(
                        name: entry.name,
                        packageName: entry.packageName,
                        resources: .init(
                            apkUrl: absolute(
                                entry.resources.apkUrl,
                                relativeTo: baseURL
                            ),
                            iconUrl: entry.resources.iconUrl.map {
                                absolute($0, relativeTo: baseURL)
                            },
                            jarUrl: absolute(
                                entry.resources.jarUrl,
                                relativeTo: baseURL
                            )
                        ),
                        extensionLib: entry.extensionLib,
                        versionCode: entry.versionCode,
                        versionName: entry.versionName,
                        contentWarning: entry.contentWarning,
                        sources: entry.sources.map { source in
                            .init(
                                id: source.id,
                                name: source.name,
                                language: source.language,
                                homeUrl: source.homeUrl.map {
                                    absolute($0, relativeTo: baseURL)
                                }
                            )
                        }
                    )
                }
            )
        )
        guard resolved.extensionList.extensions.contains(where: {
            $0.usesSupportedExtensionLibrary &&
                ["http", "https"].contains(
                    $0.resources.jarUrl.scheme?.lowercased() ?? ""
                )
        }) else {
            throw RepositoryError.noCompatibleJars
        }
        guard resolved.extensionList.extensions.allSatisfy({ entry in
            ["http", "https"].contains(
                entry.resources.jarUrl.scheme?.lowercased() ?? ""
            )
        }) else {
            throw RepositoryError.invalidArtifactURL
        }
        return resolved
    }

    private static func fetchData(
        from url: URL,
        using session: URLSession
    ) async throws -> Data {
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadRevalidatingCacheData
        request.timeoutInterval = 30
        let (temporaryFile, response) = try await session.download(for: request)
        defer { try? FileManager.default.removeItem(at: temporaryFile) }
        if
            let httpResponse = response as? HTTPURLResponse,
            !(200..<300).contains(httpResponse.statusCode)
        {
            throw URLError(.badServerResponse)
        }
        let fileSize = Int64(
            (try temporaryFile.resourceValues(forKeys: [.fileSizeKey]))
                .fileSize ?? 0
        )
        guard
            response.expectedContentLength <= maximumCatalogSize,
            fileSize <= maximumCatalogSize
        else {
            throw RepositoryError.repositoryTooLarge
        }
        let data = try Data(contentsOf: temporaryFile, options: .mappedIfSafe)
        if data.starts(with: [0x1f, 0x8b]) {
            let uncompressed = try TachiJVMCompression.gunzip(data)
            guard uncompressed.count <= maximumCatalogSize else {
                throw RepositoryError.repositoryTooLarge
            }
            return uncompressed
        }
        return data
    }

    private static func firstMeaningfulByte(in data: Data) -> UInt8? {
        data.first {
            ![0x09, 0x0a, 0x0d, 0x20].contains($0)
        }
    }

    private static func decodeProtobufCatalog(
        _ data: Data,
        catalogURL: URL,
        using session: URLSession
    ) async throws -> Catalog {
        var reader = ProtobufReader(data)
        var name: String?
        var extensionListData: Data?
        var extensionListURL: URL?
        while let field = try reader.nextField() {
            switch (field.number, field.value) {
                case (1, .bytes(let value)):
                    name = try protobufString(value)
                case (101, .bytes(let value)):
                    extensionListData = Data(value)
                case (102, .bytes(let value)):
                    let string = try protobufString(value)
                    extensionListURL = URL(
                        string: string,
                        relativeTo: catalogURL.deletingLastPathComponent()
                    )?.absoluteURL
                default:
                    break
            }
        }
        guard let name else {
            throw RepositoryError.malformedProtobuf
        }

        let listData: Data
        if let extensionListData {
            listData = extensionListData
        } else if let extensionListURL {
            listData = try await fetchData(
                from: extensionListURL,
                using: session
            )
        } else {
            throw RepositoryError.malformedProtobuf
        }

        let extensionList: Catalog.ExtensionList
        if firstMeaningfulByte(in: listData) == UInt8(ascii: "{") {
            extensionList = try JSONDecoder().decode(
                Catalog.ExtensionList.self,
                from: listData
            )
        } else {
            extensionList = try decodeProtobufExtensionList(listData)
        }
        return Catalog(name: name, extensionList: extensionList)
    }

    private static func decodeProtobufExtensionList(
        _ data: Data
    ) throws -> Catalog.ExtensionList {
        var reader = ProtobufReader(data)
        var extensions: [Catalog.Extension] = []
        while let field = try reader.nextField() {
            guard
                field.number == 1,
                case .bytes(let value) = field.value,
                let entry = try decodeProtobufExtension(Data(value))
            else {
                continue
            }
            extensions.append(entry)
        }
        return .init(extensions: extensions)
    }

    private static func decodeProtobufExtension(
        _ data: Data
    ) throws -> Catalog.Extension? {
        var reader = ProtobufReader(data)
        var name: String?
        var packageName: String?
        var resources: Catalog.Resources?
        var extensionLibrary: String?
        var versionCode: UInt64 = 0
        var versionName: String?
        var contentWarning: String?
        var sources: [Catalog.Source] = []
        while let field = try reader.nextField() {
            switch (field.number, field.value) {
                case (1, .bytes(let value)):
                    name = try protobufString(value)
                case (2, .bytes(let value)):
                    packageName = try protobufString(value)
                case (3, .bytes(let value)):
                    resources = try decodeProtobufResources(Data(value))
                case (4, .bytes(let value)):
                    extensionLibrary = try protobufString(value)
                case (5, .varint(let value)):
                    versionCode = value
                case (6, .bytes(let value)):
                    versionName = try protobufString(value)
                case (7, .varint(let value)):
                    contentWarning = protobufContentWarning(value)
                case (8, .bytes(let value)):
                    sources.append(
                        try decodeProtobufSource(Data(value))
                    )
                default:
                    break
            }
        }
        guard
            let name,
            let packageName,
            let resources,
            let extensionLibrary,
            let versionName
        else {
            throw RepositoryError.malformedProtobuf
        }
        return .init(
            name: name,
            packageName: packageName,
            resources: resources,
            extensionLib: extensionLibrary,
            versionCode: String(versionCode),
            versionName: versionName,
            contentWarning: contentWarning,
            sources: sources
        )
    }

    private static func decodeProtobufResources(
        _ data: Data
    ) throws -> Catalog.Resources? {
        var reader = ProtobufReader(data)
        var apkURL: URL?
        var iconURL: URL?
        var jarURL: URL?
        while let field = try reader.nextField() {
            guard case .bytes(let value) = field.value else { continue }
            let string = try protobufString(value)
            switch field.number {
                case 1:
                    apkURL = URL(string: string)
                case 2:
                    iconURL = URL(string: string)
                case 501:
                    jarURL = URL(string: string)
                default:
                    break
            }
        }
        guard let apkURL, let jarURL else { return nil }
        return .init(apkUrl: apkURL, iconUrl: iconURL, jarUrl: jarURL)
    }

    private static func decodeProtobufSource(
        _ data: Data
    ) throws -> Catalog.Source {
        var reader = ProtobufReader(data)
        var id: Int64 = 0
        var name: String?
        var language: String?
        var homeURL: URL?
        while let field = try reader.nextField() {
            switch (field.number, field.value) {
                case (1, .varint(let value)):
                    id = Int64(bitPattern: value)
                case (2, .bytes(let value)):
                    name = try protobufString(value)
                case (3, .bytes(let value)):
                    language = try protobufString(value)
                case (4, .bytes(let value)):
                    let string = try protobufString(value)
                    if !string.isEmpty {
                        homeURL = URL(string: string)
                    }
                default:
                    break
            }
        }
        guard let name, let language else {
            throw RepositoryError.malformedProtobuf
        }
        return .init(
            id: String(id),
            name: name,
            language: language,
            homeUrl: homeURL
        )
    }

    private static func protobufString(_ bytes: [UInt8]) throws -> String {
        guard let value = String(bytes: bytes, encoding: .utf8) else {
            throw RepositoryError.malformedProtobuf
        }
        return value
    }

    private static func protobufContentWarning(_ value: UInt64) -> String? {
        switch value {
            case 1: "CONTENT_WARNING_SAFE"
            case 2: "CONTENT_WARNING_MIXED"
            case 3: "CONTENT_WARNING_NSFW"
            default: nil
        }
    }

    private static func absolute(_ url: URL, relativeTo baseURL: URL) -> URL {
        guard url.scheme == nil else { return url }
        return URL(
            string: url.relativeString,
            relativeTo: baseURL
        )?.absoluteURL ?? url
    }
}

private struct ProtobufReader {
    enum Value {
        case varint(UInt64)
        case bytes([UInt8])
        case ignored
    }

    let bytes: [UInt8]
    var offset = 0

    init(_ data: Data) {
        bytes = Array(data)
    }

    mutating func nextField() throws -> (number: Int, value: Value)? {
        guard offset < bytes.count else { return nil }
        let tag = try readVarint()
        let number = Int(tag >> 3)
        guard number > 0 else {
            throw TachiyomiXJarRepository.RepositoryError.malformedProtobuf
        }
        switch tag & 0x07 {
            case 0:
                return (number, .varint(try readVarint()))
            case 1:
                try skip(8)
                return (number, .ignored)
            case 2:
                let count = try readVarint()
                guard count <= UInt64(Int.max) else {
                    throw TachiyomiXJarRepository.RepositoryError
                        .malformedProtobuf
                }
                let value = try readBytes(Int(count))
                return (number, .bytes(value))
            case 5:
                try skip(4)
                return (number, .ignored)
            default:
                throw TachiyomiXJarRepository.RepositoryError
                    .malformedProtobuf
        }
    }

    private mutating func readVarint() throws -> UInt64 {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        while offset < bytes.count, shift < 64 {
            let byte = bytes[offset]
            offset += 1
            result |= UInt64(byte & 0x7f) << shift
            if byte & 0x80 == 0 {
                return result
            }
            shift += 7
        }
        throw TachiyomiXJarRepository.RepositoryError.malformedProtobuf
    }

    private mutating func readBytes(_ count: Int) throws -> [UInt8] {
        guard count >= 0, offset <= bytes.count - count else {
            throw TachiyomiXJarRepository.RepositoryError.malformedProtobuf
        }
        defer { offset += count }
        return Array(bytes[offset..<(offset + count)])
    }

    private mutating func skip(_ count: Int) throws {
        guard count >= 0, offset <= bytes.count - count else {
            throw TachiyomiXJarRepository.RepositoryError.malformedProtobuf
        }
        offset += count
    }
}
