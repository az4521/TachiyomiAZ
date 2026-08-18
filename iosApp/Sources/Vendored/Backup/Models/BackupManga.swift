//
//  BackupManga.swift
//  Aidoku
//
//  Created by Skitty on 2/26/22.
//

import Foundation

struct BackupManga: Codable, Hashable {
    var id: String
    var sourceId: String
    var title: String
    var author: String?
    var artist: String?
    var desc: String?
    var tags: [String]?
    var cover: String?
    var url: String?
    var status: Int
    var nsfw: Int
    var viewer: Int
    var neverUpdate: Bool?
    var nextUpdateTime: Date?
    var chapterFlags: Int?
    var langFilter: String?
    var scanlatorFilter: [String]?
    var editedKeys: Int?

    init(
        id: String,
        sourceId: String,
        title: String,
        author: String? = nil,
        artist: String? = nil,
        desc: String? = nil,
        tags: [String]? = nil,
        cover: String? = nil,
        url: String? = nil,
        status: Int = 0,
        nsfw: Int = 0,
        viewer: Int = 0,
        neverUpdate: Bool? = nil,
        nextUpdateTime: Date? = nil,
        chapterFlags: Int? = nil,
        langFilter: String? = nil,
        scanlatorFilter: [String]? = nil,
        editedKeys: Int? = nil
    ) {
        self.id = id
        self.sourceId = sourceId
        self.title = title
        self.author = author
        self.artist = artist
        self.desc = desc
        self.tags = tags
        self.cover = cover
        self.url = url
        self.status = status
        self.nsfw = nsfw
        self.viewer = viewer
        self.neverUpdate = neverUpdate
        self.nextUpdateTime = nextUpdateTime
        self.chapterFlags = chapterFlags
        self.langFilter = langFilter
        self.scanlatorFilter = scanlatorFilter
        self.editedKeys = editedKeys
    }

}
