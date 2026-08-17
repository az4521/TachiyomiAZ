import Foundation
import TachiyomiKit

/// `Category` alone is ambiguous -- the name exists in more than one module in scope -- so the
/// shared one is aliased once here and used everywhere in the app.
typealias MangaCategory = TachiyomiKit.Category

/// The app's view of the shared database.
///
/// Everything here goes through `IosDatabaseHandler`, which is the same query surface the Android
/// app uses -- the mixins in `:core-database` are default methods, so `getLibraryMangas()` here and
/// on Android run the identical SQL against tables generated from the identical `.sq` files.
///
/// Nothing in this file interprets the data. Sorting, filtering and category rules live in
/// `:core-domain` precisely so the two apps cannot disagree about them; when this screen grows
/// those features it should call into that module rather than reimplement them in Swift.
@MainActor
final class LibraryStore: ObservableObject {
    @Published private(set) var categories: [MangaCategory] = []
    @Published private(set) var manga: [LibraryManga] = []
    @Published private(set) var isLoading = true
    @Published private(set) var loadError: String?

    private var handler: IosDatabaseHandler?

    /// Application Support is where iOS expects non-user-facing app data, and it keeps the
    /// database out of any future document picker.
    ///
    /// Returns the *directory*, not a file path. sqliter rejects a name containing a path
    /// separator, and because the Kotlin factory is not `@Throws`, that failure terminates the
    /// process rather than surfacing as a Swift error -- so the split is enforced by the API.
    private static func databaseDirectory() -> String {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.path
    }

    func load() async {
        isLoading = true
        loadError = nil

        let handler = self.handler ?? IosDatabaseHandler.companion.open(
            name: "tachiyomi.db",
            directoryPath: Self.databaseDirectory()
        )
        self.handler = handler

        // First run has no categories at all, and a library with no category cannot render tabs.
        // Seeding the default is what the Android app does on first launch too.
        if handler.getCategories().isEmpty {
            // Kotlin interfaces expose their companion as a separate Swift type, not a member.
            handler.insertCategory(category: CategoryCompanion.shared.createDefault())
        }

        categories = handler.getCategories()
        manga = handler.getLibraryMangas()
        isLoading = false

        // Lets the seeded state be reached without a tap, so the write path can be exercised from
        // a script or a UI test:
        //
        //     xcrun simctl launch booted eu.kanade.tachiyomi.az.ios --seed-sample-library
        if ProcessInfo.processInfo.arguments.contains("--seed-sample-library"), manga.isEmpty {
            await seedSampleData()
        }
    }

    func manga(inCategory category: MangaCategory?) -> [LibraryManga] {
        guard let category, category.id != nil else { return manga }
        return manga.filter { $0.category == category.id?.int32Value }
    }

    /// Inserts a handful of rows so the library is not empty on a fresh simulator.
    ///
    /// This writes through the shared queries on purpose: it exercises Kotlin -> SQLDelight ->
    /// SQLite on the device and back out through the read path, which is the whole stack the port
    /// depends on. It is a development affordance, not a feature, and should go once real sources
    /// can populate the library.
    func seedSampleData() async {
        guard let handler else { return }

        let samples = [
            ("Sample: One Piece", "https://example.invalid/one-piece", "Eiichiro Oda"),
            ("Sample: Berserk", "https://example.invalid/berserk", "Kentaro Miura"),
            ("Sample: Vinland Saga", "https://example.invalid/vinland", "Makoto Yukimura"),
            ("Sample: Vagabond", "https://example.invalid/vagabond", "Takehiko Inoue"),
            ("Sample: Monster", "https://example.invalid/monster", "Naoki Urasawa"),
            ("Sample: Blame!", "https://example.invalid/blame", "Tsutomu Nihei")
        ]

        handler.inTransaction {
            for (title, url, author) in samples {
                let m = MangaImpl()
                m.title = title
                m.url = url
                m.author = author
                m.source = 1
                m.favorite = true
                m.initialized = true
                m.date_added = Int64(Date().timeIntervalSince1970 * 1000)
                handler.insertManga(manga: m)
            }
        }

        manga = handler.getLibraryMangas()
    }

    func clearLibrary() async {
        guard let handler else { return }
        handler.inTransaction {
            for m in handler.getFavoriteMangas() {
                handler.deleteManga(manga: m)
            }
        }
        manga = handler.getLibraryMangas()
    }
}
