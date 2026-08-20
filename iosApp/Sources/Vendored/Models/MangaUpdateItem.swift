//
//  MangaUpdateItem.swift
//
//  Extracted from tachiyomiazios (Shared/Data/Database/Objects/MangaUpdateObject.swift). The
//  CoreData entity around it is not vendored; this is the plain value the updates screen renders.
//

import Foundation

struct MangaUpdateItem {
    let sourceId: String?
    let chapterId: String?
    let mangaId: String?
    let viewed: Bool
}
