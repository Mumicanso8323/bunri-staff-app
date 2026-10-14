import XCTest
@testable import BunriStaff

final class AdminAPITests: XCTestCase {
    private let base = URL(string: "https://bunrisai.jp")!

    /// 受けた要求(メソッド パス)を順に控える。
    private var log: [String] = []
    private var cookieHeaders: [String?] = []

    override func setUp() {
        log = []
        cookieHeaders = []
        MockURLProtocol.handler = nil
    }

    override func tearDown() { MockURLProtocol.handler = nil }

    private func makeAPI(password: String? = "pw") -> AdminAPI {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        return AdminAPI(baseURL: base, configuration: config, passwordProvider: { password })
    }

    private func response(_ status: Int, _ headers: [String: String] = [:]) -> HTTPURLResponse {
        HTTPURLResponse(url: base, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
    }

    private func loginOK(_ token: String = "T1") -> (HTTPURLResponse, Data) {
        (response(303, ["Location": "/admin",
                        "Set-Cookie": "admin_token=\(token); Path=/; Max-Age=86400; HttpOnly; Secure; SameSite=Lax"]), Data())
    }

    private func json(_ status: Int, _ text: String, _ headers: [String: String] = [:]) -> (HTTPURLResponse, Data) {
        (response(status, headers), Data(text.utf8))
    }

    private func route(_ routes: @escaping (_ key: String, _ request: URLRequest) throws -> (HTTPURLResponse, Data)) {
        MockURLProtocol.handler = { [unowned self] request in
            let key = "\(request.httpMethod ?? "GET") \(request.url!.path)"
            self.log.append(key)
            self.cookieHeaders.append(request.value(forHTTPHeaderField: "Cookie"))
            return try routes(key, request)
        }
    }

    private let playerJSON = """
    {"guestId":"11111111-2222-3333-4444-555555555555","sessionId":"S1","sessionStatus":"active","isLatestSession":true,
     "hasBingo":true,"claimed":false,"claimedAt":null,"joinedAt":"2026-10-18T05:00:00.000Z","card":[1,2,3],"drawsCount":10}
    """

    // MARK: ログイン

    func testLoginSendsPasswordAsFormAndStoresCookie() async throws {
        var body = ""
        route { key, request in
            if key == "POST /api/admin/login" { body = request.bodyString; return self.loginOK("abc.def") }
            return self.json(200, #"{"id":"S1","status":"waiting","created_at":"2026-10-18T05:00:00.000Z"}"#)
        }
        let api = makeAPI(password: "p w&=/x")
        _ = try await api.createSession()
        XCTAssertEqual(body, "password=p%20w%26%3D%2Fx")
        XCTAssertEqual(log, ["POST /api/admin/login", "POST /api/admin/bingo/session"])
        XCTAssertEqual(cookieHeaders.last ?? nil, "admin_token=abc.def")
    }

    func testSecondCallReusesTheCookie() async throws {
        route { key, _ in
            key == "POST /api/admin/login" ? self.loginOK() : self.json(200, #"{"id":"S1","status":"waiting","created_at":"x"}"#)
        }
        let api = makeAPI()
        _ = try await api.createSession()
        _ = try await api.createSession()
        XCTAssertEqual(log.filter { $0.hasSuffix("/login") }.count, 1)
    }

    func testBadPasswordIsReportedAndNotRetried() async {
        route { _, _ in (self.response(303, ["Location": "/admin?error=1"]), Data()) }
        let api = makeAPI()
        do {
            _ = try await api.createSession()
            XCTFail("should throw")
        } catch {
            XCTAssertEqual(error as? APIError, .login(.badPassword))
        }
        XCTAssertEqual(log, ["POST /api/admin/login"])
    }

    func testLockedIsReported() async {
        route { _, _ in (self.response(303, ["Location": "/admin?error=locked"]), Data()) }
        do {
            try await makeAPI().login()
            XCTFail("should throw")
        } catch {
            XCTAssertEqual(error as? APIError, .login(.locked))
        }
    }

    func testNoPasswordDoesNotTouchTheNetwork() async {
        route { _, _ in XCTFail("no request expected"); return self.json(500, "") }
        for password in [nil, ""] {
            do {
                try await makeAPI(password: password).login()
                XCTFail("should throw")
            } catch {
                XCTAssertEqual(error as? APIError, .login(.noPassword))
            }
        }
        XCTAssertTrue(log.isEmpty)
    }

    func testRedirectWithoutCookieIsNotSuccess() async {
        route { _, _ in (self.response(303, ["Location": "/admin"]), Data()) }
        do {
            try await makeAPI().login()
            XCTFail("should throw")
        } catch {
            XCTAssertEqual(error as? APIError, .login(.unexpected(303)))
        }
    }

    // MARK: 401 のやり直し

    func testUnauthorizedLogsInAgainAndRetriesOnce() async throws {
        var draws = 0
        var logins = 0
        route { key, _ in
            switch key {
            case "POST /api/admin/login":
                logins += 1
                return self.loginOK("T\(logins)")
            default:
                draws += 1
                return draws == 1 ? self.json(401, #"{"error":"unauthorized"}"#)
                                  : self.json(200, #"{"number":42,"remaining":74,"totalDrawn":1}"#)
            }
        }
        let outcome = try await makeAPI().draw(sessionId: "S1")
        XCTAssertEqual(outcome, .drawn(DrawResult(number: 42, remaining: 74, totalDrawn: 1)))
        XCTAssertEqual(log, ["POST /api/admin/login", "POST /api/admin/bingo/draw", "POST /api/admin/login", "POST /api/admin/bingo/draw"])
        XCTAssertEqual(cookieHeaders.last ?? nil, "admin_token=T2")
    }

    func testSecondUnauthorizedIsAFailureNotALoop() async {
        route { key, _ in key.hasSuffix("/login") ? self.loginOK() : self.json(401, #"{"error":"unauthorized"}"#) }
        do {
            _ = try await makeAPI().draw(sessionId: "S1")
            XCTFail("should throw")
        } catch {
            XCTAssertEqual(error as? APIError, .unauthorized)
        }
        XCTAssertEqual(log.count, 4) // login, draw, login, draw
    }

    func testNetworkErrorOnWriteIsNotRetried() async {
        var draws = 0
        route { key, _ in
            if key.hasSuffix("/login") { return self.loginOK() }
            draws += 1
            throw URLError(.timedOut)
        }
        do {
            _ = try await makeAPI().draw(sessionId: "S1")
            XCTFail("should throw")
        } catch {
            XCTAssertEqual(error as? APIError, .network)
        }
        XCTAssertEqual(draws, 1)
    }

    // MARK: 状態の取得(ETag)

    func testSnapshotSendsETagAndHandles304() async throws {
        let body = #"{"session":{"id":"S1","status":"active","created_at":"x"},"draws":[5,9],"playerCount":3,"bingoCount":1,"updatedAt":1000}"#
        var seenIfNoneMatch: [String?] = []
        route { _, request in
            seenIfNoneMatch.append(request.value(forHTTPHeaderField: "If-None-Match"))
            if request.value(forHTTPHeaderField: "If-None-Match") == "W/\"abc\"" { return self.json(304, "") }
            return self.json(200, body, ["ETag": "W/\"abc\""])
        }
        let api = makeAPI()
        guard case .changed(let snapshot, let etag) = try await api.snapshot(etag: nil) else { return XCTFail("expected 200") }
        XCTAssertEqual(snapshot.draws, [5, 9])
        XCTAssertEqual(snapshot.session?.status, .active)
        XCTAssertEqual(etag, "W/\"abc\"")
        let again = try await api.snapshot(etag: etag)
        XCTAssertEqual(again, .notModified)
        XCTAssertEqual(seenIfNoneMatch.count, 2)
        XCTAssertNil(seenIfNoneMatch[0] ?? nil)
        XCTAssertEqual(seenIfNoneMatch[1] ?? nil, "W/\"abc\"")
        XCTAssertFalse(log.contains { $0.hasSuffix("/login") }, "公開 API はログインしない")
    }

    func testSnapshotWithNoSession() async throws {
        route { _, _ in self.json(200, #"{"session":null,"draws":[],"playerCount":0,"bingoCount":0,"updatedAt":1}"#) }
        guard case .changed(let s, _) = try await makeAPI().snapshot(etag: nil) else { return XCTFail() }
        XCTAssertNil(s.session)
    }

    // MARK: 抽選

    func testDrawOutcomes() async throws {
        let cases: [(Int, String, DrawOutcome)] = [
            (409, #"{"error":"Session is not active"}"#, .notActive),
            (400, #"{"error":"All 75 numbers have been drawn","remaining":0}"#, .exhausted),
            (404, #"{"error":"not_found"}"#, .notFound),
        ]
        for (status, body, expected) in cases {
            route { key, _ in key.hasSuffix("/login") ? self.loginOK() : self.json(status, body) }
            let outcome = try await makeAPI().draw(sessionId: "S1")
            XCTAssertEqual(outcome, expected, "status \(status)")
        }
    }

    func testDrawServerErrorThrows() async {
        route { key, _ in key.hasSuffix("/login") ? self.loginOK() : self.json(500, "oops") }
        do {
            _ = try await makeAPI().draw(sessionId: "S1")
            XCTFail("should throw")
        } catch {
            XCTAssertEqual(error as? APIError, .server(status: 500, code: nil))
        }
    }

    func testSetStatusBody() async throws {
        var sent = ""
        route { key, request in
            if key.hasSuffix("/login") { return self.loginOK() }
            sent = request.bodyString
            return self.json(200, #"{"id":"S1","status":"finished","created_at":"x"}"#)
        }
        let s = try await makeAPI().setStatus(sessionId: "S1", status: .finished)
        XCTAssertEqual(s.status, .finished)
        XCTAssertTrue(sent.contains(#""status":"finished""#) && sent.contains(#""sessionId":"S1""#))
        XCTAssertEqual(log.last, "PATCH /api/admin/bingo/session")
    }

    // MARK: 景品

    func testClaimSuccess() async throws {
        route { key, _ in key.hasSuffix("/login") ? self.loginOK() : self.json(200, #"{"ok":true,"player":\#(self.playerJSON)}"#) }
        let outcome = try await makeAPI().claim(guestId: "G", sessionId: "S1")
        guard case .claimed(let p) = outcome else { return XCTFail("\(outcome)") }
        XCTAssertEqual(p.sessionId, "S1")
    }

    func testClaimRefusals() async throws {
        let cases: [(String, ClaimRefusal)] = [
            ("already_claimed", .alreadyClaimed), ("no_bingo", .noBingo), ("stale_session", .staleSession), ("weird", .other("weird")),
        ]
        for (code, expected) in cases {
            route { key, _ in key.hasSuffix("/login") ? self.loginOK() : self.json(409, #"{"error":"\#(code)","player":\#(self.playerJSON)}"#) }
            let outcome = try await makeAPI().claim(guestId: "G", sessionId: "S1")
            guard case .refused(let reason, let player) = outcome else { return XCTFail("\(outcome)") }
            XCTAssertEqual(reason, expected)
            XCTAssertNotNil(player)
        }
    }

    func testClaimNotFoundAndNullPlayer() async throws {
        route { key, _ in key.hasSuffix("/login") ? self.loginOK() : self.json(404, #"{"error":"not_found"}"#) }
        let notFound = try await makeAPI().claim(guestId: "G", sessionId: "S1")
        XCTAssertEqual(notFound, .notFound)
        route { key, _ in key.hasSuffix("/login") ? self.loginOK() : self.json(409, #"{"error":"already_claimed","player":null}"#) }
        let refused = try await makeAPI().claim(guestId: "G", sessionId: "S1")
        XCTAssertEqual(refused, .refused(.alreadyClaimed, nil))
    }

    func testPlayerLookup() async throws {
        var query: String?
        route { key, request in
            if key.hasSuffix("/login") { return self.loginOK() }
            query = request.url?.query
            return self.json(200, #"{"player":\#(self.playerJSON)}"#)
        }
        let result = try await makeAPI().player(guestId: "11111111-2222-3333-4444-555555555555")
        guard case .found(let p) = result else { return XCTFail() }
        XCTAssertTrue(p.hasBingo)
        XCTAssertEqual(query, "guest=11111111-2222-3333-4444-555555555555")
    }
}
