// SPDX-License-Identifier: GPL-3.0-or-later
import XCTest
@testable import MusicCore

final class CoreTests: XCTestCase {
    func testAccountOriginRequiresHTTPSAndRejectsEmbeddedCredentials() throws {
        XCTAssertEqual(try MusicAPI.validateServer("https://music.example.org/base").path, "/base")
        for value in ["http://music.example.org", "https://user:secret@music.example.org", "https://music.example.org/?token=secret", "https://music.example.org/#x", "file:///tmp/music"] {
            XCTAssertThrowsError(try MusicAPI.validateServer(value))
        }
    }
    func testQueueKeepsDuplicatePositionsAndShuffleAffectsTheVeryNextStep() {
        var queue = PlaybackQueue(); queue.set(count: 5, selected: 2); queue.shuffle(true)
        XCTAssertEqual(queue.current, 2); XCTAssertEqual(queue.order.first, 2)
        XCTAssertEqual(Set(queue.order), Set(0..<5))
        let next = queue.step(true); XCTAssertNotEqual(next, 2)
        XCTAssertEqual(queue.step(false), 2)
    }
    func testSingleTrackEndsWithoutWrapping() {
        var queue = PlaybackQueue(); queue.set(count: 1, selected: 0)
        XCTAssertNil(queue.step(true)); XCTAssertEqual(queue.current, 0)
        XCTAssertEqual(queue.step(true, wrap: true), 0)
    }
    func testImportProgressUsesEligibleCountAndNoInventedDiscoveryPercent() throws {
        let json = #"{"id":"42","state":"running","phase":"download","discovered":12,"total":10,"downloaded":2,"skipped":4,"failed":1,"unavailable":1,"excluded":2,"processed":8}"#
        let job = try JSONDecoder().decode(ImportJob.self, from: Data(json.utf8))
        XCTAssertEqual(job.progress, 0.8); XCTAssertTrue(job.active)
        let discovery = try JSONDecoder().decode(ImportJob.self, from: Data(json.replacingOccurrences(of: "download\"", with: "inventory\"").utf8))
        XCTAssertNil(discovery.progress)
    }
    func testShortMetadataAndIntegerDurationsDecode() throws {
        let song = try JSONDecoder().decode(Song.self, from: Data(#"{"id":"same","title":"Track","duration":120}"#.utf8))
        XCTAssertEqual(song.duration, 120); XCTAssertNil(song.artist)
    }
    func testRadioMoodTokensAndEmptyAnalysisRemainExplicit() throws {
        XCTAssertEqual(Set(Mood.allCases.map(\.apiValue)), Set(["ANY", "CHILL", "ENERGY", "FOCUS", "WORKOUT", "PARTY", "SAD", "HAPPY"]))
        let mix = try JSONDecoder().decode(RadioMix.self, from: Data(#"{"songs":[],"reason":"ANALYZING","analysis":{"indexed":0,"total":100}}"#.utf8))
        XCTAssertTrue(mix.songs.isEmpty); XCTAssertTrue(mix.emptyMessage.contains("анализирует"))
    }
    func testOldImportResponseAllowsMissingPlaylistCounters() throws {
        let json = #"{"job":{"id":"42","state":"paused_auth","phase":"paused","discovered":0,"total":0,"downloaded":0,"skipped":0,"failed":0,"unavailable":0,"excluded":0,"processed":0}}"#
        let envelope = try JSONDecoder().decode(ImportEnvelope.self, from: Data(json.utf8))
        XCTAssertTrue(envelope.job?.resumable == true); XCTAssertNil(envelope.job?.playlistCount)
    }
}
