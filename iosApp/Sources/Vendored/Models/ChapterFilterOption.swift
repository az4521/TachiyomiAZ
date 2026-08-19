//
//  ChapterFilterOption.swift
//  Aidoku
//
//  Created by Skitty on 2/14/24.
//

import Foundation
import TachiyomiKit

struct ChapterFilterOption: Hashable {
    var type: ChapterFilterMethod
    var exclude: Bool

    /// The filters `flags` describes, in the shared layout.
    ///
    /// Android has no "locked" filter -- that is an Aidoku concept for paid chapters and there is
    /// no bit for it in the shared column -- so it is not read back. Its bookmarked filter has no
    /// counterpart in this screen's menu, so it passes through untouched rather than being dropped
    /// when this app rewrites the column.
    static func parseOptions(flags: Int) -> [ChapterFilterOption] {
        var result: [ChapterFilterOption] = []
        switch ChapterFlags.shared.readFilter(flags: Int32(flags)) {
            case .include: result.append(.init(type: .unread, exclude: false))
            case .exclude: result.append(.init(type: .unread, exclude: true))
            default: break
        }
        switch ChapterFlags.shared.downloadedFilter(flags: Int32(flags)) {
            case .include: result.append(.init(type: .downloaded, exclude: false))
            case .exclude: result.append(.init(type: .downloaded, exclude: true))
            default: break
        }
        return result
    }

    /// Writes these filters into `flags`, leaving the sort and the bookmarked filter alone.
    static func apply(_ options: [ChapterFilterOption], to flags: Int) -> Int {
        var result = Int32(flags)
        let read = options.first { $0.type == .unread }
        result = ChapterFlags.shared.withReadFilter(
            flags: result,
            filter: read.map { $0.exclude ? ChapterFilter.exclude : ChapterFilter.include } ?? ChapterFilter.all
        )
        let downloaded = options.first { $0.type == .downloaded }
        result = ChapterFlags.shared.withDownloadedFilter(
            flags: result,
            filter: downloaded.map { $0.exclude ? ChapterFilter.exclude : ChapterFilter.include } ?? ChapterFilter.all
        )
        return Int(result)
    }
}

enum ChapterFilterMethod: CaseIterable, Hashable {
    case downloaded
    case unread
    case locked

    var stringValue: String {
        switch self {
            case .downloaded: NSLocalizedString("DOWNLOADED", comment: "")
            case .unread: NSLocalizedString("UNREAD", comment: "")
            case .locked: NSLocalizedString("LOCKED", comment: "")
        }
    }
}
