import SwiftUI

@main
struct BunriStaffApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

private enum StaffTab: Hashable { case lottery, tournament, verify, guide }

struct RootView: View {
    @State private var tab: StaffTab = .lottery
    @State private var loginProblem: String?
    @StateObject private var lottery: LotteryModel
    @StateObject private var tournament: TournamentModel
    @StateObject private var verify: VerifyModel
    private let api: AdminAPI

    init() {
        let api = AdminAPI(passwordProvider: { Credentials.adminPassword })
        self.api = api
        _lottery = StateObject(wrappedValue: LotteryModel(api: api))
        _tournament = StateObject(wrappedValue: TournamentModel(api: api))
        _verify = StateObject(wrappedValue: VerifyModel(api: api))
    }

    var body: some View {
        TabView(selection: $tab) {
            LotteryView(model: lottery)
                .tabItem { Label("抽選", systemImage: "dice") }
                .tag(StaffTab.lottery)
            TournamentView(model: tournament)
                .tabItem { Label("トーナメント", systemImage: "trophy") }
                .tag(StaffTab.tournament)
            VerifyView(model: verify, isTabActive: tab == .verify)
                .tabItem { Label("QR確認", systemImage: "qrcode.viewfinder") }
                .tag(StaffTab.verify)
            GuideView()
                .tabItem { Label("使い方", systemImage: "book") }
                .tag(StaffTab.guide)
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if let loginProblem {
                HStack(spacing: 12) {
                    Text(loginProblem).font(.footnote).foregroundStyle(.white)
                    Spacer(minLength: 0)
                    Button("やり直す") { Task { await login() } }
                        .buttonStyle(.bordered).tint(.white)
                }
                .padding(.horizontal, 16).padding(.vertical, 8)
                .background(Color.red)
            }
        }
        .task { await login() }
    }

    /// 起動時の自動ログイン。失敗しても各画面の操作で、必要なときにもう一度ためす(401 のとき)。
    private func login() async {
        // 単体テストのホストとして起動したときは通信しない
        if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil { return }
        do {
            try await api.ensureLoggedIn()
            loginProblem = nil
        } catch let error as APIError {
            loginProblem = Messages.describe(error)
        } catch {
            loginProblem = Messages.describe(.network)
        }
    }
}
