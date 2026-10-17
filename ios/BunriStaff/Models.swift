import Foundation

// 形は /data/ashwell/bunri の src/types/bingo.ts と同じ。

enum SessionStatus: String, Codable, Equatable {
    case waiting, active, finished

    var label: String {
        switch self {
        case .waiting: return "待機中"
        case .active: return "進行中"
        case .finished: return "終了"
        }
    }
}

struct BingoSession: Codable, Equatable {
    let id: String
    let status: SessionStatus
    let created_at: String
}

struct BingoSnapshot: Codable, Equatable {
    let session: BingoSession?
    let draws: [Int]
    let playerCount: Int
    let bingoCount: Int
    let updatedAt: Double
    /// 景品抽選で引いた景品番号(引いた順)。古いサーバーには無いので Optional。
    let prizes: [Int]?
    /// 景品の総数。古いサーバーには無い。
    let prizeTotal: Int?

    static let defaultPrizeTotal = 81

    var prizeList: [Int] { prizes ?? [] }
    var prizeCapacity: Int { prizeTotal ?? Self.defaultPrizeTotal }
}

struct PlayerView: Codable, Equatable {
    let guestId: String
    let sessionId: String
    let sessionStatus: SessionStatus
    let isLatestSession: Bool
    let hasBingo: Bool
    let claimed: Bool
    let claimedAt: String?
    let joinedAt: String
    let card: [Int]
    let drawsCount: Int
}

struct DrawResult: Codable, Equatable {
    let number: Int
    let remaining: Int
    let totalDrawn: Int
}

enum BingoLetter {
    static func of(_ n: Int) -> String {
        switch n {
        case ...15: return "B"
        case ...30: return "I"
        case ...45: return "N"
        case ...60: return "G"
        default: return "O"
        }
    }
}
