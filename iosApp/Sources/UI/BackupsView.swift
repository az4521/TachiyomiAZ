import SwiftUI

/// Backups.
///
/// Not wired up yet. Upstream's backup manager builds CoreData objects directly on restore, so it
/// cannot be carried over onto the shared database as-is. The replacement is written against
/// `TachibkBackupCodec` -- Tachiyomi's own `.tachibk` protobuf format -- so a backup taken here
/// restores on TachiyomiAZ for Android and vice versa, which a fork-specific JSON format would not.
struct BackupsView: View {
    var body: some View {
        UnavailableView(
            NSLocalizedString("BACKUPS"),
            systemImage: "externaldrive",
            description: Text("Backups are not available in this build yet.")
        )
        .navigationTitle(NSLocalizedString("BACKUPS"))
    }
}
