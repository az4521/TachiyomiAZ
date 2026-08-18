//
//  BackupChapter.swift
//  Aidoku
//
//  Created by Skitty on 2/26/22.
//

import Foundation

struct BackupChapter: Codable, Hashable {
    var sourceId: String
    var mangaId: String
    var id: String
    var title: String?
    var scanlator: String?
    var url: String?
    var lang: String
    var chapter: Float?
    var volume: Float?
    var dateUploaded: Date?
    var thumbnail: String?
    var locked: Bool?
    var bookmarked: Bool?
    var sourceOrder: Int

    init(
        sourceId: String,
        mangaId: String,
        id: String,
        title: String? = nil,
        scanlator: String? = nil,
        url: String? = nil,
        lang: String = "",
        chapter: Float? = nil,
        volume: Float? = nil,
        dateUploaded: Date? = nil,
        thumbnail: String? = nil,
        locked: Bool? = nil,
        bookmarked: Bool? = nil,
        sourceOrder: Int = 0
    ) {
        self.sourceId = sourceId
        self.mangaId = mangaId
        self.id = id
        self.title = title
        self.scanlator = scanlator
        self.url = url
        self.lang = lang
        self.chapter = chapter
        self.volume = volume
        self.dateUploaded = dateUploaded
        self.thumbnail = thumbnail
        self.locked = locked
        self.bookmarked = bookmarked
        self.sourceOrder = sourceOrder
    }

}
