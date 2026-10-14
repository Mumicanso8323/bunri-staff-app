import XCTest
@testable import BunriStaff

final class ModelTests: XCTestCase {
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

    private func player(bingo: Bool = true, claimed: Bool = false, latest: Bool = true) -> String {
        """
        {"guestId":"0f8fad5b-d9cb-469f-a165-70867728950e","sessionId":"S1","sessionStatus":"active","isLatestSession":\(latest),
         "hasBingo":\(bingo),"claimed":\(claimed),"claimedAt":null,"joinedAt":"x","card":[],"drawsCount":1}
        """
    }

    private let qr = "https://bunrisai.jp/admin/bingo/verify?player=0f8fad5b-d9cb-469f-a165-70867728950e"

    private func verifyModel(playerBody: String) -> VerifyModel {
        MockURLProtocol.handler = { [self] request in
            switch request.url!.path {
            case "/api/admin/login": return reply(303, "", login)
            case "/api/admin/bingo/player": return reply(200, #"{"player":\#(playerBody)}"#)
            default: return reply(200, #"{"ok":true,"player":\#(player(claimed: true))}"#)
            }
        }
        return VerifyModel(api: makeAPI())
    }

    @MainActor func testVerifyOutcomesFromLookup() async {
        let cases: [(String, (VerifyResult) -> Bool)] = [
            (player(), { if case .readyToClaim = $0 { return true }; return false }),
            (player(claimed: true), { if case .alreadyClaimed = $0 { return true }; return false }),
            (player(bingo: false), { if case .notBingo = $0 { return true }; return false }),
            (player(latest: false), { $0 == .stale }),
        ]
        for (body, check) in cases {
            let model = verifyModel(playerBody: body)
            await model.handle(code: qr)
            guard case .result(let result) = model.phase else { return XCTFail("\(model.phase)") }
            XCTAssertTrue(check(result), "\(result)")
        }
    }

    @MainActor func testInvalidQRNeverCallsTheAPI() async {
        MockURLProtocol.handler = { _ in XCTFail("no request expected"); return (HTTPURLResponse(), Data()) }
        let model = VerifyModel(api: makeAPI())
        await model.handle(code: "hello")
        XCTAssertEqual(model.phase, .result(.invalidQR))
    }

    @MainActor func testClaimThenNext() async {
        let model = verifyModel(playerBody: player())
        await model.handle(code: qr)
        guard case .result(.readyToClaim(let p)) = model.phase else { return XCTFail("\(model.phase)") }
        await model.claim(p)
        guard case .result(.claimedNow) = model.phase else { return XCTFail("\(model.phase)") }
        model.next()
        XCTAssertEqual(model.phase, .scanning)
    }

    @MainActor func testScansAreIgnoredWhileShowingAResult() async {
        let model = verifyModel(playerBody: player())
        await model.handle(code: qr)
        let before = model.phase
        await model.handle(code: "https://x/?player=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        XCTAssertEqual(model.phase, before)
    }

    @MainActor func testDrawIsBlockedWhileCoolingAndDoubleTapDrawsOnce() async {
        var draws = 0
        MockURLProtocol.handler = { [self] request in
            switch request.url!.path {
            case "/api/admin/login": return reply(303, "", login)
            case "/api/admin/bingo/draw":
                draws += 1
                return reply(200, #"{"number":7,"remaining":74,"totalDrawn":1}"#)
            default:
                return reply(200, #"{"session":{"id":"S1","status":"active","created_at":"x"},"draws":[7],"playerCount":0,"bingoCount":0,"updatedAt":1}"#, ["ETag": "W/\"1\""])
            }
        }
        let model = LotteryModel(api: makeAPI())
        await model.refresh()
        XCTAssertTrue(model.canDraw)
        await model.draw()
        XCTAssertFalse(model.canDraw, "押した直後は押せない")
        await model.draw()
        XCTAssertEqual(draws, 1)
    }
}
