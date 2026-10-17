package com.yusukedoi.bunrisaistaff

import org.json.JSONArray
import org.json.JSONObject

// 形は /data/ashwell/bunri の src/types/bingo.ts と同じ。

enum class SessionStatus(val raw: String, val label: String) {
    WAITING("waiting", "待機中"),
    ACTIVE("active", "進行中"),
    FINISHED("finished", "終了");

    companion object {
        fun of(raw: String): SessionStatus =
            entries.firstOrNull { it.raw == raw } ?: throw IllegalArgumentException("status: $raw")
    }
}

data class BingoSession(val id: String, val status: SessionStatus, val createdAt: String) {
    companion object {
        fun fromJson(o: JSONObject) = BingoSession(o.getString("id"), SessionStatus.of(o.getString("status")), o.optString("created_at", ""))
    }
}

data class BingoSnapshot(
    val session: BingoSession?,
    val draws: List<Int>,
    val playerCount: Int,
    val bingoCount: Int,
    val updatedAt: Double,
    /** その回で引いた景品番号(引いた順)。古いサーバーには無いので、無ければ空。 */
    val prizes: List<Int> = emptyList(),
    /** 景品の総数。古いサーバーには無いので、無ければ 81。 */
    val prizeTotal: Int = DEFAULT_PRIZE_TOTAL,
) {
    companion object {
        const val DEFAULT_PRIZE_TOTAL = 81

        fun fromJson(o: JSONObject): BingoSnapshot = BingoSnapshot(
            session = o.optJSONObject("session")?.let(BingoSession::fromJson),
            draws = o.getJSONArray("draws").ints(),
            playerCount = o.getInt("playerCount"),
            bingoCount = o.getInt("bingoCount"),
            updatedAt = o.getDouble("updatedAt"),
            prizes = o.optJSONArray("prizes")?.ints() ?: emptyList(),
            prizeTotal = o.optInt("prizeTotal", DEFAULT_PRIZE_TOTAL),
        )
    }
}

data class PlayerView(
    val guestId: String,
    val sessionId: String,
    val sessionStatus: SessionStatus,
    val isLatestSession: Boolean,
    val hasBingo: Boolean,
    val claimed: Boolean,
    val claimedAt: String?,
    val joinedAt: String,
    val card: List<Int>,
    val drawsCount: Int,
) {
    companion object {
        fun fromJson(o: JSONObject) = PlayerView(
            guestId = o.getString("guestId"),
            sessionId = o.getString("sessionId"),
            sessionStatus = SessionStatus.of(o.getString("sessionStatus")),
            isLatestSession = o.getBoolean("isLatestSession"),
            hasBingo = o.getBoolean("hasBingo"),
            claimed = o.getBoolean("claimed"),
            claimedAt = if (o.isNull("claimedAt")) null else o.getString("claimedAt"),
            joinedAt = o.optString("joinedAt", ""),
            card = o.optJSONArray("card")?.ints() ?: emptyList(),
            drawsCount = o.optInt("drawsCount", 0),
        )
    }
}

data class DrawResult(val number: Int, val remaining: Int, val totalDrawn: Int)

internal fun JSONArray.ints(): List<Int> = List(length()) { getInt(it) }

object BingoLetter {
    fun of(n: Int): String = when {
        n <= 15 -> "B"
        n <= 30 -> "I"
        n <= 45 -> "N"
        n <= 60 -> "G"
        else -> "O"
    }
}
