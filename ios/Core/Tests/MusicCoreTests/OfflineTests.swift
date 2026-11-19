import XCTest
@testable import MusicCore

final class OfflineTests: XCTestCase {
    func testPublishedAudioSurvivesCrashBeforeIndexWrite() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let library = OfflineLibrary(root: root, key: { $0 })
        let song = try JSONDecoder().decode(Song.self, from: Data("{\"id\":\"a\",\"title\":\"Audio\"}".utf8))
        try library.saveQueued([song])
        try Data([1, 2, 3]).write(to: library.audio(song))
        let restored = OfflineLibrary(root: root, key: { $0 })
        XCTAssertEqual(restored.queued().map(\.id), ["a"])
        XCTAssertTrue(restored.offline().isEmpty)
        XCTAssertTrue(try restored.recoverDownloaded(song))
        try restored.finishQueued(song)
        XCTAssertEqual(restored.offline().map(\.id), ["a"])
        XCTAssertTrue(restored.queued().isEmpty)
    }
    func testFailedBatchCanBePersistedAgainBeforeResume() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let library = OfflineLibrary(root: root, key: { $0 })
        let songs = try JSONDecoder().decode([Song].self, from: Data("[{\"id\":\"a\",\"title\":\"A\"},{\"id\":\"b\",\"title\":\"B\"}]".utf8))
        let obstacle = root.appendingPathComponent("download-queue/b.json")
        try FileManager.default.createDirectory(at: obstacle, withIntermediateDirectories: true)
        XCTAssertThrowsError(try library.saveQueued(songs))
        XCTAssertEqual(library.queued().map(\.id), ["a"])
        try FileManager.default.removeItem(at: obstacle)
        try library.saveQueued(songs)
        XCTAssertEqual(Set(OfflineLibrary(root: root, key: { $0 }).queued().map(\.id)), Set(["a", "b"]))
        XCTAssertTrue(library.offline().isEmpty)
    }
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
