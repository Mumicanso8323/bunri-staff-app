import Foundation

/// アプリの中で開いてよい場所の判定。画面や WKWebView に依らない純粋な関数だけ(単体テストで確かめる)。
enum StaffSite {
    static let host = "bunrisai.jp"
    static let lottery = URL(string: "https://bunrisai.jp/admin/bingo")!
    static let verify = URL(string: "https://bunrisai.jp/admin/bingo/verify")!

    /// https・ホストが bunrisai.jp と完全一致・利用者名なし・ポートは既定のみ。
    /// (www. などのサブドメイン、bunrisai.jp.example.com、https://bunrisai.jp@example.com は通さない)
    static func isOwnOrigin(_ url: URL?) -> Bool {
        guard let url, url.scheme?.lowercased() == "https",
              url.user == nil, url.password == nil,
              url.port == nil || url.port == 443,
              url.host?.lowercased() == host else { return false }
        return true
    }
}

enum NavigationDecision: Equatable {
    case allow      // この WebView で開く
    case external   // Safari(など)に渡す
    case cancel     // 何もしない
}

enum NavigationPolicy {
    /// - isMainFrame: 画面全体の遷移か、埋め込み(iframe)か
    static func decide(url: URL?, isMainFrame: Bool) -> NavigationDecision {
        guard let url else { return .cancel }
        if url.absoluteString == "about:blank" { return .allow }
        if StaffSite.isOwnOrigin(url) { return .allow }
        // 埋め込みの他サイトは黙って止める(Safari は開かない)
        guard isMainFrame else { return .cancel }
        switch url.scheme?.lowercased() {
        case "https", "http", "mailto", "tel": return .external
        default: return .cancel
        }
    }

    /// 画面全体の読み込みで、サーバが 5xx を返したときだけ失敗の表示にする。
    static func isServerError(statusCode: Int) -> Bool { statusCode >= 500 }
}

enum MediaPolicy {
    /// カメラ・マイクの要求を許す相手。bunrisai.jp(https)だけ。
    static func shouldGrant(scheme: String, host: String, port: Int) -> Bool {
        scheme.lowercased() == "https" && host.lowercased() == StaffSite.host && (port == 0 || port == 443)
    }
}
