// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import XCTest
@testable import MusicCore

private final class HeadersWithoutBody: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let response = HTTPURLResponse(url: request.url!, statusCode: 201, httpVersion: "HTTP/1.1",
            headerFields: ["Content-Length": "1000000000", "Content-Type": "application/octet-stream"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        // Deliberately never send a body or completion: upload must finish from headers alone.
    }
    override func stopLoading() {}
}

final class UploadTests: XCTestCase {
    func testUploadAcceptsHeadersWithoutReadingHugeOrUnfinishedResponseBody() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [HeadersWithoutBody.self]
        let transfer = HeaderOnlyUpload(configuration: config)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Data("audio fixture".utf8).write(to: file); defer { try? FileManager.default.removeItem(at: file) }
        var request = URLRequest(url: URL(string: "https://music.example.org/upload")!); request.httpMethod = "POST"
        let response = try await transfer.send(request, file: file)
        XCTAssertEqual(response.statusCode, 201)
    }
    func testCancellationBeforeSessionRegistrationCannotStartAnUpload() async throws {
        let transfer = HeaderOnlyUpload(); transfer.cancel()
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Data("audio fixture".utf8).write(to: file); defer { try? FileManager.default.removeItem(at: file) }
        var request = URLRequest(url: URL(string: "https://music.example.org/upload")!); request.httpMethod = "POST"
        do { _ = try await transfer.send(request, file: file); XCTFail("Cancelled upload completed") }
        catch is CancellationError {}
    }
}
