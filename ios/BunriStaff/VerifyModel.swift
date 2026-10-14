import Foundation
import SwiftUI

enum VerifyResult: Equatable {
    case readyToClaim(PlayerView)
    case claimedNow(PlayerView)
    case alreadyClaimed(PlayerView)
    case notBingo(PlayerView)
    case stale
    case notFound
    case invalidQR
    case failed(String)
}

enum VerifyPhase: Equatable {
    case scanning
    case working
    case result(VerifyResult)
}

@MainActor
final class VerifyModel: ObservableObject {
    let api: AdminAPI
    @Published private(set) var phase: VerifyPhase = .scanning

    init(api: AdminAPI) { self.api = api }

    /// QR を読んだ(または ID を手入力した)。処理中・結果表示中は、あとから来る読み取りを無視する。
    func handle(code: String) async {
        guard phase == .scanning else { return }
        phase = .working
        guard let guestId = PlayerCode.extract(code) else { return finish(.invalidQR) }
        do {
            switch try await api.player(guestId: guestId) {
            case .found(let player):
                if !player.isLatestSession { finish(.stale) }
                else if !player.hasBingo { finish(.notBingo(player)) }
                else if player.claimed { finish(.alreadyClaimed(player)) }
                else { finish(.readyToClaim(player)) }
            case .notFound: finish(.notFound)
            case .invalid: finish(.invalidQR)
            }
        } catch let error as APIError {
            finish(.failed(Messages.describe(error)))
        } catch {
            finish(.failed(Messages.describe(.network)))
        }
    }

    /// 「景品受取済みにする」。
    func claim(_ player: PlayerView) async {
        guard case .result(.readyToClaim) = phase else { return }
        phase = .working
        do {
            switch try await api.claim(guestId: player.guestId, sessionId: player.sessionId) {
            case .claimed(let updated): finish(.claimedNow(updated))
            case .notFound: finish(.notFound)
            case .refused(let reason, let current):
                switch reason {
                case .alreadyClaimed: finish(.alreadyClaimed(current ?? player))
                case .noBingo: finish(.notBingo(current ?? player))
                case .staleSession: finish(.stale)
                case .other(let code): finish(.failed("記録できませんでした(\(code))。もう一度読み取ってください。"))
                }
            }
        } catch let error as APIError {
            var text = Messages.describe(error)
            // 通信エラーでも、サーバー側では記録できているかもしれない。
            if error == .network { text += "記録できたかどうかは、もう一度QRを読むと分かります。" }
            finish(.failed(text))
        } catch {
            finish(.failed(Messages.describe(.network)))
        }
    }

    /// 「次を読む」。
    func next() { phase = .scanning }

    private func finish(_ result: VerifyResult) {
        phase = .result(result)
        let feedback = UINotificationFeedbackGenerator()
        switch result {
        case .claimedNow, .readyToClaim: feedback.notificationOccurred(.success)
        default: feedback.notificationOccurred(.warning)
        }
    }
}
