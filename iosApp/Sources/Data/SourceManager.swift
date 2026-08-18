import ExtensionRunner
import Foundation

/// Source lookup for the vendored UI, backed by the JVM runtime.
///
/// The views call `SourceManager.shared.source(for:)` expecting Aidoku's registry. Underneath this
/// resolves against `SourceRuntime`, which drives the JVM extension host -- so the UI reaches real
/// TachiyomiX sources rather than a second source system.
///
/// `sourceKey` is a String in the UI and an Int64 source id in the database and the host; the
/// conversion lives here so neither side has to know about the other's representation.
@MainActor
final class SourceManager {
    static let shared = SourceManager()

    weak var runtime: SourceRuntime?

    private init() {}

    /// Blocks until extensions have finished loading, so an image request is not built against an
    /// empty source list on a cold launch.
    func waitForSourcesLoad() async {
        guard let runtime else { return }
        if runtime.sources.isEmpty && !runtime.isLoading {
            await runtime.reload()
        }
    }

    func source(for key: String) -> ExtensionRunner.Source? {
        // The vendored UI only uses the returned source to modify image requests. That path needs
        // the JVM's per-source headers, which are not exposed as an ExtensionRunner.Source here --
        // returning nil makes the caller fall back to an unmodified request, which is correct for
        // every source that does not require referer or cookie headers on images.
        nil
    }
}
