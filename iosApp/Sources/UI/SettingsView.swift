import SwiftUI
import TachiyomiKit

/// Settings.
///
/// The library-update options here are the ones `selectLibraryMangaToUpdate` in `:core-domain`
/// actually consumes, plus the read-state filters applied alongside it — so what this screen
/// promises is what a refresh does, on both platforms.
struct SettingsView: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var jvm: JVMHost
    @EnvironmentObject private var catalog: ExtensionCatalog
    @EnvironmentObject private var runtime: SourceRuntime

    var body: some View {
        List {
            Section {
                Toggle("Bottom navigation bar", isOn: $settings.usesBottomTabs)
                Picker("Library columns", selection: $settings.libraryColumns) {
                    ForEach(2...5, id: \.self) { Text("\($0)").tag($0) }
                }
            } header: {
                Text("Appearance")
            } footer: {
                Text("Navigation is the drawer by default, as on Android. The tab bar is the iOS convention if you prefer it.")
            }

            Section {
                Toggle("Skip completed series", isOn: $settings.skipCompleted)
                Toggle("Skip fully read", isOn: $settings.skipFullyRead)
                Toggle("Skip entries with unread chapters", isOn: $settings.skipUnread)
                Toggle("Skip not started", isOn: $settings.skipNotStarted)
            } header: {
                Text("Library updates")
            } footer: {
                Text("Applied when refreshing the whole library. \"Skip completed\" is handled by the shared selection rule; the rest filter on read state.")
            }

            Section {
                if library.categories.isEmpty {
                    Text("No categories.").foregroundStyle(.secondary)
                } else {
                    ForEach(library.categories, id: \.name) { category in
                        categoryToggle(category)
                    }
                }
            } header: {
                Text("Categories to update")
            } footer: {
                Text(settings.updateCategories.isEmpty
                     ? "None selected, so every category updates."
                     : "Only the selected categories update.")
            }

            Section("Content") {
                Picker("Show", selection: $settings.contentFilter) {
                    ForEach(AppSettings.ContentFilter.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.inline)
            }

            Section("Runtime") {
                jvmRow
                LabeledRow("Installed extensions", value: "\(catalog.installed.count)")
                LabeledRow("Sources", value: "\(runtime.sources.count)")
                LabeledRow("Library entries", value: "\(library.manga.count)")
            }

            Section {
                Button("Add sample library entries") {
                    Task { await library.seedSampleData() }
                }
                Button("Clear library", role: .destructive) {
                    Task { await library.clearLibrary() }
                }
            } header: {
                Text("Development")
            }
        }
    }

    private func categoryToggle(_ category: MangaCategory) -> some View {
        let id = category.id?.int32Value
        let isOn = Binding<Bool>(
            get: { id.map { settings.updateCategories.contains($0) } ?? false },
            set: { newValue in
                guard let id else { return }
                if newValue {
                    if !settings.updateCategories.contains(id) {
                        settings.updateCategories.append(id)
                    }
                } else {
                    settings.updateCategories.removeAll { $0 == id }
                }
            }
        )
        return Toggle(category.name, isOn: isOn)
    }

    @ViewBuilder
    private var jvmRow: some View {
        switch jvm.state {
        case .notStarted:
            Button("Start JVM runtime") { Task { await jvm.start() } }
        case .starting:
            HStack {
                Text("JVM runtime")
                Spacer()
                ProgressView().controlSize(.small)
            }
        case let .running(javaVersion, runtimeName):
            LabeledRow("JVM runtime", value: "Java \(javaVersion)")
            LabeledRow("Interpreter", value: runtimeName)
        case let .failed(reason):
            VStack(alignment: .leading, spacing: 4) {
                Text("JVM runtime failed")
                Text(reason).font(.caption).foregroundStyle(.red).textSelection(.enabled)
                Button("Retry") { Task { await jvm.retry() } }.font(.caption)
            }
        }
    }
}
