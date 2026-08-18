import SwiftUI
import TachiJVMRunner
import TachiyomiKit

/// One manga: details from the source, its chapters, and whether it is in the library.
///
/// Chapter ordering and filtering come from `filterAndSortChapters` in `:core-domain` -- the same
/// rule the Android app uses. Reimplementing it here would let the two apps disagree about which
/// chapters exist and in what order, which is exactly what the shared modules exist to prevent.
struct MangaDetailView: View {
    let source: SourceDescriptor
    let manga: TachiyomiXManga

    @EnvironmentObject private var runtime: SourceRuntime
    @EnvironmentObject private var library: LibraryStore

    @State private var details: TachiyomiXManga?
    @State private var chapters: [TachiyomiXChapter] = []
    @State private var isLoading = true
    @State private var error: String?
    @State private var inLibrary = false

    private var shown: TachiyomiXManga { details ?? manga }

    var body: some View {
        List {
            header

            if let description = shown.description, !description.isEmpty {
                Section("Description") {
                    Text(description).font(.callout)
                }
            }

            if let genre = shown.genre, !genre.isEmpty {
                Section("Tags") {
                    Text(genre).font(.callout).foregroundStyle(.secondary)
                }
            }

            Section {
                if isLoading && chapters.isEmpty {
                    HStack {
                        ProgressView().controlSize(.small)
                        Text("Loading chapters\u{2026}").foregroundStyle(.secondary)
                    }
                } else if let error {
                    Text(error).font(.footnote).foregroundStyle(.red)
                } else if chapters.isEmpty {
                    Text("No chapters.").foregroundStyle(.secondary)
                } else {
                    ForEach(Array(chapters.enumerated()), id: \.offset) { _, chapter in
                        NavigationLink(destination: ReaderView(source: source, manga: shown, chapter: chapter)) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(chapter.name).lineLimit(2)
                                if let scanlator = chapter.scanlator, !scanlator.isEmpty {
                                    Text(scanlator).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            } header: {
                Text("\(chapters.count) chapters")
            }
        }
        .navigationTitle(shown.title)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .refreshable { await load() }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 14) {
            MangaCoverImage(url: shown.thumbnailURL, title: shown.title)
                .frame(width: 100, height: 150)
                .clipShape(RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 5) {
                Text(shown.title).font(.headline).lineLimit(3)
                if let author = shown.author, !author.isEmpty {
                    Text(author).font(.subheadline).foregroundStyle(.secondary)
                }
                if let artist = shown.artist, !artist.isEmpty, artist != shown.author {
                    Text(artist).font(.caption).foregroundStyle(.secondary)
                }
                Text(source.name).font(.caption).foregroundStyle(.secondary)

                Button {
                    Task { await toggleLibrary() }
                } label: {
                    Label(
                        inLibrary ? "In library" : "Add to library",
                        systemImage: inLibrary ? "heart.fill" : "heart"
                    )
                    .font(.caption)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .padding(.top, 2)
            }
        }
        .padding(.vertical, 4)
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        inLibrary = library.contains(url: manga.url, sourceId: source.id)
        do {
            let update = try await runtime.mangaDetails(
                source,
                url: manga.url,
                title: manga.title,
                memo: manga.memo
            )
            details = update.manga
            chapters = update.chapters
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func toggleLibrary() async {
        if inLibrary {
            await library.remove(url: manga.url, sourceId: source.id)
        } else {
            await library.add(manga: shown, source: source)
        }
        inLibrary = library.contains(url: manga.url, sourceId: source.id)
    }
}
