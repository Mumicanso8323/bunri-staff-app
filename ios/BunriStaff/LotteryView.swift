import SwiftUI

struct LotteryView: View {
    @ObservedObject var model: LotteryModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var confirmFinish = false
    @State private var confirmRebuild = false
    @State private var confirmReopen = false

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                if !model.connected { banner("接続待ち…(電波を確かめてください)", color: .orange) }
                if let message = model.message {
                    banner(message, color: .red) { model.message = nil }
                }
                if model.snapshot == nil {
                    ProgressView().padding(.top, 60)
                } else {
                    statusCard
                    latestNumber
                    mainControls
                    NumberGrid(draws: model.draws)
                    dangerZone
                }
            }
            .padding(16)
        }
        .onAppear { model.start() }
        .onDisappear { model.stop() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.start() } else { model.stop() }
        }
        .confirmationDialog("ビンゴを終了しますか?", isPresented: $confirmFinish, titleVisibility: .visible) {
            Button("終了する", role: .destructive) {
                if let s = model.session { Task { await model.finish(s) } }
            }
            Button("やめる", role: .cancel) {}
        } message: {
            Text("番号を引けなくなります。間違えたときは「終了を取り消す」で戻せます。")
        }
        .confirmationDialog("セッションを作り直しますか?", isPresented: $confirmRebuild, titleVisibility: .visible) {
            Button("作り直す", role: .destructive) { Task { await model.createSession() } }
            Button("やめる", role: .cancel) {}
        } message: {
            Text("いまのセッションは終わり、新しいセッションになります。")
        }
        .confirmationDialog("終了を取り消しますか?", isPresented: $confirmReopen, titleVisibility: .visible) {
            Button("進行中に戻す") {
                if let s = model.session { Task { await model.reopen(s) } }
            }
            Button("やめる", role: .cancel) {}
        } message: {
            Text("同じセッションを進行中に戻します。引いた番号と参加者はそのままです。")
        }
    }

    // MARK: 部品

    private func banner(_ text: String, color: Color, dismiss: (() -> Void)? = nil) -> some View {
        HStack(alignment: .top) {
            Text(text).font(.subheadline).foregroundStyle(.white)
            Spacer(minLength: 0)
            if let dismiss {
                Button(action: dismiss) { Image(systemName: "xmark") }.foregroundStyle(.white)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(color, in: RoundedRectangle(cornerRadius: 10))
    }

    private var statusCard: some View {
        VStack(spacing: 12) {
            HStack {
                Text("ステータス").foregroundStyle(.secondary)
                if let s = model.session {
                    Text(s.status.label).font(.subheadline.bold())
                        .padding(.horizontal, 12).padding(.vertical, 4)
                        .background(color(for: s.status).opacity(0.2), in: Capsule())
                        .foregroundStyle(color(for: s.status))
                    Spacer()
                    Text("ID: \(s.id.prefix(8))").font(.caption).foregroundStyle(.secondary)
                } else {
                    Text("セッションがありません").font(.subheadline.bold())
                    Spacer()
                }
            }
            HStack {
                stat("参加者数", model.snapshot?.playerCount ?? 0)
                stat("ビンゴ達成者", model.snapshot?.bingoCount ?? 0)
            }
        }
        .padding(16)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 14))
    }

    private func stat(_ title: String, _ value: Int) -> some View {
        VStack {
            Text("\(value)").font(.system(size: 30, weight: .black, design: .rounded))
            Text(title).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    private func color(for status: SessionStatus) -> Color {
        switch status {
        case .waiting: return .orange
        case .active: return .green
        case .finished: return .gray
        }
    }

    private var latestNumber: some View {
        VStack(spacing: 4) {
            if let n = model.latestNumber {
                Text(BingoLetter.of(n)).font(.title3.bold()).foregroundStyle(.secondary)
                Text("\(n)").font(.system(size: 88, weight: .black, design: .rounded))
                    .contentTransition(.numericText())
                Text("引いた数 \(model.draws.count)/75  残り \(model.remaining)個").font(.footnote).foregroundStyle(.secondary)
            } else {
                Text("?").font(.system(size: 72, weight: .black, design: .rounded)).foregroundStyle(.tertiary)
                Text("まだ番号を引いていません").font(.footnote).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 14))
        .animation(.spring, value: model.latestNumber)
    }

    /// 状態に合うボタンだけを出す。
    @ViewBuilder private var mainControls: some View {
        switch model.session?.status {
        case .none:
            bigButton("新しいセッション", color: .blue) { Task { await model.createSession() } }
        case .waiting:
            bigButton("開始", color: .green) { if let s = model.session { Task { await model.start(s) } } }
        case .active:
            bigButton(model.drawCooling ? "少し待ってください…" : (model.remaining == 0 ? "全て引きました" : "次の番号を引く"),
                      color: .blue, enabled: model.canDraw, height: 96) { Task { await model.draw() } }
        case .finished:
            VStack(spacing: 12) {
                bigButton("新しいセッション", color: .blue) { Task { await model.createSession() } }
                Button("終了を取り消す(同じセッションを再開)") { confirmReopen = true }
                    .disabled(model.busy)
                    .font(.subheadline)
            }
        }
    }

    private func bigButton(_ title: String, color: Color, enabled: Bool = true, height: CGFloat = 64, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.title2.bold()).frame(maxWidth: .infinity, minHeight: height)
        }
        .buttonStyle(.borderedProminent)
        .tint(color)
        .disabled(!enabled || model.busy)
    }

    /// 取り違えないよう、開始・引くボタンから離し、最下部に置く。
    @ViewBuilder private var dangerZone: some View {
        switch model.session?.status {
        case .active:
            VStack(spacing: 8) {
                Divider().padding(.top, 40)
                Text("ここから下は、ビンゴを終わらせる操作です").font(.caption).foregroundStyle(.secondary)
                Button("終了", role: .destructive) { confirmFinish = true }
                    .buttonStyle(.bordered).tint(.red)
                    .disabled(model.busy)
            }
        case .waiting:
            VStack(spacing: 8) {
                Divider().padding(.top, 40)
                Button("作り直す", role: .destructive) { confirmRebuild = true }
                    .buttonStyle(.bordered).disabled(model.busy)
            }
        default:
            EmptyView()
        }
    }
}

/// 1〜75 を B I N G O の5行で並べ、引いた番号に色をつける。
struct NumberGrid: View {
    let draws: [Int]

    var body: some View {
        let drawn = Set(draws)
        VStack(alignment: .leading, spacing: 8) {
            Text("引いた番号(\(draws.count)/75)").font(.headline)
            VStack(spacing: 4) {
                ForEach(0..<5, id: \.self) { row in
                    HStack(spacing: 3) {
                        Text(["B", "I", "N", "G", "O"][row]).font(.caption.bold()).frame(width: 14)
                        ForEach(1...15, id: \.self) { i in
                            let n = row * 15 + i
                            Text("\(n)")
                                .font(.system(size: 11, weight: .semibold, design: .rounded))
                                .frame(maxWidth: .infinity, minHeight: 24)
                                .background(drawn.contains(n) ? Color.accentColor : Color.secondary.opacity(0.12),
                                            in: RoundedRectangle(cornerRadius: 4))
                                .foregroundStyle(drawn.contains(n) ? Color.white : Color.secondary)
                        }
                    }
                }
            }
        }
    }
}
