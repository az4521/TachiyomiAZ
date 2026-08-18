//
//  BackupUpdate.swift
//  Aidoku
//
//  Created by Skitty on 12/21/25.
//

import Foundation

struct BackupUpdate: Codable, Hashable {
    var date: Date
    var viewed: Bool
    var sourceId: String
    var mangaId: String
    var chapterId: String

}
