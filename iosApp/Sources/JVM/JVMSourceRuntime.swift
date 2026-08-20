import CryptoKit
import ExtensionRunner
import Foundation
import TachiJVMRunner

actor JVMSourceRuntime {
    static let shared = JVMSourceRuntime()

    enum RuntimeError: LocalizedError {
        case missingBundleResource(String)
        case checksumMismatch(expected: String, actual: String)
        case extensionTooLarge(Int64)
        case invalidExtensionIdentifier
        case extensionIdentityMismatch(expected: String, actual: String)
        case hostRejected(String)

        var errorDescription: String? {
            switch self {
                case .missingBundleResource(let name):
                    "The bundled JVM resource is missing: \(name)"
                case .checksumMismatch(let expected, let actual):
                    "Extension checksum mismatch. Expected \(expected), got \(actual)."
                case .extensionTooLarge(let limit):
                    "The extension JAR exceeds the \(limit / 1_048_576) MB download limit."
                case .invalidExtensionIdentifier:
                    "The extension identifier is invalid."
                case .extensionIdentityMismatch(let expected, let actual):
                    "Expected extension \(expected), got \(actual)."
                case .hostRejected(let message):
                    "The Java extension host rejected the request: \(message)"
            }
        }
    }

    private let fileManager: FileManager
    private var runtime: JVMRuntime?
    private var runtimeStartupTask: Task<JVMRuntime, Error>?
    private var cloudflareBypassTasks: [String: Task<String, Error>] = [:]
    private var cloudflareSessionUserAgents: [String: String] = [:]
    /// Bumped whenever a source's clearance is replaced, so a request whose retry failed can tell
    /// "nobody has solved anything since" from "someone already solved a newer one".
    private var cloudflareSessionGenerations: [String: Int] = [:]
    private var preparedImageDirectory = false

    private static let maximumExtensionSize: Int64 = 64 * 1_048_576

    private struct WebLoginInfo: Decodable {
        let baseURL: String
        let userAgent: String
    }

    init(fileManager: FileManager = .default) {
        self.fileManager = fileManager
    }

    func ping() async throws -> ExtensionHostResponse {
        try await dispatch(.init(operation: "ping"))
    }

    func inspect(jar: URL) async throws -> JVMExtensionInspection {
        let secured = jar.startAccessingSecurityScopedResource()
        defer {
            if secured {
                jar.stopAccessingSecurityScopedResource()
            }
        }
        let response = try await dispatch(
            .init(operation: "inspectExtension", jarPath: jar.path)
        )
        return try JVMExtensionInspection(response: response)
    }

    @discardableResult
    func install(
        jar sourceJar: URL,
        manifest: JVMExtensionManifest
    ) async throws -> URL {
        guard !manifest.directoryName.isEmpty else {
            throw RuntimeError.invalidExtensionIdentifier
        }

        let secured = sourceJar.startAccessingSecurityScopedResource()
        defer {
            if secured {
                sourceJar.stopAccessingSecurityScopedResource()
            }
        }

        let checksum = try sha256(of: sourceJar)
        guard checksum.caseInsensitiveCompare(manifest.sha256) == .orderedSame else {
            throw RuntimeError.checksumMismatch(
                expected: manifest.sha256,
                actual: checksum
            )
        }

        let directory = try extensionDirectory(for: manifest)
        let packageDirectory = directory.deletingLastPathComponent()
        try fileManager.createDirectory(
            at: packageDirectory,
            withIntermediateDirectories: true
        )

        let transactionId = UUID().uuidString
        let stagingDirectory = packageDirectory.appendingPathComponent(
            ".install-\(transactionId)",
            isDirectory: true
        )
        let backupDirectory = packageDirectory.appendingPathComponent(
            ".backup-\(transactionId)",
            isDirectory: true
        )
        try fileManager.createDirectory(
            at: stagingDirectory,
            withIntermediateDirectories: false
        )
        let stagedJar = stagingDirectory.appendingPathComponent(
            "extension.jar",
            isDirectory: false
        )
        let stagedMetadata = stagingDirectory.appendingPathComponent(
            "manifest.json",
            isDirectory: false
        )

        do {
            try fileManager.copyItem(at: sourceJar, to: stagedJar)

            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            try encoder.encode(manifest).write(
                to: stagedMetadata,
                options: .atomic
            )

            // Construct the extension before replacing any installed version.
            // A separate host id keeps a failed validation from displacing the
            // currently running extension.
            let validationId = "\(manifest.id).validation.\(transactionId)"
            let validationResponse = try await dispatch(
                .init(
                    operation: "loadExtension",
                    extensionId: validationId,
                    jarPath: stagedJar.path,
                    entryClass: manifest.entryClass
                )
            )
            try requireSuccess(validationResponse)
            let unloadResponse = try await dispatch(
                .init(
                    operation: "unloadExtension",
                    extensionId: validationId
                )
            )
            try requireSuccess(unloadResponse)

            if fileManager.fileExists(atPath: directory.path) {
                try fileManager.moveItem(
                    at: directory,
                    to: backupDirectory
                )
            }
            do {
                try fileManager.moveItem(
                    at: stagingDirectory,
                    to: directory
                )
                let installedJar = directory.appendingPathComponent(
                    "extension.jar",
                    isDirectory: false
                )
                let response = try await dispatch(
                    .init(
                        operation: "loadExtension",
                        extensionId: manifest.id,
                        jarPath: installedJar.path,
                        entryClass: manifest.entryClass
                    )
                )
                try requireSuccess(response)
                if fileManager.fileExists(atPath: backupDirectory.path) {
                    try? fileManager.removeItem(at: backupDirectory)
                }
                return installedJar
            } catch {
                if fileManager.fileExists(atPath: directory.path) {
                    try? fileManager.removeItem(at: directory)
                }
                if fileManager.fileExists(atPath: backupDirectory.path) {
                    try? fileManager.moveItem(
                        at: backupDirectory,
                        to: directory
                    )
                }
                throw error
            }
        } catch {
            if fileManager.fileExists(atPath: stagingDirectory.path) {
                try? fileManager.removeItem(at: stagingDirectory)
            }
            throw error
        }
    }

    @discardableResult
    func install(
        catalogEntry: TachiyomiXJarRepository.Catalog.Extension,
        using session: URLSession = .shared
    ) async throws -> JVMExtensionManifest {
        var request = URLRequest(url: catalogEntry.resources.jarUrl)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = 60
        let (temporaryJar, response) = try await session.download(for: request)
        defer { try? fileManager.removeItem(at: temporaryJar) }
        if
            let httpResponse = response as? HTTPURLResponse,
            !(200..<300).contains(httpResponse.statusCode)
        {
            throw URLError(.badServerResponse)
        }
        let downloadedSize = Int64(
            (try temporaryJar.resourceValues(forKeys: [.fileSizeKey]))
                .fileSize ?? 0
        )
        if
            response.expectedContentLength > Self.maximumExtensionSize ||
            downloadedSize > Self.maximumExtensionSize
        {
            throw RuntimeError.extensionTooLarge(Self.maximumExtensionSize)
        }
        let inspection = try await inspect(jar: temporaryJar)
        guard inspection.packageName == catalogEntry.packageName else {
            throw RuntimeError.extensionIdentityMismatch(
                expected: catalogEntry.packageName,
                actual: inspection.packageName
            )
        }
        guard inspection.version == catalogEntry.versionName else {
            throw RuntimeError.extensionIdentityMismatch(
                expected: catalogEntry.versionName,
                actual: inspection.version
            )
        }
        guard inspection.versionCode == catalogEntry.versionCode else {
            throw RuntimeError.extensionIdentityMismatch(
                expected: catalogEntry.versionCode,
                actual: inspection.versionCode
            )
        }
        guard inspection.extensionLibrary == catalogEntry.extensionLib else {
            throw RuntimeError.extensionIdentityMismatch(
                expected: catalogEntry.extensionLib,
                actual: inspection.extensionLibrary ?? "missing"
            )
        }
        let checksum = try sha256(of: temporaryJar)
        let manifest = JVMExtensionManifest(
            inspection: inspection,
            sourceURL: catalogEntry.resources.jarUrl,
            iconURL: catalogEntry.resources.iconUrl,
            sha256: checksum,
            versionCode: catalogEntry.versionCode,
            extensionLibrary: catalogEntry.extensionLib,
            isNsfw: catalogEntry.isNsfw
        )
        try await install(jar: temporaryJar, manifest: manifest)
        try removeSupersededVersions(of: manifest)
        return manifest
    }

    func loadInstalled(_ manifest: JVMExtensionManifest) async throws {
        let jar = try extensionDirectory(for: manifest)
            .appendingPathComponent("extension.jar")
        let checksum = try sha256(of: jar)
        guard checksum.caseInsensitiveCompare(manifest.sha256) == .orderedSame else {
            throw RuntimeError.checksumMismatch(
                expected: manifest.sha256,
                actual: checksum
            )
        }
        let response = try await dispatch(
            .init(
                operation: "loadExtension",
                extensionId: manifest.id,
                jarPath: jar.path,
                entryClass: manifest.entryClass
            )
        )
        try requireSuccess(response)
    }

    func installedManifests() throws -> [JVMExtensionManifest] {
        let applicationSupport = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let root = applicationSupport.appendingPathComponent(
            "JVMExtensions",
            isDirectory: true
        )
        guard fileManager.fileExists(atPath: root.path) else {
            return []
        }

        let packageDirectories = try fileManager.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        )
        var manifestsById: [String: JVMExtensionManifest] = [:]
        for packageDirectory in packageDirectories {
            let versions = try fileManager.contentsOfDirectory(
                at: packageDirectory,
                includingPropertiesForKeys: [.isDirectoryKey],
                options: [.skipsHiddenFiles]
            )
            for versionDirectory in versions {
                let metadata = versionDirectory.appendingPathComponent(
                    "manifest.json",
                    isDirectory: false
                )
                guard
                    let data = try? Data(contentsOf: metadata),
                    let manifest = try? JSONDecoder().decode(
                        JVMExtensionManifest.self,
                        from: data
                    )
                else {
                    continue
                }
                if
                    let current = manifestsById[manifest.id],
                    current.version.compare(
                        manifest.version,
                        options: .numeric
                    ) != .orderedAscending
                {
                    continue
                }
                manifestsById[manifest.id] = manifest
            }
        }
        return manifestsById.values.sorted { $0.name < $1.name }
    }

    func verifiedInstalledManifests() throws -> [JVMExtensionManifest] {
        try installedManifests().filter { manifest in
            guard
                let directory = try? extensionDirectory(for: manifest),
                let checksum = try? sha256(
                    of: directory.appendingPathComponent("extension.jar")
                )
            else {
                return false
            }
            return checksum.caseInsensitiveCompare(manifest.sha256) ==
                .orderedSame
        }
    }

    /// Which extension provides each source, keyed by source key.
    ///
    /// The host addresses everything by extension id, while the rest of the app addresses sources
    /// by `mihon.<sourceId>`. Only this function sees both at once -- it has the manifest and the
    /// descriptors it produced -- so the pairing is recorded here rather than guessed at later.
    private(set) var extensionIdsBySourceKey: [String: String] = [:]

    func extensionId(forSourceKey key: String) -> String? {
        extensionIdsBySourceKey[key]
    }

    func installedAidokuSources() async -> [ExtensionRunner.Source] {
        guard let manifests = try? installedManifests() else {
            return []
        }
        var result: [ExtensionRunner.Source] = []
        var extensionIds: [String: String] = [:]
        for manifest in manifests {
            do {
                try await loadInstalled(manifest)
                let descriptors = try await sources(
                    extensionId: manifest.id
                )
                for descriptor in descriptors {
                    extensionIds[TachiyomiXSourceRunner.key(for: descriptor.id)] = manifest.id
                }
                result.append(contentsOf: descriptors.map {
                    .tachiyomix(
                        manifest: manifest,
                        descriptor: $0
                    )
                })
            } catch {
                LogManager.logger.error(
                    "Failed to load JVM extension \(manifest.id): \(error)"
                )
            }
        }
        extensionIdsBySourceKey = extensionIds
        return result
    }

    func invoke(
        extensionId: String,
        method: String,
        argument: String? = nil
    ) async throws -> String? {
        let response = try await dispatch(
            .init(
                operation: "invoke",
                extensionId: extensionId,
                method: method,
                argument: argument
            )
        )
        try requireSuccess(response)
        return response.result
    }

    func popularManga(
        extensionId: String,
        sourceId: Int64? = nil,
        page: Int
    ) async throws -> TachiyomiXMangaPage {
        try await pagedManga(
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
    ) async throws -> TachiyomiXMangaPage {
        try await pagedManga(
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
        page: Int,
        filters: [ExtensionRunner.FilterValue] = []
    ) async throws -> TachiyomiXMangaPage {
        return try await pagedManga(
            operation: "searchManga",
            extensionId: extensionId,
            sourceId: sourceId,
            page: page,
            query: query,
            filterStates: Self.encode(filters: filters)
        )
    }

    func searchFilters(
        extensionId: String,
        sourceId: Int64
    ) async throws -> [TachiyomiXFilterDescriptor] {
        try await decodedResult(
            .init(
                operation: "getSearchFilters",
                extensionId: extensionId,
                sourceId: String(sourceId)
            ),
            as: [TachiyomiXFilterDescriptor].self
        )
    }

    func settings(
        extensionId: String,
        sourceId: Int64
    ) async throws -> [TachiyomiXSettingDescriptor] {
        try await decodedResult(
            .init(
                operation: "getSettings",
                extensionId: extensionId,
                sourceId: String(sourceId)
            ),
            as: [TachiyomiXSettingDescriptor].self
        )
    }

    func setSetting(
        extensionId: String,
        sourceId: Int64,
        key: String,
        type: String,
        value: String
    ) async throws {
        let response = try await dispatch(
            .init(
                operation: "setSetting",
                extensionId: extensionId,
                sourceId: String(sourceId),
                settingKey: key,
                settingType: type,
                settingValue: value
            )
        )
        try requireSuccess(response)
    }

    func cookieSummary(
        extensionId: String,
        sourceId: Int64
    ) async throws -> String {
        let response = try await dispatch(
            .init(
                operation: "getCookieSummary",
                extensionId: extensionId,
                sourceId: String(sourceId)
            )
        )
        try requireSuccess(response)
        return response.result ?? "No cookies stored."
    }

    func clearCookies(
        extensionId: String,
        sourceId: Int64
    ) async throws {
        let response = try await dispatch(
            .init(
                operation: "clearCookies",
                extensionId: extensionId,
                sourceId: String(sourceId)
            )
        )
        try requireSuccess(response)
        if
            let info = try? await webLoginInfo(
                extensionId: extensionId,
                sourceId: sourceId
            ),
            let url = URL(string: info.baseURL)
        {
            await CloudflareHandler.shared.clearWebSession(for: url)
        }
        cloudflareSessionUserAgents["\(extensionId):\(sourceId)"] = nil
    }

    func setWebLoginCookies(
        extensionId: String,
        sourceId: Int64,
        cookies: [String: String]
    ) async throws {
        let allowed = CharacterSet.alphanumerics.union(
            CharacterSet(charactersIn: "-._~")
        )
        let encoded = cookies.sorted { $0.key < $1.key }.map {
            let name = $0.key.addingPercentEncoding(
                withAllowedCharacters: allowed
            ) ?? ""
            let value = $0.value.addingPercentEncoding(
                withAllowedCharacters: allowed
            ) ?? ""
            return "\(name)\t\(value)"
        }
        .joined(separator: "\n")
        let response = try await dispatch(
            .init(
                operation: "setWebLoginCookies",
                extensionId: extensionId,
                sourceId: String(sourceId),
                argument: encoded
            )
        )
        try requireSuccess(response)
    }

    private func setWebLoginCookies(
        extensionId: String,
        sourceId: Int64,
        cookies: [HTTPCookie],
        userAgent: String,
        usingRawDispatch: Bool = false
    ) async throws {
        let allowed = CharacterSet.alphanumerics.union(
            CharacterSet(charactersIn: "-._~")
        )
        let encoded = cookies.sorted { $0.name < $1.name }.map { cookie in
            let name = cookie.name.addingPercentEncoding(
                withAllowedCharacters: allowed
            ) ?? ""
            let value = cookie.value.addingPercentEncoding(
                withAllowedCharacters: allowed
            ) ?? ""
            let domain = cookie.domain.addingPercentEncoding(
                withAllowedCharacters: allowed
            ) ?? ""
            let path = cookie.path.addingPercentEncoding(
                withAllowedCharacters: allowed
            ) ?? ""
            let expires = cookie.expiresDate.map {
                String(Int64($0.timeIntervalSince1970 * 1_000))
            } ?? ""
            return [
                name,
                value,
                domain,
                path,
                expires,
                String(cookie.isSecure),
                String(cookie.isHTTPOnly),
                String(!cookie.domain.hasPrefix("."))
            ].joined(separator: "\t")
        }
        .joined(separator: "\n")
        let request = ExtensionHostRequest(
            operation: "setWebLoginCookies",
            extensionId: extensionId,
            sourceId: String(sourceId),
            argument: encoded,
            userAgent: userAgent
        )
        let response: ExtensionHostResponse
        if usingRawDispatch {
            response = try await rawDispatch(request)
        } else {
            response = try await dispatch(request)
        }
        try requireSuccess(response)
    }

    func webLoginUserAgent(
        extensionId: String,
        sourceId: Int64
    ) async throws -> String {
        let info = try await webLoginInfo(
            extensionId: extensionId,
            sourceId: sourceId
        )
        if !info.userAgent.isEmpty {
            return info.userAgent
        }
        return await UserAgentProvider.shared.getExtensionNetworkUserAgent()
    }

    func mangaWebURL(
        extensionId: String,
        sourceId: Int64,
        manga: ExtensionRunner.Manga
    ) async throws -> URL {
        let initialized = await CoreDataManager.shared.sharedManga(
            sourceId: SourceIdentity.key(for: sourceId),
            mangaId: manga.key
        )?.initialized ?? false
        let response = try await dispatch(
            .init(
                operation: "getMangaUrl",
                extensionId: extensionId,
                sourceId: String(sourceId),
                mangaURL: manga.key,
                mangaTitle: manga.title,
                mangaThumbnailURL: manga.cover,
                mangaArtist: manga.artists?.joined(separator: ", "),
                mangaAuthor: manga.authors?.joined(separator: ", "),
                mangaStatus: String(manga.status.tachiyomiXValue),
                mangaDescription: manga.description,
                mangaGenre: manga.tags?.joined(separator: ", "),
                mangaUpdateStrategy: manga.updateStrategy == .never
                    ? "ONLY_FETCH_ONCE"
                    : "ALWAYS_UPDATE",
                mangaInitialized: String(initialized),
                mangaMemo: manga.memo
            )
        )
        try requireSuccess(response)
        guard
            let value = response.result,
            let url = URL(string: value),
            url.scheme == "http" || url.scheme == "https"
        else {
            throw RuntimeError.hostRejected(
                "The extension did not provide a valid manga website URL."
            )
        }
        return url
    }

    func chapterWebURL(
        extensionId: String,
        sourceId: Int64,
        chapter: ExtensionRunner.Chapter
    ) async throws -> URL {
        let response = try await dispatch(
            .init(
                operation: "getChapterUrl",
                extensionId: extensionId,
                sourceId: String(sourceId),
                chapterURL: chapter.key,
                chapterName: chapter.title ?? "",
                chapterNumber: chapter.chapterNumber.map { String($0) },
                chapterScanlator: chapter.scanlators?.joined(separator: ", "),
                chapterDateUpload: String(
                    chapter.dateUploaded.map {
                        Int64($0.timeIntervalSince1970 * 1_000)
                    } ?? 0
                ),
                chapterMemo: chapter.memo
            )
        )
        try requireSuccess(response)
        guard
            let value = response.result,
            let url = URL(string: value),
            url.scheme == "http" || url.scheme == "https"
        else {
            throw RuntimeError.hostRejected(
                "The extension did not provide a valid chapter website URL."
            )
        }
        return url
    }

    func webLoginCookies(
        extensionId: String,
        sourceId: Int64,
        url: URL
    ) async throws -> [HTTPCookie] {
        let response = try await dispatch(
            .init(
                operation: "getWebLoginCookies",
                extensionId: extensionId,
                sourceId: String(sourceId),
                mangaURL: url.absoluteString
            )
        )
        try requireSuccess(response)
        guard let encoded = response.result, !encoded.isEmpty else { return [] }
        return encoded.split(separator: "\n").compactMap { line in
            let fields = line.split(separator: "\t", omittingEmptySubsequences: false)
            guard fields.count == 8 else { return nil }
            func decode(_ index: Int) -> String {
                String(fields[index]).removingPercentEncoding ?? String(fields[index])
            }
            let rawDomain = decode(2)
            let hostOnly = String(fields[7]) == "true"
            let domain = hostOnly || rawDomain.hasPrefix(".")
                ? rawDomain
                : "." + rawDomain
            var properties: [HTTPCookiePropertyKey: Any] = [
                .name: decode(0),
                .value: decode(1),
                .domain: domain,
                .path: decode(3).isEmpty ? "/" : decode(3),
                .secure: String(fields[5]),
                HTTPCookiePropertyKey("HttpOnly"): String(fields[6])
            ]
            if
                let milliseconds = Double(String(fields[4])),
                milliseconds < 253_402_300_799_999
            {
                properties[.expires] = Date(
                    timeIntervalSince1970: milliseconds / 1_000
                )
            }
            return HTTPCookie(properties: properties)
        }
    }

    func setWebLoginSession(
        extensionId: String,
        sourceId: Int64,
        cookies: [HTTPCookie],
        userAgent: String
    ) async throws {
        try await setWebLoginCookies(
            extensionId: extensionId,
            sourceId: sourceId,
            cookies: cookies,
            userAgent: userAgent
        )
        cloudflareSessionUserAgents["\(extensionId):\(sourceId)"] = userAgent
    }

    private func pagedManga(
        operation: String,
        extensionId: String,
        sourceId: Int64?,
        page: Int,
        query: String? = nil,
        filterStates: String? = nil
    ) async throws -> TachiyomiXMangaPage {
        guard page > 0 else {
            throw RuntimeError.hostRejected(
                "Manga page must be at least 1."
            )
        }
        let response = try await dispatch(
            .init(
                operation: operation,
                extensionId: extensionId,
                sourceId: sourceId.map(String.init),
                argument: String(page),
                query: query,
                filterStates: filterStates
            )
        )
        try requireSuccess(response)
        guard let result = response.result else {
            throw RuntimeError.hostRejected(
                "The manga-page operation returned no payload."
            )
        }
        do {
            return try JSONDecoder().decode(
                TachiyomiXMangaPage.self,
                from: Data(result.utf8)
            )
        } catch {
            throw RuntimeError.hostRejected(
                "Unable to decode the manga page: \(error.localizedDescription)"
            )
        }
    }

    private nonisolated static func encode(
        filters: [ExtensionRunner.FilterValue]
    ) -> String? {
        let allowed = CharacterSet.alphanumerics.union(
            CharacterSet(charactersIn: "-._~")
        )
        let lines = filters.compactMap { filter -> String? in
            let id: String
            let kind: String
            let value: String
            let auxiliary: String?
            switch filter {
                case .text(let filterId, let text):
                    (id, kind, value, auxiliary) = (
                        filterId,
                        "text",
                        text,
                        nil
                    )
                case .check(let filterId, let state):
                    (id, kind, value, auxiliary) = (
                        filterId,
                        "check",
                        String(state),
                        nil
                    )
                case .select(let filterId, let selected):
                    (id, kind, value, auxiliary) = (
                        filterId,
                        "select",
                        selected,
                        nil
                    )
                case .sort(let selection):
                    (id, kind, value, auxiliary) = (
                        selection.id,
                        "sort",
                        String(selection.index),
                        String(selection.ascending)
                    )
                case .multiselect, .range:
                    return nil
            }
            let escaped = value.addingPercentEncoding(
                withAllowedCharacters: allowed
            ) ?? ""
            return [id, kind, escaped, auxiliary]
                .compactMap { $0 }
                .joined(separator: "\t")
        }
        return lines.isEmpty ? nil : lines.joined(separator: "\n")
    }

    func sources(
        extensionId: String
    ) async throws -> [TachiyomiXSourceDescriptor] {
        let response = try await dispatch(
            .init(
                operation: "listSources",
                extensionId: extensionId
            )
        )
        try requireSuccess(response)
        guard let result = response.result else {
            throw RuntimeError.hostRejected(
                "The source-list operation returned no payload."
            )
        }
        do {
            return try JSONDecoder().decode(
                [TachiyomiXSourceDescriptor].self,
                from: Data(result.utf8)
            )
        } catch {
            throw RuntimeError.hostRejected(
                "Unable to decode the source list: \(error.localizedDescription)"
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
        fetchDetails: Bool = true,
        fetchChapters: Bool = true
    ) async throws -> TachiyomiXMangaUpdate {
        let oldChapters: [TachiyomiXChapter]
        if let sourceId {
            // The shared query orders by source_order. Do not reuse a view's manga.chapters:
            // that array may currently be sorted by number or upload date for display.
            oldChapters = await CoreDataManager.shared.getChapters(
                sourceId: SourceIdentity.key(for: sourceId),
                mangaId: mangaURL
            ).map(\.tachiyomiXChapter)
        } else {
            oldChapters = []
        }
        let encodedChapters = String(
            decoding: try JSONEncoder().encode(oldChapters),
            as: UTF8.self
        )
        let response = try await dispatch(
            .init(
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
        try requireSuccess(response)
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
    ) async throws -> [TachiyomiXPage] {
        let response = try await dispatch(
            .init(
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
        try requireSuccess(response)
        return try decodeResult(response, as: [TachiyomiXPage].self)
    }

    private func decodeResult<Value: Decodable>(
        _ response: ExtensionHostResponse,
        as type: Value.Type
    ) throws -> Value {
        guard let result = response.result else {
            throw RuntimeError.hostRejected(
                "The extension operation returned no payload."
            )
        }
        do {
            return try JSONDecoder().decode(
                type,
                from: Data(result.utf8)
            )
        } catch {
            throw RuntimeError.hostRejected(
                "Unable to decode the extension payload: \(error.localizedDescription)"
            )
        }
    }

    private func decodedResult<Value: Decodable>(
        _ request: ExtensionHostRequest,
        as type: Value.Type
    ) async throws -> Value {
        let response = try await dispatch(request)
        try requireSuccess(response)
        return try decodeResult(response, as: type)
    }

    func unload(extensionId: String) async throws {
        let response = try await dispatch(
            .init(
                operation: "unloadExtension",
                extensionId: extensionId
            )
        )
        try requireSuccess(response)
    }

    func uninstall(extensionId: String) async throws {
        if runtime != nil {
            try await unload(extensionId: extensionId)
        }
        guard
            let manifest = try installedManifests().first(
                where: { $0.id == extensionId }
            )
        else {
            return
        }
        let packageDirectory = try extensionDirectory(for: manifest)
            .deletingLastPathComponent()
        if fileManager.fileExists(atPath: packageDirectory.path) {
            try fileManager.removeItem(at: packageDirectory)
        }
    }

    private func dispatch(
        _ request: ExtensionHostRequest
    ) async throws -> ExtensionHostResponse {
        let response = try await rawDispatch(request)
        guard
            shouldAttemptCloudflareBypass(response),
            let extensionId = request.extensionId,
            let sourceIdValue = request.sourceId,
            let sourceId = Int64(sourceIdValue)
        else {
            logFailure(response, request: request)
            return response
        }

        let key = "\(extensionId):\(sourceId)"
        let userAgent = try await solveCloudflareChallenge(
            extensionId: extensionId,
            sourceId: sourceId
        )
        let solvedGeneration = cloudflareSessionGenerations[key] ?? 0
        var retriedRequest = request
        retriedRequest.userAgent = userAgent
        var retriedResponse = try await rawDispatch(retriedRequest)

        // A clearance can come back already spent: bound to an agent the wire does not carry, or
        // simply stale. Reusing it fails exactly as if nothing had been solved.
        //
        // Whether that is worth another challenge depends on what else has happened. A library
        // refresh puts dozens of requests through the same source at once, and they fail as a
        // group; if each one treats its own failure as a reason to discard the session and solve
        // again, one challenge becomes one per manga, every one of them asking the user to sit
        // through a captcha. So a request that finds a newer clearance than the one it used just
        // takes it. Only the request that still holds the newest -- meaning nothing has been
        // solved since, and its failure is genuinely unexplained -- pays for a fresh challenge.
        if shouldAttemptCloudflareBypass(retriedResponse) {
            if
                cloudflareSessionGenerations[key] ?? 0 != solvedGeneration,
                let newer = cloudflareSessionUserAgents[key]
            {
                retriedRequest.userAgent = newer
                retriedResponse = try await rawDispatch(retriedRequest)
            } else {
                await discardCloudflareSession(
                    extensionId: extensionId,
                    sourceId: sourceId
                )
                if
                    let refreshed = try? await solveCloudflareChallenge(
                        extensionId: extensionId,
                        sourceId: sourceId
                    )
                {
                    retriedRequest.userAgent = refreshed
                    retriedResponse = try await rawDispatch(retriedRequest)
                }
            }
        }

        logFailure(retriedResponse, request: request)
        return retriedResponse
    }

    /// Drops a clearance that did not work, so the next solve starts from nothing.
    private func discardCloudflareSession(
        extensionId: String,
        sourceId: Int64
    ) async {
        let key = "\(extensionId):\(sourceId)"
        cloudflareSessionUserAgents[key] = nil
        cloudflareSessionGenerations[key] = (cloudflareSessionGenerations[key] ?? 0) + 1
        if
            let info = try? await webLoginInfo(
                extensionId: extensionId,
                sourceId: sourceId
            ),
            let url = URL(string: info.baseURL)
        {
            await CloudflareHandler.shared.clearWebSession(for: url)
        }
    }

    private func logFailure(
        _ response: ExtensionHostResponse,
        request: ExtensionHostRequest
    ) {
        guard !response.success else { return }
        let extensionId = request.extensionId ?? "host"
        let sourceId = request.sourceId ?? "default"
        let message = response.error ?? "Unknown Java error"
        LogManager.logger.error(
            "JVM extension failure [\(request.operation)] " +
                "extension=\(extensionId) source=\(sourceId): \(message)"
        )
    }

    private func rawDispatch(
        _ originalRequest: ExtensionHostRequest
    ) async throws -> ExtensionHostResponse {
        var request = originalRequest
        if
            request.userAgent == nil,
            request.extensionId != nil
        {
            let key = request.sourceId.map {
                "\(request.extensionId!):\($0)"
            }
            if
                let key,
                let sessionUserAgent = cloudflareSessionUserAgents[key]
            {
                request.userAgent = sessionUserAgent
            } else {
                request.userAgent = await UserAgentProvider.shared
                    .getExtensionNetworkUserAgent()
            }
        }
        let activeRuntime = try await runtimeInstance()
        // JNI attaches each caller to the process-wide VM. Run blocking Java
        // extension calls outside this actor so one slow HTTP request does not
        // serialize every library update, download, and browse request.
        return try await Task.detached(priority: .utility) {
            try activeRuntime.dispatch(
                request,
                as: ExtensionHostResponse.self
            )
        }.value
    }

    private func runtimeInstance() async throws -> JVMRuntime {
        if let runtime { return runtime }
        if let runtimeStartupTask {
            return try await runtimeStartupTask.value
        }

        let startupTask = Task { try await makeRuntime() }
        runtimeStartupTask = startupTask
        do {
            let runtime = try await startupTask.value
            self.runtime = runtime
            runtimeStartupTask = nil
            return runtime
        } catch {
            runtimeStartupTask = nil
            throw error
        }
    }

    private func shouldAttemptCloudflareBypass(
        _ response: ExtensionHostResponse
    ) -> Bool {
        guard !response.success else { return false }
        let message = response.error?.lowercased() ?? ""
        return message.contains("tachiyomiazcloudflarechallenge") ||
            message.contains("cloudflare bypass currently disabled")
    }

    private func solveCloudflareChallenge(
        extensionId: String,
        sourceId: Int64
    ) async throws -> String {
        let key = "\(extensionId):\(sourceId)"
        if let existing = cloudflareBypassTasks[key] {
            return try await existing.value
        }
        let task = Task {
            try await self.performCloudflareChallenge(
                extensionId: extensionId,
                sourceId: sourceId
            )
        }
        cloudflareBypassTasks[key] = task
        defer { cloudflareBypassTasks[key] = nil }
        let userAgent = try await task.value
        cloudflareSessionUserAgents[key] = userAgent
        cloudflareSessionGenerations[key] = (cloudflareSessionGenerations[key] ?? 0) + 1
        return userAgent
    }

    private func webLoginInfo(
        extensionId: String,
        sourceId: Int64
    ) async throws -> WebLoginInfo {
        let infoResponse = try await rawDispatch(
            .init(
                operation: "getWebLoginInfo",
                extensionId: extensionId,
                sourceId: String(sourceId)
            )
        )
        try requireSuccess(infoResponse)
        guard
            let payload = infoResponse.result?.data(using: .utf8),
            let info = try? JSONDecoder().decode(WebLoginInfo.self, from: payload),
            let url = URL(string: info.baseURL)
        else {
            throw RuntimeError.hostRejected(
                "The extension did not provide a valid Cloudflare login URL."
            )
        }
        return info
    }

    private func performCloudflareChallenge(
        extensionId: String,
        sourceId: Int64
    ) async throws -> String {
        let info = try await webLoginInfo(
            extensionId: extensionId,
            sourceId: sourceId
        )
        guard let url = URL(string: info.baseURL) else {
            throw RuntimeError.hostRejected(
                "The extension did not provide a valid Cloudflare login URL."
            )
        }

        // `info.userAgent` is the OkHttp client's effective UA: the Advanced
        // default unless this extension intentionally supplied its own.
        let userAgent: String
        if info.userAgent.isEmpty {
            userAgent = await UserAgentProvider.shared
                .getExtensionNetworkUserAgent()
        } else {
            userAgent = info.userAgent
        }
        var request = URLRequest(url: url)
        if !userAgent.isEmpty {
            request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        }
        let session = try await CloudflareHandler.shared.solve(request: request)
        try await setWebLoginCookies(
            extensionId: extensionId,
            sourceId: sourceId,
            cookies: session.cookies,
            userAgent: session.userAgent,
            usingRawDispatch: true
        )
        return session.userAgent
    }

    func imageRequest(
        extensionId: String,
        sourceId: Int64,
        imageURL: String,
        pageURL: String?
    ) async throws -> TachiyomiXImageRequest {
        try await decodedResult(
            .init(
                operation: "getImageRequest",
                extensionId: extensionId,
                sourceId: String(sourceId),
                imageURL: imageURL,
                pageURL: pageURL
            ),
            as: TachiyomiXImageRequest.self
        )
    }

    func materializeImage(
        extensionId: String,
        sourceId: Int64,
        imageURL: String,
        pageURL: String?
    ) async throws -> TachiyomiXMaterializedImage {
        guard let temporaryDirectory = fileManager.temporaryDirectory else {
            throw RuntimeError.hostRejected(
                "Unable to create a temporary directory for the extension image."
            )
        }
        let directory = temporaryDirectory.appendingPathComponent(
            "TachiyomiAZ-JVM-Images",
            isDirectory: true
        )
        if !preparedImageDirectory {
            try? fileManager.removeItem(at: directory)
            preparedImageDirectory = true
        }
        try fileManager.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        let destination = directory.appendingPathComponent(
            UUID().uuidString,
            isDirectory: false
        )
        do {
            let descriptor: TachiyomiXMaterializedImageDescriptor =
                try await decodedResult(
                    .init(
                        operation: "materializeImage",
                        extensionId: extensionId,
                        sourceId: String(sourceId),
                        imageURL: imageURL,
                        pageURL: pageURL,
                        destinationPath: destination.path
                    ),
                    as: TachiyomiXMaterializedImageDescriptor.self
                )
            let resourceKeys: Set<URLResourceKey> = [
                .fileSizeKey,
                .isRegularFileKey
            ]
            let values = try destination.resourceValues(forKeys: resourceKeys)
            guard
                values.isRegularFile == true,
                let size = values.fileSize,
                size > 0
            else {
                throw RuntimeError.hostRejected(
                    "The extension returned an empty image."
                )
            }
            return .init(
                fileURL: destination,
                contentType: descriptor.contentType
            )
        } catch {
            try? fileManager.removeItem(at: destination)
            throw error
        }
    }

    private func makeRuntime() async throws -> JVMRuntime {
        let configuration = try JVMRuntimeConfiguration.bundled(in: .main)
        // Construct the process-wide VM on the main actor. Runtime requests
        // remain actor-isolated and JNI attaches their worker threads later.
        return try await MainActor.run {
            try JVMRuntime(configuration: configuration)
        }
    }

    private func extensionDirectory(
        for manifest: JVMExtensionManifest
    ) throws -> URL {
        let applicationSupport = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        return applicationSupport
            .appendingPathComponent("JVMExtensions", isDirectory: true)
            .appendingPathComponent(manifest.directoryName, isDirectory: true)
            .appendingPathComponent(
                manifest.versionDirectoryName,
                isDirectory: true
            )
    }

    private func sha256(of url: URL) throws -> String {
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        return SHA256.hash(data: data)
            .map { String(format: "%02x", $0) }
            .joined()
    }

    private func removeSupersededVersions(
        of manifest: JVMExtensionManifest
    ) throws {
        let installed = try extensionDirectory(for: manifest)
        let packageDirectory = installed.deletingLastPathComponent()
        for directory in try fileManager.contentsOfDirectory(
            at: packageDirectory,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) where directory.lastPathComponent != installed.lastPathComponent {
            try fileManager.removeItem(at: directory)
        }
    }

    private func requireSuccess(
        _ response: ExtensionHostResponse
    ) throws {
        if !response.success {
            throw RuntimeError.hostRejected(
                Self.readableHostError(response.error)
            )
        }
    }

    /// A host error worded for the person reading it.
    ///
    /// `CloudflareInterceptor` throws `IOException("Cloudflare bypass currently disabled")` on
    /// every challenge it sees, because the bypass it means is FlareSolverr -- a separate headless
    /// browser service this app does not ship and never enables. The string is not a setting
    /// anyone can switch on, and it is not what failed: it is the signal this runtime watches for
    /// to start its own WebView bypass, which by the time anyone reads this has already run and
    /// been retried against a fresh challenge.
    ///
    /// Shown as-is it describes a disabled feature, which is neither true nor actionable. What
    /// happened is that the site would not accept the request even after the challenge was
    /// solved.
    static func readableHostError(_ message: String?) -> String {
        guard let message, !message.isEmpty else { return "Unknown Java error" }
        let lowered = message.lowercased()
        guard
            lowered.contains("cloudflare bypass currently disabled") ||
            lowered.contains("tachiyomiazcloudflarechallenge")
        else {
            return message
        }
        return NSLocalizedString(
            "CLOUDFLARE_CHALLENGE_UNSOLVED",
            comment: ""
        )
    }
}

private extension ExtensionRunner.Source {
    static func tachiyomix(
        manifest: JVMExtensionManifest,
        descriptor: TachiyomiXSourceDescriptor
    ) -> ExtensionRunner.Source {
        let language = descriptor.lang == "all"
            ? "multi"
            : descriptor.lang
        var listings = [
            ExtensionRunner.Listing(
                id: "popular",
                name: NSLocalizedString("POPULAR")
            )
        ]
        if descriptor.supportsLatest {
            listings.append(
                ExtensionRunner.Listing(
                    id: "latest",
                    name: NSLocalizedString("LATEST")
                )
            )
        }
        return .init(
            url: nil,
            key: TachiyomiXSourceRunner.key(for: descriptor.id),
            name: descriptor.name,
            version: Int(manifest.versionCode ?? "") ?? 1,
            languages: [language],
            urls: descriptor.baseURL.flatMap(URL.init(string:)).map {
                [$0]
            } ?? [],
            contentRating:
                manifest.isNsfw == true ? .primarilyNsfw : .safe,
            imageUrl: manifest.iconURL,
            config: .init(
                languageSelectType: .single,
                supportsTagSearch: true
            ),
            staticListings: listings,
            staticFilters: [],
            staticSettings: [],
            runner: TachiyomiXSourceRunner(
                extensionId: manifest.id,
                descriptor: descriptor
            )
        )
    }
}

actor TachiyomiXSourceRunner: ExtensionRunner.Runner {
    let features = ExtensionRunner.SourceFeatures(
        providesListings: true,
        dynamicFilters: true,
        dynamicSettings: true,
        providesImageRequests: true,
        providesBaseUrl: true,
        handlesNotifications: true,
        handlesWebLogin: true
    )

    nonisolated let extensionId: String
    private let descriptor: TachiyomiXSourceDescriptor
    private let sourceKey: String

    init(
        extensionId: String,
        descriptor: TachiyomiXSourceDescriptor
    ) {
        self.extensionId = extensionId
        self.descriptor = descriptor
        sourceKey = Self.key(for: descriptor.id)
    }

    nonisolated static func key(for sourceId: Int64) -> String {
        "mihon.\(sourceId)"
    }

    private func performSourceOperation<T>(
        _ operation: String,
        action: () async throws -> T
    ) async throws -> T {
        do {
            return try await action()
        } catch let error as ExtensionRunner.SourceError {
            throw error
        } catch {
            let message = error.localizedDescription
            LogManager.logger.error(
                "JVM source \(descriptor.id) failed during " +
                    "\(operation): \(message)"
            )
            throw ExtensionRunner.SourceError.message(message)
        }
    }

    func uninstall() async throws {
        try await JVMSourceRuntime.shared.uninstall(
            extensionId: extensionId
        )
    }

    func getSearchMangaList(
        query: String?,
        page: Int,
        filters: [ExtensionRunner.FilterValue]
    ) async throws -> ExtensionRunner.MangaPageResult {
        try await performSourceOperation("browse") {
            let result: TachiyomiXMangaPage
            if let query, !query.isEmpty {
                result = try await JVMSourceRuntime.shared.searchManga(
                    extensionId: extensionId,
                    sourceId: descriptor.id,
                    query: query,
                    page: page,
                    filters: filters
                )
            } else if !filters.isEmpty {
                result = try await JVMSourceRuntime.shared.searchManga(
                    extensionId: extensionId,
                    sourceId: descriptor.id,
                    query: "",
                    page: page,
                    filters: filters
                )
            } else {
                result = try await JVMSourceRuntime.shared.popularManga(
                    extensionId: extensionId,
                    sourceId: descriptor.id,
                    page: page
                )
            }
            return result.intoAidoku(sourceKey: sourceKey)
        }
    }

    func getSearchFilters() async throws -> [ExtensionRunner.Filter] {
        try await performSourceOperation("search filters") {
            try await JVMSourceRuntime.shared.searchFilters(
                extensionId: extensionId,
                sourceId: descriptor.id
            ).map(\.intoAidoku)
        }
    }

    func getSettings() async throws -> [ExtensionRunner.Setting] {
        let settings = try await JVMSourceRuntime.shared.settings(
            extensionId: extensionId,
            sourceId: descriptor.id
        )
        let items = settings.map {
            $0.intoAidoku(sourceKey: sourceKey)
        }
        let cookieSummary = try await JVMSourceRuntime.shared.cookieSummary(
            extensionId: extensionId,
            sourceId: descriptor.id
        )
        var groups: [ExtensionRunner.Setting] = []
        if !items.isEmpty {
            groups.append(.init(value: .group(.init(items: items))))
        }
        groups.append(
            .init(
                value: .group(
                    .init(
                        footer: cookieSummary,
                        items: [
                            .init(
                                key: "jvm-web-login",
                                title: "Web Login / Cloudflare",
                                value: .login(
                                    .init(
                                        method: .web,
                                        url: descriptor.homeURL ?? descriptor.baseURL
                                    )
                                )
                            ),
                            .init(
                                title: "Clear Cookies",
                                notification: "tachiyomix-clear-cookies",
                                refreshes: ["settings"],
                                value: .button(
                                    .init(
                                        destructive: true,
                                        confirmTitle: "Clear Cookies?",
                                        confirmText: "This clears login and challenge cookies used by JVM extensions."
                                    )
                                )
                            )
                        ]
                    )
                )
            )
        )
        return groups
    }

    func getBaseUrl() async throws -> URL? {
        (descriptor.homeURL ?? descriptor.baseURL).flatMap(URL.init(string:))
    }

    func handleWebLogin(
        key _: String,
        cookies: [String: String]
    ) async throws -> Bool {
        guard !cookies.isEmpty else {
            return false
        }
        try await JVMSourceRuntime.shared.setWebLoginCookies(
            extensionId: extensionId,
            sourceId: descriptor.id,
            cookies: cookies
        )
        return true
    }

    func webLoginUserAgent() async throws -> String {
        try await JVMSourceRuntime.shared.webLoginUserAgent(
            extensionId: extensionId,
            sourceId: descriptor.id
        )
    }

    func commitWebLogin(
        cookies: [HTTPCookie],
        userAgent: String
    ) async throws {
        try await JVMSourceRuntime.shared.setWebLoginSession(
            extensionId: extensionId,
            sourceId: descriptor.id,
            cookies: cookies,
            userAgent: userAgent
        )
    }

    func handleNotification(notification: String) async throws {
        if notification == "tachiyomix-clear-cookies" {
            try await JVMSourceRuntime.shared.clearCookies(
                extensionId: extensionId,
                sourceId: descriptor.id
            )
            return
        }
        let prefix = "tachiyomix-setting:"
        guard notification.hasPrefix(prefix) else {
            return
        }
        let key = String(notification.dropFirst(prefix.count))
        let settings = try await JVMSourceRuntime.shared.settings(
            extensionId: extensionId,
            sourceId: descriptor.id
        )
        guard let setting = settings.first(where: { $0.key == key }) else {
            return
        }
        let storedKey = "\(sourceKey).\(key)"
        let value: String = switch setting.type {
            case "toggle":
                String(UserDefaults.standard.bool(forKey: storedKey))
            case "multiselect":
                (UserDefaults.standard.stringArray(forKey: storedKey) ?? [])
                    .joined(separator: "\n")
            default:
                UserDefaults.standard.string(forKey: storedKey) ?? ""
        }
        try await JVMSourceRuntime.shared.setSetting(
            extensionId: extensionId,
            sourceId: descriptor.id,
            key: key,
            type: setting.type,
            value: value
        )
    }

    func getMangaList(
        listing: ExtensionRunner.Listing,
        page: Int
    ) async throws -> ExtensionRunner.MangaPageResult {
        try await performSourceOperation("listing \(listing.id)") {
            let result = if listing.id == "latest" {
                try await JVMSourceRuntime.shared.latestManga(
                    extensionId: extensionId,
                    sourceId: descriptor.id,
                    page: page
                )
            } else {
                try await JVMSourceRuntime.shared.popularManga(
                    extensionId: extensionId,
                    sourceId: descriptor.id,
                    page: page
                )
            }
            return result.intoAidoku(sourceKey: sourceKey)
        }
    }

    func getMangaUpdate(
        manga: ExtensionRunner.Manga,
        needsDetails: Bool,
        needsChapters: Bool
    ) async throws -> ExtensionRunner.Manga {
        try await performSourceOperation("manga update") {
            let initialized = await CoreDataManager.shared.sharedManga(
                sourceId: sourceKey,
                mangaId: manga.key
            )?.initialized ?? false
            let result = try await JVMSourceRuntime.shared.mangaUpdate(
                extensionId: extensionId,
                sourceId: descriptor.id,
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
                mangaInitialized: initialized,
                mangaMemo: manga.memo,
                fetchDetails: needsDetails,
                fetchChapters: needsChapters
            )
            var updated = manga
            if needsDetails {
                updated = manga.copy(
                    from: result.manga.intoAidoku(sourceKey: sourceKey)
                )
            }
            if needsChapters {
                updated.chapters = result.chapters.map(\.intoAidoku)
            }
            return updated
        }
    }

    func getPageList(
        manga: ExtensionRunner.Manga,
        chapter: ExtensionRunner.Chapter
    ) async throws -> [ExtensionRunner.Page] {
        try await performSourceOperation("page list") {
            let pages = try await JVMSourceRuntime.shared.pages(
                extensionId: extensionId,
                sourceId: descriptor.id,
                chapterURL: chapter.key,
                chapterName: chapter.title ?? "",
                chapterNumber: chapter.chapterNumber,
                chapterScanlator: chapter.scanlators?.joined(separator: ", "),
                chapterDateUpload: chapter.dateUploaded.map {
                    Int64($0.timeIntervalSince1970 * 1_000)
                } ?? 0,
                chapterMemo: chapter.memo
            )
            return try pages.map { try $0.intoAidoku }
        }
    }

    func mangaWebURL(for manga: ExtensionRunner.Manga) async throws -> URL {
        try await JVMSourceRuntime.shared.mangaWebURL(
            extensionId: extensionId,
            sourceId: descriptor.id,
            manga: manga
        )
    }

    func chapterWebURL(for chapter: ExtensionRunner.Chapter) async throws -> URL {
        try await JVMSourceRuntime.shared.chapterWebURL(
            extensionId: extensionId,
            sourceId: descriptor.id,
            chapter: chapter
        )
    }

    func webLoginCookies(for url: URL) async throws -> [HTTPCookie] {
        try await JVMSourceRuntime.shared.webLoginCookies(
            extensionId: extensionId,
            sourceId: descriptor.id,
            url: url
        )
    }

    func getImageRequest(
        url: String,
        context: ExtensionRunner.PageContext?
    ) async throws -> URLRequest {
        let pageURL = context?["mihonPageURL"]
        // Foundation URL round-tripping must not be allowed to discard local
        // fragment metadata used by extension image interceptors.
        let imageURL = context?["mihonImageURL"] ?? url
        // Return a stable virtual request before doing any extension or
        // network work. Nuke can now satisfy a cached cover/page directly;
        // URLProtocol materializes it through the extension only on a miss.
        return JVMImageURLProtocol.request(
            extensionId: extensionId,
            sourceId: descriptor.id,
            imageURL: imageURL,
            pageURL: pageURL
        )
    }
}

struct TachiyomiXFilterDescriptor: Decodable, Sendable {
    let id: String
    let type: String
    let name: String
    let options: [String]?
    let defaultValue: String?
    let auxiliary: String?
    let group: String?

    var intoAidoku: ExtensionRunner.Filter {
        let value = switch type {
            case "text":
                ExtensionRunner.Filter(
                    id: id,
                    title: name,
                    value: .text(placeholder: nil)
                )
            case "check":
                ExtensionRunner.Filter(
                    id: id,
                    title: group,
                    value: .check(
                        name: name,
                        canExclude: auxiliary == "true",
                        defaultValue: defaultState
                    )
                )
            case "select":
                ExtensionRunner.Filter(
                    id: id,
                    title: name,
                    value: .select(.init(
                        options: options ?? [],
                        ids: (options ?? []).indices.map(String.init),
                        defaultValue: defaultValue
                    ))
                )
            case "sort":
                ExtensionRunner.Filter(
                    id: id,
                    title: name,
                    value: .sort(
                        canAscend: true,
                        options: options ?? [],
                        defaultValue: defaultValue
                            .flatMap(Int.init)
                            .map {
                                .init(
                                    index: $0,
                                    ascending: auxiliary == "true"
                                )
                            }
                    )
                )
            default:
                ExtensionRunner.Filter(
                    id: id,
                    title: nil,
                    value: .note(name)
                )
        }
        return value
    }

    private var defaultState: Bool? {
        switch defaultValue {
            case "true", "1": true
            case "false", "0": false
            case .some: nil
            case .none: nil
        }
    }
}

struct TachiyomiXSettingDescriptor: Decodable, Sendable {
    let key: String
    let title: String?
    let summary: String?
    let type: String
    let enabled: Bool
    let currentValue: String?
    let options: [String]?
    let values: [String]?

    func intoAidoku(sourceKey: String) -> ExtensionRunner.Setting {
        seedCurrentValue(sourceKey: sourceKey)
        let notification = "tachiyomix-setting:\(key)"
        switch type {
            case "toggle":
                return .init(
                    key: key,
                    title: title ?? key,
                    notification: notification,
                    refreshes: ["content", "listings", "filters", "settings"],
                    value: .toggle(.init(subtitle: summary))
                )
            case "select":
                return .init(
                    key: key,
                    title: title ?? key,
                    notification: notification,
                    refreshes: ["content", "listings", "filters", "settings"],
                    value: .select(.init(
                        values: values ?? [],
                        titles: options ?? []
                    ))
                )
            case "multiselect":
                return .init(
                    key: key,
                    title: title ?? key,
                    notification: notification,
                    refreshes: ["content", "listings", "filters", "settings"],
                    value: .multiselect(.init(
                        values: values ?? [],
                        titles: options ?? []
                    ))
                )
            default:
                return .init(
                    key: key,
                    title: title ?? key,
                    notification: notification,
                    refreshes: ["content", "listings", "filters", "settings"],
                    value: .text(.init(
                        placeholder: summary,
                        autocorrectionDisabled: true,
                        defaultValue: currentValue
                    ))
                )
        }
    }

    private func seedCurrentValue(sourceKey: String) {
        let defaults = UserDefaults.standard
        let namespacedKey = "\(sourceKey).\(key)"
        guard defaults.object(forKey: namespacedKey) == nil else {
            return
        }
        switch type {
            case "toggle":
                defaults.set(currentValue == "true", forKey: namespacedKey)
            case "multiselect":
                defaults.set(
                    currentValue?.components(separatedBy: "\n") ?? [],
                    forKey: namespacedKey
                )
            default:
                defaults.set(currentValue ?? "", forKey: namespacedKey)
        }
    }
}

struct TachiyomiXImageRequest: Decodable, Sendable {
    let url: URL
    let headers: [String: String]
}

private extension TachiyomiXMangaPage {
    func intoAidoku(sourceKey: String) -> ExtensionRunner.MangaPageResult {
        .init(
            entries: mangas.map { $0.intoAidoku(sourceKey: sourceKey) },
            hasNextPage: hasNextPage
        )
    }
}

extension TachiyomiXManga {
    func intoAidoku(sourceKey: String) -> ExtensionRunner.Manga {
        let publishingStatus: ExtensionRunner.PublishingStatus = switch status {
            case 1: .ongoing
            case 2, 4: .completed
            case 5: .cancelled
            case 6: .hiatus
            default: .unknown
        }
        return .init(
            sourceKey: sourceKey,
            key: url,
            title: title,
            cover: thumbnailURL,
            artists: artist.map { [$0] },
            authors: author.map { [$0] },
            description: description,
            url: URL(string: url),
            tags: genre?
                .split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespaces) } ?? [],
            status: publishingStatus,
            updateStrategy: updateStrategy == "ONLY_FETCH_ONCE" ? .never : .always,
            memo: memo
        )
    }
}

private extension TachiyomiXChapter {
    var intoAidoku: ExtensionRunner.Chapter {
        .init(
            key: url,
            title: name.isEmpty ? nil : name,
            chapterNumber: chapterNumber.flatMap { $0 >= 0 ? $0 : nil },
            dateUploaded: dateUpload > 0
                ? Date(timeIntervalSince1970: Double(dateUpload) / 1_000)
                : nil,
            scanlators: scanlator.flatMap {
                $0.isEmpty ? nil : [$0]
            },
            url: URL(string: url),
            memo: memo
        )
    }
}

private extension ExtensionRunner.Chapter {
    var tachiyomiXChapter: TachiyomiXChapter {
        TachiyomiXChapter(
            url: key,
            name: title ?? "",
            chapterNumber: chapterNumber,
            scanlator: scanlators?.joined(separator: ", "),
            dateUpload: dateUploaded.map { Int64($0.timeIntervalSince1970 * 1_000) } ?? 0,
            memo: memo
        )
    }
}

private extension TachiyomiXPage {
    var intoAidoku: ExtensionRunner.Page {
        get throws {
            let candidates = [imageURL, uri, url.isEmpty ? nil : url]
            guard
                let value = candidates.compactMap({ $0 }).first,
                let resolvedURL = URL(string: value)
            else {
                throw SourceError.message("INVALID_PAGE_URL")
            }
            return .init(
                content: .url(
                    url: resolvedURL,
                    context: [
                        "mihonPageURL": url,
                        "mihonImageURL": value
                    ]
                )
            )
        }
    }
}

struct TachiyomiXMaterializedImageDescriptor: Decodable, Sendable {
    let contentType: String
}

struct TachiyomiXMaterializedImage: Sendable {
    let fileURL: URL
    let contentType: String
}

final class JVMImageURLProtocol: URLProtocol {
    private struct Descriptor: Sendable {
        let extensionId: String
        let sourceId: Int64
        let imageURL: String
        let pageURL: String?
    }

    private static let registryLock = NSLock()
    nonisolated(unsafe) private static var registry: [String: Descriptor] = [:]
    private var loadingTask: Task<Void, Never>?

    static func request(
        extensionId: String,
        sourceId: Int64,
        imageURL: String,
        pageURL: String?
    ) -> URLRequest {
        let identity = [
            extensionId,
            String(sourceId),
            imageURL,
            pageURL ?? ""
        ].joined(separator: "\u{0}")
        let token = SHA256.hash(data: Data(identity.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
        registryLock.lock()
        if registry.count >= 4_096, registry[token] == nil {
            if let discardedToken = registry.keys.first {
                registry.removeValue(forKey: discardedToken)
            }
        }
        registry[token] = .init(
            extensionId: extensionId,
            sourceId: sourceId,
            imageURL: imageURL,
            pageURL: pageURL
        )
        registryLock.unlock()
        return URLRequest(
            url: URL(string: "tachiyomiaz-jvm-image://\(token)")!
        )
    }

    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.scheme == "tachiyomiaz-jvm-image"
    }

    override class func canonicalRequest(
        for request: URLRequest
    ) -> URLRequest {
        request
    }

    override func startLoading() {
        guard
            let token = request.url?.host,
            let descriptor = Self.descriptor(for: token)
        else {
            client?.urlProtocol(
                self,
                didFailWithError: URLError(.resourceUnavailable)
            )
            return
        }
        loadingTask = Task { [weak self] in
            guard let self else { return }
            do {
                let image = try await JVMSourceRuntime.shared.materializeImage(
                    extensionId: descriptor.extensionId,
                    sourceId: descriptor.sourceId,
                    imageURL: descriptor.imageURL,
                    pageURL: descriptor.pageURL
                )
                defer {
                    try? FileManager.default.removeItem(at: image.fileURL)
                }
                try Task.checkCancellation()
                let data = try Data(
                    contentsOf: image.fileURL,
                    options: [.mappedIfSafe]
                )
                try Task.checkCancellation()
                let response = URLResponse(
                    url: request.url!,
                    mimeType: image.contentType,
                    expectedContentLength: data.count,
                    textEncodingName: nil
                )
                client?.urlProtocol(
                    self,
                    didReceive: response,
                    cacheStoragePolicy: .notAllowed
                )
                client?.urlProtocol(self, didLoad: data)
                client?.urlProtocolDidFinishLoading(self)
            } catch is CancellationError {
                return
            } catch {
                client?.urlProtocol(self, didFailWithError: error)
            }
        }
    }

    override func stopLoading() {
        loadingTask?.cancel()
        loadingTask = nil
    }

    private static func descriptor(for token: String) -> Descriptor? {
        registryLock.lock()
        defer { registryLock.unlock() }
        return registry[token]
    }
}
