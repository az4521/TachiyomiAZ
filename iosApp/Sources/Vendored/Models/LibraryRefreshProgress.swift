//
//  LibraryRefreshProgress.swift
//
//  Extracted from tachiyomiazios (Shared/Managers/Manga/MangaManager.swift). That class is not
//  vendored -- chapter syncing comes from :core-domain here -- but the tab bar shows this progress
//  value, and it carries no logic of its own.
//

import Foundation

struct LibraryRefreshProgress: Equatable, Sendable {
    let completed: Int
    let total: Int

    init(completed: Int64, total: Int64) {
        self.total = max(0, Int(total))
        self.completed = min(max(0, Int(completed)), self.total)
    }

    init(completed: Int, total: Int) {
        self.init(completed: Int64(completed), total: Int64(total))
    }

    var fractionCompleted: Float {
        guard total > 0 else { return 0 }
        return Float(completed) / Float(total)
    }

    var localizedDetail: String {
        String(
            format: NSLocalizedString("%i_OF_%i"),
            completed,
            total
        )
    }
}
