import XCTest
@testable import MusicCore

final class OfflineTests: XCTestCase {
    func testActualListeningProducesOneOffer() {
        var gate = OfflineListenGate()
        for second in 0..<8 { XCTAssertFalse(gate.observe(key: "a:x", playing: true, position: Double(second), duration: 10, clock: Double(second))) }
        XCTAssertTrue(gate.observe(key: "a:x", playing: true, position: 8, duration: 10, clock: 8))
        XCTAssertFalse(gate.observe(key: "a:x", playing: true, position: 9, duration: 10, clock: 9))
    }
    func testSeekAndAccountChangeDoNotQualify() {
        var gate = OfflineListenGate()
        XCTAssertFalse(gate.observe(key: "a:x", playing: true, position: 0, duration: 100, clock: 0))
        XCTAssertFalse(gate.observe(key: "a:x", playing: true, position: 90, duration: 100, clock: 1))
        XCTAssertFalse(gate.observe(key: "b:x", playing: true, position: 91, duration: 100, clock: 2))
    }
}
