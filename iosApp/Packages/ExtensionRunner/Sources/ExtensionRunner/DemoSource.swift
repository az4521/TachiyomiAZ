import Foundation

public extension Source {
    /// Static source used by previews and existing demo-source preferences.
    static func demo() -> Source {
        .init(
            key: "demo",
            name: "Demo Source",
            version: 1,
            languages: ["en"],
            contentRating: .safe,
            staticListings: [
                .init(id: "popular", name: "Popular")
            ],
            runner: DemoSourceRunner()
        )
    }
}

private final class DemoSourceRunner: Runner, Sendable {
    let features = SourceFeatures(providesListings: true)
    let partialMangaPublisher: SinglePublisher<Manga>? = nil

    func getSearchMangaList(
        query: String?,
        page: Int,
        filters: [FilterValue]
    ) async throws -> MangaPageResult {
        let title = query?.isEmpty == false ? query! : "Demo Manga"
        return MangaPageResult(
            entries: [Manga(sourceKey: "demo", key: "demo", title: title)],
            hasNextPage: false
        )
    }

    func getMangaUpdate(
        manga: Manga,
        needsDetails: Bool,
        needsChapters: Bool
    ) async throws -> Manga {
        manga
    }

    func getPageList(
        manga: Manga,
        chapter: Chapter
    ) async throws -> [Page] {
        []
    }

    func getMangaList(
        listing: Listing,
        page: Int
    ) async throws -> MangaPageResult {
        MangaPageResult(
            entries: [
                Manga(
                    sourceKey: "demo",
                    key: "demo",
                    title: "Demo Manga"
                )
            ],
            hasNextPage: false
        )
    }
}
