// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

private final class DownloadOrigin: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

/// Stream into a caller-owned temporary file, with a hard bound even for chunked responses.
public enum AudioDownload {
    public static func save(_ url: URL, to file: URL, wifiOnly: Bool) async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 20; configuration.timeoutIntervalForResource = 180
        configuration.httpCookieStorage = nil; configuration.urlCredentialStorage = nil; configuration.urlCache = nil
        configuration.allowsCellularAccess = !wifiOnly
        let session = URLSession(configuration: configuration, delegate: DownloadOrigin(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        try await withTaskCancellationHandler {
            let (bytes, response) = try await session.bytes(from: url)
            let limit = 100_000_000
            guard (response as? HTTPURLResponse)?.statusCode == 200, response.mimeType?.hasPrefix("audio/") == true,
                  response.expectedContentLength <= Int64(limit) else { throw MusicError.message("Не удалось скачать аудио до 100 МБ") }
            guard FileManager.default.createFile(atPath: file.path, contents: nil) else { throw MusicError.message("Не удалось сохранить аудио") }
            let output = try FileHandle(forWritingTo: file); defer { try? output.close() }
            var count = 0; var chunk = Data(); chunk.reserveCapacity(65536)
            for try await byte in bytes {
                try Task.checkCancellation()
                guard count < limit else { throw MusicError.message("Аудиофайл превышает 100 МБ") }
                chunk.append(byte); count += 1
                if chunk.count == 65536 { try output.write(contentsOf: chunk); chunk.removeAll(keepingCapacity: true) }
            }
            try Task.checkCancellation()
            guard count > 0 else { throw MusicError.message("Сервер вернул пустой аудиофайл") }
            if !chunk.isEmpty { try output.write(contentsOf: chunk) }
        } onCancel: { session.invalidateAndCancel() }
    }
}
