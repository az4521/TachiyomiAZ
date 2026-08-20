//
//  BackupReadingSession.swift
//  Aidoku
//
//  Created by Skitty on 12/21/25.
//

import Foundation

struct BackupReadingSession: Codable, Hashable {
    var pagesRead: Int
    var startDate: Date
    var endDate: Date

    var sourceId: String
    var mangaId: String
    var chapterId: String

    var identifier: ChapterIdentifier {
        .init(sourceKey: sourceId, mangaKey: mangaId, chapterKey: chapterId)
    }

}
