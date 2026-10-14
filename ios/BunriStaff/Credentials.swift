import Foundation

/// 管理者パスワードの読み出し。値はビルド時に Config/secrets.xcconfig → Info.plist(16 進)で入る。
/// ソース・ログ・画面には出さない(このファイルにも値は無い)。
enum Credentials {
    static var adminPassword: String? {
        guard let hex = Bundle.main.object(forInfoDictionaryKey: "StaffAdminPasswordHex") as? String else { return nil }
        return decodeHex(hex)
    }

    /// 16 進の文字列を UTF-8 の文字列に戻す。空・奇数桁・16 進でない文字(未展開の `$(...)` など)は nil。
    static func decodeHex(_ hex: String) -> String? {
        let s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty, s.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        var index = s.startIndex
        while index < s.endIndex {
            let next = s.index(index, offsetBy: 2)
            guard let byte = UInt8(s[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        let text = String(decoding: bytes, as: UTF8.self)
        return text.isEmpty ? nil : text
    }
}
