import Foundation
import TachiyomiKit

/// A repository the user has added, plus whatever we last learned by fetching it.
struct Repository: Identifiable, Codable, Hashable {
    var url: String
    var name: String?
    var badgeLabel: String?
    var extensionCount: Int?
    var lastError: String?

    var id: String { url }
}

/// Extension repositories: stored, fetched, and decoded through the shared decoder.
///
/// The decode is `ExtensionStoreDecoder` from `:core-domain` — the same one `:app` uses, so both
/// apps agree on what a repository contains. Only the fetch is written here, because only the
/// fetch is platform-specific.
@MainActor
final class RepositoryStore: ObservableObject {
    private let key = "extensions.repositories"

    @Published private(set) var repositories: [Repository] = []
    @Published private(set) var refreshing: Set<String> = []

    private let decoder = ExtensionStoreDecoder.companion.default()

    init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let stored = try? JSONDecoder().decode([Repository].self, from: data) {
            repositories = stored
        }
    }

    func add(_ url: String) async {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !repositories.contains(where: { $0.url == trimmed }) else { return }
        repositories.append(Repository(url: trimmed))
        persist()
        await refresh(trimmed)
    }

    func remove(at offsets: IndexSet) {
        repositories.remove(atOffsets: offsets)
        persist()
    }

    func refreshAll() async {
        for repository in repositories {
            await refresh(repository.url)
        }
    }

    func refresh(_ url: String) async {
        guard let requestUrl = URL(string: url) else {
            update(url) { $0.lastError = "Not a valid URL." }
            return
        }

        refreshing.insert(url)
        defer { refreshing.remove(url) }

        do {
            let (data, response) = try await URLSession.shared.data(from: requestUrl)
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                update(url) { $0.lastError = "The server returned HTTP \(http.statusCode)." }
                return
            }

            // Straight to the shared decoder. It sniffs gzip and JSON-vs-protobuf itself, because
            // the file name says nothing about which one a repository chose.
            let store = decoder.decodeStore(bytes: data.kotlinByteArray)
            update(url) {
                $0.name = store.name.isEmpty ? nil : store.name
                $0.badgeLabel = store.badgeLabel.isEmpty ? nil : store.badgeLabel
                $0.extensionCount = store.extensionList?.extensions.count
                $0.lastError = store.extensionList == nil && store.extensionListUrl != nil
                    ? "This index points at a separate extension list, which is not fetched yet."
                    : nil
            }
        } catch {
            update(url) { $0.lastError = error.localizedDescription }
        }
    }

    private func update(_ url: String, _ mutate: (inout Repository) -> Void) {
        guard let index = repositories.firstIndex(where: { $0.url == url }) else { return }
        mutate(&repositories[index])
        persist()
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(repositories) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }
}

private extension Data {
    /// Kotlin/Native exposes `ByteArray` as `KotlinByteArray`, which has no bulk initialiser, so
    /// the bytes are copied in one pass rather than element by element through the bridge.
    var kotlinByteArray: KotlinByteArray {
        let array = KotlinByteArray(size: Int32(count))
        withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            guard let base = raw.bindMemory(to: Int8.self).baseAddress else { return }
            for index in 0..<count {
                array.set(index: Int32(index), value: base[index])
            }
        }
        return array
    }
}
