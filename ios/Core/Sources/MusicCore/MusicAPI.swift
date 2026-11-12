// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

private final class NoRedirect: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
public actor MusicAPI {
    public static let defaultServer = "https://music.dedseckms.ru"
    public static let defaultUploadServer = "https://upload.music.dedseckms.ru"
    private let credentials: Credentials
    private let base: URL
    private let session: URLSession
    private var token: String?
    private var authentication: Task<String, Error>?
    private var valid = true
    private var uploads: [UUID: HeaderOnlyUpload] = [:]
    public static let tokenGuide = URL(string: "https://github.com/Dedseckms/Dedsec-Music/blob/main/docs/YANDEX_IMPORT.md#получить-токен")!
    public init(_ credentials: Credentials) throws {
        self.credentials = credentials
        self.base = try Self.validateServer(credentials.server)
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 15; config.timeoutIntervalForResource = 30
        config.httpCookieStorage = nil; config.urlCredentialStorage = nil; config.urlCache = nil
        self.session = URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
    }
    public static func validateServer(_ value: String) throws -> URL {
        guard let parts = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              parts.scheme == "https", let host = parts.host, !host.isEmpty, parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil, let url = parts.url else { throw MusicError.message("Укажи HTTPS-адрес музыкального сервера") }
        return url
    }
    private func endpoint(_ path: String) -> URL { base.appendingPathComponent(path) }
    private func data(_ request: URLRequest, limit: Int = 4_194_304) async throws -> (Data, HTTPURLResponse) {
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse, response.expectedContentLength <= Int64(limit) else { throw MusicError.message("Ответ сервера слишком большой") }
        var output = Data(); output.reserveCapacity(min(limit, 65536))
        for try await byte in bytes {
            try Task.checkCancellation()
            guard output.count < limit else { throw MusicError.message("Ответ сервера слишком большой") }
            output.append(byte)
        }
        return (output, http)
    }
    private func authorize() async throws -> String {
        guard valid else { throw CancellationError() }
        if let token { return token }
        if let authentication { return try await authentication.value }
        let credentials = credentials; let url = endpoint("auth/login")
        let pending = Task<String, Error> {
            var request = URLRequest(url: url); request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(["username": credentials.username, "password": credentials.password])
            let (body, response) = try await self.data(request, limit: 65536)
            guard response.statusCode == 200, let json = try JSONSerialization.jsonObject(with: body) as? [String: Any],
                  let value = json["token"] as? String, !value.isEmpty, value.count < 16384 else { throw MusicError.message("Не удалось войти. Проверь логин и пароль") }
            return value
        }
        authentication = pending
        do { let result = try await pending.value; guard valid else { throw CancellationError() }; token = result; authentication = nil; return result }
        catch { authentication = nil; throw error }
    }
    public func login() async throws { _ = try await authorize() }
    private func request(_ path: String, method: String = "GET", body: Data? = nil, mime: String = "application/json", limit: Int = 4_194_304, query: [URLQueryItem] = []) async throws -> Data {
        for attempt in 0...1 {
            var url = URLComponents(url: endpoint(path), resolvingAgainstBaseURL: false)!
            if !query.isEmpty { url.queryItems = query }
            var request = URLRequest(url: url.url!); request.httpMethod = method; request.httpBody = body
            request.setValue("Bearer \(try await authorize())", forHTTPHeaderField: "Authorization")
            if body != nil { request.setValue(mime, forHTTPHeaderField: "Content-Type") }
            let (bytes, response) = try await data(request, limit: limit)
            if response.statusCode == 401 && attempt == 0 { token = nil; continue }
            guard (200..<300).contains(response.statusCode) else {
                let message: String
                switch response.statusCode {
                case 401, 403: message = "Нет доступа. Войди заново"
                case 404: message = "Эта функция пока не включена на сервере"
                case 409: message = "Задача уже запущена. Обнови статус"
                case 413: message = "Файл или запрос слишком большой"
                case 429: message = "Сервер занят. Попробуй позже"
                default: message = "Сервер не выполнил операцию"
                }
                throw MusicError.message(message)
            }
            return bytes
        }
        throw MusicError.message("Войди в аккаунт заново")
    }
    private func subsonic(_ method: String, _ params: [URLQueryItem] = []) async throws -> [String: Any] {
        var url = URLComponents(url: endpoint("rest/\(method).view"), resolvingAgainstBaseURL: false)!
        url.queryItems = [URLQueryItem(name: "u", value: credentials.username), URLQueryItem(name: "jwt", value: try await authorize()),
            URLQueryItem(name: "v", value: "1.16.1"), URLQueryItem(name: "c", value: "DedSecMusicIOS"), URLQueryItem(name: "f", value: "json")] + params
        var request = URLRequest(url: endpoint("rest/\(method).view")); request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = url.percentEncodedQuery?.data(using: .utf8)
        let (bytes, response) = try await data(request)
        guard response.statusCode == 200, let outer = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              let result = outer["subsonic-response"] as? [String: Any] else { throw MusicError.message("Сервер музыки не отвечает") }
        guard result["status"] as? String == "ok" else {
            if (result["error"] as? [String: Any])?["code"] as? Int == 40 { token = nil }
            throw MusicError.message("Не удалось выполнить музыкальный запрос. Обнови данные или войди заново")
        }
        return result
    }
    private func decode<T: Decodable>(_ object: Any, as type: T.Type) throws -> T {
        try JSONDecoder().decode(type, from: JSONSerialization.data(withJSONObject: object))
    }
    private func p(_ name: String, _ value: String) -> URLQueryItem { URLQueryItem(name: name, value: value) }
    public func songs(query: String = "", offset: Int = 0) async throws -> [Song] {
        let result = try await subsonic("search3", [p("query", query), p("songCount", "100"), p("songOffset", String(offset)), p("artistCount", "0"), p("albumCount", "0")])
        return try decode((result["searchResult3"] as? [String: Any])?["song"] ?? [], as: [Song].self)
    }
    public func search(_ query: String) async throws -> SearchResult {
        let result = try await subsonic("search3", [p("query", query), p("songCount", "60"), p("artistCount", "25"), p("albumCount", "20")])
        return try decode(result["searchResult3"] ?? [:], as: SearchResult.self)
    }
    public func albums() async throws -> [Album] {
        let result = try await subsonic("getAlbumList2", [p("type", "newest"), p("size", "100")])
        return try decode((result["albumList2"] as? [String: Any])?["album"] ?? [], as: [Album].self)
    }
    public func favorites() async throws -> [Song] {
        let result = try await subsonic("getStarred2")
        return try decode((result["starred2"] as? [String: Any])?["song"] ?? [], as: [Song].self)
    }
    public func random() async throws -> [Song] {
        let result = try await subsonic("getRandomSongs", [p("size", "200")])
        return try decode((result["randomSongs"] as? [String: Any])?["song"] ?? [], as: [Song].self)
    }
    public func album(_ id: String) async throws -> [Song] {
        let result = try await subsonic("getAlbum", [p("id", id)])
        return try decode((result["album"] as? [String: Any])?["song"] ?? [], as: [Song].self)
    }
    public func artist(_ id: String) async throws -> [Album] {
        let result = try await subsonic("getArtist", [p("id", id)])
        return try decode((result["artist"] as? [String: Any])?["album"] ?? [], as: [Album].self)
    }
    public func playlists() async throws -> [MusicPlaylist] {
        let result = try await subsonic("getPlaylists")
        return try decode((result["playlists"] as? [String: Any])?["playlist"] ?? [], as: [MusicPlaylist].self)
            .filter { $0.owner?.caseInsensitiveCompare(credentials.username) == .orderedSame }
    }
    public func playlist(_ id: String) async throws -> MusicPlaylist {
        let result = try await subsonic("getPlaylist", [p("id", id)])
        let value = try decode(result["playlist"] ?? [:], as: MusicPlaylist.self)
        guard value.owner?.caseInsensitiveCompare(credentials.username) == .orderedSame else { throw MusicError.message("Это плейлист другого аккаунта") }
        return value
    }
    public func createPlaylist(_ name: String) async throws {
        let title = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, title.count <= 160 else { throw MusicError.message("Введи название до 160 символов") }
        let result = try await subsonic("createPlaylist", [p("name", title)])
        guard let id = (result["playlist"] as? [String: Any])?["id"] as? String else { throw MusicError.message("Не удалось создать плейлист") }
        do { _ = try await subsonic("updatePlaylist", [p("playlistId", id), p("public", "false")]) }
        catch { _ = try? await subsonic("deletePlaylist", [p("id", id)]); throw error }
    }
    public func add(_ song: Song, to playlist: MusicPlaylist) async throws {
        _ = try await self.playlist(playlist.id)
        _ = try await subsonic("updatePlaylist", [p("playlistId", playlist.id), p("songIdToAdd", song.id)])
    }
    public func remove(_ index: Int, from expected: MusicPlaylist) async throws {
        let fresh = try await playlist(expected.id)
        guard fresh.entry?.map(\.id) == expected.entry?.map(\.id), fresh.entry?.indices.contains(index) == true else { throw MusicError.message("Плейлист изменился. Открой его заново") }
        _ = try await subsonic("updatePlaylist", [p("playlistId", expected.id), p("songIndexToRemove", String(index))])
    }
    public func favorite(_ song: Song, enabled: Bool) async throws { _ = try await subsonic(enabled ? "star" : "unstar", [p("id", song.id)]) }
    public func mediaURL(_ method: String, id: String) async throws -> URL {
        guard ["stream", "getCoverArt"].contains(method) else { throw MusicError.message("Недопустимый запрос") }
        var url = URLComponents(url: endpoint("rest/\(method).view"), resolvingAgainstBaseURL: false)!
        url.queryItems = [p("u", credentials.username), p("jwt", try await authorize()), p("v", "1.16.1"), p("c", "DedSecMusicIOS"), p("id", id)]
        if method == "stream" { url.queryItems! += [p("format", "mp3"), p("maxBitRate", "320")] }
        else { url.queryItems!.append(p("size", "512")) }
        return url.url!
    }
    public func cover(_ id: String) async throws -> Data {
        let (bytes, response) = try await data(URLRequest(url: mediaURL("getCoverArt", id: id)), limit: 2_097_152)
        guard response.statusCode == 200, response.mimeType?.hasPrefix("image/") == true else { throw MusicError.message("Обложка недоступна") }
        return bytes
    }
    public func currentImport() async throws -> ImportJob? { try JSONDecoder().decode(ImportEnvelope.self, from: await request("dedsec/import/v1/yandex/jobs/current")).job }
    public func submitImport(_ oauth: String, resume id: String? = nil) async throws -> ImportJob? {
        guard !oauth.isEmpty, oauth.utf8.count <= 8192, !oauth.contains(where: \.isWhitespace) else { throw MusicError.message("Вставь только значение токена") }
        if let id { guard UUID(uuidString: id) != nil else { throw MusicError.message("Обнови статус переноса") } }
        let path = "dedsec/import/v1/yandex/jobs" + (id.map { "/\($0)/resume" } ?? "")
        return try JSONDecoder().decode(ImportEnvelope.self, from: await request(path, method: "POST", body: JSONEncoder().encode(["oauthToken": oauth]))).job
    }
    public func cancelImport(_ id: String) async throws -> ImportJob? {
        guard UUID(uuidString: id) != nil else { throw MusicError.message("Обнови статус переноса") }
        return try JSONDecoder().decode(ImportEnvelope.self, from: await request("dedsec/import/v1/yandex/jobs/\(id)/cancel", method: "POST", body: Data("{}".utf8))).job
    }
    public func upload(_ file: URL, server: String, username: String, password: String) async throws {
        let base = try Self.validateServer(server)
        let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, size <= 100_000_000 else { throw MusicError.message("Максимальный размер — 100 МБ") }
        var loginURL = URLComponents(url: base.appendingPathComponent("api/auth/login"), resolvingAgainstBaseURL: false)!
        loginURL.queryItems = [p("username", username)]
        var login = URLRequest(url: loginURL.url!); login.httpMethod = "POST"
        var encoded = URLComponents(); encoded.queryItems = [p("p", password)]
        login.setValue(String((encoded.percentEncodedQuery ?? "p=").dropFirst(2)), forHTTPHeaderField: "X-Password")
        let (bytes, response) = try await data(login, limit: 65536)
        try Task.checkCancellation(); guard valid else { throw CancellationError() }
        guard response.statusCode == 200, let jwt = String(data: bytes, encoding: .utf8), !jwt.isEmpty, jwt.count < 16384 else { throw MusicError.message("Проверь отдельный логин и пароль загрузки") }
        var target = URLComponents(url: base.appendingPathComponent("api/resources"), resolvingAgainstBaseURL: false)!
        target.queryItems = [p("source", "Uploads"), p("path", "/" + file.lastPathComponent), p("override", "false")]
        var request = URLRequest(url: target.url!); request.httpMethod = "POST"
        request.setValue("Bearer \(jwt)", forHTTPHeaderField: "Authorization"); request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        let transfer = HeaderOnlyUpload(); let id = UUID(); uploads[id] = transfer
        defer { uploads.removeValue(forKey: id) }
        let result = try await transfer.send(request, file: file)
        try Task.checkCancellation(); guard valid else { throw CancellationError() }
        guard (200..<300).contains(result.statusCode) else { throw MusicError.message("Файл отклонён сервером: проверь формат и длительность до 20 минут") }
    }
    public func isAdmin() async throws -> Bool {
        let data = try await request("dedsec/profile/v1/admin/me", limit: 65536)
        return (try JSONSerialization.jsonObject(with: data) as? [String: Any])?["isAdmin"] as? Bool == true
    }
    public func adminUsers() async throws -> [AdminUser] {
        struct Envelope: Decodable { let users: [AdminUser] }
        return try JSONDecoder().decode(Envelope.self, from: await request("dedsec/profile/v1/admin/users", limit: 524288)).users
    }
    private func adminUserPath(_ id: String) throws -> String {
        guard !id.isEmpty, id.count <= 80, id.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_" || $0 == "-") }) else { throw MusicError.message("Некорректный аккаунт") }
        return "dedsec/profile/v1/admin/users/" + id
    }
    public func adminUploads(_ id: String, offset: Int = 0) async throws -> AdminUploads {
        // Query parameters are built separately to preserve the fixed origin and route.
        let path = try adminUserPath(id) + "/uploads"
        return try JSONDecoder().decode(AdminUploads.self, from: await request(path, limit: 524288, query: [p("offset", String(max(0, offset))), p("limit", "50")]))
    }
    public func adminCreate(_ username: String, password: String) async throws -> Bool {
        guard !username.isEmpty, username.count <= 80, password.count >= 12, password.utf8.count <= 72 else { throw MusicError.message("Проверь логин и пароль: минимум 12 символов") }
        let data = try await request("dedsec/profile/v1/admin/users", method: "POST", body: JSONEncoder().encode(["username": username, "password": password]), limit: 65536)
        return (try JSONSerialization.jsonObject(with: data) as? [String: Any])?["uploadReady"] as? Bool == true
    }
    public func adminDelete(_ id: String) async throws { _ = try await request(adminUserPath(id), method: "DELETE", limit: 65536) }
    public func radio(_ mood: Mood, favoritesOnly: Bool = false, exclude: [String] = []) async throws -> RadioMix {
        let payload: [String: Any] = ["mood": mood.apiValue, "mode": favoritesOnly ? "FAVORITES" : "BALANCED", "size": 40,
                                     "excludeIds": Array(exclude.suffix(100)), "history": [], "dislikedIds": []]
        let bytes = try await request("dedsec/profile/v1/radio/mix", method: "POST", body: JSONSerialization.data(withJSONObject: payload), limit: 524288)
        let mix = try JSONDecoder().decode(RadioMix.self, from: bytes)
        guard mix.songs.count <= 60 else { throw MusicError.message("Слишком большой микс") }
        return mix
    }
    public func radioEvent(_ id: String, kind: String, position: Double, duration: Double) async throws {
        guard ["played", "skipped", "disliked"].contains(kind), position.isFinite, duration.isFinite else { return }
        let payload: [String: Any] = ["id": id, "kind": kind, "positionMs": Int(max(0, min(position, 86400)) * 1000),
                                     "durationMs": Int(max(0, min(duration, 86400)) * 1000)]
        _ = try await request("dedsec/profile/v1/radio/events", method: "POST", body: JSONSerialization.data(withJSONObject: payload), limit: 65536)
    }
    public func invalidate() {
        valid = false; authentication?.cancel(); authentication = nil; token = nil
        uploads.values.forEach { $0.cancel() }; uploads.removeAll(); session.invalidateAndCancel()
    }
}
