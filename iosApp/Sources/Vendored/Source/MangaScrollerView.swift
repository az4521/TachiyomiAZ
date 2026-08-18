//
//  MangaScrollerView.swift
//  Aidoku
//

import ExtensionRunner
import SwiftUI

/// A horizontal manga result strip used by search and migration.
struct MangaScrollerView: View {
    let source: ExtensionRunner.Source
    let entries: [ExtensionRunner.Manga]
    let pressAction: ((ExtensionRunner.Manga) -> Void)?

    static let coverHeight: CGFloat = 180

    @State private var bookmarkedItems: Set<String> = .init()
    @State private var loadedBookmarks = false
    @EnvironmentObject private var path: NavigationCoordinator

    init(
        source: ExtensionRunner.Source,
        entries: [ExtensionRunner.Manga],
        pressAction: ((ExtensionRunner.Manga) -> Void)? = nil
    ) {
        self.source = source
        self.entries = entries
        self.pressAction = pressAction
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: 16) {
                ForEach(entries.indices, id: \.self) { offset in
                    let manga = entries[offset]
                    let label = VStack(alignment: .leading) {
                        MangaCoverView(
                            source: source,
                            sourceKey: manga.sourceKey,
                            coverImage: manga.cover ?? "",
                            width: Self.coverHeight * 2/3,
                            height: Self.coverHeight,
                            downsampleWidth: 200,
                            bookmarked: bookmarkedItems.contains(manga.key)
                        )

                        Text(manga.title)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                            .padding(.horizontal, 4)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(width: Self.coverHeight * 2/3)

                    let open = {
                        if let pressAction {
                            pressAction(manga)
                        } else {
                            path.push(MangaViewController(source: source, manga: manga, parent: path.rootViewController))
                        }
                    }

                    if #unavailable(iOS 18.0), pressAction != nil {
                        label
                            .onTapGesture { open() }
                            .onLongPressGesture {
                                path.push(MangaViewController(source: source, manga: manga, parent: path.rootViewController))
                            }
                    } else {
                        let button = Button { open() } label: { label }
                            .foregroundStyle(.primary)
                            .buttonStyle(.borderless)

                        if pressAction != nil {
                            button.simultaneousGesture(
                                LongPressGesture().onEnded { _ in
                                    path.push(MangaViewController(source: source, manga: manga, parent: path.rootViewController))
                                }
                            )
                        } else {
                            button
                        }
                    }
                }
            }
            .padding(.horizontal)
            .scrollTargetLayoutPlease()
        }
        .scrollViewAlignedPlease()
        .task {
            if !loadedBookmarks { await loadBookmarked() }
        }
        .onChange(of: entries) { _ in
            loadedBookmarks = false
            Task { await loadBookmarked() }
        }
    }

    func loadBookmarked() async {
        guard !entries.isEmpty else { return }
        bookmarkedItems = await CoreDataManager.shared.container.performBackgroundTask { context in
            Set(entries.compactMap { manga in
                CoreDataManager.shared.hasLibraryManga(
                    sourceId: source.key,
                    mangaId: manga.key,
                    context: context
                ) ? manga.key : nil
            })
        }
        loadedBookmarks = true
    }
}

struct PlaceholderMangaScroller: View {
    var showTitle = true

    var body: some View {
        VStack(alignment: .leading) {
            if showTitle {
                Text("Loading")
                    .font(.title3)
                    .fontWeight(.semibold)
                    .padding(.horizontal)
            }
            Self.mainView
        }
        .redacted(reason: .placeholder)
        .shimmering()
    }

    static var mainView: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: 16) {
                ForEach(0..<20) { _ in
                    VStack(alignment: .leading) {
                        MangaGridItem.placeholder.frame(height: 180)
                        Text("Loading\n")
                            .padding(.horizontal, 4)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(width: 180 * 2/3)
                }
            }
            .padding(.horizontal)
            .scrollTargetLayoutPlease()
        }
        .scrollViewAlignedPlease()
    }
}
