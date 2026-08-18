import SwiftUI

/// Sources you can browse, which means sources from extensions you have installed.
///
/// Empty until something is installed, as on Android. Adding a repository only tells the app
/// *where* extensions can be downloaded from; it does not make any of them available to browse.
/// The catalogue of downloadable extensions lives under Extensions.
struct BrowseView: View {
    @EnvironmentObject private var catalog: ExtensionCatalog
    @EnvironmentObject private var jvm: JVMHost

    var body: some View {
        Group {
            if catalog.installed.isEmpty {
                emptyState
            } else {
                list
            }
        }
    }

    private var list: some View {
        List {
            Section {
                ForEach(catalog.installed) { item in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(item.name)
                        Text("\(item.versionName) · \(item.packageName)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    .padding(.vertical, 2)
                }
            } header: {
                Text("Installed extensions")
            } footer: {
                // Honest about the gap: the extension is on disk and the host has validated it,
                // but nothing calls loadExtension/listSources yet, so these are not browsable.
                Text("Listing each extension's sources needs loadExtension and listSources wired to the host. Not done yet.")
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "safari")
                .font(.system(size: 44))
                .foregroundStyle(.tertiary)
            Text("No sources yet")
                .font(.headline)
            Text("Install an extension under Extensions to browse its sources.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
