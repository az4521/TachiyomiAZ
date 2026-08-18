//
//  LocalSource.swift
//  Aidoku
//
//  Created by Skitty on 6/5/25.
//

import ExtensionRunner
import Foundation

extension ExtensionRunner.Source {
    static func local() -> ExtensionRunner.Source {
        .init(
            url: nil,
            key: LocalSourceRunner.sourceKey,
            name: NSLocalizedString("LOCAL_FILES"),
            version: 1,
            languages: ["multi"],
            urls: [],
            contentRating: .safe,
            config: .init(
                languageSelectType: .single
            ),
            staticListings: [],
            staticFilters: [],
            staticSettings: [],
            runner: LocalSourceRunner()
        )
    }
}

final class LocalSourceRunner: ExtensionRunner.Runner {
    static let sourceKey = "local"

    let features = ExtensionRunner.SourceFeatures(
        providesListings: true,
        dynamicFilters: false,
        dynamicSettings: false,
        dynamicListings: false,
        processesPages: false,
        providesImageRequests: false,
        providesPageDescriptions: false,
        providesAlternateCovers: false,
        providesBaseUrl: false,
        handlesNotifications: false,
        handlesDeepLinks: false,
        handlesBasicLogin: false,
        handlesWebLogin: false
    )

    func getSearchMangaList(query: String?, page: Int, filters: [ExtensionRunner.FilterValue]) async throws -> ExtensionRunner.MangaPageResult {
        await LocalFileManager.shared.scanLocalFiles()
        let manga = await LocalFileDataManager.shared.fetchLocalSeries(query: query)
        return .init(entries: manga, hasNextPage: false)
    }

    func getMangaUpdate(manga: ExtensionRunner.Manga, needsDetails: Bool, needsChapters: Bool) async throws -> ExtensionRunner.Manga {
        var manga = manga
        if needsDetails {}
        if needsChapters {
            manga.chapters = await LocalFileDataManager.shared.fetchChapters(mangaId: manga.key)
        }
        return manga
    }

    func getPageList(manga: ExtensionRunner.Manga, chapter: ExtensionRunner.Chapter) async throws -> [ExtensionRunner.Page] {
        await LocalFileManager.shared.fetchPages(mangaId: manga.key, chapterId: chapter.key)
    }

    func getMangaList(listing: ExtensionRunner.Listing, page: Int) async throws -> ExtensionRunner.MangaPageResult {
        let manga = await LocalFileDataManager.shared.fetchLocalSeries()
        return .init(entries: manga, hasNextPage: false)
    }
}
