//
//  BackupCategory.swift
//  Aidoku
//
//  Created by Skitty on 2/26/26.
//

import Foundation

struct BackupCategory {
    let title: String?
    let sort: Int?
    let group: Bool?
    let data: Data?

    init(
        title: String?,
        sort: Int? = nil,
        group: Bool? = nil,
        data: Data? = nil
    ) {
        self.title = title
        self.sort = sort
        self.group = group
        self.data = data
    }

}

extension BackupCategory: Decodable {
    init(from decoder: any Decoder) throws {
        // try decoding just as a string
        let container = try decoder.singleValueContainer()
        if let title = try? container.decode(String.self) {
            self.title = title
            self.sort = nil
            self.group = nil
            self.data = nil
            return
        }
        // otherwise, assume object
        let objectContainer = try decoder.container(keyedBy: CodingKeys.self)
        self.title = try? objectContainer.decodeIfPresent(String.self, forKey: .title)
        self.sort = try? objectContainer.decodeIfPresent(Int.self, forKey: .sort)
        self.group = try? objectContainer.decodeIfPresent(Bool.self, forKey: .group)
        self.data = try? objectContainer.decodeIfPresent(Data.self, forKey: .data)
    }

    private enum CodingKeys: String, CodingKey {
        case title
        case sort
        case group
        case data
    }
}

extension BackupCategory: Encodable {}

extension BackupCategory: Hashable {}
