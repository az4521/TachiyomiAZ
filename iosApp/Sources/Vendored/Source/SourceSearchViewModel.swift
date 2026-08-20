//
//  SourceSearchViewModel.swift
//  Aidoku
//
//  Created by Skitty on 11/19/25.
//

import ExtensionRunner
import SwiftUI

@MainActor
class SourceSearchViewModel: ObservableObject {
    private let source: ExtensionRunner.Source

    @Published var entries: [ExtensionRunner.Manga] = []
    @Published var error: Error?
    @Published var loadingInitial = true
    @Published var shouldScrollToTop = false
    @Published var bookmarkedItems: Set<String> = .init()

    private(set) var hasAppeared = false
    private(set) var hasMore = true
    private(set) var nextPage = 1

    private var currentSearch: String = "_"
    private var searchTask: Task<(), Never>?
    private var loadMoreTask: Task<(), Never>?

    init(source: ExtensionRunner.Source) {
        self.source = source
    }

    func onAppear(searchText: String, filters: [FilterValue]) {
        guard !hasAppeared, entries.isEmpty else { return }
        hasAppeared = true
        loadManga(searchText: searchText, filters: filters)
    }

    func waitForSearch() async {
        await searchTask?.value
    }

    func loadManga(
        searchText: String,
        filters: [FilterValue],
        delay: Bool = false,
        force: Bool = false
    ) {
        guard force || currentSearch != searchText else { return }
        error = nil
        nextPage = 1
        currentSearch = searchText
        searchTask?.cancel()
        loadMoreTask?.cancel()
        searchTask = Task {
            if delay {
                // delay for one second
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
            guard !Task.isCancelled else { return }
            do {
                let result = try await source.getSearchMangaList(
                    query: searchText,
                    page: 1,
                    filters: filters
                )
                guard !Task.isCancelled else { return }
                await SharedDataStore.shared.cacheMangaSummaries(result.entries)
                await loadBookmarks(entries: result.entries)
                hasMore = result.hasNextPage
                entries = result.entries
                nextPage = 2
                shouldScrollToTop = true
            } catch {
                self.error = error
            }
            loadingInitial = false
        }
    }

    func loadMore(searchText: String, filters: [FilterValue]) async {
        await loadMoreTask?.value
        guard hasMore else { return }
        loadMoreTask = Task {
            await searchTask?.value
            guard !Task.isCancelled else { return }
            do {
                let result = try await source.getSearchMangaList(
                    query: searchText,
                    page: nextPage,
                    filters: filters
                )
                guard !Task.isCancelled else { return }
                await SharedDataStore.shared.cacheMangaSummaries(result.entries)
                await loadBookmarks(entries: result.entries)
                hasMore = result.hasNextPage

                // ensure no duplicate entries
                var hashValues = Set(entries.map { $0.hashValue })
                let newEntries = result.entries.filter { hashValues.insert($0.hashValue).inserted }
                entries += newEntries

                nextPage += 1
                if result.entries.isEmpty && hasMore {
                    await loadMore(searchText: searchText, filters: filters)
                }
            } catch {
                self.error = error
            }
        }
    }

    func loadBookmarks(entries: [ExtensionRunner.Manga]) async {
        let bookmarkedKeys: [String] = await DatabaseContainer.shared.performBackgroundTask { context in
            var keys: [String] = []
            for manga in entries where SharedDataStore.shared.hasLibraryManga(
                sourceId: self.source.key,
                mangaId: manga.key,
                context: context
            ) {
                keys.append(manga.key)
            }
            return keys
        }
        bookmarkedItems.formUnion(bookmarkedKeys)
    }
}
