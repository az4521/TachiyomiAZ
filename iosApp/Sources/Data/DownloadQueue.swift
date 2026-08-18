import Foundation

/// A queued chapter download.
struct DownloadItem: Identifiable, Codable, Hashable {
    let id: String
    let mangaTitle: String
    let chapterName: String
    let chapterUrl: String
    let sourceId: Int64
    var progress: Double
}

/// The download queue.
///
/// Ordering, persistence and removal are real; the transfer is not. Page fetching needs the
/// source's `getPageList` plus an image pipeline and on-disk layout, so queued items sit at zero
/// progress and the screen says why rather than implying work is happening.
@MainActor
final class DownloadQueue: ObservableObject {
    private let key = "downloads.queue"

    @Published private(set) var items: [DownloadItem] = []
    @Published var isPaused = false

    init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let stored = try? JSONDecoder().decode([DownloadItem].self, from: data) {
            items = stored
        }
    }

    func enqueue(mangaTitle: String, chapterName: String, chapterUrl: String, sourceId: Int64) {
        let id = "\(sourceId)|\(chapterUrl)"
        guard !items.contains(where: { $0.id == id }) else { return }
        items.append(
            DownloadItem(
                id: id,
                mangaTitle: mangaTitle,
                chapterName: chapterName,
                chapterUrl: chapterUrl,
                sourceId: sourceId,
                progress: 0
            )
        )
        persist()
    }

    func remove(at offsets: IndexSet) {
        items.remove(atOffsets: offsets)
        persist()
    }

    func move(from source: IndexSet, to destination: Int) {
        items.move(fromOffsets: source, toOffset: destination)
        persist()
    }

    func clear() {
        items.removeAll()
        persist()
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(items) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }
}
