import SwiftUI

/// Download Queue.
///
/// The queue itself is real -- entries are added, ordered, paused and cancelled here -- but nothing
/// downloads yet: page fetching needs the source's `getPageList` plus an image pipeline and a
/// place on disk, none of which exists. The screen says so rather than showing a queue that never
/// moves and looks broken.
struct DownloadQueueView: View {
    @EnvironmentObject private var downloads: DownloadQueue

    var body: some View {
        List {
            Section {
                if downloads.items.isEmpty {
                    Text("The queue is empty.").foregroundStyle(.secondary)
                } else {
                    ForEach(downloads.items) { item in
                        VStack(alignment: .leading, spacing: 3) {
                            Text(item.mangaTitle).font(.subheadline).lineLimit(1)
                            Text(item.chapterName).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            ProgressView(value: item.progress)
                        }
                    }
                    .onDelete { downloads.remove(at: $0) }
                    .onMove { downloads.move(from: $0, to: $1) }
                }
            } header: {
                Text("Queue")
            } footer: {
                Text("Downloading is not implemented yet. Queued chapters persist, but nothing is fetched — see IOS_PORT.md.")
            }

            if !downloads.items.isEmpty {
                Section {
                    Button(downloads.isPaused ? "Resume" : "Pause") {
                        downloads.isPaused.toggle()
                    }
                    Button("Clear queue", role: .destructive) {
                        downloads.clear()
                    }
                }
            }
        }
        .toolbar { EditButton() }
    }
}
