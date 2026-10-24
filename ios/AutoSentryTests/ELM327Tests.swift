import XCTest
@testable import AutoSentry

final class ELM327Tests: XCTestCase {
    func testReadsRpmWithSpacesOff() {
        // 0x1AF8 = 6904, / 4 = 1726 rpm
        XCTAssertEqual(ELM327.rpm(from: "410C1AF8\r\r>"), 1726)
    }

    func testReadsRpmWithSpacesOn() {
        // 0x0BB8 = 3000, / 4 = 750 rpm
        XCTAssertEqual(ELM327.rpm(from: "41 0C 0B B8 \r\r>"), 750)
    }

    func testSkipsTheProtocolSearchLine() {
        XCTAssertEqual(ELM327.rpm(from: "SEARCHING...\r410C0000\r\r>"), 0)
    }

    func testNoAnswerIsNil() {
        XCTAssertNil(ELM327.rpm(from: "NO DATA\r\r>"))
        XCTAssertNil(ELM327.rpm(from: "STOPPED\r\r>"))
        XCTAssertNil(ELM327.rpm(from: ""))
    }

    func testBadHexIsNil() {
        XCTAssertNil(ELM327.parseReply("410CZZ12\r>", header: "410C"))
    }

    func testOddLengthPayloadIsSkipped() {
        XCTAssertNil(ELM327.parseReply("410C1AF\r>", header: "410C"))
    }
}
