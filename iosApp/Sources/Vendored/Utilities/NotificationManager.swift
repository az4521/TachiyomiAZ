//
//  NotificationManager.swift
//  Aidoku
//

import Foundation
import UserNotifications

#if os(iOS)
import UIKit
#endif

actor NotificationManager {
    static let shared = NotificationManager()

    enum ProgressOperation: String, Sendable {
        case libraryUpdate
        case downloads
    }

    struct NewChaptersSummary {
        let mangaIdentifier: MangaIdentifier
        let mangaTitle: String
        let chapterCount: Int
    }

    static let settingKey = "Library.notifyNewChapters"
    static let categoryIdentifier = "newChapters"
    static let threadIdentifier = "newChapters"
    static let sourceIdInfoKey = "sourceId"
    static let mangaIdInfoKey = "mangaId"
    static let batchNotificationThreshold = 3
    static let libraryProgressSettingKey = "Library.progressNotifications"
    static let downloadProgressSettingKey = "Downloads.progressNotifications"
    static let progressCategoryIdentifier = "backgroundTaskProgress"
    static let progressCompletionCategoryIdentifier = "backgroundTaskCompletion"
    static let progressThreadIdentifier = "backgroundTasks"

    nonisolated static var calculatingLibraryRefreshDetail: String {
        NSLocalizedString(
            "CALCULATING_LIBRARY_REFRESH",
            value: "Calculating titles to refresh…",
            comment: "Library refresh status while determining the eligible title count"
        )
    }

    private struct ProgressState {
        var completed: Double
        var total: Int
        var detail: String?
        var percentage: Int
        var lastUpdate: Date
    }

    private var progressStates: [ProgressOperation: ProgressState] = [:]
    /// iOS 26 continued-processing tasks provide their own system progress UI.
    /// Keep the operation marked so fallback notifications don't duplicate it.
    private var systemManagedProgressOperations: Set<ProgressOperation> = []

    nonisolated func isEnabled() -> Bool {
        UserDefaults.standard.bool(forKey: Self.settingKey)
    }

    func requestAuthorization() async -> Bool {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
            case .authorized, .provisional:
                return true
            case .notDetermined:
                return (try? await center.requestAuthorization(options: [.alert, .badge, .sound])) ?? false
            default:
                return false
        }
    }

    func notifyNewChapters(_ summaries: [NewChaptersSummary]) async {
        guard !summaries.isEmpty, isEnabled() else { return }

        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized
            || settings.authorizationStatus == .provisional
        else { return }

        if summaries.count > Self.batchNotificationThreshold {
            await Self.sendNotification(
                identifier: "newChapters.batch.\(Int(Date.now.timeIntervalSince1970))",
                title: NSLocalizedString("NEW_CHAPTERS_AVAILABLE"),
                body: String(format: NSLocalizedString("X_SERIES_HAVE_NEW_CHAPTERS"), summaries.count),
                center: center
            )
            return
        }

        for summary in summaries {
            let timestamp = Int(Date.now.timeIntervalSince1970)
            let identifier = "newChapters.\(summary.mangaIdentifier.sourceKey).\(summary.mangaIdentifier.mangaKey).\(timestamp)"
            await Self.sendNotification(
                identifier: identifier,
                title: summary.mangaTitle,
                body: Self.body(for: summary),
                userInfo: [
                    Self.sourceIdInfoKey: summary.mangaIdentifier.sourceKey,
                    Self.mangaIdInfoKey: summary.mangaIdentifier.mangaKey
                ],
                center: center
            )
        }
    }

    func beginProgress(
        _ operation: ProgressOperation,
        total: Int,
        detail: String? = nil
    ) async {
        guard !systemManagedProgressOperations.contains(operation),
              progressNotificationsEnabled(for: operation),
              Self.shouldPublishProgress(total: total, detail: detail)
        else {
            progressStates.removeValue(forKey: operation)
            return
        }
        progressStates[operation] = .init(
            completed: 0,
            total: total,
            detail: detail,
            percentage: 0,
            lastUpdate: .distantPast
        )

        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        if settings.authorizationStatus == .notDetermined {
            // Do not hold up a library update or download queue while the
            // system permission sheet is waiting for user input. Subsequent
            // progress callbacks will publish after permission is granted.
            Task { _ = await self.requestAuthorization() }
            return
        }
        guard settings.authorizationStatus == .authorized
            || settings.authorizationStatus == .provisional
        else { return }
        await updateProgress(
            operation,
            completed: 0,
            total: total,
            detail: detail,
            force: true
        )
    }

    func updateProgress(
        _ operation: ProgressOperation,
        completed: Double,
        total: Int,
        detail: String? = nil,
        force: Bool = false
    ) async {
        guard !systemManagedProgressOperations.contains(operation),
              progressNotificationsEnabled(for: operation),
              Self.shouldPublishProgress(total: total, detail: detail)
        else { return }

        let fraction = total > 0 ? min(1, max(0, completed / Double(total))) : 0
        let percentage = Int((fraction * 100).rounded())
        let now = Date.now
        if let state = progressStates[operation], !force {
            let changedEnough = abs(percentage - state.percentage) >= 2
            let waitedLongEnough = now.timeIntervalSince(state.lastUpdate) >= 1
            guard percentage == 100 || changedEnough || waitedLongEnough else { return }
        }
        progressStates[operation] = .init(
            completed: completed,
            total: total,
            detail: detail,
            percentage: percentage,
            lastUpdate: now
        )

        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized
            || settings.authorizationStatus == .provisional
        else { return }

        let content = UNMutableNotificationContent()
        content.title = title(for: operation)
        content.body = Self.progressBody(
            completed: completed,
            total: total,
            detail: detail
        )
        content.threadIdentifier = Self.progressThreadIdentifier
        content.categoryIdentifier = Self.progressCategoryIdentifier
        content.interruptionLevel = .passive
        content.userInfo = ["progressOperation": operation.rawValue]

        let request = UNNotificationRequest(
            identifier: progressIdentifier(for: operation),
            content: content,
            trigger: nil
        )
        try? await center.add(request)
    }

    /// Reissues the latest progress notification immediately. SceneDelegate
    /// uses this when the app moves to the background so an operation that is
    /// currently waiting on a network request is still visible right away.
    func republishProgress(_ operation: ProgressOperation) async {
        guard let state = progressStates[operation] else { return }
        await updateProgress(
            operation,
            completed: state.completed,
            total: state.total,
            detail: state.detail,
            force: true
        )
    }

    func finishProgress(
        _ operation: ProgressOperation,
        success: Bool,
        summary: String? = nil
    ) async {
        let state = progressStates.removeValue(forKey: operation)
        let wasSystemManaged = systemManagedProgressOperations.remove(operation) != nil
        guard !wasSystemManaged, state != nil else { return }
        guard progressNotificationsEnabled(for: operation) else { return }

#if os(iOS)
        let isActive = await MainActor.run {
            UIApplication.shared.applicationState == .active
        }
        if isActive {
            await removeProgress(operation)
            return
        }
#endif

        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized
            || settings.authorizationStatus == .provisional
        else { return }

        let content = UNMutableNotificationContent()
        content.title = success
            ? String(
                format: NSLocalizedString(
                    "TASK_FINISHED_FORMAT",
                    value: "%@ finished",
                    comment: "Background operation completion notification title"
                ),
                title(for: operation)
            )
            : String(
                format: NSLocalizedString(
                    "TASK_STOPPED_FORMAT",
                    value: "%@ stopped",
                    comment: "Background operation stopped notification title"
                ),
                title(for: operation)
            )
        content.body = summary ?? (success
            ? NSLocalizedString(
                "BACKGROUND_TASK_COMPLETED",
                value: "The operation completed successfully.",
                comment: "Background operation completion notification body"
            )
            : NSLocalizedString(
                "BACKGROUND_TASK_INCOMPLETE",
                value: "The operation did not finish and can be resumed in the app.",
                comment: "Background operation stopped notification body"
            ))
        content.sound = nil
        content.threadIdentifier = Self.progressThreadIdentifier
        content.categoryIdentifier = Self.progressCompletionCategoryIdentifier
        content.interruptionLevel = .passive

        let request = UNNotificationRequest(
            identifier: progressIdentifier(for: operation),
            content: content,
            trigger: nil
        )
        try? await center.add(request)
    }

    func removeProgress(_ operation: ProgressOperation) async {
        progressStates.removeValue(forKey: operation)
        let identifiers = [progressIdentifier(for: operation)]
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
    }

    /// Suppress the app-owned notification while an iOS 26 continued task is
    /// displaying its native system progress UI.
    func useSystemManagedProgress(_ operation: ProgressOperation) {
        systemManagedProgressOperations.insert(operation)
        progressStates.removeValue(forKey: operation)
        let identifiers = [progressIdentifier(for: operation)]
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
    }

    func stopUsingSystemManagedProgress(_ operation: ProgressOperation) {
        systemManagedProgressOperations.remove(operation)
    }

    nonisolated static func progressBody(
        completed: Double,
        total: Int,
        detail: String? = nil
    ) -> String {
        guard total > 0 else {
            return detail ?? NSLocalizedString(
                "PREPARING",
                value: "Preparing…",
                comment: "Preparing a background operation"
            )
        }
        let fraction = min(1, max(0, completed / Double(total)))
        let percentage = Int((fraction * 100).rounded())
        let barWidth = 10
        let filled = min(barWidth, max(0, Int((fraction * Double(barWidth)).rounded())))
        let bar = String(repeating: "█", count: filled)
            + String(repeating: "░", count: barWidth - filled)
        let progress = "\(bar) \(percentage)%"
        guard let detail, !detail.isEmpty else { return progress }
        return "\(progress)\n\(detail)"
    }

    nonisolated static func shouldPublishProgress(total: Int, detail: String?) -> Bool {
        total > 0 || (total == 0 && !(detail?.isEmpty ?? true))
    }

    private func progressNotificationsEnabled(for operation: ProgressOperation) -> Bool {
        let key = switch operation {
            case .libraryUpdate: Self.libraryProgressSettingKey
            case .downloads: Self.downloadProgressSettingKey
        }
        return UserDefaults.standard.bool(forKey: key)
    }

    private func progressIdentifier(for operation: ProgressOperation) -> String {
        "backgroundTaskProgress.\(operation.rawValue)"
    }

    private func title(for operation: ProgressOperation) -> String {
        switch operation {
            case .libraryUpdate:
                NSLocalizedString("REFRESHING_LIBRARY")
            case .downloads:
                NSLocalizedString("DOWNLOADING")
        }
    }

    private static func sendNotification(
        identifier: String,
        title: String,
        body: String,
        userInfo: [AnyHashable: Any] = [:],
        center: UNUserNotificationCenter
    ) async {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.threadIdentifier = threadIdentifier
        content.categoryIdentifier = categoryIdentifier
        content.userInfo = userInfo

        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: nil)
        try? await center.add(request)
    }

    private static func body(for summary: NewChaptersSummary) -> String {
        if summary.chapterCount == 1 {
            return NSLocalizedString("1_NEW_CHAPTER_AVAILABLE")
        }
        return String(format: NSLocalizedString("X_NEW_CHAPTERS_AVAILABLE"), summary.chapterCount)
    }
}
