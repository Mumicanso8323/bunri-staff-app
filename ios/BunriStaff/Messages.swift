import Foundation

/// 失敗を、現場の人が次にすることが分かる文にする。
enum Messages {
    static func describe(_ error: APIError) -> String {
        switch error {
        case .network:
            return "通信できませんでした。電波を確かめてください。"
        case .login(.noPassword):
            return "このアプリにはログイン情報が入っていません。リードに知らせてください。"
        case .login(.badPassword):
            return "ログインできませんでした(パスワードが合いません)。リードに知らせてください。"
        case .login(.locked):
            return "ログインが一時的に止められています。10分ほど待ってから、もう一度ためしてください。"
        case .login(.unexpected(let status)):
            return "ログインできませんでした(\(status))。もう一度ためしてください。"
        case .unauthorized:
            return "ログインが切れました。もう一度押してください。"
        case .server(let status, _):
            return "サーバーがエラーを返しました(\(status))。もう一度ためしてください。"
        case .decoding:
            return "サーバーの返事を読めませんでした。もう一度ためしてください。"
        }
    }
}

enum PlayerCode {
    private static let uuid = try! NSRegularExpression(
        pattern: "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", options: [.caseInsensitive])

    /// QR の中身(`https://…/admin/bingo/verify?player=<UUID>` または UUID そのもの)から来場者 ID を取り出す。
    /// UUID の形でなければ nil(API を呼ばずに「無効な QR」にする)。
    static func extract(_ input: String) -> String? {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if let url = URL(string: text),
           let value = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "player" })?.value,
           let found = firstUUID(in: value) { return found }
        return firstUUID(in: text)
    }

    private static func firstUUID(in text: String) -> String? {
        let range = NSRange(text.startIndex..., in: text)
        guard let match = uuid.firstMatch(in: text, range: range), let r = Range(match.range, in: text) else { return nil }
        return String(text[r])
    }
}
