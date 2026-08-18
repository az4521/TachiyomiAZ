//
//  BackupLibraryManga.swift
//  Aidoku
//
//  Created by Skitty on 2/26/22.
//

import Foundation

struct BackupLibraryManga: Codable, Hashable {
    var lastOpened: Date
    var lastUpdated: Date
    var lastUpdatedChapters: Date?
    var lastChapter: Date?
    var lastRead: Date?
    var dateAdded: Date
    var categories: [String]?

    var mangaId: String
    var sourceId: String

    var identifier: MangaIdentifier {
        .init(sourceKey: sourceId, mangaKey: mangaId)
    }

    init(
        lastOpened: Date = .distantPast,
        lastUpdated: Date = .distantPast,
        lastUpdatedChapters: Date? = nil,
        lastChapter: Date? = nil,
        lastRead: Date? = nil,
        dateAdded: Date,
        categories: [String]? = nil,
        mangaId: String,
        sourceId: String
    ) {
        self.lastOpened = lastOpened
        self.lastUpdated = lastUpdated
        self.lastUpdatedChapters = lastUpdatedChapters
        self.lastChapter = lastChapter
        self.lastRead = lastRead
        self.dateAdded = dateAdded
        self.categories = categories
        self.mangaId = mangaId
        self.sourceId = sourceId
    }

}
