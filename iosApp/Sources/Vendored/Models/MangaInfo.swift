//
//  MangaInfo.swift
//  Aidoku
//
//  Created by Skitty on 8/7/22.
//

import ExtensionRunner
import Foundation

struct MangaInfo: Hashable, Sendable {
    var identifier: MangaIdentifier { .init(sourceKey: sourceId, mangaKey: mangaId) }

    let mangaId: String
    let sourceId: String

    var coverUrl: URL?
    var title: String?
    var author: String?
    var tags: [String]?

    var url: URL?

    var unread: Int = 0
    var downloads: Int = 0

    func toManga() -> Manga {
        // Field names differ from upstream's older MangaInfo: sourceKey/key rather than
        // sourceId/id, cover as a String, and authors as an array.
        Manga(
            sourceKey: sourceId,
            key: mangaId,
            title: title ?? "",
            cover: coverUrl?.absoluteString,
            authors: author.map { [$0] },
            url: url,
            tags: tags
        )
    }
}
