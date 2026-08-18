import SwiftUI
import TachiyomiKit

/// Extensions: the repositories to download from, what is installed, and what is available.
///
/// A repository is only an address. Adding one makes extensions *available*; installing one is
/// what puts a source in Browse. The two are kept visibly separate here because conflating them
/// is the easiest way to make the app look like it did something it did not.
///
/// Fetching happens here; decoding does not. The bytes go to `ExtensionStoreDecoder` in
/// `:core-domain`, the same decoder `:app` uses, so both apps read a repository identically.
struct ExtensionsView: View {
    @EnvironmentObject private var repositories: RepositoryStore
    @EnvironmentObject private var catalog: ExtensionCatalog
    @EnvironmentObject private var jvm: JVMHost

    @State private var newRepoUrl = ""
    @State private var showingAdd = false

    var body: some View {
        List {
            installedSection
            availableSection
            repositoriesSection
            statusSection
        }
        .refreshable { await repositories.refreshAll() }
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
                .keyboardType(.URL)
            Button("Cancel", role: .cancel) { newRepoUrl = "" }
            Button("Add") {
                let url = newRepoUrl
                newRepoUrl = ""
                Task { await repositories.add(url) }
            }
        } message: {
            Text("Paste the direct URL to the repository's index file.")
        }
    }

    @ViewBuilder
    private var installedSection: some View {
        Section("Installed") {
            if catalog.installed.isEmpty {
                Text("Nothing installed yet.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(catalog.installed) { item in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(item.name)
                        Text("\(item.versionName) · \(item.packageName)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    .swipeActions {
                        Button("Uninstall", role: .destructive) { catalog.uninstall(item) }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var availableSection: some View {
        Section {
            NavigationLink {
                AvailableExtensionsView()
                    .navigationTitle("Available")
                    .navigationBarTitleDisplayMode(.inline)
            } label: {
                HStack {
                    Label("Available extensions", systemImage: "square.and.arrow.down")
                    Spacer()
                    if !repositories.available.isEmpty {
                        Text("\(repositories.available.count)")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .disabled(repositories.repositories.isEmpty)
        } footer: {
            if repositories.repositories.isEmpty {
                Text("Add a repository first.")
            }
        }
    }

    @ViewBuilder
    private var repositoriesSection: some View {
        Section {
            if repositories.repositories.isEmpty {
                Text("No repositories added.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(repositories.repositories) { repository in
                    repositoryRow(repository)
                }
                .onDelete { repositories.remove(at: $0) }
            }
        } header: {
            Text("Repositories")
        } footer: {
            Text("An index may be served under any file name and any extension, so the format is decided by reading the content, never the name. JAR extensions only — a legacy APK index will not load.")
        }
    }

    @ViewBuilder
    private func repositoryRow(_ repository: Repository) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(repository.name ?? repository.url)
                    .font(.body.weight(repository.name == nil ? .regular : .semibold))
                    .lineLimit(1)
                if let badge = repository.badgeLabel {
                    Text(badge)
                        .font(.caption2.weight(.bold))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Capsule().fill(Color.accentColor.opacity(0.18)))
                        .foregroundStyle(Color.accentColor)
                }
                Spacer()
                if repositories.refreshing.contains(repository.url) {
                    ProgressView().controlSize(.small)
                }
            }

            if repository.name != nil {
                Text(repository.url).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }

            if let count = repository.extensionCount {
                // "available", not "extensions": adding a repository installs nothing.
                Text("\(count) extensions available")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let error = repository.lastError {
                Text(error).font(.caption).foregroundStyle(.red)
            }
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder
    private var statusSection: some View {
        Section("Status") {
            LabeledContent("Index models", value: "shared")
            LabeledContent("Fetch + decode", value: "working")
            jvmStatusRow
        }
    }

    @ViewBuilder
    private var jvmStatusRow: some View {
        switch jvm.state {
        case .notStarted:
            Button("Start JVM runtime") {
                Task { await jvm.start() }
            }
        case .starting:
            HStack {
                Text("JVM runtime")
                Spacer()
                ProgressView().controlSize(.small)
            }
        case let .running(javaVersion, runtime):
            LabeledContent("JVM runtime", value: "Java \(javaVersion)")
            LabeledContent("Interpreter", value: runtime)
        case let .failed(reason):
            VStack(alignment: .leading, spacing: 4) {
                Text("JVM runtime failed").foregroundStyle(.primary)
                Text(reason)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .textSelection(.enabled)
                Button("Retry") { Task { await jvm.retry() } }
                    .font(.caption)
            }
        }
    }
}
