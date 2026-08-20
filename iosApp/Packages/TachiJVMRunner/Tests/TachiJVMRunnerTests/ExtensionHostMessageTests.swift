import Foundation
import XCTest
@testable import TachiJVMRunner

final class ExtensionHostMessageTests: XCTestCase {
    func testRequestEncodingUsesStableFieldNames() throws {
        let request = ExtensionHostRequest(
            operation: "loadExtension",
            extensionId: "fixture",
            sourceId: "2499283573021220255",
            jarPath: "/tmp/fixture.jar",
            entryClass: "fixture.EchoExtension",
            userAgent: "TachiyomiAZ-Test",
            mangaStatus: "6",
            mangaUpdateStrategy: "ONLY_FETCH_ONCE",
            mangaInitialized: "true",
            mangaChapters: "[{\"url\":\"/chapter/2\"},{\"url\":\"/chapter/1\"}]",
            chapterNumber: "12.5",
            chapterScanlator: "Group",
            chapterDateUpload: "1724137200000",
            fetchDetails: "false",
            fetchChapters: "true"
        )

        let data = try JSONEncoder().encode(request)
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: String]
        )

        XCTAssertEqual(object["operation"], "loadExtension")
        XCTAssertEqual(object["extensionId"], "fixture")
        XCTAssertEqual(object["sourceId"], "2499283573021220255")
        XCTAssertEqual(object["entryClass"], "fixture.EchoExtension")
        XCTAssertEqual(object["userAgent"], "TachiyomiAZ-Test")
        XCTAssertEqual(object["mangaStatus"], "6")
        XCTAssertEqual(object["mangaUpdateStrategy"], "ONLY_FETCH_ONCE")
        XCTAssertEqual(object["mangaInitialized"], "true")
        XCTAssertEqual(
            object["mangaChapters"],
            "[{\"url\":\"/chapter/2\"},{\"url\":\"/chapter/1\"}]"
        )
        XCTAssertEqual(object["chapterNumber"], "12.5")
        XCTAssertEqual(object["chapterScanlator"], "Group")
        XCTAssertEqual(object["chapterDateUpload"], "1724137200000")
        XCTAssertEqual(object["fetchDetails"], "false")
        XCTAssertEqual(object["fetchChapters"], "true")
    }

    func testMangaDecodingPreservesExtensionState() throws {
        let manga = try JSONDecoder().decode(
            TachiyomiXManga.self,
            from: Data(
                """
                {
                  "url":"/series/1",
                  "title":"Series",
                  "status":1,
                  "updateStrategy":"ONLY_FETCH_ONCE",
                  "initialized":true,
                  "memo":"{\\"token\\":\\"value\\"}"
                }
                """.utf8
            )
        )

        XCTAssertEqual(manga.updateStrategy, "ONLY_FETCH_ONCE")
        XCTAssertEqual(manga.initialized, true)
        XCTAssertEqual(manga.memo, "{\"token\":\"value\"}")
    }
}
