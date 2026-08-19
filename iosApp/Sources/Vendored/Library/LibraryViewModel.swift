//
//  LibraryViewModel.swift
//  Aidoku (iOS)
//
//  Created by Skitty on 7/25/22.
//

import ExtensionRunner
import CoreData
import UIKit

@MainActor
class LibraryViewModel {
    var manga: [MangaInfo] = []
    var pinnedManga: [MangaInfo] = []
    var sourceKeys: [String] = []

    // temporary storage when searching
    private var searchQuery: String = ""
    private var storedManga: [MangaInfo]?
    private var storedPinnedManga: [MangaInfo]?
    private var unreadBadgeCache: [MangaIdentifier: Int] = [:]
    private var downloadBadgeCache: [MangaIdentifier: Int] = [:]

    enum PinType: String, CaseIterable {
        case none
        case unread
        case updatedChapters

        var title: String {
            switch self {
                case .none: NSLocalizedString("PIN_DISABLED")
                case .unread: NSLocalizedString("PIN_UNREAD")
                case .updatedChapters: NSLocalizedString("PIN_UPDATED_CHAPTERS")
            }
        }

        var needsUpdateOnContentOpen: Bool {
            switch self {
                case .none: false
                case .unread: false
                case .updatedChapters: true
            }
        }
    }

    enum SortMethod: Int, CaseIterable {
        case alphabetical = 0
        case lastRead
        case lastOpened
        case lastUpdated
        case dateAdded
        case lastChapter
        case unreadChapters
        case totalChapters

        var title: String {
            switch self {
                case .alphabetical: NSLocalizedString("SORT_TITLE")
                case .lastRead: NSLocalizedString("SORT_LAST_READ")
                case .lastOpened: NSLocalizedString("SORT_LAST_OPENED")
                case .lastUpdated: NSLocalizedString("SORT_LAST_UPDATED")
                case .dateAdded: NSLocalizedString("SORT_DATE_ADDED")
                case .lastChapter: NSLocalizedString("SORT_LATEST_CHAPTER")
                case .unreadChapters: NSLocalizedString("SORT_UNREAD_CHAPTERS")
                case .totalChapters: NSLocalizedString("SORT_TOTAL_CHAPTERS")
            }
        }

        var descendingTitle: String {
            switch self {
                case .alphabetical: NSLocalizedString("ASCENDING") // reverse default for alphabetical sort
                case .lastRead: NSLocalizedString("NEWEST_FIRST")
                case .lastOpened: NSLocalizedString("NEWEST_FIRST")
                case .lastUpdated: NSLocalizedString("NEWEST_FIRST")
                case .dateAdded: NSLocalizedString("NEWEST_FIRST")
                case .lastChapter: NSLocalizedString("NEWEST_FIRST")
                case .unreadChapters: NSLocalizedString("HIGHEST_FIRST")
                case .totalChapters: NSLocalizedString("HIGHEST_FIRST")
            }
        }

        var ascendingTitle: String {
            switch self {
                case .alphabetical: NSLocalizedString("DESCENDING")
                case .lastRead: NSLocalizedString("OLDEST_FIRST")
                case .lastOpened: NSLocalizedString("OLDEST_FIRST")
                case .lastUpdated: NSLocalizedString("OLDEST_FIRST")
                case .dateAdded: NSLocalizedString("OLDEST_FIRST")
                case .lastChapter: NSLocalizedString("OLDEST_FIRST")
                case .unreadChapters: NSLocalizedString("LOWEST_FIRST")
                case .totalChapters: NSLocalizedString("LOWEST_FIRST")
            }
        }

        // `sortStringValue` lived here: CoreData key paths for the fetch request's sort
        // descriptor. The fetch is gone, so they named attributes of an entity this app does not
        // have -- "manga.chapterCount" among them, which never existed here at all. `LibrarySort`
        // does the ordering now.
    }

    struct BadgeType: OptionSet {
        let rawValue: Int

        static let unread = BadgeType(rawValue: 1 << 0)
        static let downloaded = BadgeType(rawValue: 1 << 1)
    }

    lazy var pinType: PinType = getPinType()
    lazy var sortMethod = SortMethod(rawValue: UserDefaults.standard.integer(forKey: "Library.sortOption")) ?? .lastOpened
    lazy var sortAscending = UserDefaults.standard.bool(forKey: "Library.sortAscending")
    lazy var badgeType: BadgeType = {
        var type: BadgeType = []
        if UserDefaults.standard.bool(forKey: "Library.unreadChapterBadges") {
            type.insert(.unread)
        }
        if UserDefaults.standard.bool(forKey: "Library.downloadedChapterBadges") {
            type.insert(.downloaded)
        }
        return type
    }()

    var filters: [LibraryFilter] {
        didSet {
            saveFilters()
        }
    }

    var categories: [String] = []
    var filterGroups: [FilterGroup] = []
    lazy var currentCategory: String? = UserDefaults.standard.string(forKey: "Library.currentCategory") {
        didSet {
            UserDefaults.standard.set(currentCategory, forKey: "Library.currentCategory")
        }
    }
    var isInRealCategory: Bool {
        if let currentCategory, !currentCategory.isEmpty {
            categories.contains(currentCategory)
        } else {
            false
        }
    }
    var isInUncategorizedCategory: Bool {
        currentCategory?.isEmpty ?? false
    }
    private(set) var hasUncategorizedManga = false
    private(set) var actuallyEmpty = true

    init() {
        let filtersData = UserDefaults.standard.data(forKey: "Library.filters")
        if let filtersData {
            let decodedFilters =
                (try? JSONDecoder().decode([LibraryFilter].self, from: filtersData))
                ?? []
            // Categories are selected by the library tab bar. Discard legacy
            // drawer category filters so they cannot remain invisibly active.
            self.filters = decodedFilters.filter { $0.type != .category }
            if self.filters != decodedFilters,
               let cleanedData = try? JSONEncoder().encode(self.filters)
            {
                UserDefaults.standard.set(cleanedData, forKey: "Library.filters")
            }
        } else {
            self.filters = []
        }
        unreadBadgeCache = LibraryBadgeCache.load(.unread)
        downloadBadgeCache = LibraryBadgeCache.load(.downloaded)
    }

    private func saveUnreadBadgeCache() {
        LibraryBadgeCache.save(unreadBadgeCache, kind: .unread)
    }

    private func saveDownloadBadgeCache() {
        LibraryBadgeCache.save(downloadBadgeCache, kind: .downloaded)
    }

    func reloadPersistedBadgeCaches() {
        unreadBadgeCache = LibraryBadgeCache.load(.unread)
        downloadBadgeCache = LibraryBadgeCache.load(.downloaded)
    }
}

extension LibraryViewModel {
    private var effectiveFilters: [LibraryFilter] {
        if
            let currentCategory,
            let group = filterGroups.first(where: {
                $0.title == currentCategory
            })
        {
            return group.filters + filters
        }
        return filters
    }

    private var needsUnreadData: Bool {
        badgeType.contains(.unread) ||
            pinType == .unread ||
            sortMethod == .unreadChapters ||
            effectiveFilters.contains { $0.type == .hasUnread }
    }

    private var needsDownloadData: Bool {
        badgeType.contains(.downloaded) ||
            effectiveFilters.contains { $0.type == .downloaded }
    }

    func hasEffectiveFilter(
        _ methods: Set<LibraryFilter.FilterMethod>
    ) -> Bool {
        effectiveFilters.contains { methods.contains($0.type) }
    }

    func isCategoryLocked() -> Bool {
        guard UserDefaults.standard.bool(forKey: "Library.lockLibrary") else { return false }
        if let currentCategory, !currentCategory.isEmpty {
            let lockedCategories = UserDefaults.standard.stringArray(forKey: "Library.lockedCategories") ?? []
            return lockedCategories.contains(currentCategory)
        }
        return true
    }

    func getPinType() -> PinType {
        UserDefaults.standard.string(forKey: "Library.pinTitles").flatMap(PinType.init) ?? .none
    }

    func refreshCategories(skipDataLoad: Bool = false) async {
        (categories, filterGroups) = await CoreDataManager.shared.container.performBackgroundTask { @Sendable context in
            (
                CoreDataManager.shared.getCategoryTitles(context: context),
                CoreDataManager.shared.getFilterGroups(context: context)
            )
        }
        if !skipDataLoad {
            await loadLibrary()
        }
    }

    // swiftlint:disable:next cyclomatic_complexity
    @discardableResult
    func loadLibrary(
        refreshBadges: Bool = false,
        refreshCategoryAvailability: Bool = true
    ) async -> Bool {
        let previousInfo = Dictionary(
            (manga + pinnedManga).map { ($0.identifier, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        let previouslyHadUncategorizedManga = hasUncategorizedManga
        if refreshCategoryAvailability {
            hasUncategorizedManga = await CoreDataManager.shared.container
                .performBackgroundTask { @Sendable context in
                // Answered by the library query itself. This walked every library entry and ran a
                // category lookup per entry -- two queries each, on every single library load.
                CoreDataManager.shared.hasUncategorizedLibraryManga()
            }
            normalizeCurrentCategory()
        }

        // handle filter groups
        let filters = effectiveFilters
        let currentCategory = (isInUncategorizedCategory || isInRealCategory) ? self.currentCategory : nil

        // The refresh filter asks a question about the library as a whole, so it is answered once
        // here rather than per entry. Computed only when the filter is on, because it runs the
        // whole selection rule. Deliberately not scoped to the current category: the filter means
        // "a refresh would fetch this", and the category tab already narrows what is on screen.
        // A title's content rating comes from the source that provides it.
        //
        // Aidoku rates each manga and stores it on the row; Android's schema has no such column,
        // because a Tachiyomi extension declares NSFW for the whole source rather than per title.
        // So `nsfw` on a row here is always 0 -- which meant the filter sheet offered a content
        // rating filter that matched everything under "Safe" and nothing under either NSFW option,
        // emptying the library. Answered from the source's own rating instead, which is the
        // granularity the data actually has.
        let ratingsBySource = Dictionary(
            SourceManager.shared.sources.map { ($0.key, Int16($0.contentRating.rawValue)) },
            uniquingKeysWith: { first, _ in first }
        )

        let refreshEligible: Set<MangaIdentifier>? = filters.contains { $0.type == .willRefresh }
            ? Set(
                MangaManager.shared.library?
                    .refreshTargets(settings: AppEnvironment.shared.settings)
                    .map { MangaIdentifier(sourceKey: $0.legacySourceId, mangaKey: $0.url) } ?? []
            )
            : nil

        let (
            success,
            actuallyEmpty,
            pinnedManga,
            manga,
            sourceKeys,
            unappliedFilters
        ) = await CoreDataManager.shared.container.performBackgroundTask { @Sendable [sortMethod, sortAscending, pinType, refreshEligible] context in
            var pinnedManga: [MangaInfo] = []
            var manga: [MangaInfo] = []
            var sourceKeys: Set<String> = []
            var unappliedFilters: [LibraryFilter] = []

            // Rewritten from a raw fetch. `getLibraryManga(category:)` runs the shared query;
            // "uncategorised" is not a category there, so it is filtered here instead. Sorting
            // stays below, where the view model already sorts what it has built.
            // An empty category name is the library screen's "Uncategorized" tab. Both branches
            // are now a filter over rows the library query already returned; the uncategorised one
            // used to run a category lookup per library entry.
            let libraryObjects: [LibraryMangaObject] = if let currentCategory {
                if currentCategory.isEmpty {
                    CoreDataManager.shared.getUncategorizedLibraryManga()
                } else {
                    CoreDataManager.shared.getLibraryManga(category: currentCategory)
                }
            } else {
                CoreDataManager.shared.getLibraryManga()
            }

            let actuallyEmpty = libraryObjects.isEmpty

            // Upstream sorts with an NSSortDescriptor on the fetch request. The fetch is gone --
            // this reads the shared query instead -- and the sort went with it, which is why every
            // option except title and unread count appeared to do nothing. Same keys, same
            // inversion for alphabetical, applied to the rows before they become view models.
            let sortedObjects = LibrarySort.sorted(
                libraryObjects,
                by: sortMethod,
                ascending: sortAscending
            )

            var ids = Set<String>()

            // Two queries for the whole library, rather than the two per entry this used to run
            // inside the loop below -- the single biggest cost of loading the library screen.
            let categoriesByManga = CoreDataManager.shared.libraryCategoryNames()

            main: for libraryObject in sortedObjects {
                guard
                    let mangaObject = libraryObject.manga,
                    // ensure the manga hasn't already been accounted for
                    ids.insert("\(mangaObject.sourceId)|\(mangaObject.id)").inserted
                else {
                    continue
                }

                let categories = categoriesByManga[
                    MangaIdentifier(sourceKey: mangaObject.sourceId, mangaKey: mangaObject.id)
                ] ?? []

                let info = MangaInfo(
                    mangaId: mangaObject.id,
                    sourceId: mangaObject.sourceId,
                    coverUrl: mangaObject.cover.flatMap { URL(string: $0) },
                    title: mangaObject.title,
                    author: mangaObject.author,
                    tags: mangaObject.tags,
                    url: mangaObject.url.flatMap { URL(string: $0) }
                )

                sourceKeys.insert(mangaObject.sourceId)

                // process filters
                var filteredSourceKeys: Set<String> = []
                var filteredContentRatings: Set<Int16> = []
                var filteredCategories: Set<String> = []
                for filter in filters {
                    let condition: Bool
                    switch filter.type {
                        case .downloaded:
                            unappliedFilters.append(filter)
                            continue
                        case .tracking:
                            condition = CoreDataManager.shared.hasTrack(
                                sourceId: info.sourceId,
                                mangaId: info.mangaId,
                                context: context
                            )
                        case .hasUnread:
                            unappliedFilters.append(filter)
                            continue
                        case .started:
                            condition = CoreDataManager.shared.hasHistory(
                                sourceId: info.sourceId,
                                mangaId: info.mangaId,
                                context: context
                            )
                        case .completed:
                            condition = mangaObject.status == ExtensionRunner.PublishingStatus.completed.rawValue
                        case .willRefresh:
                            condition = refreshEligible?.contains(info.identifier) ?? false
                        case .source:
                            guard let sourceId = filter.value else { continue }
                            if filter.exclude {
                                condition = info.sourceId == sourceId
                            } else {
                                // handle included source filters as OR
                                filteredSourceKeys.insert(sourceId)
                                continue
                            }
                        case .contentRating:
                            guard let contentRating = filter.value.flatMap(MangaContentRating.init) else { continue }
                            if filter.exclude {
                                condition = (ratingsBySource[info.sourceId] ?? 0) == contentRating.rawValue
                            } else {
                                // handle included content rating filters as OR
                                filteredContentRatings.insert(Int16(contentRating.rawValue))
                                continue
                            }
                        case .category:
                            guard let category = filter.value else { continue }
                            if filter.exclude {
                                condition = categories.contains(category)
                            } else {
                                // handle included category filters as OR
                                filteredCategories.insert(category)
                                continue
                            }

                    }
                    let shouldSkip = filter.exclude ? condition : !condition
                    if shouldSkip {
                        continue main
                    }
                }
                if !filteredSourceKeys.isEmpty && !filteredSourceKeys.contains(info.sourceId) {
                    continue main
                }
                if !filteredContentRatings.isEmpty && !filteredContentRatings.contains(ratingsBySource[info.sourceId] ?? 0) {
                    continue main
                }
                if !filteredCategories.isEmpty && !filteredCategories.contains(where: { categories.contains($0) }) {
                    continue main
                }

                switch pinType {
                    case .none:
                        manga.append(info)
                    case .unread:
                        // don't have unread info to sort yet
                        manga.append(info)
                    case .updatedChapters:
                        if (libraryObject.lastUpdatedChapters ?? .distantPast) > (libraryObject.lastOpened ?? .distantPast) {
                            pinnedManga.append(info)
                        } else {
                            manga.append(info)
                        }
                }
            }

            return (true, actuallyEmpty, pinnedManga, manga, sourceKeys, unappliedFilters)
        }

        guard success else {
            return previouslyHadUncategorizedManga != hasUncategorizedManga
        }

        self.pinnedManga = pinnedManga
        self.manga = manga
        self.storedPinnedManga = nil
        self.storedManga = nil
        self.sourceKeys = sourceKeys.sorted()
        self.actuallyEmpty = actuallyEmpty

        if refreshBadges {
            if needsUnreadData {
                await fetchUnreads(skipSortCheck: true)
            }
            if needsDownloadData {
                // The enclosing load applies downloaded filters after the
                // counts are populated, so avoid starting a nested reload.
                await fetchDownloadCounts(reapplyFilters: false)
            }
        } else {
            for index in self.manga.indices {
                let identifier = self.manga[index].identifier
                self.manga[index].unread = unreadBadgeCache[identifier]
                    ?? previousInfo[identifier]?.unread
                    ?? 0
                self.manga[index].downloads = downloadBadgeCache[identifier]
                    ?? previousInfo[identifier]?.downloads
                    ?? 0
            }
            for index in self.pinnedManga.indices {
                let identifier = self.pinnedManga[index].identifier
                self.pinnedManga[index].unread = unreadBadgeCache[identifier]
                    ?? previousInfo[identifier]?.unread
                    ?? 0
                self.pinnedManga[index].downloads = downloadBadgeCache[identifier]
                    ?? previousInfo[identifier]?.downloads
                    ?? 0
            }
        }

        if !unappliedFilters.isEmpty {
            let filter: (MangaInfo) -> Bool = { info in
                for filter in unappliedFilters {
                    let condition: Bool
                    switch filter.type {
                        case .downloaded: condition = info.downloads > 0
                        case .hasUnread: condition = info.unread > 0
                        default: continue
                    }
                    let shouldSkip = filter.exclude ? condition : !condition
                    guard !shouldSkip else { return false }
                }
                return true
            }
            self.pinnedManga = self.pinnedManga.filter(filter)
            self.manga = self.manga.filter(filter)
        }

        if pinType == .unread {
            let currentManga = self.manga + self.pinnedManga
            var pinnedManga: [MangaInfo] = []
            var manga: [MangaInfo] = []
            for item in currentManga {
                if item.unread > 0 {
                    pinnedManga.append(item)
                } else {
                    manga.append(item)
                }
            }
            self.pinnedManga = pinnedManga
            self.manga = manga
        }

        if sortMethod == .unreadChapters {
            await sortLibrary()
        }

        if !searchQuery.isEmpty {
            await search(query: searchQuery)
        }

        return previouslyHadUncategorizedManga != hasUncategorizedManga
    }

    private func normalizeCurrentCategory() {
        let showAllCategory = UserDefaults.standard.bool(forKey: "Library.showAllCategory")
        let isInFilterGroup = filterGroups.contains(where: { $0.title == currentCategory })
        let isValidCategory = currentCategory.map { categories.contains($0) } ?? false

        let fallbackCategory: String? = if hasUncategorizedManga {
            ""
        } else if let category = categories.first {
            category
        } else {
            filterGroups.first?.title
        }

        if currentCategory == nil, !showAllCategory {
            currentCategory = fallbackCategory
        } else if currentCategory?.isEmpty == true, !hasUncategorizedManga {
            currentCategory = showAllCategory ? nil : fallbackCategory
        } else if currentCategory != nil, !isValidCategory, !isInFilterGroup, currentCategory?.isEmpty == false {
            currentCategory = showAllCategory ? nil : fallbackCategory
        }
    }

    // updates unread counts and manga sort order for history change
    func updateHistory(for changedManga: [MangaInfo], read: Bool) async {
        let identifiers = Set(changedManga.map(\.identifier))
        let unreadCounts = await withTaskGroup(
            of: (MangaIdentifier, Int).self,
            returning: [MangaIdentifier: Int].self
        ) { group in
            for identifier in identifiers {
                group.addTask {
                    let count = await CoreDataManager.shared.container.performBackgroundTask { context in
                        let filters = CoreDataManager.shared.getMangaChapterFilters(
                            sourceId: identifier.sourceKey,
                            mangaId: identifier.mangaKey,
                            context: context
                        )
                        return CoreDataManager.shared.unreadCount(
                            sourceId: identifier.sourceKey,
                            mangaId: identifier.mangaKey,
                            lang: filters.language,
                            scanlators: filters.scanlators,
                            context: context
                        )
                    }
                    return (identifier, count)
                }
            }
            var ret: [MangaIdentifier: Int] = [:]
            for await (identifier, count) in group {
                ret[identifier] = count
            }
            return ret
        }
        for (identifier, count) in unreadCounts {
            unreadBadgeCache[identifier] = count
        }
        saveUnreadBadgeCache()

        for (identifier, count) in unreadCounts {
            if let pinnedIndex = pinnedManga.firstIndex(where: { $0.identifier == identifier }) {
                pinnedManga[pinnedIndex].unread = count
                if read && sortMethod == .lastRead && pinnedIndex != 0 {
                    let manga = pinnedManga.remove(at: pinnedIndex)
                    pinnedManga.insert(manga, at: 0)
                }
            } else if let mangaIndex = self.manga.firstIndex(where: { $0.identifier == identifier }) {
                self.manga[mangaIndex].unread = count
                if read && sortMethod == .lastRead && mangaIndex != 0 {
                    let manga = self.manga.remove(at: mangaIndex)
                    self.manga.insert(manga, at: 0)
                }
            }
        }
        if pinType == .unread || hasEffectiveFilter([.hasUnread, .started]) {
            await loadLibrary()
        } else if sortMethod == .unreadChapters {
            await sortLibrary()
        }
    }

    @discardableResult
    func refreshUncachedBadges() async -> Bool {
        let identifiers = Set((manga + pinnedManga).map(\.identifier))
        let needsUnreads = needsUnreadData && identifiers.contains {
            unreadBadgeCache[$0] == nil
        }
        let needsDownloads = needsDownloadData && identifiers.contains {
            downloadBadgeCache[$0] == nil
        }
        if needsUnreads {
            await fetchUnreads(skipSortCheck: true, onlyUncached: true)
        }
        if needsDownloads {
            await fetchDownloadCounts(onlyUncached: true)
        }
        return needsUnreads || needsDownloads
    }

    func fetchUnreads(
        skipSortCheck: Bool = false,
        onlyUncached: Bool = false
    ) async {
        if !skipSortCheck && pinType == .unread {
            // Refresh counts as part of the reload so newly read titles move
            // out of the pinned section immediately.
            await loadLibrary(refreshBadges: true)
            return
        }

        // Use one grouped store query. Issuing multiple Core Data requests for
        // every title makes a history refresh scale with the number of manga.
        let allUnreadCounts = await CoreDataManager.shared.container
            .performBackgroundTask { @Sendable context in
                CoreDataManager.shared.libraryUnreadCounts(
                    context: context
                )
            }

        let unreadCounts = if onlyUncached {
            allUnreadCounts.filter { unreadBadgeCache[$0.key] == nil }
        } else {
            allUnreadCounts
        }

        // Keep counts for the whole library current. This matters for exclude
        // filters: a currently hidden title may need to reappear after a
        // history change.
        for (identifier, count) in unreadCounts {
            unreadBadgeCache[identifier] = count
        }
        saveUnreadBadgeCache()

        // set unread counts
        for (i, manga) in self.manga.enumerated() {
            guard let count = unreadCounts[manga.identifier] else { continue }
            self.manga[i].unread = count
        }
        for (i, manga) in self.pinnedManga.enumerated() {
            guard let count = unreadCounts[manga.identifier] else { continue }
            self.pinnedManga[i].unread = count
        }

        // re-sort library if needed
        if !skipSortCheck && sortMethod == .unreadChapters {
            await sortLibrary()
        }
    }

    func fetchUnreads(for identifier: MangaIdentifier) async {
        let unreadCount = await CoreDataManager.shared.container.performBackgroundTask { @Sendable context in
            let filters = CoreDataManager.shared.getMangaChapterFilters(
                sourceId: identifier.sourceKey,
                mangaId: identifier.mangaKey,
                context: context
            )
            return CoreDataManager.shared.unreadCount(
                sourceId: identifier.sourceKey,
                mangaId: identifier.mangaKey,
                lang: filters.language,
                scanlators: filters.scanlators,
                context: context
            )
        }
        unreadBadgeCache[identifier] = unreadCount
        saveUnreadBadgeCache()
        var didUpdate = false
        if let index = self.manga.firstIndex(where: { $0.identifier == identifier }) {
            if self.manga[index].unread != unreadCount {
                didUpdate = true
                self.manga[index].unread = unreadCount
            }
        } else if let index = self.pinnedManga.firstIndex(where: { $0.identifier == identifier }) {
            if self.pinnedManga[index].unread != unreadCount {
                didUpdate = true
                self.pinnedManga[index].unread = unreadCount
            }
        }
        // re-sort library if needed
        if didUpdate || hasEffectiveFilter([.hasUnread]) {
            if pinType == .unread || hasEffectiveFilter([.hasUnread]) {
                await loadLibrary()
            } else if sortMethod == .unreadChapters {
                await sortLibrary()
            }
        }
    }

    func fetchDownloadCounts(
        for identifier: MangaIdentifier? = nil,
        onlyUncached: Bool = false,
        reapplyFilters: Bool = true
    ) async {
        var downloadCounts: [MangaIdentifier: Int] = [:]
        if let identifier {
            downloadCounts[identifier] = await DownloadManager.shared.downloadsCount(for: identifier)
        } else {
            let currentManga = (self.manga + self.pinnedManga).filter {
                !onlyUncached || downloadBadgeCache[$0.identifier] == nil
            }
            for manga in currentManga {
                let identifier = manga.identifier
                downloadCounts[identifier] = await DownloadManager.shared.downloadsCount(for: identifier)
            }
        }
        for (identifier, count) in downloadCounts {
            downloadBadgeCache[identifier] = count
        }
        saveDownloadBadgeCache()
        for (i, manga) in self.pinnedManga.enumerated() {
            if let count = downloadCounts[manga.identifier] {
                self.pinnedManga[i].downloads = count
            }
        }
        for (i, manga) in self.manga.enumerated() {
            if let count = downloadCounts[manga.identifier] {
                self.manga[i].downloads = count
            }
        }
        if reapplyFilters && hasEffectiveFilter([.downloaded]) {
            await loadLibrary()
        }
    }

    func clearDownloadCounts() async {
        downloadBadgeCache.removeAll()
        saveDownloadBadgeCache()
        for index in pinnedManga.indices {
            pinnedManga[index].downloads = 0
        }
        for index in manga.indices {
            manga[index].downloads = 0
        }
        if hasEffectiveFilter([.downloaded]) {
            await loadLibrary()
        }
    }

    @MainActor
    func sortLibrary() async {
        switch sortMethod {
            case .alphabetical:
                if sortAscending {
                    pinnedManga.sort { $0.title ?? "" > $1.title ?? "" }
                    manga.sort { $0.title ?? "" > $1.title ?? "" }
                } else {
                    pinnedManga.sort { $0.title ?? "" < $1.title ?? "" }
                    manga.sort { $0.title ?? "" < $1.title ?? "" }
                }

            case .unreadChapters:
                if sortAscending {
                    pinnedManga.sort {
                        if $0.unread == 0 {
                            false
                        } else if $1.unread == 0 {
                            true
                        } else {
                            $0.unread < $1.unread
                        }
                    }
                    manga.sort {
                        if $0.unread == 0 {
                            false
                        } else if $1.unread == 0 {
                            true
                        } else {
                            $0.unread < $1.unread
                        }
                    }
                } else {
                    pinnedManga.sort { $0.unread > $1.unread }
                    manga.sort { $0.unread > $1.unread }
                }

            default:
                await loadLibrary()
        }
    }

    func setSort(method: SortMethod, ascending: Bool) async {
        guard sortMethod != method || sortAscending != ascending else {
            return
        }
        if sortAscending != ascending {
            sortAscending = ascending
            UserDefaults.standard.set(sortAscending, forKey: "Library.sortAscending")
        }
        if sortMethod != method {
            sortMethod = method
            UserDefaults.standard.set(sortMethod.rawValue, forKey: "Library.sortOption")
        }
        await sortLibrary()
    }

    func toggleFilter(method: LibraryFilter.FilterMethod, value: String? = nil) async {
        let filterIndex = filters.firstIndex(where: { $0.type == method && $0.value == value })
        if let filterIndex {
            if filters[filterIndex].exclude {
                filters.remove(at: filterIndex)
            } else {
                filters[filterIndex].exclude = true
            }
        } else {
            filters.append(LibraryFilter(type: method, value: value, exclude: false))
        }
        await loadLibrary()
    }

    private func saveFilters() {
        let filtersData = try? JSONEncoder().encode(filters)
        if let filtersData {
            UserDefaults.standard.set(filtersData, forKey: "Library.filters")
        }
    }

    func search(query: String) async {
        searchQuery = query

        guard !query.isEmpty else {
            var shouldResort = false
            if let storedManga {
                manga = storedManga
                self.storedManga = nil
                shouldResort = true
            }
            if let storedPinnedManga {
                pinnedManga = storedPinnedManga
                self.storedPinnedManga = nil
                shouldResort = true
            }
            if shouldResort {
                await sortLibrary()
            }
            return
        }
        if storedManga == nil {
            storedManga = manga
            storedPinnedManga = pinnedManga
        }
        guard let storedManga, let storedPinnedManga else {
            return
        }

        let search = LibrarySearchQuery(query)
        pinnedManga = storedPinnedManga.filter(search.matches)
        manga = storedManga.filter(search.matches)
    }

    // returns true if library was reloaded
    @discardableResult
    func mangaOpened(sourceId: String, mangaId: String) async -> Bool {
        guard sortMethod == .lastOpened || pinType.needsUpdateOnContentOpen else { return false }

        var libraryReloaded = false

        let pinnedIndex = pinnedManga.firstIndex(where: { $0.mangaId == mangaId && $0.sourceId == sourceId })
        if let pinnedIndex {
            if sortMethod == .lastOpened {
                let manga = pinnedManga.remove(at: pinnedIndex)
                if pinType.needsUpdateOnContentOpen {
                    self.manga.insert(manga, at: 0)
                } else {
                    pinnedManga.insert(manga, at: 0)
                }
            } else {
                await loadLibrary() // don't know where to put in manga array, just refresh
                libraryReloaded = true
            }
        } else if sortMethod == .lastOpened {
            let index = manga.firstIndex(where: { $0.mangaId == mangaId && $0.sourceId == sourceId })
            if let index {
                let manga = manga.remove(at: index)
                if sortAscending {
                    // add to end
                    self.manga.append(manga)
                } else {
                    // add to start
                    self.manga.insert(manga, at: 0)
                }
            }
        }

        return libraryReloaded
    }

    func mangaRead(sourceId: String, mangaId: String) {
        guard sortMethod == .lastRead else { return }
        if let pinnedIndex = pinnedManga.firstIndex(where: { $0.mangaId == mangaId && $0.sourceId == sourceId }) {
            let manga = pinnedManga.remove(at: pinnedIndex)
            self.manga.insert(manga, at: 0)
        } else if let index = manga.firstIndex(where: { $0.mangaId == mangaId && $0.sourceId == sourceId }) {
            let manga = manga.remove(at: index)
            self.manga.insert(manga, at: 0)
        }
    }

    func removeFromLibrary(manga removedManga: [MangaInfo]) {
        let identifiers = Set(removedManga.map(\.identifier))
        guard !identifiers.isEmpty else { return }
        pinnedManga.removeAll { identifiers.contains($0.identifier) }
        manga.removeAll { identifiers.contains($0.identifier) }
        storedPinnedManga?.removeAll { identifiers.contains($0.identifier) }
        storedManga?.removeAll { identifiers.contains($0.identifier) }
    }

    func addToCurrentCategory(manga: [MangaInfo]) async {
        guard let currentCategory, isInRealCategory else { return }
        await CoreDataManager.shared.addCategoriesToManga(
            manga.map(\.identifier),
            categories: [currentCategory]
        )
    }

    func removeFromCurrentCategory(manga removedManga: [MangaInfo]) async {
        guard let currentCategory, isInRealCategory else { return }
        let identifiers = Set(removedManga.map(\.identifier))
        guard !identifiers.isEmpty else { return }
        pinnedManga.removeAll { identifiers.contains($0.identifier) }
        manga.removeAll { identifiers.contains($0.identifier) }
        storedPinnedManga?.removeAll { identifiers.contains($0.identifier) }
        storedManga?.removeAll { identifiers.contains($0.identifier) }
        await CoreDataManager.shared.removeCategoriesFromManga(
            Array(identifiers),
            categories: [currentCategory]
        )
    }
}

/// Orders library rows the way upstream's sort descriptor did.
///
/// Outside `LibraryViewModel` because that type is `@MainActor` and this runs on the background
/// task that loads the library, alongside the query it orders.
///
/// Two details carried over from upstream rather than reasoned out fresh: `.alphabetical` inverts
/// the ascending flag (its menu labels it the other way round), and `.unreadChapters` is not
/// sorted here at all -- the counts are not known until the badge pass has run, so `sortLibrary`
/// handles that one afterwards.
enum LibrarySort {
    static func sorted(
        _ objects: [LibraryMangaObject],
        by method: LibraryViewModel.SortMethod,
        ascending: Bool
    ) -> [LibraryMangaObject] {
        // A missing date sorts as the distant past, which puts it first ascending -- where a nil
        // sorts under upstream's descriptor.
        func by<T: Comparable>(
            _ key: (LibraryMangaObject) -> T,
            _ ascending: Bool
        ) -> [LibraryMangaObject] {
            objects.sorted { ascending ? key($0) < key($1) : key($0) > key($1) }
        }

        switch method {
            case .alphabetical: return by({ ($0.manga?.title ?? "").lowercased() }, !ascending)
            case .lastRead: return by({ $0.lastRead ?? .distantPast }, ascending)
            case .lastOpened: return by({ $0.lastOpened ?? .distantPast }, ascending)
            case .lastUpdated: return by({ $0.lastUpdated ?? .distantPast }, ascending)
            case .dateAdded: return by({ $0.dateAdded ?? .distantPast }, ascending)
            case .lastChapter: return by({ $0.lastChapter ?? .distantPast }, ascending)
            case .totalChapters: return by({ $0.totalChapters }, ascending)
            case .unreadChapters: return objects
        }
    }
}

struct LibrarySearchQuery {
    struct Term: Equatable {
        let value: String
        let isExcluded: Bool
    }

    let terms: [Term]

    init(_ query: String) {
        terms = Self.parse(query)
    }

    /// Whether a library entry matches the query.
    ///
    /// Matches the Android app's rule, which is a plain case-insensitive substring test. This used
    /// `fuzzyMatch`, a *subsequence* test -- "nrt" matches "Naruto" because n, r and t appear in
    /// that order somewhere -- so almost any short query matched most of a library. Searching the
    /// same words on the two apps returned wildly different sets.
    ///
    /// Tags are no longer searched by plain terms either, for the same reason: on Android a genre
    /// is only searched behind an explicit flag, so "action" matched nothing here and every
    /// action title there. They are still searched by `-term`, which can only ever remove results.
    ///
    /// Known remaining differences from Android, both of which would *add* results rather than
    /// inflate them: it also matches the artist and the source name, and searches linked trackers.
    /// `MangaInfo` carries neither artist nor source name today.
    func matches(_ manga: MangaInfo) -> Bool {
        let fields = [manga.title, manga.author].compactMap { $0?.searchNormalized }
        let tags = (manga.tags ?? []).map(\.searchNormalized)

        for term in terms {
            let normalizedTerm = term.value.searchNormalized
            guard !normalizedTerm.isEmpty else { continue }

            if term.isExcluded {
                if tags.contains(where: { $0.contains(normalizedTerm) }) {
                    return false
                }
            } else if !fields.contains(where: { $0.contains(normalizedTerm) }) {
                return false
            }
        }
        return true
    }

    static func parse(_ query: String) -> [Term] {
        var terms: [Term] = []
        var index = query.startIndex

        while index < query.endIndex {
            while index < query.endIndex, query[index].isWhitespace {
                index = query.index(after: index)
            }
            guard index < query.endIndex else { break }

            var isExcluded = false
            if query[index] == "-" {
                let nextIndex = query.index(after: index)
                if nextIndex < query.endIndex, !query[nextIndex].isWhitespace {
                    isExcluded = true
                    index = nextIndex
                }
            }

            var value = ""
            if index < query.endIndex, query[index] == "\"" {
                index = query.index(after: index)
                while index < query.endIndex, query[index] != "\"" {
                    value.append(query[index])
                    index = query.index(after: index)
                }
                if index < query.endIndex {
                    index = query.index(after: index)
                }
            } else {
                while index < query.endIndex, !query[index].isWhitespace {
                    value.append(query[index])
                    index = query.index(after: index)
                }
            }

            if !value.isEmpty {
                terms.append(Term(value: value, isExcluded: isExcluded))
            }
        }
        return terms
    }
}

private extension String {
    var searchNormalized: String {
        folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current).lowercased()
    }
}
