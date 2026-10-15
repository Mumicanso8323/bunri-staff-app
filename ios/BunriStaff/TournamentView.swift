import SwiftUI

struct TournamentView: View {
    @ObservedObject var model: TournamentModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var round: Int?
    @State private var pendingWin: PendingWin?
    @State private var confirmUndo = false
    @State private var confirmReset = false
    @State private var confirmUnwin: MatchView?

    struct PendingWin: Identifiable, Equatable {
        let round: Int
        let match: Int
        let team: String
        let replacing: Bool
        var id: String { "\(round)-\(match)-\(team)" }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                if !model.connected { banner("接続待ち…(電波を確かめてください)", color: .orange) }
                if let message = model.message { banner(message, color: .red) { model.message = nil } }
                if let state = model.state {
                    content(state)
                } else {
                    ProgressView().padding(.top, 60)
                }
            }
            .padding(16)
        }
        .onAppear { model.start() }
        .onDisappear { model.stop() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.start() } else { model.stop() }
        }
        .confirmationDialog(winTitle, isPresented: Binding(get: { pendingWin != nil }, set: { if !$0 { pendingWin = nil } }),
                            titleVisibility: .visible, presenting: pendingWin) { p in
            Button("勝ちにする") { Task { await model.win(round: p.round, match: p.match, team: p.team) } }
            Button("やめる", role: .cancel) {}
        } message: { p in
            Text(p.replacing ? "この試合の勝者を付け替えます。この試合に絡む、後の勝ち上がりは取り消されます。" : "次の回戦に上がります。押し間違えたときは「1手戻す」で戻せます。")
        }
        .confirmationDialog("1手戻しますか?", isPresented: $confirmUndo, titleVisibility: .visible) {
            Button("1手戻す") { Task { await model.undo() } }
            Button("やめる", role: .cancel) {}
        } message: {
            Text("直前の操作を取り消します。")
        }
        .confirmationDialog("勝者を取り消しますか?", isPresented: Binding(get: { confirmUnwin != nil }, set: { if !$0 { confirmUnwin = nil } }),
                            titleVisibility: .visible, presenting: confirmUnwin) { mv in
            Button("取り消す", role: .destructive) { Task { await model.unwin(round: mv.round, match: mv.match) } }
            Button("やめる", role: .cancel) {}
        } message: { _ in
            Text("この試合の勝者と、この試合に絡む後の勝ち上がりを取り消します。")
        }
        .confirmationDialog("すべて初期状態に戻しますか?", isPresented: $confirmReset, titleVisibility: .visible) {
            Button("初期状態に戻す", role: .destructive) { Task { await model.reset() } }
            Button("やめる", role: .cancel) {}
        } message: {
            Text("チーム一覧・回戦名・ゲーム名がすべて初期状態に戻り、組み合わせは空になります。勝ち上がりも消えます。")
        }
    }

    private var winTitle: String {
        pendingWin.map { "「\($0.team)」の勝ちにしますか?" } ?? ""
    }

    // MARK: 中身

    @ViewBuilder private func content(_ state: TournamentState) -> some View {
        let bracket = state.bracket
        let selected = min(max(round ?? defaultRound(bracket, state: state), 0), bracket.rounds - 1)

        Text(state.title).font(.title2.bold()).frame(maxWidth: .infinity, alignment: .leading)

        if !state.placementComplete { placePanel(state) }

        if let champion = bracket.champion {
            VStack(spacing: 4) {
                Text("優勝").font(.subheadline).foregroundStyle(.secondary)
                Text(champion).font(.title.bold()).multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity).padding(16)
            .background(Color.yellow.opacity(0.25), in: RoundedRectangle(cornerRadius: 14))
        }

        Picker("回戦", selection: Binding(get: { selected }, set: { round = $0 })) {
            ForEach(0..<bracket.rounds, id: \.self) { r in
                Text(state.roundName(r)).tag(r)
            }
        }
        .pickerStyle(.segmented)

        let game = state.gameName(selected)
        if !game.isEmpty { Text("ゲーム:\(game)").font(.subheadline).foregroundStyle(.secondary) }

        ForEach(bracket.matches[selected]) { mv in
            if !mv.dead { matchCard(mv, state: state) }
        }

        Button { confirmUndo = true } label: {
            Text("1手戻す").font(.headline).frame(maxWidth: .infinity, minHeight: 48)
        }
        .buttonStyle(.borderedProminent).tint(.orange)
        .disabled(model.busy || !state.canUndo)

        dangerZone
    }

    /// 「抜けた順に入れる」。未配置のチームのボタンを押すと、次の空枠に入る(確認なし。間違えたら「1手戻す」)
    private func placePanel(_ state: TournamentState) -> some View {
        let unplaced = state.unplacedTeams
        return VStack(alignment: .leading, spacing: 10) {
            Text("抜けた順に入れる").font(.headline)
            HStack {
                Text("配置 \(state.placedTeams.count) / \(state.allTeams.count)").font(.subheadline)
                if let next = state.nextEmptySlot {
                    Text("次の枠:\(state.slotLabel(next))").font(.subheadline).foregroundStyle(.secondary)
                }
            }
            if unplaced.isEmpty {
                Text("入れるチームの一覧がありません。ホールの管理画面で確かめてください。").font(.subheadline).foregroundStyle(.secondary)
            } else {
                ForEach(unplaced, id: \.self) { team in
                    Button {
                        Task { await model.place(team: team) }
                    } label: {
                        Text(team).font(.headline).multilineTextAlignment(.leading)
                            .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    }
                    .buttonStyle(.borderedProminent).tint(.indigo).disabled(model.busy)
                }
            }
            Button { confirmUndo = true } label: {
                Text("1手戻す").font(.subheadline.bold()).frame(maxWidth: .infinity, minHeight: 40)
            }
            .buttonStyle(.bordered).tint(.orange).disabled(model.busy || !state.canUndo)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.indigo.opacity(0.10), in: RoundedRectangle(cornerRadius: 14))
    }

    private func defaultRound(_ bracket: Bracket, state: TournamentState) -> Int {
        if !state.placementComplete { return 0 }
        for row in bracket.matches {
            if let mv = row.first(where: { $0.ready && $0.winner == nil }) { return mv.round }
        }
        return bracket.rounds - 1
    }

    private func matchCard(_ mv: MatchView, state: TournamentState) -> some View {
        let isCurrent = state.current == mv.id
        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("第\(mv.match + 1)試合").font(.subheadline.bold())
                if isCurrent {
                    Text("いまの試合").font(.caption.bold()).padding(.horizontal, 8).padding(.vertical, 2)
                        .background(Color.red, in: Capsule()).foregroundStyle(.white)
                }
                Spacer()
                if mv.winner != nil {
                    Text(mv.auto ? "不戦勝" : "決着").font(.caption.bold()).foregroundStyle(.green)
                } else if mv.ready {
                    Text("勝者を選ぶ").font(.caption).foregroundStyle(.secondary)
                } else if mv.a == .empty || mv.b == .empty {
                    Text("枠が空いている").font(.caption).foregroundStyle(.secondary)
                } else {
                    Text("前の試合待ち").font(.caption).foregroundStyle(.secondary)
                }
            }
            teamButton(mv, mv.a)
            teamButton(mv, mv.b)
            if mv.ready {
                HStack {
                    if mv.winner == nil {
                        Button(isCurrent ? "いまの試合です" : "いまの試合にする") {
                            Task { await model.setCurrent(round: mv.round, match: mv.match) }
                        }
                        .buttonStyle(.bordered).disabled(model.busy || isCurrent)
                    } else if !mv.auto {
                        Button("勝者を取り消す", role: .destructive) { confirmUnwin = mv }
                            .buttonStyle(.bordered).disabled(model.busy)
                    }
                    if isCurrent && mv.winner == nil {
                        Button("解除") { Task { await model.clearCurrent() } }
                            .buttonStyle(.bordered).disabled(model.busy)
                    }
                }
                .font(.subheadline)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(isCurrent ? Color.red : Color.clear, lineWidth: 2))
    }

    @ViewBuilder private func teamButton(_ mv: MatchView, _ side: Participant) -> some View {
        switch side {
        case .team(let name):
            let won = mv.winner == name
            let lost = mv.winner != nil && !won
            if mv.ready {
                Button {
                    pendingWin = PendingWin(round: mv.round, match: mv.match, team: name, replacing: mv.winner != nil)
                } label: {
                    HStack {
                        Text(name).font(.headline).multilineTextAlignment(.leading)
                        Spacer(minLength: 0)
                        if won { Image(systemName: "checkmark.circle.fill") }
                    }
                    .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                }
                .buttonStyle(.borderedProminent)
                .tint(won ? .green : (lost ? .gray : .blue))
                .disabled(model.busy || won)
            } else {
                teamLabel(name, won: won)
            }
        case .pending:
            Text("(前の試合の勝者)").foregroundStyle(.secondary).frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        case .empty:
            Text("(空き枠)").foregroundStyle(.secondary).frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        case .none:
            Text("シード(不戦勝)").foregroundStyle(.secondary).frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        }
    }

    private func teamLabel(_ name: String, won: Bool) -> some View {
        HStack {
            Text(name).font(.headline)
            Spacer(minLength: 0)
            if won { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
    }

    private var dangerZone: some View {
        VStack(spacing: 8) {
            Divider().padding(.top, 40)
            Text("ここから下は、全部を消す操作です").font(.caption).foregroundStyle(.secondary)
            Button("リセット(初期状態に戻す)", role: .destructive) { confirmReset = true }
                .buttonStyle(.bordered).tint(.red).disabled(model.busy)
        }
    }

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
}
