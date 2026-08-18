import SwiftUI

/// Recently Read: chapters in reading order, resumable, with the history wipeable.
///
/// Rows come from `getRecentMangaLimit` in `:core-database` -- the same joined query the Android
/// app's history screen uses -- so the two apps show the same history from the same database.
struct HistoryView: View {
    @EnvironmentObject private var history: HistoryStore
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var runtime: SourceRuntime

    @State private var search = ""
    @State private var confirmingClear = false

    private var visible: [HistoryEntry] {
        guard !search.isEmpty else { return history.entries }
        return history.entries.filter {
            $0.mangaTitle.localizedCaseInsensitiveContains(search)
                || $0.chapterName.localizedCaseInsensitiveContains(search)
        }
    }

    var body: some View {
        Group {
            if history.isLoading && history.entries.isEmpty {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if visible.isEmpty {
                emptyState
            } else {
                list
            }
        }
        .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .automatic))
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(role: .destructive) {
                    confirmingClear = true
                } label: {
                    Image(systemName: "trash")
                }
                .disabled(history.entries.isEmpty)
                .accessibilityLabel("Clear history")
            }
        }
        .alert("Clear reading history?", isPresented: $confirmingClear) {
            Button("Cancel", role: .cancel) {}
            Button("Clear", role: .destructive) {
                Task { await history.clearAll() }
            }
        } message: {
            Text("Chapters stay marked as read. Only the history list is wiped.")
        }
        .task { await history.load() }
        .refreshable { await history.load() }
    }

    private var list: some View {
        List {
            ForEach(grouped, id: \.key) { group in
                Section(group.key) {
                    ForEach(group.value) { entry in
                        row(entry)
                    }
                }
            }
        }
    }

    private func row(_ entry: HistoryEntry) -> some View {
        HStack(spacing: 10) {
            MangaCoverImage(url: entry.thumbnailUrl, title: entry.mangaTitle)
                .frame(width: 40, height: 60)
                .clipShape(RoundedRectangle(cornerRadius: 4))
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.mangaTitle).font(.subheadline).lineLimit(1)
                Text(entry.chapterName).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                Text(entry.lastReadDate, style: .time)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            Spacer()
            if resumeTarget(for: entry) != nil {
                Image(systemName: "play.circle").foregroundStyle(Color.accentColor)
            }
        }
        .swipeActions {
            Button("Remove", role: .destructive) {
                Task { await history.remove(entry) }
            }
        }
    }

    /// Resuming needs the source installed; without it the chapter cannot be fetched.
    private func resumeTarget(for entry: HistoryEntry) -> SourceDescriptor? {
        runtime.sources.first { $0.id == entry.sourceId }
    }

    private var grouped: [(key: String, value: [HistoryEntry])] {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        let byDay = Dictionary(grouping: visible) { formatter.string(from: $0.lastReadDate) }
        return byDay
            .map { (key: $0.key, value: $0.value.sorted { $0.lastRead > $1.lastRead }) }
            .sorted { ($0.value.first?.lastRead ?? 0) > ($1.value.first?.lastRead ?? 0) }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "clock")
                .font(.system(size: 44))
                .foregroundStyle(.tertiary)
            Text(search.isEmpty ? "Nothing read yet" : "No matches").font(.headline)
            Text(search.isEmpty
                 ? "Chapters you read appear here."
                 : "No history matches \"\(search)\".")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
