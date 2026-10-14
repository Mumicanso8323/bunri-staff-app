import AVFoundation
import SwiftUI

struct VerifyView: View {
    @ObservedObject var model: VerifyModel
    let isTabActive: Bool
    @State private var cameraAccess = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var manualID = ""

    private var hasCamera: Bool { AVCaptureDevice.default(for: .video) != nil }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                switch model.phase {
                case .scanning: scanner
                case .working:
                    ProgressView("確かめています…").frame(maxWidth: .infinity, minHeight: 240)
                case .result(let result):
                    ResultPanel(result: result, model: model)
                }
            }
            .padding(16)
        }
        .task { await askCameraIfNeeded() }
    }

    @ViewBuilder private var scanner: some View {
        Text("ビンゴした人のスマートフォンの QR を、枠に入れてください").font(.headline).multilineTextAlignment(.center)
        switch cameraAccess {
        case .authorized:
            if hasCamera {
                QRScannerView(isActive: isTabActive && model.phase == .scanning) { code in
                    Task { await model.handle(code: code) }
                }
                .frame(height: 340)
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(.white.opacity(0.6), lineWidth: 2))
            } else {
                notice("この端末にはカメラがありません。下の「IDを入力」を使ってください。")
            }
        case .notDetermined:
            ProgressView().frame(height: 200)
        default:
            VStack(spacing: 12) {
                notice("カメラが許可されていません。設定でカメラを許可してください。")
                Button("設定を開く") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }.buttonStyle(.borderedProminent)
            }
        }
        VStack(alignment: .leading, spacing: 6) {
            Text("読めないときは、IDを入力").font(.footnote).foregroundStyle(.secondary)
            HStack {
                TextField("来場者ID(UUID)", text: $manualID)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .textFieldStyle(.roundedBorder)
                Button("検索") {
                    let id = manualID
                    manualID = ""
                    Task { await model.handle(code: id) }
                }
                .buttonStyle(.borderedProminent)
                .disabled(manualID.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    private func notice(_ text: String) -> some View {
        Text(text).multilineTextAlignment(.center).padding(16).frame(maxWidth: .infinity)
            .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
    }

    private func askCameraIfNeeded() async {
        if cameraAccess == .notDetermined {
            _ = await AVCaptureDevice.requestAccess(for: .video)
        }
        cameraAccess = AVCaptureDevice.authorizationStatus(for: .video)
    }
}

private struct ResultPanel: View {
    let result: VerifyResult
    @ObservedObject var model: VerifyModel

    private struct Look {
        let symbol: String
        let color: Color
        let title: String
        let detail: String
    }

    private var look: Look {
        switch result {
        case .readyToClaim:
            return Look(symbol: "gift.fill", color: .green, title: "ビンゴ達成",
                        detail: "景品をお渡しして、下のボタンを押してください。押すまでは記録されません。")
        case .claimedNow:
            return Look(symbol: "checkmark.circle.fill", color: .green, title: "受け取り済みにしました",
                        detail: "景品受取を記録しました。")
        case .alreadyClaimed(let p):
            return Look(symbol: "exclamationmark.triangle.fill", color: .orange, title: "すでに受け取り済み",
                        detail: "このプレイヤーは既に景品を受け取っています。" + Self.claimedTime(p.claimedAt))
        case .notBingo:
            return Look(symbol: "xmark.circle.fill", color: .orange, title: "まだビンゴではありません",
                        detail: "このプレイヤーはまだビンゴを達成していません。当たったマスをタップして開けているか、本人に確かめてください。")
        case .stale:
            return Look(symbol: "clock.arrow.circlepath", color: .orange, title: "過去のセッションです",
                        detail: "過去のセッションの参加者です(景品対象外)。")
        case .notFound:
            return Look(symbol: "questionmark.circle.fill", color: .red, title: "見つかりません",
                        detail: "プレイヤーが見つかりません。")
        case .invalidQR:
            return Look(symbol: "qrcode", color: .red, title: "無効な QR",
                        detail: "ビンゴの QR ではありません。来場者のビンゴ画面に出る QR を読んでください。")
        case .failed(let text):
            return Look(symbol: "wifi.exclamationmark", color: .red, title: "確認できませんでした", detail: text)
        }
    }

    var body: some View {
        let look = self.look
        VStack(spacing: 16) {
            Image(systemName: look.symbol).font(.system(size: 72)).foregroundStyle(look.color)
            Text(look.title).font(.system(size: 30, weight: .black)).multilineTextAlignment(.center)
            Text(look.detail).font(.body).multilineTextAlignment(.center).foregroundStyle(.secondary)
            if case .readyToClaim(let player) = result {
                Button {
                    Task { await model.claim(player) }
                } label: {
                    Text("景品受取済みにする").font(.title2.bold()).frame(maxWidth: .infinity, minHeight: 64)
                }
                .buttonStyle(.borderedProminent).tint(.green)
            }
            Button {
                model.next()
            } label: {
                Text("次を読む").font(.title3.bold()).frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(.bordered)
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(look.color.opacity(0.12), in: RoundedRectangle(cornerRadius: 18))
    }

    private static func claimedTime(_ iso: String?) -> String {
        guard let iso else { return "" }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let date = f.date(from: iso) else { return "" }
        let out = DateFormatter()
        out.locale = Locale(identifier: "ja_JP")
        out.dateFormat = "H:mm"
        return "(\(out.string(from: date))に受け取り)"
    }
}
