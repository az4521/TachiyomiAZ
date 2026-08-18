import SwiftUI
import UIKit

/// Sources from installed extensions, grouped by language as on Android.
struct SourcesView: View {
    @EnvironmentObject private var runtime: SourceRuntime
    @EnvironmentObject private var catalog: ExtensionCatalog
    @EnvironmentObject private var jvm: JVMHost

    @State private var search = ""

    private var grouped: [(language: String, sources: [SourceDescriptor])] {
        let filtered = runtime.sources.filter {
            search.isEmpty || $0.name.localizedCaseInsensitiveContains(search)
        }
        let byLanguage = Dictionary(grouping: filtered, by: \.displayLanguage)
        return byLanguage
            .map { (language: $0.key, sources: $0.value.sorted { $0.name < $1.name }) }
            .sorted { $0.language < $1.language }
    }

    var body: some View {
        Group {
            if runtime.isLoading && runtime.sources.isEmpty {
                ProgressView("Loading sources\u{2026}")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if runtime.sources.isEmpty {
                emptyState
            } else {
                list
            }
        }
        .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .automatic))
        .task { await runtime.reload() }
        .refreshable { await runtime.reload() }
    }

    private var list: some View {
        List {
            if !runtime.loadErrors.isEmpty {
                Section("Problems") {
                    ForEach(runtime.loadErrors.sorted(by: { $0.key < $1.key }), id: \.key) { entry in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(entry.key == "*" ? "Compatibility layer" : entry.key)
                                .font(.caption.weight(.semibold))
                            Text(entry.value).font(.caption).foregroundStyle(.red)
                        }
                    }
                }
            }

            ForEach(grouped, id: \.language) { group in
                Section(group.language) {
                    ForEach(group.sources) { source in
                        NavigationLink(destination: SourceScreen(descriptor: source)) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(source.name)
                                Text(source.extensionName)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "books.vertical")
                .font(.system(size: 44))
                .foregroundStyle(.tertiary)
            Text("No sources")
                .font(.headline)
            Text(catalog.installed.isEmpty
                 ? "Install an extension under Extensions to get sources."
                 : "Installed extensions produced no sources. Pull to retry.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)

            // Errors have to show here too. Previously they only rendered inside the populated
            // list, so the one case where they matter -- nothing loaded at all -- showed nothing.
            if !runtime.loadErrors.isEmpty {
                ScrollView {
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(runtime.loadErrors.sorted(by: { $0.key < $1.key }), id: \.key) { entry in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(entry.key == "*" ? "Compatibility layer" : entry.key)
                                    .font(.caption.weight(.semibold))
                                Text(entry.value)
                                    .font(.caption2)
                                    .foregroundStyle(.red)
                                    .textSelection(.enabled)
                            }
                        }
                    }
                    .padding(.horizontal, 24)
                }
                .frame(maxHeight: 260)
            }
            if !jvm.isRunning {
                Text("The JVM is not running.")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Opens the vendored source screen for one source.
///
/// `NewSourceViewController` is the screen upstream uses for runner-backed sources -- listings,
/// search and the filter sheet -- so browsing goes there rather than to a second implementation.
struct SourceScreen: UIViewControllerRepresentable {
    let descriptor: SourceDescriptor

    func makeUIViewController(context: Context) -> UIViewController {
        guard let source = SourceManager.shared.source(for: SourceIdentity.key(for: descriptor.id)) else {
            return UIHostingController(
                rootView: UnavailableView(
                    descriptor.name,
                    systemImage: "exclamationmark.triangle",
                    description: Text("This source is not loaded.")
                )
            )
        }
        return NewSourceViewController(source: source)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
