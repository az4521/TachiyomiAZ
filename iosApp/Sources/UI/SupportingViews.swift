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

    var body: some View {
        List {
            Section("Shared core") {
                LabeledContent("Framework", value: SharedCore.shared.description_)
                LabeledContent("Library entries", value: "\(library.manga.count)")
                LabeledContent("Categories", value: "\(library.categories.count)")
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
