import Foundation
import XCTest
@testable import TachiJVMRunner

final class TachiyomiXExtensionClientTests: XCTestCase {
func testMangaPageDecodesFromExtensionHostPayload() throws {
    let payload = """
    {
      "mangas": [
        {
          "url": "/series/nano-machine",
          "title": "Nano Machine",
          "thumbnailURL": "https://cdn.example/cover.webp",
          "artist": null,
          "author": null,
          "status": 0,
          "description": null,
          "genre": null,
          "memo": "{\\\"slug\\\":\\\"nano-machine\\\"}"
        }
      ],
      "hasNextPage": true
    }
    """

    let page = try JSONDecoder().decode(
        TachiyomiXMangaPage.self,
        from: Data(payload.utf8)
    )

    XCTAssertEqual(page.mangas.count, 1)
    XCTAssertEqual(page.mangas[0].title, "Nano Machine")
    XCTAssertEqual(page.mangas[0].memo, "{\"slug\":\"nano-machine\"}")
    XCTAssertTrue(page.hasNextPage)
}

func testSourceFactoryDescriptorsDecodeFromExtensionHostPayload() throws {
    let payload = """
    [
      {
        "id": 2499283573021220255,
        "name": "Example Source",
        "lang": "en",
        "supportsLatest": true,
        "baseURL": "https://example.org"
      }
    ]
    """

    let sources = try JSONDecoder().decode(
        [TachiyomiXSourceDescriptor].self,
        from: Data(payload.utf8)
    )

    XCTAssertEqual(sources, [
        TachiyomiXSourceDescriptor(
            id: 2499283573021220255,
            name: "Example Source",
            lang: "en",
            supportsLatest: true,
            baseURL: "https://example.org"
        )
    ])
}

func testMangaUpdateAndPagesDecodeFromExtensionHostPayloads() throws {
    let updatePayload = """
    {
      "manga": {
        "url": "/manga/example",
        "title": "Example",
        "thumbnailURL": null,
        "artist": null,
        "author": "Author",
        "status": 1,
        "description": "Description",
        "genre": "Action",
        "memo": "{\\\"slug\\\":\\\"example\\\"}"
      },
      "chapters": [
        {
          "url": "/chapter/1",
          "name": "Chapter 1",
          "chapterNumber": 1.0,
          "scanlator": "Group",
          "dateUpload": 1700000000000,
          "memo": "{\\\"mangaId\\\":\\\"example\\\"}"
        }
      ]
    }
    """
    let pagesPayload = """
    [
      {
        "index": 0,
        "url": "",
        "imageURL": "https://uploads.example/page-1.jpg",
        "uri": null
      }
    ]
    """

    let update = try JSONDecoder().decode(
        TachiyomiXMangaUpdate.self,
        from: Data(updatePayload.utf8)
    )
    let pages = try JSONDecoder().decode(
        [TachiyomiXPage].self,
        from: Data(pagesPayload.utf8)
    )

    XCTAssertEqual(update.manga.title, "Example")
    XCTAssertEqual(update.manga.memo, "{\"slug\":\"example\"}")
    XCTAssertEqual(update.chapters[0].chapterNumber, 1)
    XCTAssertEqual(update.chapters[0].memo, "{\"mangaId\":\"example\"}")
    XCTAssertEqual(update.chapters[0].dateUpload, 1_700_000_000_000)
    XCTAssertEqual(pages[0].imageURL, "https://uploads.example/page-1.jpg")
}
}
