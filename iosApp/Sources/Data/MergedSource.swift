import ExtensionRunner
import Foundation
import TachiyomiKit

/// The Android app's merged source is an application source, not an extension. Keep the same
/// source id and URL wire format on iOS so a full backup restores a usable source rather than a
/// library row whose chapters can never be opened.
enum MergedSourceSupport {
    static let sourceId: Int64 = 6969
    static let sourceKey = SourceIdentity.key(for: sourceId)

    static let source: ExtensionRunner.Source = .init(
        key: sourceKey,
        name: "MergedSource",
        version: 1,
        languages: ["all"],
        contentRating: .safe,
        runner: MergedSourceRunner()
    )
}

private struct MergedMangaConfig: Decodable {
    let children: [Child]

    struct Child: Decodable {
        let source: Int64
        let url: String

        enum CodingKeys: String, CodingKey {
            case source = "s"
            case url = "u"
        }
    }

    enum CodingKeys: String, CodingKey {
        case children = "c"
    }
}

private struct MergedChapterConfig: Codable {
    let source: Int64
    let url: String
    let mangaUrl: String

    enum CodingKeys: String, CodingKey {
        case source = "s"
        case url = "u"
        case mangaUrl = "m"
    }
}

private final class MergedSourceRunner: Runner, @unchecked Sendable {
    let features = SourceFeatures()
    let partialMangaPublisher: SinglePublisher<ExtensionRunner.Manga>? = nil

    func getSearchMangaList(
        query: String?,
        page: Int,
        filters: [ExtensionRunner.FilterValue]
    ) async throws -> ExtensionRunner.MangaPageResult {
        throw SourceError.unimplemented
    }

    func getMangaUpdate(
        manga: ExtensionRunner.Manga,
        needsDetails: Bool,
        needsChapters: Bool
    ) async throws -> ExtensionRunner.Manga {
        let config = try mangaConfig(from: manga.key)
        var children: [ExtensionRunner.Manga] = []
        var chapters: [ExtensionRunner.Chapter] = []

        for child in config.children {
            guard
                let source = SourceManager.shared.source(for: SourceIdentity.key(for: child.source))
            else {
                throw SourceError.message("Merged source \(child.source) is not installed")
            }

            let childManga = SharedDataStore.shared.getManga(
                sourceId: SourceIdentity.key(for: child.source),
                mangaId: child.url
            ) ?? ExtensionRunner.Manga(
                sourceKey: SourceIdentity.key(for: child.source),
                key: child.url,
                title: manga.title
            )

            let updated = try await source.getMangaUpdate(
                manga: childManga,
                needsDetails: needsDetails,
                needsChapters: needsChapters
            )
            children.append(updated)

            if needsChapters {
                let childChapters: [ExtensionRunner.Chapter] = updated.chapters ?? []
                chapters.append(contentsOf: try childChapters.map { chapter in
                    var wrapped = chapter
                    wrapped.key = try encodeChapterConfig(
                        .init(source: child.source, url: chapter.key, mangaUrl: child.url)
                    )
                    return wrapped
                })
            }
        }

        var result = manga
        if needsDetails, let first = children.first {
            result.title = first.title
            result.cover = first.cover
            result.artists = first.artists
            result.authors = first.authors
            result.description = first.description
            result.tags = first.tags
            result.status = first.status
            result.contentRating = first.contentRating
            result.viewer = first.viewer
            result.updateStrategy = first.updateStrategy
            result.memo = first.memo
        }
        if needsChapters {
            result.chapters = chapters
        }
        return result
    }

    func getPageList(
        manga: ExtensionRunner.Manga,
        chapter: ExtensionRunner.Chapter
    ) async throws -> [ExtensionRunner.Page] {
        let config = try decodeChapterConfig(from: chapter.key)
        guard let source = SourceManager.shared.source(for: SourceIdentity.key(for: config.source)) else {
            throw SourceError.message("Merged source \(config.source) is not installed")
        }

        let childManga = SharedDataStore.shared.getManga(
            sourceId: SourceIdentity.key(for: config.source),
            mangaId: config.mangaUrl
        ) ?? ExtensionRunner.Manga(
            sourceKey: SourceIdentity.key(for: config.source),
            key: config.mangaUrl,
            title: manga.title
        )
        var childChapter = chapter
        childChapter.key = config.url
        return try await source.getPageList(manga: childManga, chapter: childChapter)
    }

    private func mangaConfig(from url: String) throws -> MergedMangaConfig {
        guard let data = url.data(using: .utf8) else {
            throw SourceError.message("Invalid merged source configuration")
        }
        do {
            return try JSONDecoder().decode(MergedMangaConfig.self, from: data)
        } catch {
            throw SourceError.message("Invalid merged source configuration: \(error.localizedDescription)")
        }
    }

    private func decodeChapterConfig(from url: String) throws -> MergedChapterConfig {
        guard let data = url.data(using: .utf8) else {
            throw SourceError.message("Invalid merged chapter configuration")
        }
        do {
            return try JSONDecoder().decode(MergedChapterConfig.self, from: data)
        } catch {
            throw SourceError.message("Invalid merged chapter configuration: \(error.localizedDescription)")
        }
    }

    private func encodeChapterConfig(_ config: MergedChapterConfig) throws -> String {
        let data = try JSONEncoder().encode(config)
        guard let value = String(data: data, encoding: .utf8) else {
            throw SourceError.message("Unable to encode merged chapter configuration")
        }
        return value
    }
}
