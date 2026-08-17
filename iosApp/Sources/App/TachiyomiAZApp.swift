import SwiftUI

@main
struct TachiyomiAZApp: App {
    @StateObject private var library = LibraryStore()
    @StateObject private var settings = AppSettings()
    @StateObject private var repositories = RepositoryStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(library)
                .environmentObject(settings)
                .environmentObject(repositories)
                .task { await library.load() }
        }
    }
}
