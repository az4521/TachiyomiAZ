import SwiftUI
import TachiyomiKit

/// Honest placeholder. Says which shared piece already exists and what is actually missing, so the
/// screen is a status report rather than a dead end.
struct NotYetPortedView: View {
    let area: String
    let detail: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "hammer")
                .font(.system(size: 40))
                .foregroundStyle(.tertiary)
            Text("\(area) is not ported yet")
                .font(.headline)
            Text(detail)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 36)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct MoreView: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        List {
            Section {
                Toggle("Bottom navigation bar", isOn: $settings.usesBottomTabs)
            } header: {
                Text("Appearance")
            } footer: {
                Text("Off by default: navigation is the drawer, as on Android. Turn this on for the iOS convention of a tab bar, which the drawer still works alongside.")
            }

            Section("Shared core") {
                LabeledRow("Framework", value: SharedCore.shared.description_)
                LabeledRow("Library entries", value: "\(library.manga.count)")
                LabeledRow("Categories", value: "\(library.categories.count)")
            }

            Section("Development") {
                Button("Add sample entries") {
                    Task { await library.seedSampleData() }
                }
                Button("Clear library", role: .destructive) {
                    Task { await library.clearLibrary() }
                }
            }

            Section {
                Text("These write through the same shared queries the Android app uses, against a real SQLite database on this device.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
