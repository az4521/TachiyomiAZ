import SwiftUI
import TachiyomiKit

/// The library: category tabs across the top, a cover grid below, and search that looks at more
/// than the title.
///
/// Pull to refresh updates *this category*; the whole-library refresh lives in the toolbar, and
/// both go through `selectLibraryMangaToUpdate` so the user's exclusions mean the same thing here
/// as they do on Android.
struct LibraryView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var runtime: SourceRuntime

    @State private var selectedCategory: Int32?
    @State private var search = ""

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: gridMinimum, maximum: 200), spacing: 12)]
    }

    private var gridMinimum: CGFloat {
        switch settings.libraryColumns {
        case 2: return 150
        case 4: return 90
        case 5: return 74
        default: return 112
        }
    }

    /// Search covers title, author, artist, genre tags and the source name, which is what
    /// TachiyomiAZ's library search matches on. A bare title search misses most of how people
    /// actually look for something in a large library.
    private var visible: [LibraryManga] {
        let inCategory: [LibraryManga]
        if let selectedCategory {
            inCategory = library.manga.filter { $0.category == selectedCategory }
        } else {
            // "All" has to de-duplicate: the library table has one row per manga-category pairing.
            var seen = Set<Int64>()
            inCategory = library.manga.filter { entry in
                guard let id = entry.id?.int64Value else { return true }
                return seen.insert(id).inserted
            }
        }

        let needle = search.trimmingCharacters(in: .whitespaces)
        guard !needle.isEmpty else { return inCategory }

        return inCategory.filter { entry in
            let sourceName = runtime.sources.first { $0.id == entry.source }?.name ?? ""
            return [
                entry.title,
                entry.author ?? "",
                entry.artist ?? "",
                entry.genre ?? "",
                entry.description_ ?? "",
                sourceName
            ].contains { $0.localizedCaseInsensitiveContains(needle) }
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            if library.categories.count > 1 {
                categoryTabs
            }
            if let progress = library.refreshProgress {
                ProgressView(value: Double(progress.done), total: Double(progress.total)) {
                    Text("Updating \(progress.done + 1) of \(progress.total)").font(.caption)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
            }
            content
        }
        .searchable(
            text: $search,
            placement: .navigationBarDrawer(displayMode: .automatic),
            prompt: "Title, author, tag, source"
        )
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Button {
                        Task {
                            await library.refresh(categoryId: nil, runtime: runtime, settings: settings)
                        }
                    } label: {
                        Label("Update entire library", systemImage: "arrow.clockwise")
                    }
                    Picker("Columns", selection: $settings.libraryColumns) {
                        ForEach(2...5, id: \.self) { Text("\($0) columns").tag($0) }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        // Pull to refresh updates the category being looked at, not everything.
        .refreshable {
            await library.refresh(
                categoryId: selectedCategory,
                runtime: runtime,
                settings: settings
            )
        }
        .task { await library.load() }
    }

    private var categoryTabs: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                tab(title: "All", id: nil)
                ForEach(library.categories, id: \.name) { category in
                    tab(title: category.name, id: category.id?.int32Value)
                }
            }
            .padding(.horizontal, 8)
        }
        .background(.bar)
    }

    private func tab(title: String, id: Int32?) -> some View {
        Button {
            selectedCategory = id
        } label: {
            Text(title)
                .font(.subheadline.weight(selectedCategory == id ? .semibold : .regular))
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .foregroundStyle(selectedCategory == id ? Color.accentColor : Color.secondary)
                .overlay(alignment: .bottom) {
                    Rectangle()
                        .fill(selectedCategory == id ? Color.accentColor : .clear)
                        .frame(height: 2)
                }
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var content: some View {
        if library.isLoading && library.manga.isEmpty {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if visible.isEmpty {
            emptyState
        } else {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 14) {
                    ForEach(Array(visible.enumerated()), id: \.offset) { _, entry in
                        NavigationLink(destination: LibraryMangaDetailView(entry: entry)) {
                            LibraryMangaCell(entry: entry)
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button("Remove from library", role: .destructive) {
                                Task { await library.remove(entry) }
                            }
                        }
                    }
                }
                .padding(12)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "books.vertical")
                .font(.system(size: 48))
                .foregroundStyle(.tertiary)
            Text(search.isEmpty ? "Your library is empty" : "No matches")
                .font(.headline)
            Text(search.isEmpty
                 ? "Add something from Sources to see it here."
                 : "Nothing in this category matches \"\(search)\".")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct LibraryMangaCell: View {
    let entry: LibraryManga

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .topTrailing) {
                MangaCoverImage(url: entry.thumbnail_url, title: entry.title)
                    .aspectRatio(2.0 / 3.0, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                if entry.unread > 0 {
                    Text("\(entry.unread)")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(Color.accentColor))
                        .padding(6)
                }
            }
            Text(entry.title)
                .font(.caption)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
