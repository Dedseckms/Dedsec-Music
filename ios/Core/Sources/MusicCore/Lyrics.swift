// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

public struct LyricLine: Codable, Sendable {
    public let startMs: Int?
    public let text: String
}
public struct SongLyrics: Codable, Sendable {
    public let status: String
    public let synced: Bool
    public let lines: [LyricLine]
    public let source: String
    public let revision: String
    public static func parse(_ data: Data) throws -> SongLyrics {
        guard data.count <= 524288 else { throw MusicError.message("Слишком большой текст") }
        let result = try JSONDecoder().decode(Self.self, from: data)
        guard ["ready", "pending", "not_found", "instrumental", "error"].contains(result.status), result.lines.count <= 4000,
              result.lines.allSatisfy({ $0.text.count <= 4096 && ($0.startMs == nil || (0...86400000).contains($0.startMs!)) }) else { throw MusicError.message("Некорректный текст") }
        return result
    }
    public var timed: Bool { synced && !lines.isEmpty && lines.allSatisfy { $0.startMs != nil } && zip(lines, lines.dropFirst()).allSatisfy { pair in pair.0.startMs! <= pair.1.startMs! } }
    public func activeLine(at milliseconds: Int) -> Int? {
        guard timed else { return nil }
        return lines.lastIndex { $0.startMs! <= milliseconds }
    }
}
