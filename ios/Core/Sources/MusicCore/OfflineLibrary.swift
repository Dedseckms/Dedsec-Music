// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Per-track durable records. A published audio file is indexed before its queue record is retired.
public struct OfflineLibrary {
    private let root: URL
    private let key: (String) -> String
    public init(root: URL, key: @escaping (String) -> String) { self.root = root; self.key = key }
    private func directory(_ name: String) throws -> URL {
        let url = root.appendingPathComponent(name, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
    private func records(_ name: String) -> [Song] {
        guard let path = try? directory(name), let files = try? FileManager.default.contentsOfDirectory(at: path, includingPropertiesForKeys: nil) else { return [] }
        return files.filter { $0.pathExtension == "json" }.compactMap { url in
            guard let data = try? Data(contentsOf: url) else { return nil }
            return try? JSONDecoder().decode(Song.self, from: data)
        }
    }
    public func audio(_ song: Song) -> URL { root.appendingPathComponent(key(song.id) + ".mp3") }
    public func hasAudio(_ song: Song) -> Bool {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: audio(song).path),
              let size = attributes[.size] as? NSNumber else { return false }
        return size.int64Value > 0
    }
    public func offline() -> [Song] {
        let legacy = (try? Data(contentsOf: root.appendingPathComponent("offline.json")))
            .flatMap { try? JSONDecoder().decode([Song].self, from: $0) } ?? []
        var seen = Set<String>()
        return (records("offline-index") + legacy).filter { seen.insert($0.id).inserted && hasAudio($0) }
    }
    public func saveDownloaded(_ song: Song) throws {
        try JSONEncoder().encode(song).write(to: directory("offline-index").appendingPathComponent(key(song.id) + ".json"), options: .atomic)
    }
    public func recoverDownloaded(_ song: Song) throws -> Bool {
        guard hasAudio(song) else { return false }
        try saveDownloaded(song)
        return true
    }
    public func removeIndex(_ song: Song) throws {
        try removeRecord(song, "offline-index")
    }
    public func queued() -> [Song] {
        // Leave complete files queued until the downloader repairs their index. A crash can occur
        // between the atomic audio publication and the index write.
        records("download-queue")
    }
    public func saveQueued(_ songs: [Song]) throws {
        let path = try directory("download-queue")
        for song in songs { try JSONEncoder().encode(song).write(to: path.appendingPathComponent(key(song.id) + ".json"), options: .atomic) }
    }
    public func finishQueued(_ song: Song) throws { try removeRecord(song, "download-queue") }
    private func removeRecord(_ song: Song, _ name: String) throws {
        let url = try directory(name).appendingPathComponent(key(song.id) + ".json")
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }
}
