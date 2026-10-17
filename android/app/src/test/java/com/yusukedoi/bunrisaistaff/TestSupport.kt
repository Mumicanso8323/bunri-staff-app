package com.yusukedoi.bunrisaistaff

import kotlinx.coroutines.CompletableDeferred

fun session(status: SessionStatus = SessionStatus.ACTIVE) = BingoSession("s-12345678-abc", status, "2026-10-18T05:00:00.000Z")

fun snap(status: SessionStatus? = SessionStatus.ACTIVE, draws: List<Int> = emptyList(), updatedAt: Double = 1.0, prizes: List<Int> = emptyList()) =
    BingoSnapshot(status?.let { session(it) }, draws, 5, 1, updatedAt, prizes)

fun player(bingo: Boolean = true, claimed: Boolean = false, latest: Boolean = true, claimedAt: String? = null) = PlayerView(
    "123e4567-e89b-12d3-a456-426614174000", "s-12345678-abc", SessionStatus.ACTIVE, latest, bingo, claimed, claimedAt,
    "2026-10-18T05:00:00.000Z", listOf(1, 2, 3), 10,
)

fun tsnap(version: Int = 1, canUndo: Boolean = false, winners: Map<String, String> = emptyMap()) = TournamentSnapshot(
    TournamentState("サークル対抗戦", List(8) { "T$it" }, listOf("", "", "", ""), listOf("", "", "", ""), winners, null, canUndo),
    version, "2026-10-18T05:00:00.000Z",
)

/** 画面のモデルのテスト用。応答は差し替え、途中で止めたいときは gate を使う。 */
class FakeApi : StaffApi {
    var snapshotResult: () -> SnapshotResult = { SnapshotResult.Changed(snap(), "e1") }
    var tournamentResult: () -> TournamentSnapshotResult = { TournamentSnapshotResult.Changed(tsnap(), "t1") }
    var tournamentOpResult: (Map<String, Any?>) -> TournamentSnapshot = { tsnap(2) }
    var drawResult: () -> DrawOutcome = { DrawOutcome.Drawn(DrawResult(7, 68, 7)) }
    var prizeResult: () -> PrizeOutcome = { PrizeOutcome.Drawn(DrawResult(37, 80, 1)) }
    var undoPrizeResult: () -> PrizeUndoOutcome = { PrizeUndoOutcome.Undone(37) }
    var playerResult: () -> PlayerLookup = { PlayerLookup.Found(player()) }
    var claimResult: () -> ClaimOutcome = { ClaimOutcome.Claimed(player(claimed = true)) }
    var statusError: ApiException? = null
    var gate: CompletableDeferred<Unit>? = null

    val calls = mutableListOf<String>()
    val etags = mutableListOf<String?>()

    override suspend fun ensureLoggedIn() {}
    override suspend fun login() {}
    override suspend fun snapshot(etag: String?): SnapshotResult { calls += "snapshot"; etags += etag; return snapshotResult() }
    override suspend fun tournament(etag: String?): TournamentSnapshotResult { calls += "tournament"; etags += etag; return tournamentResult() }
    override suspend fun tournamentOp(op: Map<String, Any?>): TournamentSnapshot { calls += "op:${op["action"]}"; gate?.await(); return tournamentOpResult(op) }
    override suspend fun createSession() { calls += "create"; gate?.await(); statusError?.let { throw it } }
    override suspend fun setStatus(sessionId: String, status: SessionStatus) { calls += "status:${status.raw}"; gate?.await(); statusError?.let { throw it } }
    override suspend fun draw(sessionId: String): DrawOutcome { calls += "draw"; gate?.await(); return drawResult() }
    override suspend fun drawPrize(sessionId: String): PrizeOutcome { calls += "prize:draw"; gate?.await(); return prizeResult() }
    override suspend fun undoPrize(sessionId: String): PrizeUndoOutcome { calls += "prize:undo"; gate?.await(); return undoPrizeResult() }
    override suspend fun player(guestId: String): PlayerLookup { calls += "player"; return playerResult() }
    override suspend fun claim(guestId: String, sessionId: String): ClaimOutcome { calls += "claim"; gate?.await(); return claimResult() }
}
