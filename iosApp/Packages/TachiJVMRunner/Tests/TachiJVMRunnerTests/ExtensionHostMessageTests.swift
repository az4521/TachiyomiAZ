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
            userAgent: "TachiyomiAZ-Test"
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
    }
}
