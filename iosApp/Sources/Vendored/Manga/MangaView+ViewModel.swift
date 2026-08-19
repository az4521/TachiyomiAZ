//
//  MangaView+ViewModel.swift
//  Aidoku
//
//  Created by Skitty on 4/29/25.
//

import ExtensionRunner
import Combine
import SwiftUI

extension MangaView {
    @MainActor
    class ViewModel: ObservableObject {
        @Published var source: ExtensionRunner.Source?

        @Published var manga: ExtensionRunner.Manga
        @Published var chapters: [ExtensionRunner.Chapter] = []
        @Published var otherDownloadedChapters: [ExtensionRunner.Chapter] = []

        @Published var readingHistory: [String: (page: Int, date: Int)] = [:]
        @Published var chapterBookmarks: Set<String> = []
        @Published var downloadProgress: [String: Float] = [:] // chapterId: progress
        @Published var downloadStatus: [String: DownloadStatus] = [:] // chapterId: status

        @Published var bookmarked = false

        @Published var nextChapter: ExtensionRunner.Chapter?
        @Published var readingInProgress = false
        @Published var allChaptersLocked = false
        @Published var allChaptersRead = false
        @Published var initialDataLoaded = false

        @Published var chapterSortOption: ChapterSortOption = .sourceOrder {
            didSet { resortChapters() }
        }
        @Published var chapterSortAscending = false {
            didSet { resortChapters() }
        }

        @Published var chapterFilters: [ChapterFilterOption] = [] {
            didSet { refilterChapters() }
        }
        @Published var chapterLangFilter: String? {
            didSet { refilterChapters() }
        }
        @Published var chapterScanlatorFilter: [String] = [] {
            didSet { refilterChapters() }
        }

        @Published var chapterTitleDisplayMode: ChapterTitleDisplayMode {
            didSet {
                guard bookmarked, oldValue != chapterTitleDisplayMode else { return }
                Task { await saveFilters() }
            }
        }

        @Published var error: Error?

        private var fetchedDetails = false
        private var markedOpened = false
        private var cancellables = Set<AnyCancellable>()

        init(source: ExtensionRunner.Source?, manga: ExtensionRunner.Manga) {
            self.source = source
            self.manga = manga

            self.chapterTitleDisplayMode = .init(
                flags: CoreDataManager.shared.getMangaChapterFilters(
                    sourceId: manga.sourceKey,
                    mangaId: manga.key
                ).flags
            )

            setupNotifications()
        }

        private func setupNotifications() {
            NotificationCenter.default.publisher(for: .updateMangaDetails)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] output in
                    Task { @MainActor in
                        guard
                            let self,
                            let manga = output.object as? ExtensionRunner.Manga,
                            manga.identifier == self.manga.identifier
                        else {
                            return
                        }
                        self.manga = manga
                    }
                }
                .store(in: &cancellables)

            for name in [
                Notification.Name.addToLibrary,
                Notification.Name.removeFromLibrary
            ] {
                NotificationCenter.default.publisher(for: name)
                    .receive(on: DispatchQueue.main)
                    .sink { [weak self] output in
                        guard
                            let self,
                            let manga = output.object as? ExtensionRunner.Manga,
                            manga.identifier == self.manga.identifier
                        else {
                            return
                        }
                        Task {
                            await self.loadBookmarked()
                        }
                    }
                    .store(in: &cancellables)
            }

            NotificationCenter.default.publisher(for: .migratedManga)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] output in
                    guard
                        let self,
                        let migration = output.object as? (from: ExtensionRunner.Manga, to: ExtensionRunner.Manga),
                        migration.from.identifier == self.manga.identifier,
                        let newSource = SourceManager.shared.source(for: migration.to.sourceKey)
                    else { return }
                    self.source = newSource
                    self.manga = migration.to
                }
                .store(in: &cancellables)

            for notification in [Notification.Name.sourceLoaded, Notification.Name.sourceUnloaded] {
                NotificationCenter.default.publisher(for: notification)
                    .receive(on: DispatchQueue.main)
                    .sink { [weak self] output in
                        guard
                            let self,
                            let sourceKey = output.object as? String,
                            self.manga.sourceKey == sourceKey
                        else {
                            return
                        }
                        self.source = SourceManager.shared.source(for: sourceKey)
                    }
                    .store(in: &cancellables)
            }

            // history
            NotificationCenter.default.publisher(for: .updateHistory)
                .sink { [weak self] _ in
                    guard let self else { return }
                    Task { @MainActor in
                        await self.loadHistory()
                        self.updateReadButton()
                    }
                }
                .store(in: &cancellables)

            NotificationCenter.default.publisher(for: .historyAdded)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] output in
                    guard
                        let self,
                        let chapters = output.object as? [ChapterIdentifier]
                    else { return }
                    let date = Int(Date().timeIntervalSince1970)
                    for chapter in chapters where chapter.mangaIdentifier == self.manga.identifier {
                        self.readingHistory[chapter.chapterKey] = (page: -1, date: date)
                    }
                    self.updateReadButton()
                    self.checkForAllReadMarkOpened()
                }
                .store(in: &cancellables)

            NotificationCenter.default.publisher(for: .historyRemoved)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] output in
                    guard let self else { return }
                    if let chapters = output.object as? [ChapterIdentifier] {
                        for chapter in chapters where chapter.mangaIdentifier == self.manga.identifier {
                            self.readingHistory.removeValue(forKey: chapter.chapterKey)
                        }
                    } else if
                        let identifier = output.object as? MangaIdentifier,
                        identifier == self.manga.identifier
                    {
                        self.readingHistory = [:]
                    }
                }
                .store(in: &cancellables)

            NotificationCenter.default.publisher(for: .historySet)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] output in
                    guard
                        let self,
                        let item = output.object as? (chapter: ChapterIdentifier, page: Int),
                        item.chapter.mangaIdentifier == self.manga.identifier,
                        self.readingHistory[item.chapter.chapterKey]?.page != -1
                    else {
                        return
                    }
                    self.readingHistory[item.chapter.chapterKey] = (
                        page: item.page,
                        date: Int(Date().timeIntervalSince1970)
                    )
                    self.updateReadButton()
                    self.checkForAllReadMarkOpened()
                }
                .store(in: &cancellables)

            NotificationCenter.default.publisher(for: .init("Library.resumeLastOpenedChapter"))
                .receive(on: DispatchQueue.main)
                .sink { [weak self] _ in
                    self?.updateReadButton()
                }
                .store(in: &cancellables)

            // tracking
            NotificationCenter.default.publisher(for: .syncTrackItem)
                .sink { [weak self] output in
                    guard let self, let item = output.object as? TrackItem else { return }
                    Task { @MainActor in
                        await self.checkTrackerSync(item: item)
                    }
                }
                .store(in: &cancellables)

            // downloads
            NotificationCenter.default.publisher(for: .downloadsQueued)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] output in
                    guard let self, let downloads = output.object as? [Download] else { return }
                    let chapters = downloads.compactMap {
                        if $0.mangaIdentifier == self.manga.identifier {
                            $0.chapter
                        } else {
                            nil
                        }
                    }
                    for chapter in chapters {
                        self.downloadStatus[chapter.key] = .queued
                        self.downloadProgress[chapter.key] = 0
                    }
                }
                .store(in: &cancellables)

            NotificationCenter.default.publisher(for: .downloadProgressed)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] output in
                    guard
                        let self,
                        let download = output.object as? Download,
                        download.mangaIdentifier == self.manga.identifier
                    else { return }
                    self.downloadStatus[download.chapterIdentifier.chapterKey] = .downloading
                    self.downloadProgress[download.chapterIdentifier.chapterKey] = Float(download.progress) / Float(download.total)
                }
                .store(in: &cancellables)

            for name in [
                Notification.Name.downloadFinished,
                Notification.Name.downloadRemoved,
                Notification.Name.downloadCancelled
            ] {
                NotificationCenter.default.publisher(for: name)
                    .receive(on: DispatchQueue.main)
                    .sink { [weak self] output in
                        self?.removeDownload(output)
                    }
                    .store(in: &cancellables)
            }

            for name in [
                Notification.Name.downloadsRemoved,
                Notification.Name.downloadsCancelled
            ] {
                NotificationCenter.default.publisher(for: name)
                    .receive(on: DispatchQueue.main)
                    .sink { [weak self] output in
                        self?.removeDownloads(output)
                    }
                    .store(in: &cancellables)
            }
        }
    }
}

extension MangaView.ViewModel {
    func refreshReadButtonState() {
        updateReadButton()
    }

    func markUpdatesViewed() async {
        if !UserDefaults.standard.bool(forKey: "General.incognitoMode") {
            await MangaUpdateManager.shared.viewAllUpdates(of: manga)
        }
    }

    private func checkForAllReadMarkOpened() {
        guard !markedOpened else { return }
        if getNextChapter() == .allRead {
            markedOpened = true
            Task {
                await CoreDataManager.shared.setOpened(sourceId: manga.sourceKey, mangaId: manga.key)
                NotificationCenter.default.post(name: .openedManga, object: manga.identifier)
            }
        }
    }

    // fetch complete info for manga, called when view appears
    func fetchDetails() async {
        guard !fetchedDetails else { return }
        fetchedDetails = true

        // A title becomes a known database record as soon as its details
        // screen is opened, even if the source request later fails.
        await CoreDataManager.shared.cacheMangaSummaries([manga])

        if let cachedManga = CoreDataManager.shared.getManga(sourceId: self.manga.sourceKey, mangaId: self.manga.key) {
            self.manga = self.manga.copy(from: cachedManga)
        }

        let filters = CoreDataManager.shared.getMangaChapterFilters(
            sourceId: manga.sourceKey,
            mangaId: manga.key
        )
        chapterSortOption = .init(flags: filters.flags)
        chapterSortAscending = ChapterFlagMask.sortAscending(filters.flags)
        chapterFilters = ChapterFilterOption.parseOptions(flags: filters.flags)
        chapterLangFilter = filters.language
        chapterScanlatorFilter = filters.scanlators ?? []

        await loadBookmarked()
        await loadHistory()
        await fetchData()
    }

    // fetches manga data, from coredata if in library or from source if not
    func fetchData() async {
        let sourceKey = manga.sourceKey
        let mangaId = manga.key
        let storedState = await CoreDataManager.shared.container.performBackgroundTask { @Sendable context in
            (
                inLibrary: CoreDataManager.shared.hasLibraryManga(
                    sourceId: sourceKey,
                    mangaId: mangaId,
                    context: context
                ),
                chapters: CoreDataManager.shared.getChapters(
                    sourceId: sourceKey,
                    mangaId: mangaId,
                    context: context
                ).map { $0.toNewChapter() }
            )
        }
        if storedState.inLibrary || !storedState.chapters.isEmpty {
            var newManga = self.manga
            newManga.chapters = storedState.chapters
            self.manga = newManga
            self.chapters = filteredChapters()
        }
        if storedState.inLibrary {
            // Library data is refreshed by the library updater or pull-to-refresh.
        } else if let source {
            // load new data from source
            await source.partialMangaPublisher?.sink { @Sendable newManga in
                Task { @MainActor in
                    self.manga = self.manga.copy(from: newManga)
                    self.chapters = self.filteredChapters()
                }
            }
            do {
                let newManga = try await source.getMangaUpdate(
                    manga: manga,
                    needsDetails: true,
                    needsChapters: true
                )
                manga = newManga
                chapters = filteredChapters()
                await CoreDataManager.shared.cacheMangaDetails(
                    newManga,
                    includeChapters: true
                )
            } catch {
                withAnimation {
                    if storedState.chapters.isEmpty {
                        self.manga.chapters = []
                        self.chapters = []
                    }
                    self.error = error
                }
            }
            await source.partialMangaPublisher?.removeSink()
        }
        await fetchDownloadedChapters()
        await loadDownloadStatus()
        await loadChapterBookmarks()
        updateReadButton()
        initialDataLoaded = true
    }

    func fetchDownloadedChapters() async {
        let knownChapterDirectories = Set(
            (manga.chapters ?? chapters).map { $0.key.directoryName }
        )
        let downloadedChapters = await DownloadManager.shared.getDownloadedChapters(for: manga.identifier)
            .filter { chapter in
                !knownChapterDirectories.contains(chapter.chapterId.directoryName)
            }
            .map { $0.toChapter() }
            .sorted { (lhs: ExtensionRunner.Chapter, rhs: ExtensionRunner.Chapter) in
                // Primary sort: by chapter number if both have it
                if let lhsChapter = lhs.chapterNumber, let rhsChapter = rhs.chapterNumber {
                    if lhsChapter != rhsChapter {
                        return lhsChapter > rhsChapter
                    }
                    // If chapter numbers are equal, sort by volume number
                    if let lhsVolume = lhs.volumeNumber, let rhsVolume = rhs.volumeNumber {
                        return lhsVolume > rhsVolume
                    }
                }

                // Secondary sort: by volume number if only one has chapter number
                if let lhsVolume = lhs.volumeNumber, let rhsVolume = rhs.volumeNumber {
                    return lhsVolume > rhsVolume
                }

                // Final fallback: alphabetical comparison of display titles
                let lhsTitle = lhs.title?.lowercased() ?? ""
                let rhsTitle = rhs.title?.lowercased() ?? ""
                return lhsTitle.localizedStandardCompare(rhsTitle) == .orderedDescending
            }
        withAnimation {
            otherDownloadedChapters = downloadedChapters
        }
    }

    func syncTrackerProgress() async {
        // sync progress from page trackers
        await TrackerManager.shared.syncPageTrackerHistory(
            manga: manga,
            chapters: chapters
        )

        // sync progress from regular trackers if auto sync enabled
        if UserDefaults.standard.bool(forKey: "Tracking.autoSyncFromTracker") {
            let trackItems: [TrackItem] = await CoreDataManager.shared.container.performBackgroundTask { @Sendable [manga] context in
                CoreDataManager.shared.getTracks(
                    sourceId: manga.sourceKey,
                    mangaId: manga.key,
                    context: context
                ).map { $0.toItem() }
            }
            for trackItem in trackItems {
                guard let tracker = TrackerManager.getTracker(id: trackItem.trackerId) else { continue }
                await TrackerManager.shared.syncProgressFromTracker(
                    tracker: tracker,
                    trackItem: trackItem,
                    manga: manga,
                    chapters: chapters
                )
            }
        }
    }

    // refresh manga and chapter data from source, updating db
    func refresh() async {
        guard Reachability.getConnectionType() != .none, let source else {
            return
        }

        let sourceKey = source.key
        let mangaKey = manga.key

        let inLibrary = await CoreDataManager.shared.container.performBackgroundTask { @Sendable context in
            CoreDataManager.shared.hasLibraryManga(sourceId: sourceKey, mangaId: mangaKey, context: context)
        }

        do {
            let oldManga = self.manga
            var newManga = try await source.getMangaUpdate(
                manga: oldManga,
                needsDetails: true,
                needsChapters: true
            )

            // update manga in db
            if inLibrary {
                let resultManga: ExtensionRunner.Manga? =
                    await CoreDataManager.shared.container.performBackgroundTask { [
                        newManga,
                        chapterLangFilter,
                        chapterScanlatorFilter
                    ] context -> ExtensionRunner.Manga? in
                        guard
                            let libraryObject = CoreDataManager.shared.getLibraryManga(
                                sourceId: sourceKey,
                                mangaId: mangaKey,
                                context: context
                            ),
                            let mangaObject = libraryObject.manga
                        else {
                            return nil
                        }

                        // update details
                        mangaObject.load(from: newManga)

                        if let chapters = newManga.chapters {
                            let newChapters = CoreDataManager.shared.setChapters(
                                chapters,
                                sourceId: sourceKey,
                                mangaId: mangaKey,
                                context: context
                            )
                            if !newChapters.isEmpty {
                                // add manga updates
                                for chapter in newChapters
                                where
                                    chapterLangFilter != nil ? chapter.lang == chapterLangFilter : true
                                    && !chapterScanlatorFilter.isEmpty ? chapterScanlatorFilter.contains(chapter.scanlator ?? "") : true
                                {
                                    CoreDataManager.shared.createMangaUpdate(
                                        sourceId: sourceKey,
                                        mangaId: mangaKey,
                                        chapterObject: chapter,
                                        context: context
                                    )
                                }
                                libraryObject.lastChapter = chapters.compactMap { $0.dateUploaded }.max()
                                libraryObject.lastUpdatedChapters = Date.now
                            }
                        }

                        let now = Date.now
                        libraryObject.lastUpdated = now

                        if !UserDefaults.standard.bool(forKey: "General.incognitoMode") {
                            libraryObject.lastOpened = now.addingTimeInterval(1) // ensure item isn't re-pinned, since it's already open
                        }

                        try? context.save()

                        return mangaObject.toNewManga()
                    }

                if newManga.chapters != nil {
                    await markUpdatesViewed()
                }

                if var resultManga {
                    resultManga.chapters = newManga.chapters
                    newManga = resultManga
                }

                NotificationCenter.default.post(name: .updateManga, object: newManga.identifier)
            } else {
                await CoreDataManager.shared.cacheMangaDetails(
                    newManga,
                    includeChapters: true
                )
            }

            await loadHistory()

            manga = newManga
            chapters = filteredChapters()

            // ensure downloaded chapters are in the correct section if they were added/removed from the main list
            await fetchDownloadedChapters()

            // sync history with tracker
            await syncTrackerProgress()
        } catch {
            withAnimation {
                // Keep the last persisted details and chapter list visible if
                // a refresh fails or the source is temporarily unavailable.
                self.error = error
            }
        }

        updateReadButton()
    }

    private func loadDownloadStatus() async {
        var statuses = await DownloadManager.shared.getDownloadStatuses(
            for: manga.identifier,
            chapterKeys: chapters.map(\.key)
        )
        for chapter in otherDownloadedChapters {
            statuses[chapter.key] = .finished
        }
        downloadStatus = statuses
    }

    private func loadBookmarked() async {
        let sourceKey = manga.sourceKey
        let mangaId = manga.key
        let inLibrary = await CoreDataManager.shared.container.performBackgroundTask { @Sendable context in
            CoreDataManager.shared.hasLibraryManga(
                sourceId: sourceKey,
                mangaId: mangaId,
                context: context
            )
        }
        bookmarked = inLibrary
    }

    private func loadHistory() async {
        readingHistory = await CoreDataManager.shared.getReadingHistory(
            sourceId: manga.sourceKey,
            mangaId: manga.key
        )
    }

    private func loadChapterBookmarks() async {
        let sourceKey = manga.sourceKey
        let mangaKey = manga.key
        chapterBookmarks = await CoreDataManager.shared.container.performBackgroundTask {
            @Sendable context in
            Set(
                CoreDataManager.shared.getChapters(
                    sourceId: sourceKey,
                    mangaId: mangaKey,
                    context: context
                )
                .filter(\.bookmarked)
                .map(\.id)
            )
        }
    }

    private func checkTrackerSync(item: TrackItem) async {
        guard let tracker = TrackerManager.getTracker(id: item.trackerId) else { return }

        if tracker is PageTracker {
            await TrackerManager.shared.syncPageTrackerHistory(
                tracker: tracker,
                manga: self.manga,
                chapters: self.chapters
            )
            return
        }

        let chaptersToMark = await TrackerManager.shared.getChaptersToSyncProgressFromTracker(
            tracker: tracker,
            trackItem: item,
            manga: self.manga,
            chapters: self.chapters
        ).filter {
            readingHistory[$0.key]?.page != -1 // filter out chapters already marked read
        }

        if !chaptersToMark.isEmpty {
            let alert = UIAlertController(
                title: NSLocalizedString("SYNC_WITH_TRACKER"),
                message: chaptersToMark.count == 1
                    ? NSLocalizedString("SYNC_WITH_TRACKER_INFO_1")
                    : String(format: NSLocalizedString("SYNC_WITH_TRACKER_INFO_%i"), chaptersToMark.count),
                preferredStyle: .alert
            )

            alert.addAction(UIAlertAction(title: NSLocalizedString("CANCEL"), style: .cancel) { _ in })

            alert.addAction(UIAlertAction(title: NSLocalizedString("OK"), style: .default) { [weak self] _ in
                guard let self else { return }
                Task {
                    await HistoryManager.shared.addHistory(
                        sourceId: self.manga.sourceKey,
                        mangaId: self.manga.key,
                        chapters: chaptersToMark,
                        skipTracker: tracker
                    )
                }
            })

            (UIApplication.shared.delegate as? AppDelegate)?.visibleViewController?.present(alert, animated: true)
        }
    }
}

extension MangaView.ViewModel {
    func setBookmarked(
        _ bookmarked: Bool,
        chapter: ExtensionRunner.Chapter
    ) async {
        let sourceKey = manga.sourceKey
        let mangaKey = manga.key
        let changed = await CoreDataManager.shared.container.performBackgroundTask {
            @Sendable context in
            guard let object = CoreDataManager.shared.getChapter(
                sourceId: sourceKey,
                mangaId: mangaKey,
                chapterId: chapter.key,
                context: context
            ) else {
                return false
            }
            object.bookmarked = bookmarked
            do {
                try context.save()
                return true
            } catch {
                return false
            }
        }
        guard changed else { return }
        if bookmarked {
            chapterBookmarks.insert(chapter.key)
        } else {
            chapterBookmarks.remove(chapter.key)
        }
    }

    // mark given chapters as read in coredata
    func markRead(chapters: [ExtensionRunner.Chapter]) async {
        // only mark chapters that are readable as read
        let chapters = chapters.filter { !$0.locked || downloadStatus[$0.key] == .finished }

        await HistoryManager.shared.addHistory(
            sourceId: manga.sourceKey,
            mangaId: manga.key,
            chapters: chapters
        )
        let date = Int(Date().timeIntervalSince1970)
        for chapter in chapters {
            readingHistory[chapter.key] = (page: -1, date: date)
        }
        updateReadButton()
    }

    // remove coredata history for given chapters
    func markUnread(chapters: [ExtensionRunner.Chapter]) async {
        await HistoryManager.shared.removeHistory(
            sourceId: manga.sourceKey,
            mangaId: manga.key,
            chapterIds: chapters.map { $0.key }
        )
        for chapter in chapters {
            readingHistory[chapter.key] = nil
        }
        updateReadButton()
    }

    private func resortChapters() {
        withAnimation {
            chapters = sortedChapters()
        }
        if bookmarked {
            Task {
                await saveFilters()
            }
        }
    }

    private func refilterChapters() {
        withAnimation {
            chapters = filteredChapters()
        }
        updateReadButton()
        if bookmarked {
            Task {
                await saveFilters()
            }
        }
    }

    private func removeDownload(_ notification: Notification) {
        var chapter: ChapterIdentifier?
        if let identifier = notification.object as? ChapterIdentifier {
            chapter = identifier
        } else if let download = notification.object as? Download {
            chapter = download.chapterIdentifier
        }
        if let chapter {
            downloadProgress.removeValue(forKey: chapter.chapterKey)
            downloadStatus[chapter.chapterKey] = DownloadManager.shared.getDownloadStatus(for: chapter)
            if let chapterIndex = otherDownloadedChapters.firstIndex(where: { $0.key == chapter.chapterKey }) {
                withAnimation {
                    _ = otherDownloadedChapters.remove(at: chapterIndex)
                }
            }
        }
    }

    private func removeDownloads(_ notification: Notification) {
        if let chapters = notification.object as? [ChapterIdentifier] {
            for chapter in chapters {
                downloadProgress.removeValue(forKey: chapter.chapterKey)
                downloadStatus[chapter.chapterKey] = DownloadStatus.none
            }
        } else if
            let manga = notification.object as? MangaIdentifier,
            manga == self.manga.identifier
        { // all chapters
            downloadProgress = [:]
            downloadStatus = [:]
        }
    }

    private func sortedChapters() -> [ExtensionRunner.Chapter] {
        guard let chapters = manga.chapters, !chapters.isEmpty else {
            return []
        }
        return switch chapterSortOption {
            case .sourceOrder:
                chapterSortAscending ? chapters.reversed() : chapters
            case .chapter:
                if chapterSortAscending {
                    chapters.sorted(by: { $0.chapterNumber ?? -1 < $1.chapterNumber ?? -1 })
                } else {
                    chapters.sorted(by: { $0.chapterNumber ?? -1 > $1.chapterNumber ?? -1 })
                }
            case .uploadDate:
                if chapterSortAscending {
                    chapters.sorted(by: { $0.dateUploaded ?? .distantPast < $1.dateUploaded ?? .distantPast })
                } else {
                    chapters.sorted(by: { $0.dateUploaded ?? .distantPast > $1.dateUploaded ?? .distantPast })
                }
        }
    }

    private func filteredChapters() -> [ExtensionRunner.Chapter] {
        var chapters = sortedChapters()

        // filter by language and scanlators
        if chapterLangFilter != nil || !chapterScanlatorFilter.isEmpty {
            chapters = chapters.filter { chapter in
                let cond1 = if let chapterLangFilter {
                    chapter.language == chapterLangFilter
                } else {
                    true
                }
                let cond2 = if !chapterScanlatorFilter.isEmpty  {
                    if let chapterScanlators = chapter.scanlators, !chapterScanlators.isEmpty {
                        chapterScanlatorFilter.contains(where: chapterScanlators.contains)
                    } else {
                        chapterScanlatorFilter.contains("")
                    }
                } else {
                    true
                }
                return cond1 && cond2
            }
        }

        for filter in chapterFilters {
            switch filter.type {
                case .downloaded:
                    chapters = chapters.filter {
                        let downloaded = !DownloadManager.shared.isChapterDownloaded(
                            chapter: .init(sourceKey: manga.sourceKey, mangaKey: manga.key, chapterKey: $0.key)
                        )
                        return filter.exclude ? downloaded : !downloaded
                    }
                case .unread:
                    chapters = chapters.filter {
                        let isCompleted = self.readingHistory[$0.id]?.0 == -1
                        return filter.exclude ? isCompleted : !isCompleted
                    }
                case .locked:
                    chapters = chapters.filter {
                        filter.exclude ? !$0.locked : $0.locked
                    }
            }
        }

        return chapters
    }

    enum ChapterResult: Equatable {
        case none
        case allRead
        case allLocked
        case chapter(ExtensionRunner.Chapter)
    }

    private func getNextChapter() -> ChapterResult {
        guard !chapters.isEmpty else { return .none }

        let chapter = MangaManager.shared.getNextChapter(
            manga: manga,
            chapters: chapters,
            readingHistory: readingHistory,
            sortAscending: chapterSortAscending,
            downloadStatuses: downloadStatus
        )

        if let chapter {
            return .chapter(chapter)
        }
        if !chapters.contains(where: { !$0.locked }) {
            return .allLocked
        }
        return .allRead
    }

    private func updateReadButton() {
        let nextChapter = getNextChapter()
        switch nextChapter {
            case .none:
                return
            case .allRead:
                allChaptersRead = true
                allChaptersLocked = false
            case .allLocked:
                allChaptersLocked = true
            case .chapter(let nextChapter):
                allChaptersRead = false
                allChaptersLocked = false
                readingInProgress = readingHistory[nextChapter.id]?.date ?? 0 > 0
                self.nextChapter = nextChapter
        }
    }

    /// Packs the screen's sort and filters back into `mangas.chapter_flags`.
    ///
    /// Starts from the stored value rather than zero, so parts of the column this screen does not
    /// offer -- Android's bookmarked filter, and its chapter-title display mode -- survive being
    /// rewritten here. The old version built the value from scratch and erased them.
    private func generateChapterFlags() -> Int {
        // Read the stored value first: this screen does not offer every part of the column.
        var flags = CoreDataManager.shared.getMangaChapterFilters(
            sourceId: manga.sourceKey,
            mangaId: manga.key
        ).flags
        flags = ChapterFlagMask.withSortAscending(flags, chapterSortAscending)
        flags = chapterSortOption.apply(to: flags)
        flags = ChapterFilterOption.apply(chapterFilters, to: flags)
        flags = chapterTitleDisplayMode.apply(to: flags)
        return flags
    }

    private func saveFilters() async {
        // The language and scanlator filters have no column, so only the flags are persisted --
        // which is what the facade wrote even when a whole model was handed to it.
        await CoreDataManager.shared.updateMangaDetails(
            manga: manga,
            chapterFlags: generateChapterFlags()
        )
    }
}
