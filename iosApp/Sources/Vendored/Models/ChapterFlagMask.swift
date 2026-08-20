//
//  ChapterFlagMask.swift
//  Aidoku
//

import Foundation
import TachiyomiKit

/// Reads and writes `mangas.chapter_flags`.
///
/// The bit layout is not defined here any more. `ChapterFlags` in `:core-domain` owns it and the
/// Android app reads the same column through the same numbers.
///
/// This file used to declare Aidoku's layout, which disagrees with Android's on almost every bit:
/// its sort method sat at bits 1-3, over Android's read and downloaded filters, and its unread
/// filter at bit 6, over Android's bookmarked filter. One database, one column, two vocabularies --
/// so a filter set on either app produced unrelated filters and a wrong sort order on the other,
/// silently and in both directions.
///
/// Adopting Android's layout reinterprets flags already stored by this app once. That is a
/// deliberate trade: the alternative is translating on every read forever, to preserve settings
/// that were already being misread by the other side.
enum ChapterFlagMask {
    static func sortAscending(_ flags: Int) -> Bool {
        ChapterFlags.shared.ascending(flags: Int32(flags))
    }

    static func withSortAscending(_ flags: Int, _ ascending: Bool) -> Int {
        Int(ChapterFlags.shared.withAscending(flags: Int32(flags), ascending: ascending))
    }
}
