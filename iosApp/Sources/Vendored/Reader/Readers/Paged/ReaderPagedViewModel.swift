//
//  ReaderPagedViewModel.swift
//  Aidoku (iOS)
//
//  Created by Skitty on 8/15/22.
//

import Foundation
import ExtensionRunner

@MainActor
class ReaderPagedViewModel {
    let source: ExtensionRunner.Source?
    let manga: ExtensionRunner.Manga
    var chapter: ExtensionRunner.Chapter?
    var pages: [Page] = []
    var pageLoadError: Error?

    var preloadedChapter: ExtensionRunner.Chapter?
    var preloadedPages: [Page] = []
    var preloadedPageLoadError: Error?

    init(source: ExtensionRunner.Source?, manga: ExtensionRunner.Manga) {
        self.source = source
        self.manga = manga
    }

    func loadPages(chapter: ExtensionRunner.Chapter) async {
        if preloadedChapter == chapter {
            pages = preloadedPages
            pageLoadError = preloadedPageLoadError
            preloadedPages = []
            preloadedPageLoadError = nil
            preloadedChapter = nil
        } else {
            if !pages.isEmpty {
                preloadedChapter = chapter
                preloadedPages = pages
                preloadedPageLoadError = nil
            }
            self.chapter = chapter
            let result = await getPages(chapter: chapter)
            pages = result.pages
            pageLoadError = result.error
        }
    }

    func preload(chapter: ExtensionRunner.Chapter) async {
        guard preloadedChapter != chapter else { return }
        preloadedChapter = nil
        let result = await getPages(chapter: chapter)
        preloadedPages = result.pages
        preloadedPageLoadError = result.error
        preloadedChapter = chapter
    }

    private func getPages(
        chapter: ExtensionRunner.Chapter
    ) async -> (pages: [Page], error: Error?) {
        let sourceId = source?.key ?? manga.sourceKey
        let identifier = ChapterIdentifier(
            sourceKey: sourceId,
            mangaKey: manga.key,
            chapterKey: chapter.key
        )
        let isDownloaded = DownloadManager.shared.isChapterDownloaded(chapter: identifier)
        if isDownloaded {
            let pages = await DownloadManager.shared
                .getDownloadedPages(for: identifier)
                .map {
                    $0.toOld(sourceId: sourceId, chapterId: chapter.key)
                }
            return (pages, nil)
        }
        guard let source else {
            return ([], nil)
        }
        do {
            let pages = try await source.getPageList(
                    manga: manga,
                    chapter: chapter
                )
                .map {
                    $0.toOld(sourceId: sourceId, chapterId: chapter.key)
                }
            return (pages, nil)
        } catch {
            LogManager.logger.error(
                "Reader page-list failure for \(chapter.key): \(error)"
            )
            return ([], error)
        }
    }
}
