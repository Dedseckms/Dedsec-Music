// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Security
import CryptoKit
import MusicCore

enum DeviceStorage {
    private static let service = "ru.dedseckms.music.credentials"
    static func remembered() -> Credentials? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: "music", kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(Credentials.self, from: data)
    }
    static func remember(_ account: Credentials?) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "music"]
        guard let account else { SecItemDelete(query as CFDictionary); return }
        let data = try JSONEncoder().encode(account)
        let updates: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let result = SecItemUpdate(query as CFDictionary, updates as CFDictionary)
        if result == errSecItemNotFound {
            guard SecItemAdd(query.merging(updates) { _, new in new } as CFDictionary, nil) == errSecSuccess else { throw MusicError.message("Не удалось сохранить вход в Связке ключей") }
        } else if result != errSecSuccess { throw MusicError.message("Не удалось сохранить вход в Связке ключей") }
    }
    static func hash(_ value: String) -> String { SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined() }
    static func folder(_ account: Credentials) throws -> URL {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("DedSecMusic/" + hash(account.server + "|" + account.username.lowercased()), isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        var mutable = root; try mutable.setResourceValues(values)
        return root
    }
    private static func records(_ name: String, _ account: Credentials) throws -> URL {
        let path = try folder(account).appendingPathComponent(name, isDirectory: true)
        try FileManager.default.createDirectory(at: path, withIntermediateDirectories: true)
        return path
    }
    private static func readRecords(_ name: String, _ account: Credentials) -> [Song] {
        guard let path = try? records(name, account), let files = try? FileManager.default.contentsOfDirectory(at: path, includingPropertiesForKeys: nil) else { return [] }
        return files.filter { $0.pathExtension == "json" }.compactMap { url in guard let data = try? Data(contentsOf: url) else { return nil }; return try? JSONDecoder().decode(Song.self, from: data) }
    }
    static func offline(_ account: Credentials) -> [Song] {
        let legacy: [Song] = (try? Data(contentsOf: folder(account).appendingPathComponent("offline.json"))).flatMap { try? JSONDecoder().decode([Song].self, from: $0) } ?? []
        var seen = Set<String>()
        return (readRecords("offline-index", account) + legacy).filter { song in
            guard seen.insert(song.id).inserted, let file = try? audio(song, account), let attributes = try? FileManager.default.attributesOfItem(atPath: file.path), let size = attributes[.size] as? NSNumber else { return false }
            return size.int64Value > 0
        }
    }
    static func saveDownloaded(_ song: Song, _ account: Credentials) throws {
        try JSONEncoder().encode(song).write(to: records("offline-index", account).appendingPathComponent(hash(song.id) + ".json"), options: .atomic)
    }
    static func removeIndex(_ song: Song, _ account: Credentials) throws {
        let url = try records("offline-index", account).appendingPathComponent(hash(song.id) + ".json")
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }
    static func queued(_ account: Credentials) -> [Song] {
        readRecords("download-queue", account).filter { song in
            guard let file = try? audio(song, account) else { return true }
            if FileManager.default.fileExists(atPath: file.path) { try? finishQueued(song, account); return false }
            return true
        }
    }
    static func saveQueued(_ songs: [Song], _ account: Credentials) throws {
        let path = try records("download-queue", account)
        for song in songs { try JSONEncoder().encode(song).write(to: path.appendingPathComponent(hash(song.id) + ".json"), options: .atomic) }
    }
    static func finishQueued(_ song: Song, _ account: Credentials) throws {
        let file = try records("download-queue", account).appendingPathComponent(hash(song.id) + ".json")
        if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
    }
    static func audio(_ song: Song, _ account: Credentials) throws -> URL { try folder(account).appendingPathComponent(hash(song.id) + ".mp3") }
}
