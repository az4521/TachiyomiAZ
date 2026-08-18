import SwiftUI

@main
struct TachiyomiAZApp: App {
    @StateObject private var library = LibraryStore()
    @StateObject private var settings = AppSettings()
    @StateObject private var repositories = RepositoryStore()
    @StateObject private var jvm = JVMHost()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(library)
                .environmentObject(settings)
                .environmentObject(repositories)
                .environmentObject(jvm)
                .task { await library.load() }
                .task { await jvm.start() }
        }
    }
}
