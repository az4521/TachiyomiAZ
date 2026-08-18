//
//  LibraryMembershipSnapshot.swift
//
//  Extracted from tachiyomiazios (Shared/Managers/Manga/MangaManager.swift): what a title's library
//  membership looked like before it was removed, so the removal can be undone. Carries no logic.
//

import Foundation

struct LibraryMembershipSnapshot: Sendable {
    let identifier: MangaIdentifier
    let categories: [String]
    let lastOpened: Date
    let lastUpdated: Date
    let lastUpdatedChapters: Date
    let lastChapter: Date?
    let lastRead: Date?
    let dateAdded: Date
}
