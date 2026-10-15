import Foundation

// 形と解決のルールは /data/ashwell/bunri の src/lib/tournament.ts と同じ。

struct TournamentState: Codable, Equatable {
    let title: String
    /// 初戦の枠。長さは 8 か 16。
    /// チーム名 = 配置済み / nil = シード(不戦勝) / "" = まだ埋まっていない枠(クイズで抜けた順に埋める)
    let slots: [String?]
    /// 出場チームの一覧(配置の候補)。旧サーバーには無い
    let teams: [String]?
    let roundNames: [String]
    let gameNames: [String]
    /// "回戦添字-試合添字" -> 勝者チーム名
    let winners: [String: String]
    /// いま行っている試合 "r-m"
    let current: String?
    let canUndo: Bool
}

struct TournamentSnapshot: Codable, Equatable {
    let state: TournamentState
    let version: Int
    let updatedAt: String
}

enum TournamentSnapshotResult: Equatable {
    case changed(TournamentSnapshot, etag: String?)
    case notModified
}

enum Participant: Equatable {
    case team(String)
    case pending      // 前の試合待ち
    case none         // 不在(シード側の空き)
    case empty        // まだ誰も入っていない枠

    var teamName: String? {
        if case .team(let name) = self { return name }
        return nil
    }
}

struct MatchView: Equatable, Identifiable {
    let round: Int
    let match: Int
    let a: Participant
    let b: Participant
    /// 確定した勝者(不戦勝の自動通過を含む)。未確定は nil
    let winner: String?
    let auto: Bool
    /// 両チームが揃っていて、勝者入力が必要
    let ready: Bool
    let dead: Bool

    var id: String { Bracket.key(round, match) }
}

struct Bracket: Equatable {
    let size: Int
    let rounds: Int
    /// matches[r][m]
    let matches: [[MatchView]]
    let champion: String?

    static func key(_ r: Int, _ m: Int) -> String { "\(r)-\(m)" }

    static func resolve(slots: [String?], winners: [String: String]) -> Bracket {
        let size = slots.count
        let rounds = max(1, Int(log2(Double(max(size, 2))).rounded()))
        var matches: [[MatchView]] = []
        var inputs: [Participant] = slots.map { $0.map { $0.isEmpty ? Participant.empty : Participant.team($0) } ?? .none }
        for r in 0..<rounds {
            var row: [MatchView] = []
            var next: [Participant] = []
            for m in 0..<(inputs.count / 2) {
                let a = inputs[2 * m]
                let b = inputs[2 * m + 1]
                var winner: String?
                var auto = false
                let dead = a == .none && b == .none
                if let an = a.teamName, let bn = b.teamName {
                    if let w = winners[key(r, m)], w == an || w == bn { winner = w }
                } else if let an = a.teamName, b == .none {
                    winner = an; auto = true
                } else if let bn = b.teamName, a == .none {
                    winner = bn; auto = true
                }
                let seedVsEmpty = (a == .none && b == .empty) || (b == .none && a == .empty)
                let out: Participant = dead ? .none : (winner.map(Participant.team) ?? (seedVsEmpty ? .empty : .pending))
                row.append(MatchView(round: r, match: m, a: a, b: b, winner: winner, auto: auto,
                                     ready: a.teamName != nil && b.teamName != nil, dead: dead))
                next.append(out)
            }
            matches.append(row)
            inputs = next
        }
        return Bracket(size: size, rounds: rounds, matches: matches, champion: matches.last?.first?.winner)
    }
}

extension TournamentState {
    var bracket: Bracket { Bracket.resolve(slots: slots, winners: winners) }

    /// 配置済みのチーム名
    var placedTeams: [String] { slots.compactMap { $0 }.filter { !$0.isEmpty } }

    /// 出場チーム。teams が無い旧サーバーでは、配置済みのチームだけ
    var allTeams: [String] {
        var out = teams ?? []
        for t in placedTeams where !out.contains(t) { out.append(t) }
        return out
    }

    /// まだ枠に入っていないチーム(teams の順)
    var unplacedTeams: [String] {
        let placed = Set(placedTeams)
        return allTeams.filter { !placed.contains($0) }
    }

    /// 次に入れる空枠(添字の小さい順)。無ければ nil
    var nextEmptySlot: Int? { slots.firstIndex(where: { $0 == "" }) }

    /// シード以外の枠がすべて埋まっている
    var placementComplete: Bool { nextEmptySlot == nil }

    /// 次の空枠の言い方(「左ブロック 第2試合 下」)
    func slotLabel(_ index: Int) -> String {
        let half = max(slots.count / 4, 1)
        let m = index / 2
        return "\(m < half ? "左" : "右")ブロック 第\(m % half + 1)試合 \(index % 2 == 0 ? "上" : "下")"
    }

    /// 回戦 r(0 起点・初戦が 0)の名前。決勝から数えて並んでいる roundNames の末尾側を使う。
    func roundName(_ r: Int) -> String {
        let index = 4 - bracket.rounds + r
        let name = roundNames.indices.contains(index) ? roundNames[index] : ""
        return name.isEmpty ? "\(r + 1)回戦" : name
    }

    func gameName(_ r: Int) -> String {
        let index = 4 - bracket.rounds + r
        return gameNames.indices.contains(index) ? gameNames[index] : ""
    }
}
