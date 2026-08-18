import SwiftUI

/// The tracking settings page.
///
/// Upstream's version is written against its own tracker hierarchy -- eight services behind
/// `OAuthTracker`/`EnhancedTracker`, including the self-hosted Komga, Kavita and Suwayomi -- which
/// does not match the smaller `Tracker` protocol this app implements for MyAnimeList and AniList.
/// Rather than vendor a page that names services that do not exist here, this presents the accounts
/// this app actually has, over the same `TrackingStore` that writes the shared `manga_sync` rows.
struct SettingsTrackingView: View {
    var body: some View {
        TrackingSettingsView()
            .navigationTitle(NSLocalizedString("TRACKING"))
    }
}
