import XCTest
@testable import BunriStaff

final class WebPolicyTests: XCTestCase {
    private func decide(_ s: String, main: Bool = true) -> NavigationDecision {
        NavigationPolicy.decide(url: URL(string: s), isMainFrame: main)
    }

    func testOwnPagesStayInTheWebView() {
        XCTAssertEqual(decide("https://bunrisai.jp/admin/bingo"), .allow)
        XCTAssertEqual(decide("https://bunrisai.jp/admin/bingo/verify?x=1#y"), .allow)
        XCTAssertEqual(decide("https://BUNRISAI.JP/admin"), .allow)
        XCTAssertEqual(decide("https://bunrisai.jp:443/admin"), .allow)
        XCTAssertEqual(decide("about:blank"), .allow)
        XCTAssertEqual(decide("https://bunrisai.jp/admin", main: false), .allow)
    }

    func testLookalikeHostsGoToSafari() {
        for s in ["https://www.bunrisai.jp/", "https://bunrisai.jp.example.com/", "https://example.com/bunrisai.jp",
                  "https://bunrisai.jp@example.com/", "https://user:pw@bunrisai.jp/", "https://bunrisai.jp:8443/",
                  "https://bunrisai.jp./", "https://notbunrisai.jp/", "http://bunrisai.jp/admin"] {
            XCTAssertEqual(decide(s), .external, s)
        }
    }

    func testOtherSchemes() {
        XCTAssertEqual(decide("mailto:a@example.com"), .external)
        XCTAssertEqual(decide("tel:0312345678"), .external)
        XCTAssertEqual(decide("javascript:alert(1)"), .cancel)
        XCTAssertEqual(decide("file:///etc/passwd"), .cancel)
        XCTAssertEqual(decide("data:text/html,hi"), .cancel)
        XCTAssertEqual(decide("itms-apps://apps.apple.com/"), .cancel)
        XCTAssertEqual(NavigationPolicy.decide(url: nil, isMainFrame: true), .cancel)
    }

    func testForeignSubframesAreBlockedSilently() {
        XCTAssertEqual(decide("https://example.com/embed", main: false), .cancel)
    }

    func testServerErrorThreshold() {
        XCTAssertFalse(NavigationPolicy.isServerError(statusCode: 200))
        XCTAssertFalse(NavigationPolicy.isServerError(statusCode: 401))
        XCTAssertFalse(NavigationPolicy.isServerError(statusCode: 404))
        XCTAssertTrue(NavigationPolicy.isServerError(statusCode: 500))
        XCTAssertTrue(NavigationPolicy.isServerError(statusCode: 503))
    }

    func testCameraOnlyForOwnOrigin() {
        XCTAssertTrue(MediaPolicy.shouldGrant(scheme: "https", host: "bunrisai.jp", port: 0))
        XCTAssertTrue(MediaPolicy.shouldGrant(scheme: "https", host: "bunrisai.jp", port: 443))
        XCTAssertFalse(MediaPolicy.shouldGrant(scheme: "http", host: "bunrisai.jp", port: 0))
        XCTAssertFalse(MediaPolicy.shouldGrant(scheme: "https", host: "www.bunrisai.jp", port: 0))
        XCTAssertFalse(MediaPolicy.shouldGrant(scheme: "https", host: "example.com", port: 0))
        XCTAssertFalse(MediaPolicy.shouldGrant(scheme: "https", host: "bunrisai.jp", port: 8443))
    }

    func testStartPagesAreOwn() {
        XCTAssertTrue(StaffSite.isOwnOrigin(StaffSite.lottery))
        XCTAssertTrue(StaffSite.isOwnOrigin(StaffSite.verify))
    }
}
