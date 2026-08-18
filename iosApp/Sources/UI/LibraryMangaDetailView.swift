import SwiftUI
import TachiyomiKit

/// A library entry: its stored chapters, read state, and the ability to refresh from its source.
///
/// Chapter ordering and filtering come from `filterAndSortChapters` in `:core-domain` -- the same
/// rule the Android app applies -- rather than a second opinion written in Swift.
struct LibraryMangaDetailView: View {
    let entry: LibraryManga

    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var runtime: SourceRuntime
    @EnvironmentObject private var settings: AppSettings

    @State private var chapters: [Chapter] = []
    @State private var isRefreshing = false
    @State private var error: String?

    private var source: SourceDescriptor? {
        runtime.sources.first { $0.id == entry.source }
    }

    var body: some View {
        List {
            header

            Section {
                if chapters.isEmpty {
                    Text("No chapters stored. Refresh to fetch them.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(Array(chapters.enumerated()), id: \.offset) { _, chapter in
                        chapterRow(chapter)
                    }
                }
            } header: {
                HStack {
                    Text("\(chapters.count) chapters")
                    Spacer()
                    if entry.unread > 0 {
                        Text("\(entry.unread) unread").foregroundStyle(Color.accentColor)
                    }
                }
            } footer: {
                if let error {
                    Text(error).foregroundStyle(.red)
                }
            }
        }
        .navigationTitle(entry.title)
        .navigationBarTitleDisplayMode(.inline)
        .task { loadChapters() }
        .refreshable { await refresh() }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 14) {
            MangaCoverImage(url: entry.thumbnail_url, title: entry.title)
                .frame(width: 100, height: 150)
                .clipShape(RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 5) {
                Text(entry.title).font(.headline).lineLimit(3)
                if let author = entry.author, !author.isEmpty {
                    Text(author).font(.subheadline).foregroundStyle(.secondary)
                }
                Text(source?.name ?? "Source not installed")
                    .font(.caption)
                    .foregroundStyle(source == nil ? .orange : .secondary)
                if let genre = entry.genre, !genre.isEmpty {
                    Text(genre).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
                }
                Button(role: .destructive) {
                    Task { await library.remove(entry) }
                } label: {
                    Label("Remove", systemImage: "heart.slash").font(.caption)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .padding(.top, 2)
            }
        }
        .padding(.vertical, 4)
    }

    private func chapterRow(_ chapter: Chapter) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(chapter.name)
                    .lineLimit(2)
                    .foregroundStyle(chapter.read ? .secondary : .primary)
                if let scanlator = chapter.scanlator, !scanlator.isEmpty {
                    Text(scanlator).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            if chapter.read {
                Image(systemName: "checkmark").foregroundStyle(.secondary).font(.caption)
            } else if chapter.last_page_read > 0 {
                Text("p\(chapter.last_page_read + 1)").font(.caption2).foregroundStyle(.orange)
            }
        }
        .swipeActions {
            Button(chapter.read ? "Unread" : "Read") {
                toggleRead(chapter)
            }
        }
    }

    private func loadChapters() {
        guard let handler = library.handler else { return }
        let stored = handler.getChapters(manga: entry)
        // Shared rule: the same ordering and filtering the Android app shows.
        // The manga's own chapter_flags carry its filter and sort settings, so the shared rule
        // needs nothing else. Downloads are not built yet, hence isDownloaded always false.
        chapters = ChapterFilterKt.filterAndSortChapters(
            chapters: stored,
            manga: entry,
            forceDownloaded: false,
            isDownloaded: { _ in KotlinBoolean(bool: false) }
        )
    }

    private func toggleRead(_ chapter: Chapter) {
        guard let handler = library.handler else { return }
        chapter.read = !chapter.read
        if chapter.read { chapter.last_page_read = 0 }
        handler.updateChapterProgress(chapter: chapter)
        loadChapters()
        Task { await library.reload() }
    }

    private func refresh() async {
        guard source != nil else {
            error = "The source for this entry is not installed."
            return
        }
        isRefreshing = true
        defer { isRefreshing = false }
        await library.refresh(
            categoryId: entry.category,
            runtime: runtime,
            settings: settings
        )
        loadChapters()
    }
}
