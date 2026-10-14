import XCTest
@testable import BunriStaff

final class LogicTests: XCTestCase {
    func testDecodeHex() {
        XCTAssertEqual(Credentials.decodeHex("70773d2f24"), "pw=/$")
        XCTAssertEqual(Credentials.decodeHex(" e69687e79086 \n"), "文理")
        XCTAssertNil(Credentials.decodeHex(""))
        XCTAssertNil(Credentials.decodeHex("abc"))
        XCTAssertNil(Credentials.decodeHex("zz"))
        XCTAssertNil(Credentials.decodeHex("$(BUNRI_ADMIN_PASSWORD_HEX)"), "未展開の変数は無効")
    }

    func testPlayerCodeFromURLAndRawUUID() {
        let id = "0f8fad5b-d9cb-469f-a165-70867728950e"
        XCTAssertEqual(PlayerCode.extract("https://bunrisai.jp/admin/bingo/verify?player=\(id)"), id)
        XCTAssertEqual(PlayerCode.extract(id), id)
        XCTAssertEqual(PlayerCode.extract("  \(id)\n"), id)
        XCTAssertEqual(PlayerCode.extract("https://example.com/?player=\(id.uppercased())"), id.uppercased())
    }

    func testPlayerCodeRejectsNonsense() {
        XCTAssertNil(PlayerCode.extract(""))
        XCTAssertNil(PlayerCode.extract("https://example.com/"))
        XCTAssertNil(PlayerCode.extract("hello"))
        XCTAssertNil(PlayerCode.extract("1234"))
    }

    func testBingoLetter() {
        XCTAssertEqual([1, 15, 16, 30, 31, 45, 46, 60, 61, 75].map(BingoLetter.of), ["B", "B", "I", "I", "N", "N", "G", "G", "O", "O"])
    }

    func testFormEncode() {
        XCTAssertEqual(AdminAPI.formEncode("a b&c=d/é+"), "a%20b%26c%3Dd%2F%C3%A9%2B")
    }
}
