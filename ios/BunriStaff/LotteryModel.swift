import Foundation
import SwiftUI

@MainActor
final class LotteryModel: ObservableObject {
    static let drawCooldown: UInt64 = 3_000_000_000
    static let pollInterval: UInt64 = 2_500_000_000

    let api: AdminAPI
    @Published private(set) var snapshot: BingoSnapshot?
    @Published private(set) var connected = true
    @Published private(set) var busy = false
    @Published private(set) var drawCooling = false
    @Published var message: String?

    private var etag: String?
    private var pollTask: Task<Void, Never>?

    init(api: AdminAPI) { self.api = api }

    var session: BingoSession? { snapshot?.session }
    var draws: [Int] { snapshot?.draws ?? [] }
    var latestNumber: Int? { draws.last }
    var remaining: Int { 75 - draws.count }

    /// 状態に合うときだけ押せる。押した直後の約3秒と、通信中は押せない。
    var canDraw: Bool { session?.status == .active && !busy && !drawCooling && remaining > 0 }

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
            switch try await api.snapshot(etag: etag) {
            case .changed(let next, let tag):
                if snapshot == nil || next.updatedAt >= (snapshot?.updatedAt ?? 0) {
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

    func createSession() async {
        await run { _ = try await self.api.createSession() }
    }

    func start(_ session: BingoSession) async {
        await run { _ = try await self.api.setStatus(sessionId: session.id, status: .active) }
    }

    func finish(_ session: BingoSession) async {
        await run { _ = try await self.api.setStatus(sessionId: session.id, status: .finished) }
    }

    /// 間違えて終了したときの立て直し。同じセッションを進行中に戻す(番号と参加者はそのまま)。
    func reopen(_ session: BingoSession) async {
        await run { _ = try await self.api.setStatus(sessionId: session.id, status: .active) }
    }

    func draw() async {
        guard canDraw, let session else { return }
        busy = true
        drawCooling = true
        message = nil
        Task {
            try? await Task.sleep(nanoseconds: Self.drawCooldown)
            self.drawCooling = false
        }
        defer { busy = false }
        do {
            switch try await api.draw(sessionId: session.id) {
            case .drawn:
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            case .notActive:
                message = "いまは番号を引けません(進行中ではありません)。画面の状態を確かめてください。"
            case .exhausted:
                message = "75個すべて引きました。"
            case .notFound:
                message = "セッションが見つかりません。画面を確かめてください。"
            }
        } catch let error as APIError {
            message = Messages.describe(error)
            // 通信エラーでも、サーバー側では引けているかもしれない。
            if error == .network { message! += "引けたかどうかは、番号の一覧で確かめてください。" }
        } catch {
            message = Messages.describe(.network)
        }
        await refresh()
    }

    private func run(_ action: @escaping () async throws -> Void) async {
        guard !busy else { return }
        busy = true
        message = nil
        defer { busy = false }
        do {
            try await action()
        } catch let error as APIError {
            message = Messages.describe(error)
        } catch {
            message = Messages.describe(.network)
        }
        await refresh()
    }
}
