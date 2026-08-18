//
//  BackupHistory.swift
//  Aidoku
//
//  Created by Skitty on 2/26/22.
//

import Foundation

struct BackupHistory: Codable, Hashable {
    var dateRead: Date
    var sourceId: String
    var chapterId: String
    var mangaId: String
    var progress: Int?
    var total: Int?
    var completed: Bool

    init(
        dateRead: Date,
        sourceId: String,
        chapterId: String,
        mangaId: String,
        progress: Int? = nil,
        total: Int? = nil,
        completed: Bool
    ) {
        self.dateRead = dateRead
        self.sourceId = sourceId
        self.chapterId = chapterId
        self.mangaId = mangaId
        self.progress = progress
        self.total = total
        self.completed = completed
    }

}
