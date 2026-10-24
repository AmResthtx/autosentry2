import XCTest
@testable import AutoSentry

final class KeyOnDetectorTests: XCTestCase {
    func testKeyOnThenStartThenStopThenKeyOff() {
        var detector = KeyOnDetector()
        XCTAssertEqual(detector.update(rpm: 0), .keyOn)
        XCTAssertEqual(detector.state, .keyOn)
        XCTAssertNil(detector.update(rpm: 0))
        XCTAssertNil(detector.update(rpm: 180), "cranking is still key on")
        XCTAssertEqual(detector.update(rpm: 750), .engineStarted(rpm: 750))
        XCTAssertNil(detector.update(rpm: 760))
        XCTAssertEqual(detector.update(rpm: 0), .engineStopped)
        XCTAssertNil(detector.update(rpm: nil))
        XCTAssertNil(detector.update(rpm: nil))
        XCTAssertEqual(detector.update(rpm: nil), .keyOff)
        XCTAssertEqual(detector.state, .keyOff)
    }

    func testOneMissedAnswerIsNotKeyOff() {
        var detector = KeyOnDetector()
        _ = detector.update(rpm: 750)
        XCTAssertNil(detector.update(rpm: nil))
        XCTAssertNil(detector.update(rpm: 755))
        XCTAssertEqual(detector.state, .running)
    }

    func testConnectingToARunningTruckReportsTheEngine() {
        var detector = KeyOnDetector()
        XCTAssertEqual(detector.update(rpm: 700), .engineStarted(rpm: 700))
    }

    func testSilenceBeforeAnyAnswerIsJustKeyOff() {
        var detector = KeyOnDetector()
        for _ in 0..<KeyOnDetector.missesForKeyOff {
            XCTAssertNil(detector.update(rpm: nil))
        }
        XCTAssertEqual(detector.state, .keyOff)
        XCTAssertEqual(detector.update(rpm: 0), .keyOn)
    }
}
