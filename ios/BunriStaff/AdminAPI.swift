import Foundation

enum LoginFailure: Equatable {
    case noPassword          // このビルドにログイン情報が無い
    case badPassword         // パスワードが合わない
    case locked              // サーバが一時的にログインを止めている(5 回失敗で 10 分)
    case unexpected(Int)
}

enum APIError: Error, Equatable {
    case login(LoginFailure)
    case unauthorized        // ログインし直しても 401
    case network
    case server(status: Int, code: String?)
    case decoding
}

enum SnapshotResult: Equatable {
    case changed(BingoSnapshot, etag: String?)
    case notModified
}

enum DrawOutcome: Equatable {
    case drawn(DrawResult)
    case notActive           // 409
    case exhausted           // 400: 75 個すべて引いた
    case notFound
}

enum PrizeOutcome: Equatable {
    case drawn(DrawResult)
    case exhausted           // 400 + remaining 0: 景品をすべて引いた
    case notFound
}

enum PrizeUndoOutcome: Equatable {
    case undone(Int)
    case nothingToUndo       // undone が null
    case notFound
}

enum PlayerLookup: Equatable {
    case found(PlayerView)
    case notFound
    case invalid
}

enum ClaimRefusal: Equatable {
    case noBingo, alreadyClaimed, staleSession
    case other(String)
}

enum ClaimOutcome: Equatable {
    case claimed(PlayerView)
    case refused(ClaimRefusal, PlayerView?)
    case notFound
}

/// 3xx を追わない(login は 303 の Set-Cookie だけ取れればよい)。
private final class RedirectBlocker: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

/// bunrisai.jp の API クライアント。ログインの Cookie は HTTPCookieStorage に置き、
/// 401 が返ったら自動でログインし直して1回だけ再試行する(2回目の 401 は失敗として返す)。
/// 書き込み系は、通信エラーでは再試行しない(サーバ側で成功している可能性があり、二重に引いてしまうため)。
actor AdminAPI {
    static let cookieName = "admin_token"

    private let baseURL: URL
    private let session: URLSession
    private let cookies: HTTPCookieStorage
    private let passwordProvider: @Sendable () -> String?
    private let blocker = RedirectBlocker()
    private var loginTask: Task<Void, Error>?

    init(baseURL: URL = URL(string: "https://bunrisai.jp")!,
         configuration: URLSessionConfiguration = .ephemeral,
         passwordProvider: @escaping @Sendable () -> String?) {
        configuration.timeoutIntervalForRequest = 10
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.httpShouldSetCookies = false
        let storage = configuration.httpCookieStorage ?? HTTPCookieStorage.shared
        configuration.httpCookieStorage = storage
        self.baseURL = baseURL
        self.cookies = storage
        self.session = URLSession(configuration: configuration)
        self.passwordProvider = passwordProvider
    }

    // MARK: ログイン

    func ensureLoggedIn() async throws {
        if currentToken() != nil { return }
        try await login()
    }

    /// 同時に呼ばれても、ログインの通信は1回にまとめる。
    func login() async throws {
        if let running = loginTask { return try await running.value }
        let task = Task { try await self.performLogin() }
        loginTask = task
        defer { loginTask = nil }
        try await task.value
    }

    private func performLogin() async throws {
        guard let password = passwordProvider(), !password.isEmpty else { throw APIError.login(.noPassword) }
        var request = URLRequest(url: url("api/admin/login"))
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(("password=" + Self.formEncode(password)).utf8)
        request.httpShouldHandleCookies = false
        let (_, response) = try await perform(request)

        let location = response.value(forHTTPHeaderField: "Location") ?? ""
        if location.contains("error=locked") { throw APIError.login(.locked) }
        if location.contains("error") { throw APIError.login(.badPassword) }
        let fields = (response.allHeaderFields as? [String: String]) ?? [:]
        let received = HTTPCookie.cookies(withResponseHeaderFields: fields, for: baseURL)
        guard (300..<400).contains(response.statusCode),
              let token = received.first(where: { $0.name == Self.cookieName }) else {
            throw APIError.login(.unexpected(response.statusCode))
        }
        cookies.setCookie(token)
    }

    private func currentToken() -> HTTPCookie? {
        cookies.cookies(for: baseURL)?.first { $0.name == Self.cookieName }
    }

    private func clearToken() {
        for cookie in cookies.cookies(for: baseURL) ?? [] where cookie.name == Self.cookieName {
            cookies.deleteCookie(cookie)
        }
    }

    static func formEncode(_ value: String) -> String {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
    }

    // MARK: 通信の下回り

    private func url(_ path: String) -> URL {
        URL(string: path, relativeTo: baseURL)!.absoluteURL
    }

    private func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            let (data, response) = try await session.data(for: request, delegate: blocker)
            guard let http = response as? HTTPURLResponse else { throw APIError.network }
            return (data, http)
        } catch let error as APIError {
            throw error
        } catch {
            throw APIError.network
        }
    }

    private func attempt(_ make: () -> URLRequest) async throws -> (Data, HTTPURLResponse) {
        var request = make()
        request.httpShouldHandleCookies = false
        if let token = currentToken() {
            for (key, value) in HTTPCookie.requestHeaderFields(with: [token]) { request.setValue(value, forHTTPHeaderField: key) }
        }
        return try await perform(request)
    }

    private func authed(_ make: () -> URLRequest) async throws -> (Data, HTTPURLResponse) {
        try await ensureLoggedIn()
        var result = try await attempt(make)
        if result.1.statusCode == 401 {
            clearToken()
            try await login()
            result = try await attempt(make)
            if result.1.statusCode == 401 { throw APIError.unauthorized }
        }
        return result
    }

    private func json(_ method: String, _ path: String, query: [URLQueryItem] = [], body: [String: Any]? = nil) -> URLRequest {
        var components = URLComponents(url: url(path), resolvingAgainstBaseURL: true)!
        if !query.isEmpty { components.queryItems = query }
        var request = URLRequest(url: components.url!)
        request.httpMethod = method
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        return request
    }

    private struct ErrorBody: Decodable {
        let error: String?
        let player: PlayerView?
        let remaining: Int?
    }

    private func errorBody(_ data: Data) -> ErrorBody? { try? JSONDecoder().decode(ErrorBody.self, from: data) }

    private func decode<T: Decodable>(_ type: T.Type, _ data: Data) throws -> T {
        do { return try JSONDecoder().decode(T.self, from: data) } catch { throw APIError.decoding }
    }

    private func fail(_ response: HTTPURLResponse, _ data: Data) -> APIError {
        .server(status: response.statusCode, code: errorBody(data)?.error)
    }

    // MARK: 公開 API(ログイン不要)

    /// ETag つきの取得。変わっていなければ 304 = .notModified。
    func snapshot(etag: String?) async throws -> SnapshotResult {
        var request = json("GET", "api/bingo/state")
        if let etag { request.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        let (data, response) = try await perform(request)
        switch response.statusCode {
        case 304: return .notModified
        case 200: return .changed(try decode(BingoSnapshot.self, data), etag: response.value(forHTTPHeaderField: "ETag"))
        default: throw fail(response, data)
        }
    }

    /// トーナメントの状態(ログイン不要)。ETag つき。
    func tournament(etag: String?) async throws -> TournamentSnapshotResult {
        var request = json("GET", "api/tournament/state")
        if let etag { request.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        let (data, response) = try await perform(request)
        switch response.statusCode {
        case 304: return .notModified
        case 200: return .changed(try decode(TournamentSnapshot.self, data), etag: response.value(forHTTPHeaderField: "ETag"))
        default: throw fail(response, data)
        }
    }

    // MARK: 管理 API

    /// トーナメントの1操作(`POST api/admin/tournament`)。400 の error には、サーバーの日本語の理由が入る。
    func tournamentOp(_ op: [String: Any]) async throws -> TournamentSnapshot {
        let (data, response) = try await authed { json("POST", "api/admin/tournament", body: op) }
        guard response.statusCode == 200 else { throw fail(response, data) }
        return try decode(TournamentSnapshot.self, data)
    }

    func createSession() async throws -> BingoSession {
        let (data, response) = try await authed { json("POST", "api/admin/bingo/session") }
        guard response.statusCode == 200 else { throw fail(response, data) }
        return try decode(BingoSession.self, data)
    }

    func setStatus(sessionId: String, status: SessionStatus) async throws -> BingoSession {
        let (data, response) = try await authed {
            json("PATCH", "api/admin/bingo/session", body: ["sessionId": sessionId, "status": status.rawValue])
        }
        guard response.statusCode == 200 else { throw fail(response, data) }
        return try decode(BingoSession.self, data)
    }

    func draw(sessionId: String) async throws -> DrawOutcome {
        let (data, response) = try await authed { json("POST", "api/admin/bingo/draw", body: ["sessionId": sessionId]) }
        switch response.statusCode {
        case 200: return .drawn(try decode(DrawResult.self, data))
        case 404: return .notFound
        case 409: return .notActive
        case 400 where (errorBody(data)?.error ?? "").contains("75"): return .exhausted
        default: throw fail(response, data)
        }
    }

    /// 景品を1つ引く(`POST api/admin/bingo/prize`)。回の状態(待機中・進行中・終了)には関係なく引ける。
    func drawPrize(sessionId: String) async throws -> PrizeOutcome {
        let (data, response) = try await authed {
            json("POST", "api/admin/bingo/prize", body: ["sessionId": sessionId, "action": "draw"])
        }
        switch response.statusCode {
        case 200: return .drawn(try decode(DrawResult.self, data))
        case 404: return .notFound
        case 400 where errorBody(data)?.remaining == 0: return .exhausted
        default: throw fail(response, data)
        }
    }

    /// 最後に引いた景品を1つ取り消す(押し間違い用)。
    func undoPrize(sessionId: String) async throws -> PrizeUndoOutcome {
        let (data, response) = try await authed {
            json("POST", "api/admin/bingo/prize", body: ["sessionId": sessionId, "action": "undo"])
        }
        switch response.statusCode {
        case 200:
            struct Body: Decodable { let undone: Int? }
            if let number = try decode(Body.self, data).undone { return .undone(number) }
            return .nothingToUndo
        case 404: return .notFound
        default: throw fail(response, data)
        }
    }

    func player(guestId: String) async throws -> PlayerLookup {
        let (data, response) = try await authed { json("GET", "api/admin/bingo/player", query: [URLQueryItem(name: "guest", value: guestId)]) }
        switch response.statusCode {
        case 200:
            struct Body: Decodable { let player: PlayerView }
            return .found(try decode(Body.self, data).player)
        case 404: return .notFound
        case 400: return .invalid
        default: throw fail(response, data)
        }
    }

    func claim(guestId: String, sessionId: String) async throws -> ClaimOutcome {
        let (data, response) = try await authed {
            json("POST", "api/admin/bingo/claim", body: ["guestId": guestId, "sessionId": sessionId])
        }
        switch response.statusCode {
        case 200:
            struct Body: Decodable { let player: PlayerView }
            return .claimed(try decode(Body.self, data).player)
        case 404: return .notFound
        case 409:
            let body = errorBody(data)
            let reason: ClaimRefusal
            switch body?.error {
            case "already_claimed": reason = .alreadyClaimed
            case "no_bingo": reason = .noBingo
            case "stale_session": reason = .staleSession
            default: reason = .other(body?.error ?? "")
            }
            return .refused(reason, body?.player)
        default: throw fail(response, data)
        }
    }
}
