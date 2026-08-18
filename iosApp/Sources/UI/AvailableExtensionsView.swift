import SwiftUI

/// The catalogue of extensions available to download, from the added repositories.
///
/// Reached from Extensions, not Browse: adding a repository only says *where* extensions come
/// from, and installing one is what makes its sources browsable. Browse lists what is installed.
///
/// Language filtering defaults to "All languages" plus the system language, which is what a fresh
/// Tachiyomi install does -- a repository carries well over a thousand extensions across dozens of
/// languages, and showing all of them by default is unusable.
struct AvailableExtensionsView: View {
    @EnvironmentObject private var repositories: RepositoryStore
    @EnvironmentObject private var catalog: ExtensionCatalog
    @EnvironmentObject private var jvm: JVMHost
    @EnvironmentObject private var settings: AppSettings

    @State private var search = ""
    @State private var selectedLanguages: Set<String> = AvailableExtensionsView.defaultLanguages()
    @State private var showsUnsupported = false

    /// "all" plus the system language. Stored as codes so it survives a locale change sensibly.
    static func defaultLanguages() -> Set<String> {
        var languages: Set<String> = ["all"]
        if let code = Locale.current.languageCode {
            languages.insert(code)
        }
        return languages
    }

    private var allLanguages: [String] {
        Array(Set(repositories.available.flatMap { $0.languages })).sorted { left, right in
            if left == "all" { return true }
            if right == "all" { return false }
            return languageName(left) < languageName(right)
        }
    }

    private var visible: [AvailableExtension] {
        repositories.available.filter { item in
            guard showsUnsupported || item.isSupported else { return false }
            switch settings.contentFilter {
            case .hideAdult where item.isNsfw: return false
            case .hideAdultAndMixed where item.isNsfw || item.hasMixedContent: return false
            default: break
            }
            guard selectedLanguages.isEmpty || item.languages.contains(where: selectedLanguages.contains) else { return false }
            guard !search.isEmpty else { return true }
            return item.name.localizedCaseInsensitiveContains(search)
                || item.packageName.localizedCaseInsensitiveContains(search)
        }
    }

    private var hiddenUnsupported: Int {
        repositories.available.filter { !$0.isSupported }.count
    }

    var body: some View {
        Group {
            if repositories.available.isEmpty {
                if repositories.refreshing.isEmpty {
                    emptyState
                } else {
                    ProgressView("Loading extensions\u{2026}")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            } else {
                list
            }
        }
        // Entries live in memory, so a cold launch has repositories but nothing to show. Fetching
        // here rather than making the user find the pull-to-refresh on another screen.
        .task {
            if repositories.available.isEmpty && !repositories.repositories.isEmpty {
                await repositories.refreshAll()
            }
        }
        .refreshable { await repositories.refreshAll() }
        .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .automatic))
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) { filterMenu }
        }
    }

    private var list: some View {
        List {
            if !jvm.isRunning {
                Section {
                    Label(
                        "The JVM is not running, so extensions cannot be validated or installed.",
                        systemImage: "exclamationmark.triangle"
                    )
                    .font(.footnote)
                    .foregroundStyle(.orange)
                }
            }

            if let error = catalog.lastError {
                Section {
                    Text(error).font(.footnote).foregroundStyle(.red)
                }
            }

            Section {
                ForEach(visible) { item in
                    row(item)
                }
            } header: {
                Text("\(visible.count) of \(repositories.available.count)")
            } footer: {
                if hiddenUnsupported > 0 && !showsUnsupported {
                    Text("\(hiddenUnsupported) hidden: the host loads extension libraries 1.4 to 1.6 only.")
                }
            }
        }
    }

    private func row(_ item: AvailableExtension) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(item.name).font(.body)
                    if item.isNsfw || item.hasMixedContent {
                        Text(item.isNsfw ? "18+" : "mixed")
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 5).padding(.vertical, 1)
                            .background(Capsule().fill(
                                (item.isNsfw ? Color.red : Color.orange).opacity(0.15)
                            ))
                            .foregroundStyle(item.isNsfw ? Color.red : Color.orange)
                    }
                    if !item.isSupported {
                        Text("lib \(item.extensionLib)")
                            .font(.caption2)
                            .padding(.horizontal, 5).padding(.vertical, 1)
                            .background(Capsule().fill(Color.secondary.opacity(0.15)))
                            .foregroundStyle(.secondary)
                    }
                }
                Text("\(item.displayLanguages) · \(item.versionName)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()

            installControl(item)
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder
    private func installControl(_ item: AvailableExtension) -> some View {
        if catalog.installing.contains(item.packageName) {
            ProgressView().controlSize(.small)
        } else if let installedCode = catalog.installedVersion(of: item) {
            if installedCode < item.versionCode {
                Button("Update") { Task { await catalog.install(item) } }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
            } else {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                    .accessibilityLabel("Installed")
            }
        } else {
            Button("Install") { Task { await catalog.install(item) } }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .disabled(!jvm.isRunning || !item.isSupported)
        }
    }

    private var filterMenu: some View {
        Menu {
            Section("Languages") {
                Button {
                    selectedLanguages = Set(allLanguages)
                } label: {
                    Label("Select all", systemImage: "globe")
                }
                Button {
                    selectedLanguages = Self.defaultLanguages()
                } label: {
                    Label("Reset to default", systemImage: "arrow.uturn.backward")
                }
                ForEach(allLanguages, id: \.self) { code in
                    Button {
                        if selectedLanguages.contains(code) {
                            selectedLanguages.remove(code)
                        } else {
                            selectedLanguages.insert(code)
                        }
                    } label: {
                        Label(
                            languageName(code),
                            systemImage: selectedLanguages.contains(code) ? "checkmark" : ""
                        )
                    }
                }
            }
            Section("Content") {
                ForEach(AppSettings.ContentFilter.allCases) { option in
                    Button {
                        settings.contentFilter = option
                    } label: {
                        Label(
                            option.title,
                            systemImage: settings.contentFilter == option ? "checkmark" : ""
                        )
                    }
                }
            }
            Section {
                Toggle("Show unsupported", isOn: $showsUnsupported)
            }
        } label: {
            Image(systemName: "line.3.horizontal.decrease.circle")
        }
        .accessibilityLabel("Filter")
    }

    private func languageName(_ code: String) -> String {
        if code == "all" { return "All languages" }
        return Locale.current.localizedString(forLanguageCode: code) ?? code
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "puzzlepiece.extension")
                .font(.system(size: 44))
                .foregroundStyle(.tertiary)
            Text("Nothing to browse yet")
                .font(.headline)
            Text("Add a repository under Extensions, then pull to refresh it.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
