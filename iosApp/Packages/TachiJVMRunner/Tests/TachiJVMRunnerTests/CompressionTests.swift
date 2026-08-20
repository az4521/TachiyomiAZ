import Foundation
import XCTest
@testable import TachiJVMRunner

final class CompressionTests: XCTestCase {
    func testDecompressesGzipData() throws {
        let compressed = try XCTUnwrap(
            Data(base64Encoded:
                "H4sIAAAAAAAC/wtJTM7IrMzPzXSMUigoyi/JTypNUyhJLS4B" +
                "AAV4nEEZAAAA"
            )
        )
        let result = try TachiJVMCompression.gunzip(compressed)
        XCTAssertEqual(
            String(data: result, encoding: .utf8),
            "TachiyomiAZ protobuf test"
        )
    }

    func testGzipRoundTrip() throws {
        let input = Data("TachiyomiAZ .tachibk export".utf8)
        let compressed = try TachiJVMCompression.gzip(input)
        XCTAssertEqual(compressed.prefix(2), Data([0x1f, 0x8b]))
        XCTAssertEqual(try TachiJVMCompression.gunzip(compressed), input)
    }

    func testEmptyGzipRoundTrip() throws {
        let compressed = try TachiJVMCompression.gzip(Data())
        XCTAssertEqual(try TachiJVMCompression.gunzip(compressed), Data())
    }
}
