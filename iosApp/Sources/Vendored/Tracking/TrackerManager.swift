//
//  TrackerManager.swift
//  Aidoku
//
//  Created by Skitty on 6/14/22.
//

import ExtensionRunner
import CoreData
import Foundation

/// An interface to interact with title tracking services.
actor TrackerManager {
    /// The shared tracker mangaer instance.
    static let shared = TrackerManager()

    /// An instance of the AniList tracker.
    static let anilist = AniListTracker()
    /// An instance of the MyAnimeList tracker.
    static let myanimelist = MyAnimeListTracker()
    /// An instance of the MangaBaka tracker.
    static let mangabaka = MangaBakaTracker()
    /// An instance of the Shikimori tracker.
    static let shikimori = ShikimoriTracker()
    /// An instance of the Bangumi tracker.
    static let bangumi = BangumiTracker()
    /// An instance of the Kitsu tracker.
    static let kitsu = KitsuTracker()
    /// An instance of the MangaUpdates tracker.
    static let mangaupdates = MangaUpdatesTracker()
    /// An instance of the Hikka tracker.
    static let hikka = HikkaTracker()
    /// An instance of the Komga tracker.
    static let komga = KomgaTracker()
    /// An instance of the Kavita tracker.
    static let kavita = KavitaTracker()
    /// An instance of the Suwayomi tracker.
    static let suwayomi = SuwayomiTracker()

    /// An array of the available trackers.
    ///
    /// Ordered as the Android app lists them. Kitsu, MangaUpdates and Hikka have no client in
    /// tachiyomiazios, so those three are written for this port against the same endpoints Android
    /// uses -- see Sources/Data/Trackers.
    ///
    /// Komga, Kavita and Suwayomi are the enhanced ones: they track against the same server the
    /// manga came from rather than a public service, so they are never "logged in" and are offered
    /// only for titles from that server. `EnhancedSourceBridge` supplies the connection details
    /// from the JVM extension's settings, and `EnhancedTrackerModels` carries the few response
    /// shapes they decode.
    static let trackers: [Tracker] = [
        myanimelist, anilist, kitsu, shikimori, bangumi,
        mangaupdates, hikka, mangabaka,
        komga, kavita, suwayomi
    ]

    /// A boolean indicating if there is a tracker that is currently logged in.
    static var hasAvailableTrackers: Bool {
        Self.trackers.filter { !($0 is EnhancedTracker) }.contains { $0.isLoggedIn }
    }

    /// Get the instance of the tracker with the specified id.
    static func getTracker(id: String) -> Tracker? {
        Self.trackers.first { $0.id == id }
    }

    struct TrackingState: Codable {
        var pendingPageUpdates: [PageTrackUpdate] = []
    }
    private var trackingState: TrackingState
    private var pageUpdateTask: Task<(), Never>?

    init() {
        self.trackingState = UserDefaults.standard.data(forKey: "Tracker.pageTrackingState")
            .flatMap { try? JSONDecoder().decode(TrackingState.self, from: $0) } ?? .init()
    }

    /// Send chapter read update to logged in trackers.
    /// - Parameter manga: which title the chapter belongs to. The runner model does not carry its
    ///   manga the way the Aidoku-shaped one did, so the caller passes it.
    func setCompleted(
        chapter: ExtensionRunner.Chapter,
        manga: MangaIdentifier,
        skipTracker: Tracker? = nil
    ) async {
        let chapterNum = chapter.chapterNumber
        let volumeNum = chapter.volumeNumber.flatMap { Int(floor($0)) }
        guard chapterNum != nil || volumeNum != nil else { return }

        let sourceId = manga.sourceKey
        let mangaId = manga.mangaKey
        let uniqueKey = "\(sourceId).\(mangaId)"
        let displayMode = ChapterTitleDisplayMode(
            flags: CoreDataManager.shared.getMangaChapterFilters(
                sourceId: sourceId,
                mangaId: mangaId
            ).flags
        )
        let trackItems: [TrackItem] = await CoreDataManager.shared.container.performBackgroundTask { @Sendable context in
            CoreDataManager.shared.getTracks(
                sourceId: sourceId,
                mangaId: mangaId,
                context: context
            ).map { $0.toItem() }
        }

        for item in trackItems {
            guard
                skipTracker?.id != item.trackerId,
                let tracker = Self.getTracker(id: item.trackerId),
                !(tracker is PageTracker),
                let state = try? await tracker.getState(trackId: item.id)
            else { continue }

            // Persist what the service reported. These columns were never written on this side,
            // so a title tracked here showed on Android as tracked with no status or progress.
            CoreDataManager.shared.setTrackState(
                trackerId: item.trackerId,
                sourceId: sourceId,
                mangaId: mangaId,
                state: state
            )

            // Check if we need to update based on chapter display mode
            var shouldUpdate = false
            if displayMode == .chapter {
                // Chapter mode: check chapter progress, or volume progress if no chapter
                let hasChapterProgress = chapterNum != nil && (state.lastReadChapter ?? 0) < chapterNum!
                let hasVolumeProgress = volumeNum != nil && (state.lastReadChapter ?? 0) < Float(volumeNum!)
                shouldUpdate = hasChapterProgress || hasVolumeProgress
            } else {
                // Default mode: check both chapter and volume progress
                let hasChapterProgress = chapterNum != nil && (state.lastReadChapter ?? 0) < chapterNum!
                let hasVolumeProgress = volumeNum != nil && (state.lastReadVolume ?? 0) < volumeNum!
                shouldUpdate = hasChapterProgress || hasVolumeProgress
            }
            guard shouldUpdate else { continue }

            var update = TrackUpdate()

            // update last read chapter and volume based on mode
            if displayMode == .chapter {
                // chapter mode: only update chapter, don't update volume
                if let chapterNum, chapterNum > 0 {
                    let newChapterNumber = Self.applyChapterOffset(
                        to: chapterNum,
                        offset: item.chapterOffset,
                        maxChapters: state.totalChapters
                    )
                    if state.lastReadChapter ?? 0 < newChapterNumber {
                        update.lastReadChapter = newChapterNumber
                    }
                } else if let volumeNum {
                    // no chapter metadata, use volume number as chapter
                    let chapterFromVolume = Self.applyChapterOffset(
                        to: Float(volumeNum),
                        offset: item.chapterOffset,
                        maxChapters: state.totalChapters
                    )
                    if chapterFromVolume > state.lastReadChapter ?? 0 {
                        update.lastReadChapter = chapterFromVolume
                    }
                }
            } else {
                // default mode: update both chapter and volume if available
                if let chapterNum, chapterNum > 0 {
                    let newChapterNumber = Self.applyChapterOffset(
                        to: chapterNum,
                        offset: item.chapterOffset,
                        maxChapters: state.totalChapters
                    )
                    if state.lastReadChapter ?? 0 < newChapterNumber {
                        update.lastReadChapter = newChapterNumber
                    }
                }
                if let volumeNum, volumeNum > 0 && state.lastReadVolume ?? 0 < volumeNum {
                    update.lastReadVolume = volumeNum
                }
            }

            // update reading state
            let readLastChapter = if
                chapterNum != nil,
                let totalChapters = state.totalChapters,
                let lastReadChapter = state.lastReadChapter
            {
                totalChapters == Int(floor(lastReadChapter))
            } else if chapterNum == nil && update.lastReadVolume != nil {
                update.lastReadVolume == state.totalVolumes
            } else {
                false
            }
            if readLastChapter {
                if state.finishReadDate == nil {
                    update.finishReadDate = Date()
                }
                update.status = .completed
            } else if state.status != .reading && state.status != .rereading {
                // if there's no start date, and the status is planning or null, set it to current date
                if state.startReadDate == nil && state.status == nil || state.status == .planning {
                    update.startReadDate = Date()
                }
                update.status = state.status == .completed ? .rereading : .reading
            }

            do {
                try await tracker.update(trackId: item.id, update: update)
            } catch {
                LogManager.logger.error("Failed to set tracker chapter as completed (\(tracker.id)): \(error)")
            }
        }
    }

    /// Set the page progress for trackers that support it.
    func setProgress(sourceKey: String, mangaKey: String, chapter: ExtensionRunner.Chapter, progress: ChapterReadProgress) async {
        await setProgress(sourceKey: sourceKey, mangaKey: mangaKey, chapters: [chapter], progress: progress)
    }

    func setProgress(sourceKey: String, mangaKey: String, chapters: [ExtensionRunner.Chapter], progress: ChapterReadProgress) async {
        let trackItems: [TrackItem] = await CoreDataManager.shared.container.performBackgroundTask { context in
            CoreDataManager.shared.getTracks(
                sourceId: sourceKey,
                mangaId: mangaKey,
                context: context
            ).map { $0.toItem() }
        }

        var newUpdates: [PageTrackUpdate] = []

        for item in trackItems {
            guard let tracker = Self.getTracker(id: item.trackerId) as? PageTracker else {
                continue
            }
            for chapter in chapters {
                newUpdates.append(.init(
                    trackerId: tracker.id,
                    trackId: item.id,
                    chapter: chapter,
                    progress: progress
                ))
            }
        }

        queuePageUpdates(newUpdates)
        await processPendingUpdates()
    }

    /// Register a new track item to a manga and save to the data store.
    func register(tracker: Tracker, manga: ExtensionRunner.Manga, item: TrackSearchItem) async {
        let (highestReadNumber, earliestReadDate) = await CoreDataManager.shared.container.performBackgroundTask { context in
            (
                CoreDataManager.shared.getHighestReadNumber(
                    sourceId: manga.sourceKey,
                    mangaId: manga.key,
                    context: context
                ),
                CoreDataManager.shared.getEarliestReadDate(
                    sourceId: manga.sourceKey,
                    mangaId: manga.key,
                    context: context
                )
            )
        }
        do {
            let id = try await tracker.register(
                trackId: item.id,
                highestChapterRead: highestReadNumber,
                earliestReadDate: earliestReadDate
            )
            let trackItem = TrackItem(
                id: id ?? item.id,
                trackerId: tracker.id,
                sourceId: manga.sourceKey,
                mangaId: manga.key,
                title: item.title ?? manga.title,
                chapterOffset: 0
            )
            await TrackerManager.shared.saveTrackItem(item: trackItem)

            // Sync progress from tracker if enabled or is enhanced tracker
            if UserDefaults.standard.bool(forKey: "Tracking.autoSyncFromTracker") || (tracker is EnhancedTracker) || (tracker is PageTracker) {
                await syncProgressFromTracker(tracker: tracker, trackItem: trackItem, manga: manga)
            } else {
                NotificationCenter.default.post(name: .syncTrackItem, object: trackItem)
            }
        } catch {
            LogManager.logger.error("Failed to register tracker \(tracker.id): \(error)")
        }
    }

    /// Saves a TrackItem to the data store.
    private func saveTrackItem(item: TrackItem) async {
        await CoreDataManager.shared.container.performBackgroundTask { @Sendable context in
            CoreDataManager.shared.createTrack(
                id: item.id,
                trackerId: item.trackerId,
                sourceId: item.sourceId,
                mangaId: item.mangaId,
                title: item.title,
                chapterOffset: item.chapterOffset,
                context: context
            )
            do {
                try context.save()
            } catch {
                LogManager.logger.error("TrackManager.saveTrackItem(item:): \(error)")
            }
        }
        NotificationCenter.default.post(name: .updateTrackers, object: nil)
        NotificationCenter.default.post(name: .trackItemAdded, object: item)
    }

    /// Removes the TrackItem from the data store.
    func removeTrackItem(item: TrackItem) async {
        await CoreDataManager.shared.container.performBackgroundTask { @Sendable context in
            self.removeTrackItem(item: item, context: context)
        }
    }

    /// Sets the chapter offset for a track item.
    func setTrackChapterOffset(item: TrackItem, chapterOffset: Int) async {
        await CoreDataManager.shared.container.performBackgroundTask { context in
            CoreDataManager.shared.setTrackChapterOffset(
                trackerId: item.trackerId,
                sourceId: item.sourceId,
                mangaId: item.mangaId,
                chapterOffset: chapterOffset,
                context: context
            )
            do {
                try context.save()
            } catch {
                LogManager.logger.error("TrackManager.setTrackChapterOffset(item:chapterOffset:): \(error)")
            }
        }
        NotificationCenter.default.post(name: .updateTrackers, object: nil)
    }

    nonisolated func removeTrackItem(item: TrackItem, context: DatabaseContainer.Context) {
        CoreDataManager.shared.removeTrack(
            trackerId: item.trackerId,
            sourceId: item.sourceId,
            mangaId: item.mangaId,
            context: context
        )
        do {
            try context.save()
        } catch {
            LogManager.logger.error("TrackManager.removeTrackItem(item:): \(error)")
        }
        NotificationCenter.default.post(name: .updateTrackers, object: nil)
    }

    /// Checks if a manga is being tracked
    @MainActor
    func isTracking(sourceId: String, mangaId: String) -> Bool {
        CoreDataManager.shared.hasTrack(sourceId: sourceId, mangaId: mangaId)
    }

    /// Checks if there is a tracker that can be added to the given manga.
    func hasAvailableTrackers(sourceKey: String, mangaKey: String) async -> Bool {
        for tracker in Self.trackers {
            let canRegister = tracker.canRegister(sourceKey: sourceKey, mangaKey: mangaKey)
            if canRegister {
                return true
            }
        }
        return false
    }

    /// Sync progress from tracker to local history.
    func syncProgressFromTracker(
        tracker: Tracker,
        trackItem: TrackItem,
        manga: ExtensionRunner.Manga,
        chapters: [ExtensionRunner.Chapter]? = nil
    ) async {
        if tracker is PageTracker {
            await syncPageTrackerHistory(
                tracker: tracker,
                manga: manga,
                chapters: chapters
            )
        } else {
            let chaptersToMark = await getChaptersToSyncProgressFromTracker(
                tracker: tracker,
                trackItem: trackItem,
                manga: manga,
                chapters: chapters
            )
            if !chaptersToMark.isEmpty {
                await HistoryManager.shared.addHistory(
                    sourceId: manga.sourceKey,
                    mangaId: manga.key,
                    chapters: chaptersToMark,
                    skipTracker: tracker
                )
            }
        }
    }

    /// Sync progress with all linked trackers that support page progress.
    func syncPageTrackerHistory(
        tracker: Tracker? = nil,
        manga: ExtensionRunner.Manga,
        chapters: [ExtensionRunner.Chapter]? = nil
    ) async {
        let chapters = if let chapters {
            chapters
        } else {
            await getChapters(manga: manga)
        }
        guard !chapters.isEmpty else { return }

        // fetch remote history from linked page trackers
        var result: [String: ChapterReadProgress] = [:]

        let trackItems: [TrackItem] = await CoreDataManager.shared.container.performBackgroundTask { context in
            CoreDataManager.shared.getTracks(
                sourceId: manga.sourceKey,
                mangaId: manga.key,
                context: context
            ).map { $0.toItem() }
        }

        for item in trackItems {
            guard let targetTracker = Self.getTracker(id: item.trackerId) as? PageTracker else { continue }
            if let tracker, targetTracker.id != tracker.id {
                continue // if a specific tracker is provided, only sync that one
            }
            do {
                let batchProgress = try await targetTracker.getProgress(trackId: item.id, chapters: chapters)
                if result.isEmpty {
                    result = batchProgress
                } else {
                    // merge with the existing progress map, keeping newest
                    for (chapterKey, progress) in batchProgress {
                        if let existing = result[chapterKey] {
                            // replace if the new date is higher, otherwise keep existing
                            if let existingDate = existing.date, let newDate = progress.date {
                                if newDate > existingDate {
                                    result[chapterKey] = progress
                                }
                            }
                        } else {
                            result[chapterKey] = progress
                        }
                    }
                }
            } catch {
                LogManager.logger.error("Failed to get tracker progress (\(targetTracker.id)): \(error)")
            }
        }

        guard !result.isEmpty else { return }

        // create local history
        let (completed, progressed) = await CoreDataManager.shared.container.performBackgroundTask { [result] context in
            var completed: [String] = []
            var progressed: [String: Int] = [:]

            var lastRead = Date.distantPast

            for (chapterKey, progress) in result {
                let existingHistory = CoreDataManager.shared.getHistory(
                    sourceId: manga.sourceKey,
                    mangaId: manga.key,
                    chapterId: chapterKey,
                    context: context
                )
                if let existingDate = existingHistory?.dateRead, let newDate = progress.date, newDate <= existingDate {
                    // don't update if the existing history is newer than the tracker history
                    continue
                }
                // mark chapters as read
                if progress.completed {
                    if !(existingHistory?.completed ?? false) {
                        completed.append(chapterKey)
                        let readDate = progress.date ?? Date.now
                        lastRead = readDate > lastRead ? readDate : lastRead
                        CoreDataManager.shared.setCompleted(
                            sourceId: manga.sourceKey,
                            mangaId: manga.key,
                            chapterIds: [chapterKey],
                            date: progress.date ?? Date(),
                            context: context
                        )
                    }
                } else if progress.page != 0 {
                    progressed[chapterKey] = progress.page
                    let readDate = progress.date ?? Date.now
                    lastRead = readDate > lastRead ? readDate : lastRead
                    CoreDataManager.shared.setProgress(
                        progress.page,
                        sourceId: manga.sourceKey,
                        mangaId: manga.key,
                        chapterId: chapterKey,
                        dateRead: readDate,
                        completed: false,
                        context: context
                    )
                }
            }

            // mark manga as read only if history was updated
            if !completed.isEmpty || !progressed.isEmpty {
                CoreDataManager.shared.setRead(
                    sourceId: manga.sourceKey,
                    mangaId: manga.key,
                    date: lastRead,
                    context: context
                )
            }

            try? context.save()

            return (completed, progressed)
        }

        // post notifications to update ui
        if !completed.isEmpty {
            NotificationCenter.default.post(
                name: .historyAdded,
                object: completed.map {
                    Chapter(
                        sourceId: manga.sourceKey,
                        id: $0,
                        mangaId: manga.key,
                        title: "",
                        sourceOrder: -1
                    )
                }
            )
        }
        for (chapterKey, page) in progressed {
            NotificationCenter.default.post(
                name: .historySet,
                object: (
                    Chapter(
                        sourceId: manga.sourceKey,
                        id: chapterKey,
                        mangaId: manga.key,
                        title: "",
                        sourceOrder: -1
                    ),
                    page
                )
            )
        }
    }

    /// Add all applicable enhanced trackers to a given manga.
    func bindEnhancedTrackers(manga: ExtensionRunner.Manga) async {
        for tracker in Self.trackers where tracker is EnhancedTracker {
            if tracker.canRegister(sourceKey: manga.sourceKey, mangaKey: manga.key) {
                do {
                    let items = try await tracker.search(for: manga, includeNsfw: true)
                    guard let item = items.first else {
                        LogManager.logger.error("Unable to find track item from tracker \(tracker.id)")
                        return
                    }
                    await TrackerManager.shared.register(tracker: tracker, manga: manga, item: item)
                } catch {
                    LogManager.logger.error("Unable to find track item from tracker \(tracker.id): \(error)")
                }
            }
        }
    }
}

extension TrackerManager {
    static func applyChapterOffset(to chapter: Float, offset: Int, maxChapters: Int?) -> Float {
        var adjusted = chapter + Float(offset)
        adjusted = max(0, adjusted)
        if let maxChapters {
            adjusted = min(adjusted, Float(maxChapters))
        }
        return adjusted
    }
}

extension TrackerManager {
    private func getChapters(manga: ExtensionRunner.Manga) async -> [ExtensionRunner.Chapter] {
        let inLibrary = await CoreDataManager.shared.container.performBackgroundTask { context in
            CoreDataManager.shared.hasLibraryManga(sourceId: manga.sourceKey, mangaId: manga.key, context: context)
        }
        if inLibrary {
            // load data from db
            return await CoreDataManager.shared.container.performBackgroundTask { context in
                CoreDataManager.shared.getChapters(
                    sourceId: manga.sourceKey,
                    mangaId: manga.key,
                    context: context
                ).map {
                    $0.toNewChapter()
                }
            }
        } else {
            return (try? await SourceManager.shared.source(for: manga.sourceKey)?.getMangaUpdate(
                manga: manga,
                needsDetails: false,
                needsChapters: true
            ).chapters) ?? []
        }
    }

    func getChaptersToSyncProgressFromTracker(
        tracker: Tracker,
        trackItem: TrackItem,
        manga: ExtensionRunner.Manga,
        chapters: [ExtensionRunner.Chapter]? = nil,
        currentHighestRead: Float? = nil
    ) async -> [ExtensionRunner.Chapter] {
        guard
            let state = try? await tracker.getState(trackId: trackItem.id),
            case let trackerLastReadChapter = state.lastReadChapter,
            case let trackerLastReadVolume = state.lastReadVolume,
            (trackerLastReadChapter ?? 0) > 0 || (trackerLastReadVolume ?? 0) > 0
        else {
            return []
        }

        let chapters = if let chapters {
            chapters
        } else {
            await getChapters(manga: manga)
        }
        guard !chapters.isEmpty else { return [] }

        let currentHighestRead = if let currentHighestRead {
            currentHighestRead
        } else {
            await CoreDataManager.shared.container.performBackgroundTask { context in
                CoreDataManager.shared.getHighestReadNumber(
                    sourceId: manga.sourceKey,
                    mangaId: manga.key,
                    context: context
                ) ?? 0
            }
        }

        // Check for display mode
        let displayMode = ChapterTitleDisplayMode(
            flags: CoreDataManager.shared.getMangaChapterFilters(
                sourceId: manga.sourceKey,
                mangaId: manga.key
            ).flags
        )

        var chaptersToMark: [ExtensionRunner.Chapter] = []

        // Determine what to sync based on tracker progress and forced mode
        if displayMode == .chapter {
            // Forced chapter mode: sync chapter progress
            if let trackerLastReadChapter {
                let offsetTrackerLastReadChapter = Self.applyChapterOffset(
                    to: trackerLastReadChapter,
                    offset: -trackItem.chapterOffset,
                    maxChapters: state.totalChapters
                )
                if offsetTrackerLastReadChapter > currentHighestRead {
                    chaptersToMark = chapters.filter { ($0.chapterNumber ?? $0.volumeNumber ?? 0) <= offsetTrackerLastReadChapter }
                }
            }
        } else {
            // Default mode: sync both chapter and volume progress
            var checkedForChapters = false
            if let trackerLastReadChapter {
                let offsetTrackerLastReadChapter = Self.applyChapterOffset(
                    to: trackerLastReadChapter,
                    offset: -trackItem.chapterOffset,
                    maxChapters: state.totalChapters
                )
                if offsetTrackerLastReadChapter > currentHighestRead {
                    // find all chapters with a chapter number less than or equal to the last tracker chapter
                    chaptersToMark = chapters.filter {
                        // floor the chapter number so partial chapters are marked (e.g. 10.1 and 10.2 will be marked if the tracker is at 10)
                        if let chapter = $0.chapterNumber, floor(chapter) <= offsetTrackerLastReadChapter {
                            true
                        } else {
                            false
                        }
                    }
                    checkedForChapters = true
                }
            }
            // otherwise, if we didn't find any chapters, try using the volume number instead
            // note: ignores the case where we skipped checking chapters due to currentHighestRead <= trackerLastReadChapter (#753)
            let foundNoChapters = checkedForChapters && chaptersToMark.isEmpty
            if let trackerLastReadVolume, trackerLastReadVolume > 0 && (foundNoChapters || trackerLastReadChapter == nil) {
                // find all chapters with a volume number less than or equal to the last tracker volume
                chaptersToMark = chapters.filter {
                    if let volume = $0.volumeNumber, volume <= Float(trackerLastReadVolume) {
                        true
                    } else {
                        false
                    }
                }
            }
        }

        return chaptersToMark
    }
}

// MARK: Tracking State
extension TrackerManager {
    func processPendingUpdates() async {
        guard !trackingState.pendingPageUpdates.isEmpty else { return }

        if let pageUpdateTask {
            await pageUpdateTask.value
            return
        }

        pageUpdateTask = Task {
            var stillPending: [PageTrackUpdate] = []
            var successes = 0

            for var update in trackingState.pendingPageUpdates {
                guard let tracker = TrackerManager.getTracker(id: update.trackerId) as? PageTracker else {
                    continue // tracker no longer exists, remove the update
                }
                do {
                    try await tracker.setProgress(
                        trackId: update.trackId,
                        chapter: update.chapter,
                        progress: update.progress
                    )
                    if update.failCount > 0 {
                        successes += 1
                    }
                } catch {
                    LogManager.logger.error("Failed to set tracker progress (\(tracker.id)): \(error)")
                    update.failCount += 1
                    if update.failCount >= 3 {
                        LogManager.logger.warn("Removing failed page update after 3 attempts: \(update)")
                        continue // remove update after three failed attempts (initial + two retries)
                    }
                    stillPending.append(update)
                }
            }

            if successes > 0 {
                LogManager.logger.info("Processed \(successes) previously failed page tracker update\(successes > 1 ? "s" : "")")
            }

            trackingState.pendingPageUpdates = stillPending
            savePageTrackingState()
            pageUpdateTask = nil
        }
    }

    private func savePageTrackingState() {
        let data = try? JSONEncoder().encode(trackingState)
        if let data {
            UserDefaults.standard.set(data, forKey: "Tracker.pageTrackingState")
        }
    }

    private func queuePageUpdates(_ updates: [PageTrackUpdate]) {
        // merge new updates into existing failed updates, preserving the latest ones
        for update in updates {
            // remove any old update, assuming it's not as recent as the new one
            let existingUpdateIndex = trackingState.pendingPageUpdates.firstIndex(where: {
                $0.trackerId == update.trackerId && $0.trackId == update.trackId && $0.chapter.key == update.chapter.key
            })
            if let existingUpdateIndex {
                trackingState.pendingPageUpdates.remove(at: existingUpdateIndex)
            }
            // add new update
            trackingState.pendingPageUpdates.append(update)
        }
        savePageTrackingState()
    }
}
