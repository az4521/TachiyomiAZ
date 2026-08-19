//
//  MangaInfo.swift
//  Aidoku
//
//  Created by Skitty on 8/7/22.
//

import Foundation
import ExtensionRunner

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

    /// The library row as a source result.
    ///
    /// A library cell carries only what it draws, so the rest comes back empty rather than
    /// invented -- this exists to hand a title to something that wants a manga, not to be one.
    func toManga() -> ExtensionRunner.Manga {
        ExtensionRunner.Manga(
            sourceKey: sourceId,
            key: mangaId,
            title: title ?? "",
            cover: coverUrl?.absoluteString,
            artists: nil,
            authors: author.map { [$0] },
            description: nil,
            url: url,
            tags: tags,
            status: .unknown,
            contentRating: .safe,
            viewer: .unknown,
            updateStrategy: .always,
            nextUpdateTime: nil,
            chapters: nil
        )
    }
}
