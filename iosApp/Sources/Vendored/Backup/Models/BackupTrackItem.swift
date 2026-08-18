//
//  BackupTrackItem.swift
//  Aidoku
//
//  Created by Skitty on 7/21/22.
//

import Foundation

struct BackupTrackItem: Codable, Hashable {
    var id: String
    var trackerId: String
    var mangaId: String
    var sourceId: String
    var title: String?
    var chapterOffset: Int?

    init(
        id: String,
        trackerId: String,
        mangaId: String,
        sourceId: String,
        title: String? = nil,
        chapterOffset: Int? = nil
    ) {
        self.id = id
        self.trackerId = trackerId
        self.mangaId = mangaId
        self.sourceId = sourceId
        self.title = title
        self.chapterOffset = chapterOffset
    }

}
