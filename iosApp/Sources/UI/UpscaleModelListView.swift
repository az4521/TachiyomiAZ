import SwiftUI

/// Placeholder for upscaling model management.
///
/// Upstream runs CoreML super-resolution models over reader pages, backed by a model store and a
/// download manager for the weights. None of that is ported yet, and the Android app has no
/// equivalent to match, so the page says so rather than presenting controls that do nothing.
struct UpscaleModelListView: View {
    var body: some View {
        UnavailableView(
            NSLocalizedString("UPSCALING_MODELS"),
            systemImage: "wand.and.stars",
            description: Text("Image upscaling is not available in this build.")
        )
        .navigationTitle(NSLocalizedString("UPSCALING_MODELS"))
    }
}
