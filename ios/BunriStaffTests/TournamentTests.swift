import XCTest
@testable import BunriStaff

final class TournamentTests: XCTestCase {
    private let base = URL(string: "https://bunrisai.jp")!

    override func tearDown() { MockURLProtocol.handler = nil }

    private func makeAPI() -> AdminAPI {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        return AdminAPI(baseURL: base, configuration: config, passwordProvider: { "pw" })
    }

    private func reply(_ status: Int, _ text: String, _ headers: [String: String] = [:]) -> (HTTPURLResponse, Data) {
        (HTTPURLResponse(url: base, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!, Data(text.utf8))
    }

    private let login = ["Location": "/admin", "Set-Cookie": "admin_token=T; Path=/; Max-Age=86400; Secure"]

    private func snapshotJSON(winners: String = "{}", current: String = "null", canUndo: Bool = false, version: Int = 1) -> String {
        """
        {"state":{"title":"サークル対抗戦","slots":["A","B","C","D","E","F","G",null],
         "roundNames":["1回戦","2回戦","3回戦(準決勝)","決勝"],"gameNames":["","","",""],
         "winners":\(winners),"current":\(current),"canUndo":\(canUndo)},"version":\(version),"updatedAt":"2026-10-18T05:00:00.000Z"}
        """
    }

    // MARK: 解決のルール

    func testByeAdvancesAutomatically() {
        let b = Bracket.resolve(slots: ["A", "B", "C", "D", "E", "F", "G", nil], winners: [:])
        XCTAssertEqual(b.rounds, 3)
        XCTAssertEqual(b.matches[0][3].winner, "G")
        XCTAssertTrue(b.matches[0][3].auto)
        XCTAssertTrue(b.matches[0][0].ready)
        XCTAssertNil(b.matches[0][0].winner)
        XCTAssertEqual(b.matches[1][1].b, .team("G"))
        XCTAssertEqual(b.matches[1][1].a, .pending)
    }

    func testWinnerAdvancesAndChampion() {
        let slots: [String?] = ["A", "B", "C", "D", "E", "F", "G", "H"]
        var w = ["0-0": "A", "0-1": "C", "0-2": "E", "0-3": "G", "1-0": "A", "1-1": "E"]
        XCTAssertNil(Bracket.resolve(slots: slots, winners: w).champion)
        w["2-0"] = "E"
        let b = Bracket.resolve(slots: slots, winners: w)
        XCTAssertEqual(b.champion, "E")
        XCTAssertEqual(b.matches[2][0].a, .team("A"))
    }

    func testWinnerWhoIsNotInTheMatchIsIgnored() {
        let b = Bracket.resolve(slots: ["A", "B", "C", "D", "E", "F", "G", "H"], winners: ["0-0": "Z"])
        XCTAssertNil(b.matches[0][0].winner)
    }

    func testRoundNamesAlignFromTheFinal() throws {
        let snap = try JSONDecoder().decode(TournamentSnapshot.self, from: Data(snapshotJSON().utf8))
        XCTAssertEqual(snap.state.roundName(0), "2回戦")      // 8 枠は後ろの 3 つを使う
        XCTAssertEqual(snap.state.roundName(2), "決勝")
        XCTAssertNil(snap.state.slots.last!)
    }

    // MARK: API

    func testStateUsesETagAnd304() async throws {
        var seen: [String?] = []
        MockURLProtocol.handler = { [self] request in
            XCTAssertEqual(request.url!.path, "/api/tournament/state")
            seen.append(request.value(forHTTPHeaderField: "If-None-Match"))
            if request.value(forHTTPHeaderField: "If-None-Match") == "\"tournament-1\"" { return reply(304, "", ["ETag": "\"tournament-1\""]) }
            return reply(200, snapshotJSON(), ["ETag": "\"tournament-1\""])
        }
        let api = makeAPI()
        guard case .changed(let snap, let tag) = try await api.tournament(etag: nil) else { return XCTFail("changed のはず") }
        XCTAssertEqual(snap.version, 1)
        XCTAssertEqual(tag, "\"tournament-1\"")
        let second = try await api.tournament(etag: tag)
        XCTAssertEqual(second, .notModified)
    }

    func testWinPostsOneOpAsJSON() async throws {
        var body = ""
        var method = ""
        MockURLProtocol.handler = { [self] request in
            switch request.url!.path {
            case "/api/admin/login": return reply(303, "", login)
            default:
                method = request.httpMethod ?? ""
                body = request.bodyString
                return reply(200, snapshotJSON(winners: #"{"0-0":"A"}"#, canUndo: true, version: 2))
            }
        }
        let snap = try await makeAPI().tournamentOp(["action": "win", "round": 0, "match": 0, "team": "A"])
        XCTAssertEqual(method, "POST")
        let sent = try JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any]
        XCTAssertEqual(sent?["action"] as? String, "win")
        XCTAssertEqual(sent?["round"] as? Int, 0)
        XCTAssertEqual(sent?["team"] as? String, "A")
        XCTAssertEqual(snap.state.winners["0-0"], "A")
        XCTAssertTrue(snap.state.canUndo)
    }

    func testOpRefusalCarriesServerMessage() async {
        MockURLProtocol.handler = { [self] request in
            if request.url!.path == "/api/admin/login" { return reply(303, "", login) }
            return reply(400, #"{"error":"これ以上戻せません"}"#)
        }
        do {
            _ = try await makeAPI().tournamentOp(["action": "undo"])
            XCTFail("失敗するはず")
        } catch {
            XCTAssertEqual(error as? APIError, .server(status: 400, code: "これ以上戻せません"))
        }
    }

    // MARK: 画面のモデル

    @MainActor func testModelAppliesResultAndShowsRefusal() async {
        var undoRefused = false
        MockURLProtocol.handler = { [self] request in
            switch request.url!.path {
            case "/api/admin/login": return reply(303, "", login)
            case "/api/admin/tournament":
                let sent = (try? JSONSerialization.jsonObject(with: Data(request.bodyString.utf8))) as? [String: Any]
                if sent?["action"] as? String == "undo" { undoRefused = true; return reply(400, #"{"error":"これ以上戻せません"}"#) }
                return reply(200, snapshotJSON(winners: #"{"0-0":"A"}"#, canUndo: true, version: 2))
            default:
                return reply(200, snapshotJSON(winners: #"{"0-0":"A"}"#, canUndo: true, version: 2), ["ETag": "\"tournament-2\""])
            }
        }
        let model = TournamentModel(api: makeAPI())
        await model.win(round: 0, match: 0, team: "A")
        XCTAssertEqual(model.state?.winners["0-0"], "A")
        XCTAssertNil(model.message)
        await model.undo()
        XCTAssertTrue(undoRefused)
        XCTAssertEqual(model.message, "これ以上戻せません")
    }
}
