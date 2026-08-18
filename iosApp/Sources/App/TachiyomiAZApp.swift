import SwiftUI

@main
struct TachiyomiAZApp: App {
    @StateObject private var library: LibraryStore
    @StateObject private var settings = AppSettings()
    @StateObject private var repositories = RepositoryStore()
    @StateObject private var jvm: JVMHost
    @StateObject private var catalog: ExtensionCatalog
    @StateObject private var runtime: SourceRuntime
    @StateObject private var history: HistoryStore
    @StateObject private var downloads = DownloadQueue()

    init() {
        let jvm = JVMHost()
        let catalog = ExtensionCatalog(jvm: jvm)
        let library = LibraryStore()
        _jvm = StateObject(wrappedValue: jvm)
        _catalog = StateObject(wrappedValue: catalog)
        _library = StateObject(wrappedValue: library)
        _runtime = StateObject(wrappedValue: SourceRuntime(jvm: jvm, catalog: catalog))
        _history = StateObject(wrappedValue: HistoryStore(library: library))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(library)
                .environmentObject(settings)
                .environmentObject(repositories)
                .environmentObject(jvm)
                .environmentObject(catalog)
                .environmentObject(runtime)
                .environmentObject(history)
                .environmentObject(downloads)
                .task { await library.load() }
                .task { await jvm.start() }
        }
    }
}
