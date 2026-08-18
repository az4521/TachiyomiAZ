import SwiftUI

@main
struct TachiyomiAZApp: App {
    @StateObject private var library = LibraryStore()
    @StateObject private var settings = AppSettings()
    @StateObject private var repositories = RepositoryStore()
    @StateObject private var jvm = JVMHost()
    @StateObject private var catalog: ExtensionCatalog

    init() {
        let jvm = JVMHost()
        _jvm = StateObject(wrappedValue: jvm)
        _catalog = StateObject(wrappedValue: ExtensionCatalog(jvm: jvm))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(library)
                .environmentObject(settings)
                .environmentObject(repositories)
                .environmentObject(jvm)
                .environmentObject(catalog)
                .task { await library.load() }
                .task { await jvm.start() }
        }
    }
}
