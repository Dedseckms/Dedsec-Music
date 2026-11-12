// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

public struct Song: Codable, Identifiable, Equatable, Sendable {
    public let id: String
    public let title: String
    public let artist: String?
    public let album: String?
    public let albumId: String?
    public let artistId: String?
    public let coverArt: String?
    public let genre: String?
    public let duration: Double?
    public let starred: String?
}
public struct Album: Codable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let artist: String?
    public let coverArt: String?
    public let songCount: Int?
}
public struct Artist: Codable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let albumCount: Int?
    public let coverArt: String?
}
public struct MusicPlaylist: Codable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let owner: String?
    public let songCount: Int?
    public let `public`: Bool?
    public let entry: [Song]?
}
public struct SearchResult: Codable, Sendable {
    public let song: [Song]?
    public let artist: [Artist]?
    public let album: [Album]?
}
public struct ImportTrack: Codable, Sendable { public let title: String; public let artist: String }
public struct ImportJob: Codable, Identifiable, Sendable {
    public let id: String
    public let state: String
    public let phase: String
    public let discovered: Int64
    public let total: Int64
    public let downloaded: Int64
    public let skipped: Int64
    public let failed: Int64
    public let unavailable: Int64
    public let excluded: Int64
    public let processed: Int64
    public let current: ImportTrack?
    public let playlistCount: Int64?
    public let completedPlaylistCount: Int64?
    public var active: Bool { ["queued", "running", "cancelling"].contains(state) }
    public var resumable: Bool { ["cancelled", "paused_auth", "failed", "finished_with_errors"].contains(state) }
    public var syncingPlaylists: Bool { active && ["indexing", "playlists"].contains(phase) }
    public var progress: Double? {
        guard total > 0, ["download", "finished"].contains(phase) else { return nil }
        return min(1, max(0, Double(processed) / Double(total)))
    }
    public var label: String {
        if state == "paused_auth" { return "Нужен токен для продолжения" }
        if state == "cancelled" { return "Перенос остановлен" }
        if state == "completed" { return "Коллекция перенесена" }
        if state == "finished_with_errors" { return "Перенос завершён с пропусками" }
        if state == "failed" { return "Не удалось завершить перенос" }
        if state == "cancelling" { return "Останавливаем перенос" }
        return ["inventory": "Собираем коллекцию", "classification": "Отбираем музыку", "download": "Скачиваем на сервер",
                "indexing": "Обновляем каталог", "playlists": "Восстанавливаем плейлисты"][phase] ?? "Ожидаем очередь"
    }
}
public struct ImportEnvelope: Codable, Sendable { public let job: ImportJob? }
public struct Credentials: Codable, Sendable {
    public let server: String
    public let username: String
    public let password: String
    public init(server: String, username: String, password: String) { self.server = server; self.username = username; self.password = password }
}
public enum MusicError: Error, LocalizedError, Sendable {
    case message(String)
    public var errorDescription: String? { if case .message(let value) = self { return value }; return "Не удалось выполнить операцию" }
}
public enum Mood: String, CaseIterable, Identifiable, Sendable {
    case any = "Любое", chill = "Чилл", energy = "Бодро", focus = "Фокус", workout = "Спорт", party = "Вечер", sad = "Грусть", happy = "Радость"
    public var id: Self { self }
    public var icon: String { switch self { case .any: return "sparkles"; case .chill: return "leaf"; case .energy: return "bolt.fill"; case .focus: return "scope"; case .workout: return "figure.run"; case .party: return "party.popper"; case .sad: return "cloud"; case .happy: return "sun.max" } }
    public func matches(_ song: Song) -> Bool {
        let genre = (song.genre ?? "").lowercased()
        let words: [String]
        switch self {
        case .any: return true
        case .chill: words = ["chill", "ambient", "lounge", "downtempo", "lofi"]
        case .energy, .workout: words = ["rock", "metal", "dance", "drum", "hard", "phonk", "punk"]
        case .focus: words = ["ambient", "instrumental", "classical", "jazz", "lofi"]
        case .party: words = ["dance", "house", "disco", "pop", "electro"]
        case .sad: words = ["blues", "emo", "sad", "acoustic", "indie"]
        case .happy: words = ["funk", "pop", "ska", "disco", "soul"]
        }
        return words.contains { genre.contains($0) }
    }
}
public struct PlaybackQueue: Sendable {
    public private(set) var order: [Int] = []
    public private(set) var position = 0
    public init() {}
    public mutating func set(count: Int, selected: Int) {
        order = Array(0..<max(0, count)); position = order.indices.contains(selected) ? selected : 0
    }
    public mutating func shuffle(_ enabled: Bool) {
        guard !order.isEmpty else { return }
        let current = order[position]
        if enabled { order = [current] + order.filter { $0 != current }.shuffled(); position = 0 }
        else { order.sort(); position = order.firstIndex(of: current) ?? 0 }
    }
    public var current: Int? { order.indices.contains(position) ? order[position] : nil }
    public mutating func step(_ forward: Bool, wrap: Bool = false) -> Int? {
        guard !order.isEmpty else { return nil }
        let next = position + (forward ? 1 : -1)
        if order.indices.contains(next) { position = next }
        else if wrap { position = forward ? 0 : order.count - 1 }
        else { return nil }
        return current
    }
}
