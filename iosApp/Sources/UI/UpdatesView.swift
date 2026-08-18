import SwiftUI
import TachiyomiKit

/// Recent Updates: chapters added to library entries, newest first.
///
/// Built from the chapters table rather than a separate feed -- a chapter is "an update" because
/// it was added recently and belongs to something in the library, which is the same definition the
/// Android app uses.
struct UpdatesView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var runtime: SourceRuntime
    @EnvironmentObject private var settings: AppSettings

    struct Update: Identifiable, Hashable {
        let mangaTitle: String
        let thumbnailUrl: String?
        let chapterName: String
        let chapterId: Int64
        let read: Bool
        let dateFetch: Int64
        var id: Int64 { chapterId }
    }

    @State private var updates: [Update] = []
    @State private var isLoading = true

    var body: some View {
        Group {
            if isLoading && updates.isEmpty {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if updates.isEmpty {
                emptyState
            } else {
                list
            }
        }
        .task { await load() }
        .refreshable {
            await library.refresh(categoryId: nil, runtime: runtime, settings: settings)
            await load()
        }
    }

    private var list: some View {
        List {
            if let progress = library.refreshProgress {
                Section {
                    ProgressView(value: Double(progress.done), total: Double(progress.total)) {
                        Text("Updating \(progress.done + 1) of \(progress.total)").font(.caption)
                    }
                }
            }
            ForEach(grouped, id: \.key) { group in
                Section(group.key) {
                    ForEach(group.value) { update in
                        HStack(spacing: 10) {
                            MangaCoverImage(url: update.thumbnailUrl, title: update.mangaTitle)
                                .frame(width: 40, height: 60)
                                .clipShape(RoundedRectangle(cornerRadius: 4))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(update.mangaTitle).font(.subheadline).lineLimit(1)
                                Text(update.chapterName)
                                    .font(.caption)
                                    .foregroundStyle(update.read ? .secondary : .primary)
                                    .lineLimit(1)
                            }
                        }
                    }
                }
            }
        }
    }

    private var grouped: [(key: String, value: [Update])] {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        let byDay = Dictionary(grouping: updates) { update in
            formatter.string(from: Date(timeIntervalSince1970: Double(update.dateFetch) / 1000))
        }
        return byDay
            .map { (key: $0.key, value: $0.value.sorted { $0.dateFetch > $1.dateFetch }) }
            .sorted { ($0.value.first?.dateFetch ?? 0) > ($1.value.first?.dateFetch ?? 0) }
    }

    private func load() async {
        guard let handler = library.handler else { return }
        isLoading = true
        defer { isLoading = false }

        var collected: [Update] = []
        var seen = Set<Int64>()
        for entry in library.manga {
            guard let id = entry.id?.int64Value, seen.insert(id).inserted else { continue }
            for chapter in handler.getChapters(manga: entry) {
                guard let chapterId = chapter.id?.int64Value else { continue }
                collected.append(
                    Update(
                        mangaTitle: entry.title,
                        thumbnailUrl: entry.thumbnail_url,
                        chapterName: chapter.name,
                        chapterId: chapterId,
                        read: chapter.read,
                        dateFetch: chapter.date_fetch
                    )
                )
            }
        }
        updates = collected.sorted { $0.dateFetch > $1.dateFetch }.prefix(300).map { $0 }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "arrow.clockwise")
                .font(.system(size: 44))
                .foregroundStyle(.tertiary)
            Text("No updates").font(.headline)
            Text("Pull to refresh the library and check its sources for new chapters.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
