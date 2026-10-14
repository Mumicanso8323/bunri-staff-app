import SwiftUI

@main
struct BunriStaffApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

private enum StaffTab: Hashable { case lottery, verify }

struct RootView: View {
    @State private var tab: StaffTab = .lottery
    @StateObject private var lottery = WebModel(start: StaffSite.lottery)
    @StateObject private var verify = WebModel(start: StaffSite.verify)

    var body: some View {
        TabView(selection: $tab) {
            StaffWebScreen(model: lottery)
                .tabItem { Label("抽選", systemImage: "dice") }
                .tag(StaffTab.lottery)
            StaffWebScreen(model: verify)
                .tabItem { Label("QR確認", systemImage: "qrcode.viewfinder") }
                .tag(StaffTab.verify)
        }
    }
}
