//
//  SuwayomiTracker.swift
//  Aidoku
//
//  Created by skitty on 7/8/26.
//

import ExtensionRunner
import Foundation

final class SuwayomiTracker: EnhancedTracker, PageTracker {
    let id = "suwayomi"
    let name = NSLocalizedString("SUWAYOMI")
    let icon = PlatformImage(named: "suwayomi")
    let isLoggedIn = true

    private let api = SuwayomiApi()
    private let idSeparator: Character = "|"

    func getTrackerInfo() -> TrackerInfo {
        .init(supportedStatuses: [], scoreType: .tenPoint, scoreOptions: [])
    }

    func register(trackId: String, highestChapterRead: Float?, earliestReadDate: Date?) async throws -> String? {
        guard let highestChapterRead else { return nil }
        let (sourceKey, seriesId) = try getIdParts(from: trackId)

        let state = try? await api.getState(sourceKey: sourceKey, seriesId: seriesId)
        if state?.lastReadChapter == nil || highestChapterRead > state?.lastReadChapter ?? 0 {
            try await api.update(
                sourceKey: sourceKey,
                seriesId: seriesId,
                update: .init(lastReadChapter: highestChapterRead)
            )
        }

        return nil
    }

    func update(trackId: String, update: TrackUpdate) async throws {
        let (sourceKey, seriesId) = try getIdParts(from: trackId)
        try await api.update(
            sourceKey: sourceKey,
            seriesId: seriesId,
            update: update
        )
    }

    func getState(trackId: String) async throws -> TrackState {
        let (sourceKey, seriesId) = try getIdParts(from: trackId)
        guard let state = try await api.getState(sourceKey: sourceKey, seriesId: seriesId) else {
            throw SuwayomiTrackerError.getStateFailed
        }
        return state
    }

    func search(for manga: ExtensionRunner.Manga, includeNsfw: Bool) async throws -> [TrackSearchItem] {
        let state = try await api.getState(sourceKey: manga.sourceKey, seriesId: manga.key)
        if state != nil {
            return [.init(id: "\(manga.sourceKey)\(idSeparator)\(manga.key)", tracked: true)]
        } else {
            return []
        }
    }

    func getUrl(trackId: String) async -> URL? {
        nil
    }

    func canRegister(sourceKey: String, mangaKey: String) -> Bool {
        EnhancedSourceBridge.service(forSourceKey: sourceKey) == "suwayomi" && !UserDefaults.standard.bool(forKey: "\(sourceKey).disableTracking")
    }

    func setProgress(trackId: String, chapter: ExtensionRunner.Chapter, progress: ChapterReadProgress) async throws {
        let (sourceKey, seriesId) = try getIdParts(from: trackId)
        guard
            let seriesId = Int(seriesId),
            let chapterId = Int(chapter.key)
        else {
            throw SuwayomiTrackerError.invalidId
        }
        try await api.updateReadProgress(
            sourceKey: sourceKey,
            seriesId: seriesId,
            chapterId: chapterId,
            progress: progress
        )
    }

    func getProgress(trackId: String, chapters: [ExtensionRunner.Chapter]) async throws -> [String: ChapterReadProgress] {
        let (sourceKey, seriesId) = try getIdParts(from: trackId)
        return try await api.getSeriesReadProgress(sourceKey: sourceKey, seriesId: seriesId)
    }

    func logout() {
        fatalError("logout not implemented for suwayomi tracker")
    }

    func removeTrackItems(source: ExtensionRunner.Source) async {
        // Every link this tracker holds against the server this source talks to. The id carries
        // the source key, so the source being removed is what decides which rows go.
        CoreDataManager.shared.removeTracks(trackerId: id) { storedId in
            (try? self.getIdParts(from: storedId))?.sourceKey == source.key
        }
    }
}

extension SuwayomiTracker {
    private func getIdParts(from id: String) throws -> (sourceKey: String, seriesId: String) {
        let split = id.split(separator: idSeparator, maxSplits: 2).map(String.init)
        guard split.count == 2 else { throw SuwayomiTrackerError.invalidId }
        return (sourceKey: split[0], seriesId: split[1])
    }
}

enum SuwayomiTrackerError: Error {
    case invalidId
    case getStateFailed
}
