import SwiftUI
import TachiyomiKit

/// The library, shaped like the Android one: category tabs across the top, a cover grid below.
struct LibraryView: View {
    @EnvironmentObject private var library: LibraryStore
    @State private var selectedCategory: Int32?
    @State private var search = ""

    private let columns = [GridItem(.adaptive(minimum: 108, maximum: 160), spacing: 12)]

    var body: some View {
        VStack(spacing: 0) {
            if library.categories.count > 1 {
                categoryTabs
            }

            if library.isLoading {
                Spacer()
                ProgressView()
                Spacer()
            } else if visibleManga.isEmpty {
                emptyState
            } else {
                grid
            }
        }
        .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .automatic))
    }

    private var visibleManga: [LibraryManga] {
        let inCategory: [LibraryManga]
        if let selectedCategory {
            inCategory = library.manga.filter { $0.category == selectedCategory }
        } else {
            inCategory = library.manga
        }
        guard !search.isEmpty else { return inCategory }
        return inCategory.filter { ($0.title).localizedCaseInsensitiveContains(search) }
    }

    private var categoryTabs: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                ForEach(library.categories, id: \.name) { category in
                    let id = category.id?.int32Value
                    Button {
                        selectedCategory = id
                    } label: {
                        Text(category.name)
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
            }
            .padding(.horizontal, 8)
        }
        .background(.bar)
    }

    private var grid: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 14) {
                ForEach(visibleManga, id: \.url) { manga in
                    MangaCoverCell(manga: manga)
                }
            }
            .padding(12)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Spacer()
            Image(systemName: "books.vertical")
                .font(.system(size: 48))
                .foregroundStyle(.tertiary)
            Text("Your library is empty")
                .font(.headline)
            Text("Browsing sources is not wired up yet, so nothing can be added the normal way.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            Button("Add sample entries") {
                Task { await library.seedSampleData() }
            }
            .buttonStyle(.borderedProminent)
            .padding(.top, 4)
            Spacer()
        }
    }
}

/// A cover tile. Real cover art needs the source layer, so this draws a deterministic placeholder
/// from the title rather than shipping a grey box that looks like a loading failure.
struct MangaCoverCell: View {
    let manga: LibraryManga

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .topTrailing) {
                RoundedRectangle(cornerRadius: 8)
                    .fill(
                        LinearGradient(
                            colors: placeholderColors,
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .aspectRatio(2.0 / 3.0, contentMode: .fit)
                    .overlay {
                        Text(initials)
                            .font(.title.weight(.bold))
                            .foregroundStyle(.white.opacity(0.85))
                    }

                if manga.unread > 0 {
                    Text("\(manga.unread)")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(Capsule().fill(Color.accentColor))
                        .padding(6)
                }
            }

            Text(manga.title)
                .font(.caption)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var initials: String {
        let words = manga.title
            .replacingOccurrences(of: "Sample: ", with: "")
            .split(separator: " ")
        return words.prefix(2).compactMap { $0.first.map(String.init) }.joined().uppercased()
    }

    private var placeholderColors: [Color] {
        let hue = Double(abs(manga.title.hashValue) % 360) / 360.0
        return [
            Color(hue: hue, saturation: 0.5, brightness: 0.62),
            Color(hue: hue, saturation: 0.6, brightness: 0.40)
        ]
    }
}
