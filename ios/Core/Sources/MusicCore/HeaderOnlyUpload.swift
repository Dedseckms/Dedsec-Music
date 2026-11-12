// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Upload from disk and accept only the response headers; no response body is buffered.
final class HeaderOnlyUpload: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let configuration: URLSessionConfiguration
    private var continuation: CheckedContinuation<HTTPURLResponse, Error>?
    private var session: URLSession?
    private var cancelled = false

    init(configuration: URLSessionConfiguration = .ephemeral) {
        self.configuration = configuration; super.init()
    }

    func send(_ request: URLRequest, file: URL) async throws -> HTTPURLResponse {
        try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { pending in
                let config = configuration
                config.timeoutIntervalForRequest = 30; config.timeoutIntervalForResource = 300
                config.httpCookieStorage = nil; config.urlCredentialStorage = nil; config.urlCache = nil
                let connection = URLSession(configuration: config, delegate: self, delegateQueue: nil)
                let task = connection.uploadTask(with: request, fromFile: file)
                lock.lock()
                if cancelled {
                    lock.unlock(); connection.invalidateAndCancel(); pending.resume(throwing: CancellationError()); return
                }
                continuation = pending; session = connection; lock.unlock()
                task.resume()
            }
        } onCancel: { self.cancel() }
    }
    func cancel() {
        lock.lock(); cancelled = true; lock.unlock()
        finish(.failure(CancellationError()))
    }
    private func finish(_ result: Result<HTTPURLResponse, Error>) {
        lock.lock(); let pending = continuation; continuation = nil
        let connection = session; session = nil; lock.unlock()
        pending?.resume(with: result); connection?.invalidateAndCancel()
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        completionHandler(.cancel)
        if let http = response as? HTTPURLResponse { finish(.success(http)) }
        else { finish(.failure(MusicError.message("Не удалось загрузить аудио"))) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        finish(.failure(error ?? MusicError.message("Сервер не подтвердил загрузку")))
    }
}
