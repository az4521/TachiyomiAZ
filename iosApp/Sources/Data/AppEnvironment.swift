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
    let tracking: TrackingStore

    private init() {
        let catalog = ExtensionCatalog(jvm: jvm)
        self.catalog = catalog
        runtime = SourceRuntime(jvm: jvm, catalog: catalog)
        tracking = TrackingStore(library: library)

        SourceManager.shared.runtime = runtime
        SourceManager.shared.catalog = catalog
        MangaManager.shared.library = library
        MangaManager.shared.runtime = runtime
    }

    /// Brings the database and the extension runtime up. Called once, from the scene.
    func start() async {
        await library.load()
        await jvm.start()
        // Extensions installed before the runtime owned the VM live in the old flat layout and
        // would load nothing; hand those over before sources are read.
        await catalog.migrateToRuntimeLayout()
        await runtime.reload()
    }
}
