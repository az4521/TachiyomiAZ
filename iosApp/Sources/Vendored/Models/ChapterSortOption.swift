//
//  ChapterSortOption.swift
//  Aidoku
//
//  Created by Skitty on 2/14/24.
//

import Foundation
import TachiyomiKit

enum ChapterSortOption: Int, CaseIterable {
    case sourceOrder = 0
    case chapter
    case uploadDate

    init(flags: Int) {
        self = switch ChapterFlags.shared.sort(flags: Int32(flags)) {
            case .number: .chapter
            case .uploadDate: .uploadDate
            default: .sourceOrder
        }
    }

    /// Writes this option into `flags`, leaving every other part of the column alone.
    func apply(to flags: Int) -> Int {
        let sort: ChapterSort = switch self {
            case .sourceOrder: .source
            case .chapter: .number
            case .uploadDate: .uploadDate
        }
        return Int(ChapterFlags.shared.withSort(flags: Int32(flags), sort: sort))
    }

    var stringValue: String {
        switch self {
            case .sourceOrder: NSLocalizedString("SOURCE_ORDER", comment: "")
            case .chapter: NSLocalizedString("CHAPTER", comment: "")
            case .uploadDate: NSLocalizedString("UPLOAD_DATE", comment: "")
        }
    }
}
