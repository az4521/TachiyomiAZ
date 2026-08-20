//
//  ChapterTitleDisplayMode.swift
//  Aidoku
//
//  Created by Skitty on 9/29/25.
//

import Foundation
import TachiyomiKit

/// What a chapter row is titled with.
///
/// Two modes, matching the shared column. There was a third -- volume -- which nothing outside this
/// app could represent: `mangas.chapter_flags` has one bit for the display mode, Android has no
/// volume concept, and `manga_sync` stores `last_chapter_read` with no volume counterpart, so it
/// also drove tracker progress that could not be recorded. It has been dropped rather than kept as
/// a setting whose effects stopped at the edge of this app.
///
/// Stored in `chapter_flags` alongside the filters and sort order. It used to live in UserDefaults
/// under `Manga.chapterDisplayMode.<source>.<manga>`, which meant setting it here did nothing on
/// the other app and setting it there did nothing here.
enum ChapterTitleDisplayMode: Int, CaseIterable {
    /// The name the source gave the chapter.
    case `default` = 0
    /// "Chapter 12", from its number.
    case chapter = 1

    init(flags: Int) {
        self = ChapterFlags.shared.displayMode(flags: Int32(flags)) == .number ? .chapter : .default
    }

    /// Writes this mode into `flags`, leaving the filters and sort order alone.
    func apply(to flags: Int) -> Int {
        Int(
            ChapterFlags.shared.withDisplayMode(
                flags: Int32(flags),
                mode: self == .chapter ? ChapterDisplayMode.number : ChapterDisplayMode.name
            )
        )
    }
}

extension ChapterTitleDisplayMode {
    var localizedTitle: String {
        switch self {
            case .default: NSLocalizedString("DISPLAY_DEFAULT")
            case .chapter: NSLocalizedString("DISPLAY_CHAPTER")
        }
    }
}
