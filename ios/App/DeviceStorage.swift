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
    static func offline(_ account: Credentials) -> [Song] {
        guard let root = try? folder(account), let data = try? Data(contentsOf: root.appendingPathComponent("offline.json")),
              let songs = try? JSONDecoder().decode([Song].self, from: data) else { return [] }
        return songs.filter { FileManager.default.fileExists(atPath: root.appendingPathComponent(hash($0.id) + ".mp3").path) }
    }
    static func saveIndex(_ songs: [Song], _ account: Credentials) throws {
        try JSONEncoder().encode(songs).write(to: folder(account).appendingPathComponent("offline.json"), options: .atomic)
    }
    static func audio(_ song: Song, _ account: Credentials) throws -> URL { try folder(account).appendingPathComponent(hash(song.id) + ".mp3") }
}
