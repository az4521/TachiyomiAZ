//
//  SourceListingsContentView.swift
//  Aidoku
//
//  Created by Skitty on 4/28/25.
//

import ExtensionRunner
import SwiftUI

/// Displays the ordinary source listings exposed by Mihon extensions.
///
/// Mihon sources provide popular/browse and latest feeds. This view renders
/// those paginated feeds directly.
struct SourceListingsContentView: View {
    let source: ExtensionRunner.Source

    @Binding var listings: [ExtensionRunner.Listing]
    @Binding var headerListingSelection: Int

    @State private var entries: [ExtensionRunner.Manga] = []
    @State private var hasLoaded = false
    @State private var loading = true
    @State private var listingSelection = 0
    @State private var page = 1
    @State private var hasMore = false
    @State private var bookmarkedItems: Set<String> = .init()
    @State private var error: Error?
    @State private var loadTask: Task<(), Never>?
    @State private var loadListingTask: Task<(), Never>?

    enum ListingLoadState {
        case loading
        case notLoading
        case allLoaded
    }

    @State private var listingLoadState: ListingLoadState = .loading
    @StateObject private var path: NavigationCoordinator

    private var currentListing: ExtensionRunner.Listing? {
        listings[safe: listingSelection]
    }

    init(
        source: ExtensionRunner.Source,
        holdingViewController: UIViewController,
        listings: Binding<[ExtensionRunner.Listing]>,
        headerListingSelection: Binding<Int>
    ) {
        self.source = source
        self._listings = listings
        self._headerListingSelection = headerListingSelection
        self._path = StateObject(wrappedValue: NavigationCoordinator(rootViewController: holdingViewController))
    }

    var body: some View {
        ScrollViewReader { reader in
            ScrollView {
                VStack {}.id(0)

                if loading {
                    MangaGridView.placeholder
                        .transition(.opacity)
                } else if error != nil {
                    Spacer()
                        .frame(height: 200)
                        .frame(maxWidth: .infinity)
                } else if currentListing != nil {
                    MangaGridView(source: source, entries: entries, bookmarkedItems: $bookmarkedItems) {
                        if hasMore && listingLoadState != .loading {
                            await loadEntries()
                        }
                    }
                    .transition(.opacity)
                }
            }
            .overlay {
                if let error {
                    ErrorView(error: error) {
                        await reload()
                    }
                    .transition(.opacity)
                    .padding()
                }
            }
            .refreshable {
                let task = Task {
                    try? await Task.sleep(nanoseconds: 100_000_000)
                    await reload()
                }
                await task.value
            }
            .onChange(of: headerListingSelection) { value in
                loadListingTask?.cancel()
                loadListingTask = Task {
                    withAnimation { error = nil }
                    await animate(duration: 0.2) { reader.scrollTo(0) }
                    await animate(duration: 0.2, options: .easeOut) {
                        listingSelection = value
                        loading = true
                    }
                    await loadListing()
                }
            }
        }
        .onChange(of: listings) { value in
            if value.isEmpty {
                entries = []
                loading = false
            } else if listingSelection >= value.count {
                headerListingSelection = 0
            } else {
                Task { await reload() }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .init("refresh-content"))) { _ in
            loadTask?.cancel()
            loadTask = Task {
                guard !Task.isCancelled else { return }
                await reload()
            }
        }
        .task {
            guard !hasLoaded else { return }
            hasLoaded = true
            await reload(initial: true)
        }
        .environmentObject(path)
    }

    func reload(initial: Bool = false) async {
        loadListingTask?.cancel()
        withAnimation { error = nil }

        if !initial, let newListings = try? await source.getListings() {
            listings = newListings
            guard !Task.isCancelled else { return }
        }

        guard currentListing != nil else {
            loading = false
            entries = []
            return
        }
        await loadListing()
    }

    func loadListing() async {
        page = 1
        await loadEntries(initial: true)
    }

    func loadEntries(initial: Bool = false) async {
        do {
            guard let listing = currentListing else { return }
            listingLoadState = .loading

            let resultTask = Task {
                try await source.getMangaList(listing: listing, page: page)
            }

            hasMore = false
            if initial && !entries.isEmpty {
                await animate(duration: 0.2, options: .easeOut) { entries = [] }
            }
            if initial {
                await animate(duration: 0.2, options: .easeIn) { loading = true }
            }

            let result = try await resultTask.value
            guard !Task.isCancelled else { return }

            await SharedDataStore.shared.cacheMangaSummaries(result.entries)

            hasMore = result.hasNextPage
            listingLoadState = hasMore ? .notLoading : .allLoaded
            page += 1

            let bookmarkedKeys: [String] = await DatabaseContainer.shared.performBackgroundTask { context in
                result.entries.compactMap { manga in
                    SharedDataStore.shared.hasLibraryManga(
                        sourceId: self.source.key,
                        mangaId: manga.key,
                        context: context
                    ) ? manga.key : nil
                }
            }
            bookmarkedItems.formUnion(bookmarkedKeys)

            if loading {
                await animate(duration: 0.2, options: .easeIn) { loading = false }
            }
            guard !Task.isCancelled else { return }

            withAnimation(.easeIn(duration: 0.2)) {
                if initial {
                    entries = result.entries
                } else {
                    entries += result.entries
                }
            }
        } catch {
            guard !Task.isCancelled else { return }
            loading = false
            withAnimation { self.error = error }
        }
    }
}
