import SwiftUI

@main
struct TachiyomiAZApp: App {
    @StateObject private var library = LibraryStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(library)
                .task { await library.load() }
        }
    }
}
