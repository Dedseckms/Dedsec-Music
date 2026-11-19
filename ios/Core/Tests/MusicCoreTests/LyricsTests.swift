import XCTest
@testable import MusicCore

final class LyricsTests: XCTestCase {
    func testTimestampsFollowSeekingAndPlainTextHasNoInventedTiming() throws {
        let lyrics = try SongLyrics.parse(Data("{\"status\":\"ready\",\"synced\":true,\"source\":\"LRCLIB\",\"revision\":\"1\",\"lines\":[{\"startMs\":1000,\"text\":\"A\"},{\"startMs\":3000,\"text\":\"B\"}]}".utf8))
        XCTAssertNil(lyrics.activeLine(at: 999)); XCTAssertEqual(lyrics.activeLine(at: 1000), 0)
        XCTAssertEqual(lyrics.activeLine(at: 3500), 1); XCTAssertEqual(lyrics.activeLine(at: 1200), 0)
        let plain = try SongLyrics.parse(Data("{\"status\":\"ready\",\"synced\":true,\"source\":\"LRCLIB\",\"revision\":\"1\",\"lines\":[{\"text\":\"Plain\"}]}".utf8))
        XCTAssertFalse(plain.timed); XCTAssertNil(plain.activeLine(at: 99999))
    }
    func testMalformedLyricsAreRejected() {
        XCTAssertThrowsError(try SongLyrics.parse(Data("{\"status\":\"ready\",\"synced\":true,\"source\":\"x\",\"revision\":\"1\",\"lines\":[{\"startMs\":-10,\"text\":\"Bad\"}]}".utf8)))
    }
}
