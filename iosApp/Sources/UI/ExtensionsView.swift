import SwiftUI
import TachiyomiKit

/// Extension repositories.
///
/// The index models are shared (`NetworkExtensionStore` in `:core-domain`), so this screen and the
/// Android one agree on what a repository contains. What is not shared yet is the *fetch and
/// decode* pipeline: `ExtensionGithubApi` in `:app` still owns gzip handling, the legacy array
/// index, and the `repo.json` -> `index_v2` migration, all written against OkHttp streams. That is
/// the next extraction, and until it lands this screen can store repositories but not read them.
struct ExtensionsView: View {
    @EnvironmentObject private var repositories: RepositoryStore
    @State private var newRepoUrl = ""
    @State private var showingAdd = false

    var body: some View {
        List {
            Section {
                if repositories.urls.isEmpty {
                    Text("No repositories added.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(repositories.urls, id: \.self) { url in
                        VStack(alignment: .leading, spacing: 3) {
                            Text(url).font(.callout).lineLimit(2)
                            Text("Not fetched — decoder not ported yet")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .onDelete { repositories.remove(at: $0) }
                }
            } header: {
                Text("Repositories")
            } footer: {
                Text("A repository index may be served under any file name and any extension, so the URL is stored as given. The format is decided by reading the content, never by the file name.")
            }

            Section("Status") {
                LabeledContent("Index models", value: "shared")
                LabeledContent("Fetch + decode", value: "not ported")
                LabeledContent("JVM runtime", value: "not started")
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showingAdd = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Add repository")
            }
        }
        .alert("Add repository", isPresented: $showingAdd) {
            TextField("https://example.com/index.pb", text: $newRepoUrl)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Cancel", role: .cancel) { newRepoUrl = "" }
            Button("Add") {
                repositories.add(newRepoUrl)
                newRepoUrl = ""
            }
        } message: {
            Text("Paste the direct URL to the repository's index file.")
        }
    }
}

/// Repository URLs, persisted locally.
///
/// Deliberately dumb for now — it stores strings and nothing more. Validation belongs with the
/// decoder (a URL is only good if what it serves parses), so guessing here would just be a second
/// opinion that disagrees with the real one later.
@MainActor
final class RepositoryStore: ObservableObject {
    private let key = "extensions.repositoryUrls"

    @Published private(set) var urls: [String] = []

    init() {
        urls = UserDefaults.standard.stringArray(forKey: key) ?? []
    }

    func add(_ url: String) {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !urls.contains(trimmed) else { return }
        urls.append(trimmed)
        persist()
    }

    func remove(at offsets: IndexSet) {
        urls.remove(atOffsets: offsets)
        persist()
    }

    private func persist() {
        UserDefaults.standard.set(urls, forKey: key)
    }
}
