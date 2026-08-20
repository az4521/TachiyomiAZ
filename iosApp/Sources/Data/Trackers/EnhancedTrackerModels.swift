import Foundation

/// The server responses the enhanced trackers decode.
///
/// Komga, Kavita and Suwayomi track against the same server the manga came from, so their trackers
/// speak that server's API directly. Upstream gets these types from its built-in sources; here the
/// sources are JVM extensions and none of that exists on the Swift side.
///
/// Only what the trackers decode is here, which is a small fraction of what those sources define.
/// Vendoring the full model files would drag in the browsing layer -- libraries, filters, sort
/// fields, dashboard components -- none of which has a reader in this app, and all of which would
/// then need maintaining against server API changes it never exercises.

// MARK: - Komga

struct KomgaPageResponse<T: Codable & Sendable>: Codable, Sendable {
    let content: T
    let totalPages: Int
}

struct KomgaBook: Codable, Sendable {
    struct Media: Codable, Sendable {
        let mediaProfile: String
        let epubDivinaCompatible: Bool
        let pagesCount: Int
    }

    struct Metadata: Codable, Sendable {
        struct Author: Codable, Sendable {
            let name: String
            let role: String?
        }

        let title: String
        let number: String
        let numberSort: Float
        let authors: [Author]
        let tags: [String]
        let created: Date
        let releaseDate: Date?
    }

    let id: String
    let seriesId: String
    let libraryId: String
    let name: String
    let media: Media
    let metadata: Metadata
    let readProgress: KomgaBookReadProgress?
}

struct KomgaBookReadProgress: Codable, Sendable {
    let page: Int
    let completed: Bool
    let lastModified: Date?
}

// MARK: - Kavita

struct KavitaVolume: Codable, Sendable {
    struct File: Codable, Sendable {
        let format: Int
    }

    struct Chapter: Codable, Sendable {
        let id: Int
        let number: String
        let title: String
        let titleName: String?
        let createdUtc: Date
        let language: String?
        let pages: Int
        let pagesRead: Int
        let lastReadingProgressUtc: Date
        let files: [File]
    }

    let id: Int
    let name: String
    let number: Int
    let seriesId: Int
    let chapters: [Chapter]
}

// MARK: - Suwayomi

// Suwayomi speaks GraphQL, so each of these mirrors one query's response shape. They are copied
// verbatim from the fork, where they live alongside the source rather than the tracker despite
// being named for it.
struct SuwayomiTrackStateResponse: Decodable, Sendable {
    let data: DataContainer

    struct DataContainer: Decodable, Sendable {
        let manga: Manga
    }

    struct Manga: Decodable, Sendable {
        let chapters: ChapterConnection
        let latestReadChapter: Chapter?
        let highestNumberedChapter: Chapter?
    }

    struct ChapterConnection: Decodable, Sendable {
        let totalCount: Int?
    }

    struct Chapter: Decodable, Sendable {
        let chapterNumber: Float?
    }
}

struct SuwayomiTrackChaptersResponse: Decodable, Sendable {
    let data: DataContainer

    struct DataContainer: Decodable, Sendable {
        let chapters: ChapterConnection
    }

    struct ChapterConnection: Decodable, Sendable {
        let nodes: [Chapter]
    }

    struct Chapter: Decodable, Sendable {
        let id: Int
        let chapterNumber: Float?
    }
}

struct SuwayomiReadProgressResponse: Decodable, Sendable {
    let data: DataContainer

    struct DataContainer: Decodable, Sendable {
        let chapters: ChapterConnection
    }

    struct ChapterConnection: Decodable, Sendable {
        let nodes: [Chapter]
    }

    struct Chapter: Decodable, Sendable {
        let id: Int
        let isRead: Bool
        let lastPageRead: Int
        let lastReadAt: String
        let pageCount: Int
    }
}

struct SuwayomiChapterProgressPatch: Encodable, Sendable {
    var isRead: Bool?
    var lastPageRead: Int?
}

struct SuwayomiUpdateChapterResponse: Decodable, Sendable {
    let data: DataContainer?

    struct DataContainer: Decodable, Sendable {
        let updateChapter: Payload?
    }

    struct Payload: Decodable, Sendable {
        let chapter: Chapter?
    }

    struct Chapter: Decodable, Sendable {
        let id: Int
    }
}

struct SuwayomiUpdateChaptersResponse: Decodable, Sendable {
    let data: DataContainer?

    struct DataContainer: Decodable, Sendable {
        let updateChapters: Payload?
    }

    struct Payload: Decodable, Sendable {
        let chapters: [Chapter]
    }

    struct Chapter: Decodable, Sendable {
        let id: Int
    }
}

extension Date {
    /// Suwayomi's GraphQL returns timestamps as a numeric string, in seconds or milliseconds
    /// depending on the field. The threshold is the fork's: anything past the year 2286 in seconds
    /// is milliseconds.
    init?(suwayomiTimestamp: String) {
        guard let value = Double(suwayomiTimestamp) else { return nil }
        self.init(timeIntervalSince1970: value > 10_000_000_000 ? value / 1000 : value)
    }
}
