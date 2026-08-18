//
//  MangaIdentifier.swift
//  Aidoku
//
//  Created by Skitty on 10/24/25.
//

import Foundation

struct MangaIdentifier: Hashable, Equatable, Codable, Sendable {
    let sourceKey: String
    let mangaKey: String
}

extension MangaIdentifier: CustomStringConvertible {
    var description: String {
        "\(sourceKey).\(mangaKey)"
    }
}

/// Small persisted values used to decorate the library. Keeping these outside
/// Core Data avoids a schema migration and, more importantly, means opening the
/// library never has to recount every chapter or walk every download folder.
enum LibraryBadgeCache {
    enum Kind {
        case unread
        case downloaded

        fileprivate var key: String {
            switch self {
                case .unread: "Library.cachedUnreadBadges"
                case .downloaded: "Library.cachedDownloadBadges"
            }
        }
    }

    private struct StoredBadge: Codable {
        let identifier: MangaIdentifier
        let count: Int
    }

    static func load(_ kind: Kind) -> [MangaIdentifier: Int] {
        guard
            let data = UserDefaults.standard.data(forKey: kind.key),
            let badges = try? JSONDecoder().decode([StoredBadge].self, from: data)
        else {
            return [:]
        }
        return Dictionary(
            badges.map { ($0.identifier, $0.count) },
            uniquingKeysWith: { _, latest in latest }
        )
    }

    static func save(_ values: [MangaIdentifier: Int], kind: Kind) {
        let badges = values.map {
            StoredBadge(identifier: $0.key, count: $0.value)
        }
        guard let data = try? JSONEncoder().encode(badges) else { return }
        UserDefaults.standard.set(data, forKey: kind.key)
    }
}
