import Foundation

/// The app's stores, reachable from the vendored UIKit shell.
///
/// The SwiftUI `App` that used to own these is gone -- `SceneDelegate` builds the tab bar now -- so
/// there is no `@StateObject` graph to hang them off. They live here instead and are injected as
/// environment objects at each hosting-controller boundary, which is the only place SwiftUI views
/// still need them.
@MainActor
final class AppEnvironment {
    static let shared = AppEnvironment()

    let jvm = JVMHost()
    let library = LibraryStore()
    let settings = AppSettings()
    let repositories = RepositoryStore()
    let catalog: ExtensionCatalog
    let runtime: SourceRuntime

    private init() {
        let catalog = ExtensionCatalog(jvm: jvm)
        self.catalog = catalog
        runtime = SourceRuntime(jvm: jvm, catalog: catalog)

        SourceManager.shared.runtime = runtime
        SourceManager.shared.catalog = catalog
        MangaManager.shared.library = library
        MangaManager.shared.runtime = runtime
    }

    /// Brings the database and the extension runtime up. Called once, from the scene.
    func start() async {
        await library.load()
        // Primed before any request goes out: the extension host asks for this synchronously, and
        // an empty answer is what a site sees as a missing User-Agent.
        _ = await UserAgentProvider.shared.getUserAgent()

        await jvm.start()
        // Extensions installed before the runtime owned the VM live in the old flat layout and
        // would load nothing; hand those over before sources are read.
        await catalog.migrateToRuntimeLayout()
        await runtime.reload()

        MangaManager.shared.environmentDidStart()

        // The library renders each entry against its source, so entries loaded before the sources
        // existed showed as unavailable until something else triggered a reload -- which is why
        // browsing one source appeared to fix the whole library.
        //
        // `reload()` announces the source list itself now, so only the library needs a nudge here.
        NotificationCenter.default.post(name: Notification.Name("updateLibrary"), object: nil)
    }
}
