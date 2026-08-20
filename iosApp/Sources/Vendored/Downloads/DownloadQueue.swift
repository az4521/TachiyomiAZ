//
//  DownloadQueue.swift
//  Aidoku
//
//  Created by Skitty on 5/13/22.
//

import ExtensionRunner
@preconcurrency import BackgroundTasks
import Foundation

#if os(iOS)
import UIKit
#endif

// stores queued and active downloads
// creates a downloadtask for every source
// only one chapter per source is downloaded at a time
actor DownloadQueue {
    private let cache: DownloadCache
    private var onCompletion: (() -> Void)?

    private(set) var queue: [String: [Download]] = [:] // all queued downloads stored under source id
    private var tasks: [String: DownloadTask] = [:] // tasks for each source
    private var progressBlocks: [ChapterIdentifier: (Int, Int) -> Void] = [:]

    private var paused = false
    private var totalDownloads: Int = 0
    private var completedDownloads: Int = 0
    private var successfulDownloads: Int = 0
    private var bgTask: ProgressReporting?
    private var sendCancelNotification = true
    private var progressNotificationActive = false
    private var backgroundExecutionExpired = false
    private var systemSuspended = false
    private var continuedRequestPending = false
    private var continuedTaskRegistered = false

#if os(iOS)
    private var foregroundBackgroundTask: UIBackgroundTaskIdentifier = .invalid
#endif

    static let taskIdentifier = (Bundle.main.bundleIdentifier ?? "") + ".download"
    static let continuedTaskIdentifier =
        (Bundle.main.bundleIdentifier ?? "") + ".download.continued.queue"

    init(cache: DownloadCache, onCompletion: (() -> Void)? = nil) {
        self.cache = cache
        self.onCompletion = onCompletion
    }

    func setOnCompletion(_ onCompletion: (() -> Void)?) {
        self.onCompletion = onCompletion
    }

    func setContinuedTaskRegistered(_ registered: Bool) {
        continuedTaskRegistered = registered
    }

    func start() async {
        paused = false

        guard !queue.isEmpty else { return }

        if totalDownloads == 0 {
            totalDownloads = queue.values.reduce(0) { $0 + $1.count }
            completedDownloads = 0
            successfulDownloads = 0
        }
#if !os(macOS) && !targetEnvironment(simulator)
        if
            bgTask == nil,
            UserDefaults.standard.bool(forKey: "Downloads.background"),
            !ProcessInfo.processInfo.isMacCatalystApp
        {
            if #available(iOS 26.0, *),
               continuedTaskRegistered,
               !continuedRequestPending
            {
                let request = BGContinuedProcessingTaskRequest(
                    identifier: Self.continuedTaskIdentifier,
                    title: NSLocalizedString("DOWNLOADING"),
                    subtitle: NSLocalizedString("PROCESSING_QUEUE")
                )
                request.strategy = .fail
                continuedRequestPending = true
                do {
                    try BGTaskScheduler.shared.submit(request)
                    await NotificationManager.shared.useSystemManagedProgress(.downloads)
                    return
                } catch {
                    continuedRequestPending = false
                    await NotificationManager.shared.stopUsingSystemManagedProgress(.downloads)
                    LogManager.logger.error("Failed to start continued background downloading: \(error)")
                }
            }
            scheduleBackgroundProcessing()
            await beginForegroundBackgroundExecution()
        }
#endif

        await beginProgressNotification()
        await initAndResumeTasks()
    }

    private func initAndResumeTasks() async {
        for (sourceKey, downloads) in queue {
            if tasks[sourceKey] == nil {
                let task = DownloadTask(id: sourceKey, cache: cache, downloads: downloads)
                await task.setDelegate(delegate: self)
                tasks[sourceKey] = task
            }
            await tasks[sourceKey]?.resume()
        }
    }

    func resume() async {
        paused = false

        if UserDefaults.standard.bool(forKey: "Downloads.background"), bgTask == nil {
            await start()
        }

        await withTaskGroup(of: Void.self) { group in
            for task in tasks.values {
                group.addTask { await task.resume() }
            }
        }
    }

    func pause() async {
        paused = true

#if !os(macOS)
        if #available(iOS 26.0, *) {
            if let task = bgTask as? BGContinuedProcessingTask {
                task.updateTitle(
                    NSLocalizedString("DOWNLOADING"),
                    subtitle: NSLocalizedString("PAUSED")
                )
            }
        }
#endif

        await withTaskGroup(of: Void.self) { group in
            for task in tasks.values {
                group.addTask { await task.pause() }
            }
        }
#if !os(macOS) && !targetEnvironment(simulator)
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
        if #available(iOS 26.0, *) {
            BGTaskScheduler.shared.cancel(
                taskRequestWithIdentifier: Self.continuedTaskIdentifier
            )
            continuedRequestPending = false
        }
#endif
        await NotificationManager.shared.stopUsingSystemManagedProgress(.downloads)
#if os(iOS)
        await endForegroundBackgroundExecution()
#endif
        await publishDownloadProgress(force: true)
    }

    @discardableResult
    func add(chapters: [ExtensionRunner.Chapter], manga: ExtensionRunner.Manga, autoStart: Bool = true) async -> [Download] {
        var downloads: [Download] = []
        for chapter in chapters {
            let identifier = ChapterIdentifier(
                sourceKey: manga.sourceKey,
                mangaKey: manga.key,
                chapterKey: chapter.key
            )
            guard !(await cache.isChapterDownloaded(identifier: identifier)) else {
                continue
            }
            // create tmp directory so we know it's queued
            cache.tmpDirectory(for: identifier).createDirectory()
            let download = Download.from(manga: manga, chapter: chapter)
            downloads.append(download)
            if queue[manga.sourceKey] == nil {
                queue[manga.sourceKey] = [download]
            } else {
                queue[manga.sourceKey]?.append(download)
                await tasks[manga.sourceKey]?.add(download: download)
            }
        }
        totalDownloads += downloads.count
        bgTask?.progress.totalUnitCount = Int64(totalDownloads)
        if autoStart {
            await start()
        }
        await publishDownloadProgress(force: true)
        saveQueueState()
        return downloads
    }

    func cancelDownload(for chapter: ChapterIdentifier) async {
        if let task = tasks[chapter.sourceKey] {
            await task.cancel(chapter: chapter)
        } else {
            // no longer in queue but the tmp download directory still exists, so we should remove it
            cache.tmpDirectory(for: chapter).removeItem()
        }
        saveQueueState()
    }

    func cancelDownloads(for chapters: [ChapterIdentifier]) async {
        // disable individual download cancelled notifications
        sendCancelNotification = false
        defer { sendCancelNotification = true }
        for chapter in chapters {
            if let task = tasks[chapter.sourceKey] {
                await task.cancel(chapter: chapter)
            } else {
                cache.tmpDirectory(for: chapter).removeItem()
            }
            if let queueItem = queue[chapter.sourceKey]?.firstIndex(where: {
                $0.chapterIdentifier == chapter
            }) {
                queue[chapter.sourceKey]?.remove(at: queueItem)
                if queue[chapter.sourceKey]?.isEmpty == true {
                    queue.removeValue(forKey: chapter.sourceKey)
                }
            }
        }
        NotificationCenter.default.post(name: .downloadsCancelled, object: chapters)
        saveQueueState()
        if queue.isEmpty, totalDownloads > 0 {
            await finishQueue(cancelled: true)
        }
    }

    func cancelDownloads(for manga: MangaIdentifier) async {
        if let task = tasks[manga.sourceKey] {
            await task.cancel(manga: manga)
        } else {
            cache.directory(for: manga)
                .contents
                .filter { $0.lastPathComponent.hasPrefix(".tmp") }
                .forEach { $0.removeItem() }
        }
        saveQueueState()
    }

    func cancelAll() async {
        sendCancelNotification = false
        defer { sendCancelNotification = true }
        for task in tasks {
            await task.value.cancel()
        }
        queue = [:]
        NotificationCenter.default.post(name: .downloadsCancelled, object: nil)
        saveQueueState()
        if totalDownloads > 0 {
            await finishQueue(cancelled: true)
        }
    }

    // register callback for download progress change
    func onProgress(for chapter: ChapterIdentifier, block: @escaping (Int, Int) -> Void) {
        progressBlocks[chapter] = block
    }

    func removeProgressBlock(for chapter: ChapterIdentifier) {
        progressBlocks.removeValue(forKey: chapter)
    }

    func saveQueueState() {
        let queueData = try? JSONEncoder().encode(queue)
        UserDefaults.standard.set(queueData, forKey: "Data.downloadQueueState")
    }

    func loadQueueState() async {
        guard
            let queueData = UserDefaults.standard.data(forKey: "Data.downloadQueueState"),
            let queueState = try? JSONDecoder().decode([String: [Download]].self, from: queueData)
        else {
            return
        }
        queue = queueState
        if !queue.isEmpty {
            await start()
        }
    }

    func hasQueuedDownloads() -> Bool {
        !queue.isEmpty
    }

    func isPaused() -> Bool {
        paused && !queue.isEmpty
    }

    func isRunning() async -> Bool {
        for task in tasks where await task.value.running {
            return true
        }
        return false
    }
}

extension DownloadQueue {
    private func setBackgroundTask(_ task: ProgressReporting?) {
        bgTask = task
        if totalDownloads == 0 {
            totalDownloads = queue.values.reduce(0) { $0 + $1.count }
            completedDownloads = 0
            successfulDownloads = 0
        }
        bgTask?.progress.totalUnitCount = Int64(totalDownloads)
        bgTask?.progress.completedUnitCount = Int64(completedDownloads)
    }

    func runAsBackgroundTask(_ task: ProgressReporting?) async -> Bool {
        let isContinuedTask: Bool
        if #available(iOS 26.0, *) {
            isContinuedTask = task is BGContinuedProcessingTask
        } else {
            isContinuedTask = false
        }
        if isContinuedTask {
            continuedRequestPending = false
            await NotificationManager.shared.useSystemManagedProgress(.downloads)
        }
        if queue.isEmpty,
           let queueData = UserDefaults.standard.data(forKey: "Data.downloadQueueState"),
           let restoredQueue = try? JSONDecoder().decode(
               [String: [Download]].self,
               from: queueData
           )
        {
            queue = restoredQueue
        }
        guard !queue.isEmpty else { return true }
        guard !paused else { return true }

        backgroundExecutionExpired = false
        systemSuspended = false
        setBackgroundTask(task)
        await beginProgressNotification()
        await initAndResumeTasks()

        // Do not busy-spin here. The previous loop repeatedly entered this
        // actor without suspension and could consume an entire CPU core while
        // starving the download tasks it was waiting on.
        while !queue.isEmpty && !paused && !backgroundExecutionExpired && !Task.isCancelled {
            do {
                try await Task.sleep(nanoseconds: 500_000_000)
            } catch {
                break
            }
        }

        let success = queue.isEmpty || paused
        setBackgroundTask(nil)
        if !success, !isContinuedTask {
            scheduleBackgroundProcessing()
        }
        return success
    }

    func expireBackgroundExecution(reschedule: Bool = true) async {
        backgroundExecutionExpired = true
        await suspendTasksForSystem()
        setBackgroundTask(nil)
        if reschedule {
            scheduleBackgroundProcessing()
        }
        await NotificationManager.shared.finishProgress(
            .downloads,
            success: false,
            summary: reschedule
                ? NSLocalizedString(
                    "DOWNLOADS_PAUSED_RESUME",
                    value: "Downloads were paused and will resume when iOS allows background processing.",
                    comment: "Download background task expiration notification"
                )
                : NSLocalizedString(
                    "DOWNLOADS_PAUSED_OPEN_APP",
                    value: "Downloads were paused. Open the app to resume them.",
                    comment: "User-cancelled continued download task notification"
                )
        )
    }

    func resumeAfterSystemSuspension() async {
        guard systemSuspended, !paused, !queue.isEmpty else { return }
        systemSuspended = false
        await start()
    }

    private func suspendTasksForSystem() async {
        systemSuspended = true
        await withTaskGroup(of: Void.self) { group in
            for task in tasks.values {
                group.addTask { await task.pause() }
            }
        }
        saveQueueState()
    }

    private func beginProgressNotification() async {
        guard !progressNotificationActive, totalDownloads > 0 else { return }
        progressNotificationActive = true
        await NotificationManager.shared.beginProgress(
            .downloads,
            total: totalDownloads,
            detail: downloadProgressDetail()
        )
    }

    private func publishDownloadProgress(force: Bool = false) async {
        guard totalDownloads > 0 else { return }
        let partialDownloads = queue.values
            .flatMap { $0 }
            .reduce(0.0) { result, download in
                guard download.total > 0 else { return result }
                return result + min(
                    1,
                    max(0, Double(download.progress) / Double(download.total))
                )
            }
        await NotificationManager.shared.updateProgress(
            .downloads,
            completed: Double(completedDownloads) + partialDownloads,
            total: totalDownloads,
            detail: downloadProgressDetail(),
            force: force
        )
    }

    private func downloadProgressDetail() -> String {
        let progressText = String(
            format: NSLocalizedString("%i_OF_%i"),
            min(completedDownloads, totalDownloads),
            totalDownloads
        )
        if paused {
            return "\(NSLocalizedString("PAUSED")) · \(progressText)"
        }
        return progressText
    }

    private func finishQueue(cancelled: Bool) async {
        guard totalDownloads > 0 else { return }
#if !os(macOS) && !targetEnvironment(simulator)
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
        if #available(iOS 26.0, *) {
            BGTaskScheduler.shared.cancel(
                taskRequestWithIdentifier: Self.continuedTaskIdentifier
            )
            continuedRequestPending = false
        }
#endif
        let total = totalDownloads
        let successful = successfulDownloads
        totalDownloads = 0
        completedDownloads = 0
        successfulDownloads = 0
        progressNotificationActive = false

#if os(iOS)
        await endForegroundBackgroundExecution()
#endif

        let summary: String
        if cancelled {
            summary = String(
                format: NSLocalizedString(
                    "DOWNLOADS_PARTIAL_FORMAT",
                    value: "%d of %d downloads completed. The remaining downloads can be resumed in the app.",
                    comment: "Incomplete download queue notification"
                ),
                successful,
                total
            )
        } else {
            summary = String(
                format: NSLocalizedString(
                    "DOWNLOADS_COMPLETE_FORMAT",
                    value: "%d downloads completed.",
                    comment: "Completed download queue notification"
                ),
                successful
            )
        }
        await NotificationManager.shared.finishProgress(
            .downloads,
            success: !cancelled,
            summary: summary
        )
    }

    private func scheduleBackgroundProcessing() {
#if !os(macOS) && !targetEnvironment(simulator)
        guard
            !queue.isEmpty,
            UserDefaults.standard.bool(forKey: "Downloads.background")
        else { return }

        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
        let request = BGProcessingTaskRequest(identifier: Self.taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 1)
        request.requiresExternalPower = false
        request.requiresNetworkConnectivity = true
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            LogManager.logger.error("Could not schedule background downloads: \(error)")
        }
#endif
    }

#if os(iOS)
    private func beginForegroundBackgroundExecution() async {
        guard foregroundBackgroundTask == .invalid else { return }
        foregroundBackgroundTask = await MainActor.run {
            UIApplication.shared.beginBackgroundTask(
                withName: "TachiyomiAZ Downloads"
            ) { [weak self] in
                Task {
                    await self?.expireForegroundBackgroundExecution()
                }
            }
        }
    }

    private func expireForegroundBackgroundExecution() async {
        // Do not mark the queue paused when the short foreground allowance
        // expires. iOS may continue network execution briefly, suspend and
        // later resume the process, or launch the pending BGProcessing task.
        scheduleBackgroundProcessing()
        await endForegroundBackgroundExecution()
    }

    private func endForegroundBackgroundExecution() async {
        guard foregroundBackgroundTask != .invalid else { return }
        let identifier = foregroundBackgroundTask
        foregroundBackgroundTask = .invalid
        await MainActor.run {
            UIApplication.shared.endBackgroundTask(identifier)
        }
    }
#endif
}

// MARK: - Task Delegate
extension DownloadQueue: DownloadTaskDelegate {
    func taskCancelled(task: DownloadTask) async {
        await taskFinished(task: task)
    }

    func taskPaused(task _: DownloadTask) async {}

    func taskFinished(task: DownloadTask) async {
        tasks.removeValue(forKey: task.id)
        queue.removeValue(forKey: task.id)
        saveQueueState()
        if queue.isEmpty, totalDownloads > 0 {
            await finishQueue(cancelled: successfulDownloads < totalDownloads)
        }
    }

    func downloadFinished(download: Download) async {
        await remove(download: download, cancelled: false)
        onCompletion?()
        NotificationCenter.default.post(name: .downloadFinished, object: download)
    }

    func downloadCancelled(download: Download) async {
        await remove(download: download, cancelled: true)
    }

    private func remove(download: Download, cancelled: Bool) async {
        var sourceDownloads = queue[download.chapterIdentifier.sourceKey] ?? []
        sourceDownloads.removeAll { $0 == download }
        if sourceDownloads.isEmpty {
            queue.removeValue(forKey: download.chapterIdentifier.sourceKey)
        } else {
            queue[download.chapterIdentifier.sourceKey] = sourceDownloads
        }
        saveQueueState()
        progressBlocks.removeValue(forKey: download.chapterIdentifier)
        if cancelled, sendCancelNotification {
            NotificationCenter.default.post(name: .downloadCancelled, object: download)
        }

        completedDownloads += 1
        if !cancelled {
            successfulDownloads += 1
        }
        bgTask?.progress.completedUnitCount = Int64(completedDownloads)

#if !os(macOS)
        if #available(iOS 26.0, *) {
            if !paused, let task = bgTask as? BGContinuedProcessingTask {
                task.updateTitle(
                    NSLocalizedString("DOWNLOADING"),
                    subtitle: String(format: NSLocalizedString("%i_OF_%i"), completedDownloads, totalDownloads)
                )
            }
        }
#endif
        await publishDownloadProgress(force: true)
        if queue.isEmpty, totalDownloads > 0 {
            await finishQueue(cancelled: successfulDownloads < totalDownloads)
        }
    }

    func downloadProgressChanged(download: Download) async {
        if let index = queue[download.chapterIdentifier.sourceKey]?.firstIndex(where: { $0 == download }) {
            queue[download.chapterIdentifier.sourceKey]?[index] = download
        }
        if let block = progressBlocks[download.chapterIdentifier] {
            block(download.progress, download.total)
        }
        NotificationCenter.default.post(name: .downloadProgressed, object: download)
        await publishDownloadProgress()
    }
}
