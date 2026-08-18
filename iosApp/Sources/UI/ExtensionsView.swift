import SwiftUI
import TachiyomiKit

/// Extension repositories.
///
/// Fetching happens here; decoding does not. The bytes go to `ExtensionStoreDecoder` in
/// `:core-domain`, the same decoder `:app` uses, so both apps read a repository identically.
struct ExtensionsView: View {
    @EnvironmentObject private var repositories: RepositoryStore
    @EnvironmentObject private var jvm: JVMHost
    @State private var newRepoUrl = ""
    @State private var showingAdd = false

    var body: some View {
        List {
            Section {
                if repositories.repositories.isEmpty {
                    Text("No repositories added.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(repositories.repositories) { repository in
                        row(repository)
                    }
                    .onDelete { repositories.remove(at: $0) }
                }
            } header: {
                Text("Repositories")
            } footer: {
                Text("An index may be served under any file name and any extension, so the format is decided by reading the content, never the name. JAR extensions only — a legacy APK index will not load.")
            }

            Section("Status") {
                LabeledContent("Index models", value: "shared")
                LabeledContent("Fetch + decode", value: "working")
                jvmStatusRow
            }
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

    @ViewBuilder
    private func row(_ repository: Repository) -> some View {
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
                Text("\(count) extensions").font(.caption).foregroundStyle(.secondary)
            }

            if let error = repository.lastError {
                Text(error).font(.caption).foregroundStyle(.red)
            }
        }
        .padding(.vertical, 2)
    }
}
