import Foundation
import SwiftUI

@MainActor
final class TournamentModel: ObservableObject {
    static let pollInterval: UInt64 = 2_500_000_000

    let api: AdminAPI
    @Published private(set) var snapshot: TournamentSnapshot?
    @Published private(set) var connected = true
    @Published private(set) var busy = false
    @Published var message: String?

    private var etag: String?
    private var pollTask: Task<Void, Never>?

    init(api: AdminAPI) { self.api = api }

    var state: TournamentState? { snapshot?.state }

    // MARK: 取得

    func start() {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh()
                try? await Task.sleep(nanoseconds: Self.pollInterval)
            }
        }
    }

    func stop() {
        pollTask?.cancel()
        pollTask = nil
    }

    func refresh() async {
        do {
            switch try await api.tournament(etag: etag) {
            case .changed(let next, let tag):
                if snapshot == nil || next.version >= (snapshot?.version ?? 0) {
                    snapshot = next
                    etag = tag
                }
            case .notModified:
                break
            }
            connected = true
        } catch {
            connected = false
        }
    }

    // MARK: 操作

    func win(round: Int, match: Int, team: String) async {
        await send(["action": "win", "round": round, "match": match, "team": team])
    }

    func unwin(round: Int, match: Int) async {
        await send(["action": "unwin", "round": round, "match": match])
    }

    func undo() async { await send(["action": "undo"]) }

    /// 抜けたチームを次の空枠に入れる
    func place(team: String) async { await send(["action": "place", "team": team]) }

    func setCurrent(round: Int, match: Int) async {
        await send(["action": "current", "round": round, "match": match])
    }

    func clearCurrent() async { await send(["action": "clearCurrent"]) }

    func reset() async { await send(["action": "reset"]) }

    private func send(_ op: [String: Any]) async {
        guard !busy else { return }
        busy = true
        message = nil
        defer { busy = false }
        do {
            let next = try await api.tournamentOp(op)
            if next.version >= (snapshot?.version ?? 0) {
                snapshot = next
                etag = nil   // 次のポーリングで ETag つきの正しい値を取り直す
            }
        } catch let error as APIError {
            if case .server(400, let reason?) = error {
                message = reason   // サーバーの文(日本語)をそのまま出す
            } else {
                message = Messages.describe(error)
                if error == .network { message! += "押せたかどうかは、表で確かめてください。" }
            }
        } catch {
            message = Messages.describe(.network)
        }
        await refresh()
    }
}
