package com.yusukedoi.bunrisaistaff

import org.json.JSONObject

// 形と解決のルールは /data/ashwell/bunri の src/lib/tournament.ts と同じ。

data class TournamentState(
    val title: String,
    /**
     * 初戦の枠。長さは 8 か 16。
     * チーム名 = 配置済み / null = シード(不戦勝) / "" = まだ埋まっていない枠(クイズで抜けた順に埋める)
     */
    val slots: List<String?>,
    val roundNames: List<String>,
    val gameNames: List<String>,
    /** "回戦添字-試合添字" -> 勝者チーム名 */
    val winners: Map<String, String>,
    /** いま行っている試合 "r-m" */
    val current: String?,
    val canUndo: Boolean,
    /** 出場チームの一覧(配置の候補)。旧サーバーには無い */
    val teams: List<String>? = null,
) {
    val bracket: Bracket get() = Bracket.resolve(slots, winners)

    /** 配置済みのチーム名 */
    val placedTeams: List<String> get() = slots.filterNotNull().filter { it.isNotEmpty() }

    /** 出場チーム。teams が無い旧サーバーでは、配置済みのチームだけ */
    val allTeams: List<String> get() = (teams ?: emptyList()) + placedTeams.filter { it !in (teams ?: emptyList()) }

    /** まだ枠に入っていないチーム(teams の順) */
    val unplacedTeams: List<String> get() { val placed = placedTeams.toSet(); return allTeams.filter { it !in placed } }

    /** 次に入れる空枠(添字の小さい順)。無ければ null */
    val nextEmptySlot: Int? get() = slots.indexOfFirst { it == "" }.takeIf { it >= 0 }

    /** シード以外の枠がすべて埋まっている */
    val placementComplete: Boolean get() = nextEmptySlot == null

    /** 枠の言い方(「左ブロック 第2試合 下」) */
    fun slotLabel(index: Int): String {
        val half = maxOf(slots.size / 4, 1)
        val m = index / 2
        return "${if (m < half) "左" else "右"}ブロック 第${m % half + 1}試合 ${if (index % 2 == 0) "上" else "下"}"
    }

    /** 回戦 r(0 起点・初戦が 0)の名前。決勝から数えて並んでいる roundNames の末尾側を使う。 */
    fun roundName(r: Int): String {
        val index = 4 - bracket.rounds + r
        val name = roundNames.getOrNull(index) ?: ""
        return name.ifEmpty { "${r + 1}回戦" }
    }

    fun gameName(r: Int): String = gameNames.getOrNull(4 - bracket.rounds + r) ?: ""

    companion object {
        fun fromJson(o: JSONObject): TournamentState {
            val slotsJson = o.getJSONArray("slots")
            val slots = List(slotsJson.length()) { if (slotsJson.isNull(it)) null else slotsJson.getString(it) }
            val winnersJson = o.optJSONObject("winners") ?: JSONObject()
            val winners = winnersJson.keys().asSequence().associateWith { winnersJson.getString(it) }
            fun strings(key: String): List<String> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return List(a.length()) { a.optString(it, "") }
            }
            return TournamentState(
                title = o.optString("title", ""),
                slots = slots,
                roundNames = strings("roundNames"),
                gameNames = strings("gameNames"),
                winners = winners,
                current = if (o.isNull("current")) null else o.optString("current"),
                canUndo = o.optBoolean("canUndo", false),
                teams = o.optJSONArray("teams")?.let { a -> List(a.length()) { a.optString(it, "") } },
            )
        }
    }
}

data class TournamentSnapshot(val state: TournamentState, val version: Int, val updatedAt: String) {
    companion object {
        fun fromJson(o: JSONObject) = TournamentSnapshot(
            TournamentState.fromJson(o.getJSONObject("state")), o.getInt("version"), o.optString("updatedAt", ""),
        )
    }
}

sealed interface Participant {
    data class Team(val name: String) : Participant
    /** 前の試合待ち */
    data object Pending : Participant
    /** 不在(シード側の空き) */
    data object None : Participant
    /** まだ誰も入っていない枠 */
    data object Empty : Participant

    val teamName: String? get() = (this as? Team)?.name
}

data class MatchView(
    val round: Int,
    val match: Int,
    val a: Participant,
    val b: Participant,
    /** 確定した勝者(不戦勝の自動通過を含む)。未確定は null */
    val winner: String?,
    val auto: Boolean,
    /** 両チームが揃っていて、勝者入力が必要 */
    val ready: Boolean,
    val dead: Boolean,
) {
    val id: String get() = Bracket.key(round, match)
}

data class Bracket(val size: Int, val rounds: Int, val matches: List<List<MatchView>>, val champion: String?) {
    companion object {
        fun key(r: Int, m: Int) = "$r-$m"

        fun resolve(slots: List<String?>, winners: Map<String, String>): Bracket {
            val size = slots.size
            var rounds = 0
            var s = maxOf(size, 2)
            while (s > 1) { s /= 2; rounds++ }
            rounds = maxOf(1, rounds)
            val matches = mutableListOf<List<MatchView>>()
            var inputs: List<Participant> = slots.map { if (it == null) Participant.None else if (it.isEmpty()) Participant.Empty else Participant.Team(it) }
            for (r in 0 until rounds) {
                val row = mutableListOf<MatchView>()
                val next = mutableListOf<Participant>()
                for (m in 0 until inputs.size / 2) {
                    val a = inputs[2 * m]
                    val b = inputs[2 * m + 1]
                    var winner: String? = null
                    var auto = false
                    val dead = a == Participant.None && b == Participant.None
                    val an = a.teamName
                    val bn = b.teamName
                    if (an != null && bn != null) {
                        val w = winners[key(r, m)]
                        if (w != null && (w == an || w == bn)) winner = w
                    } else if (an != null && b == Participant.None) {
                        winner = an; auto = true
                    } else if (bn != null && a == Participant.None) {
                        winner = bn; auto = true
                    }
                    val seedVsEmpty = (a == Participant.None && b == Participant.Empty) || (b == Participant.None && a == Participant.Empty)
                    val out: Participant = if (dead) Participant.None else (winner?.let { Participant.Team(it) } ?: if (seedVsEmpty) Participant.Empty else Participant.Pending)
                    row += MatchView(r, m, a, b, winner, auto, an != null && bn != null, dead)
                    next += out
                }
                matches += row
                inputs = next
            }
            return Bracket(size, rounds, matches, matches.lastOrNull()?.firstOrNull()?.winner)
        }
    }
}

sealed interface TournamentSnapshotResult {
    data class Changed(val snapshot: TournamentSnapshot, val etag: String?) : TournamentSnapshotResult
    data object NotModified : TournamentSnapshotResult
}
