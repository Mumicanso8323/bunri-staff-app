package com.yusukedoi.bunrisaistaff

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelTest {
    private fun net() = ApiException(ApiError.Network)

    // region 抽選

    @Test fun lotteryCanDrawOnlyWhenActive() = runTest {
        val api = FakeApi()
        val m = LotteryModel(api, this)
        m.refresh()
        assertTrue(m.ui.value.canDraw)
        api.snapshotResult = { SnapshotResult.Changed(snap(SessionStatus.WAITING, updatedAt = 2.0), "e2") }
        m.refresh()
        assertFalse(m.ui.value.canDraw)
    }

    @Test fun drawBlocksDoubleTapAndCoolsDown() = runTest {
        val api = FakeApi()
        val m = LotteryModel(api, this)
        m.refresh()
        m.draw(); m.draw(); m.draw()
        runCurrent()
        assertEquals(1, api.calls.count { it == "draw" })
        assertTrue(m.ui.value.drawCooling)
        advanceTimeBy(2900)
        m.draw()
        runCurrent()
        assertEquals("クールダウン中は引けない", 1, api.calls.count { it == "draw" })
        advanceTimeBy(200)
        assertFalse(m.ui.value.drawCooling)
        m.draw()
        advanceUntilIdle()
        assertEquals(2, api.calls.count { it == "draw" })
    }

    @Test fun drawBlockedWhileBusy() = runTest {
        val api = FakeApi().apply { gate = CompletableDeferred() }
        val m = LotteryModel(api, this, drawCooldownMs = 0)
        m.refresh()
        m.draw()
        runCurrent()
        assertTrue(m.ui.value.busy)
        m.draw()
        m.createSession()
        runCurrent()
        assertEquals(listOf("snapshot", "draw"), api.calls)
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(m.ui.value.busy)
    }

    @Test fun drawOutcomesBecomeMessages() = runTest {
        val api = FakeApi()
        val m = LotteryModel(api, this, drawCooldownMs = 0)
        m.refresh()
        for ((outcome, expect) in listOf(
            DrawOutcome.NotActive to "いまは番号を引けません",
            DrawOutcome.Exhausted to "75個すべて引きました。",
            DrawOutcome.NotFound to "セッションが見つかりません",
        )) {
            api.drawResult = { outcome }
            m.draw()
            advanceUntilIdle()
            assertTrue(m.ui.value.message!!.startsWith(expect))
        }
        api.drawResult = { DrawOutcome.Drawn(DrawResult(1, 74, 1)) }
        m.draw(); advanceUntilIdle()
        assertNull(m.ui.value.message)
    }

    @Test fun drawNetworkErrorAddsHintAndDoesNotRetry() = runTest {
        val api = FakeApi().apply { drawResult = { throw net() } }
        val m = LotteryModel(api, this, drawCooldownMs = 0)
        m.refresh()
        m.draw()
        advanceUntilIdle()
        assertTrue(m.ui.value.message!!.contains("引けたかどうかは、番号の一覧で確かめてください。"))
        assertEquals(1, api.calls.count { it == "draw" })
    }

    @Test fun refreshKeepsEtagAndIgnoresOlderSnapshots() = runTest {
        val api = FakeApi()
        val m = LotteryModel(api, this)
        m.refresh()
        api.snapshotResult = { SnapshotResult.NotModified }
        m.refresh()
        assertEquals(listOf(null, "e1"), api.etags)
        api.snapshotResult = { SnapshotResult.Changed(snap(draws = listOf(1), updatedAt = 0.5), "old") }
        m.refresh()
        assertTrue(m.ui.value.draws.isEmpty())
        api.snapshotResult = { throw net() }
        m.refresh()
        assertFalse(m.ui.value.connected)
        api.snapshotResult = { SnapshotResult.NotModified }
        m.refresh()
        assertTrue(m.ui.value.connected)
    }

    @Test fun finishAndReopenUseStatus() = runTest {
        val api = FakeApi()
        val m = LotteryModel(api, this)
        m.refresh()
        m.finish(session()); advanceUntilIdle()
        m.reopen(session(SessionStatus.FINISHED)); advanceUntilIdle()
        assertEquals(listOf("status:finished", "status:active"), api.calls.filter { it.startsWith("status") })
        api.statusError = ApiException(ApiError.Server(500, null))
        m.start(session(SessionStatus.WAITING)); advanceUntilIdle()
        assertTrue(m.ui.value.message!!.contains("(500)"))
    }

    // endregion

    // region QR確認

    private fun TestScope.verify(api: FakeApi) = VerifyModel(api, this)

    @Test fun verifyRoutesResults() = runTest {
        val api = FakeApi()
        val m = verify(api)
        val code = "https://bunrisai.jp/admin/bingo/verify?player=123e4567-e89b-12d3-a456-426614174000"
        suspend fun check(lookup: PlayerLookup, expected: VerifyResult) {
            api.playerResult = { lookup }
            m.handle(code); advanceUntilIdle()
            assertEquals(VerifyPhase.Result(expected), m.phase.value)
            m.next()
        }
        check(PlayerLookup.Found(player()), VerifyResult.ReadyToClaim(player()))
        check(PlayerLookup.Found(player(claimed = true)), VerifyResult.AlreadyClaimed(player(claimed = true)))
        check(PlayerLookup.Found(player(bingo = false)), VerifyResult.NotBingo(player(bingo = false)))
        check(PlayerLookup.Found(player(latest = false)), VerifyResult.Stale)
        check(PlayerLookup.NotFound, VerifyResult.NotFound)
        check(PlayerLookup.Invalid, VerifyResult.InvalidQr)
    }

    @Test fun invalidQrDoesNotCallApi() = runTest {
        val api = FakeApi()
        val m = verify(api)
        m.handle("https://example.com/"); advanceUntilIdle()
        assertEquals(VerifyPhase.Result(VerifyResult.InvalidQr), m.phase.value)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun verifyIgnoresReadsWhileWorkingOrShowingResult() = runTest {
        val api = FakeApi()
        val m = verify(api)
        val id = "123e4567-e89b-12d3-a456-426614174000"
        m.handle(id); m.handle(id)
        advanceUntilIdle()
        m.handle(id); advanceUntilIdle()
        assertEquals(1, api.calls.count { it == "player" })
        m.next()
        m.handle(id); advanceUntilIdle()
        assertEquals(2, api.calls.count { it == "player" })
    }

    @Test fun claimOnlyFromReadyAndOnlyOnce() = runTest {
        val api = FakeApi().apply { gate = CompletableDeferred() }
        val m = verify(api)
        m.claim(player())          // 結果が無いので何もしない
        runCurrent()
        assertTrue(api.calls.isEmpty())
        m.handle("123e4567-e89b-12d3-a456-426614174000"); advanceUntilIdle()
        m.claim(player()); m.claim(player()); runCurrent()
        assertEquals(1, api.calls.count { it == "claim" })
        api.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(VerifyPhase.Result(VerifyResult.ClaimedNow(player(claimed = true))), m.phase.value)
        m.claim(player()); runCurrent()
        assertEquals(1, api.calls.count { it == "claim" })
    }

    @Test fun claimRefusalsAreRouted() = runTest {
        val api = FakeApi()
        val m = verify(api)
        suspend fun check(outcome: ClaimOutcome, expected: VerifyResult) {
            m.handle("123e4567-e89b-12d3-a456-426614174000"); advanceUntilIdle()
            api.claimResult = { outcome }
            m.claim(player()); advanceUntilIdle()
            assertEquals(VerifyPhase.Result(expected), m.phase.value)
            m.next()
        }
        check(ClaimOutcome.Refused(ClaimRefusal.AlreadyClaimed, player(claimed = true)), VerifyResult.AlreadyClaimed(player(claimed = true)))
        check(ClaimOutcome.Refused(ClaimRefusal.AlreadyClaimed, null), VerifyResult.AlreadyClaimed(player()))
        check(ClaimOutcome.Refused(ClaimRefusal.NoBingo, null), VerifyResult.NotBingo(player()))
        check(ClaimOutcome.Refused(ClaimRefusal.StaleSession, null), VerifyResult.Stale)
        check(ClaimOutcome.Refused(ClaimRefusal.Other("boom"), null), VerifyResult.Failed("記録できませんでした(boom)。もう一度読み取ってください。"))
        check(ClaimOutcome.NotFound, VerifyResult.NotFound)
    }

    @Test fun claimNetworkErrorHintsToReread() = runTest {
        val api = FakeApi()
        val m = verify(api)
        m.handle("123e4567-e89b-12d3-a456-426614174000"); advanceUntilIdle()
        api.claimResult = { throw net() }
        m.claim(player()); advanceUntilIdle()
        val r = (m.phase.value as VerifyPhase.Result).result as VerifyResult.Failed
        assertTrue(r.text.contains("記録できたかどうかは、もう一度QRを読むと分かります。"))
        assertEquals(1, api.calls.count { it == "claim" })
    }

    @Test fun lookupNetworkErrorShowsFailed() = runTest {
        val api = FakeApi().apply { playerResult = { throw net() } }
        val m = verify(api)
        m.handle("123e4567-e89b-12d3-a456-426614174000"); advanceUntilIdle()
        assertTrue(((m.phase.value as VerifyPhase.Result).result as VerifyResult.Failed).text.startsWith("通信できませんでした"))
    }

    // endregion

    // region トーナメント

    @Test fun tournamentWinSendsOpAndAppliesNewerVersion() = runTest {
        val api = FakeApi()
        val m = TournamentModel(api, this)
        m.refresh()
        m.win(0, 1, "T2"); advanceUntilIdle()
        assertTrue("op:win" in api.calls)
        assertEquals(2, m.ui.value.snapshot!!.version)
        assertNull(m.ui.value.message)
        // 操作のあとはETagなしで取り直す
        assertEquals(null, api.etags.last())
    }

    @Test fun tournamentBlocksDoubleSend() = runTest {
        val api = FakeApi().apply { gate = CompletableDeferred() }
        val m = TournamentModel(api, this)
        m.refresh()
        m.win(0, 0, "T0"); m.win(0, 0, "T1"); m.undo(); runCurrent()
        assertEquals(1, api.calls.count { it.startsWith("op:") })
        api.gate!!.complete(Unit); advanceUntilIdle()
        assertFalse(m.ui.value.busy)
    }

    @Test fun tournament400ShowsServerReasonVerbatim() = runTest {
        val api = FakeApi().apply { tournamentOpResult = { throw ApiException(ApiError.Server(400, "この試合はまだ対戦相手が決まっていません")) } }
        val m = TournamentModel(api, this)
        m.refresh()
        m.win(1, 0, "T0"); advanceUntilIdle()
        assertEquals("この試合はまだ対戦相手が決まっていません", m.ui.value.message)
    }

    @Test fun tournamentNetworkAndOtherErrors() = runTest {
        val api = FakeApi()
        val m = TournamentModel(api, this)
        m.refresh()
        api.tournamentOpResult = { throw net() }
        m.undo(); advanceUntilIdle()
        assertTrue(m.ui.value.message!!.endsWith("押せたかどうかは、表で確かめてください。"))
        api.tournamentOpResult = { throw ApiException(ApiError.Server(500, null)) }
        m.undo(); advanceUntilIdle()
        assertTrue(m.ui.value.message!!.contains("(500)"))
    }

    @Test fun tournamentRefreshIgnoresOlderVersion() = runTest {
        val api = FakeApi()
        val m = TournamentModel(api, this)
        api.tournamentResult = { TournamentSnapshotResult.Changed(tsnap(5), "t5") }
        m.refresh()
        api.tournamentResult = { TournamentSnapshotResult.Changed(tsnap(3), "t3") }
        m.refresh()
        assertEquals(5, m.ui.value.snapshot!!.version)
        api.tournamentResult = { TournamentSnapshotResult.NotModified }
        m.refresh()
        assertEquals("t5", api.etags.last())
    }

    @Test fun tournamentOpsSendTheRightBodies() = runTest {
        val seen = mutableListOf<Map<String, Any?>>()
        val api = FakeApi().apply { tournamentOpResult = { seen += it; tsnap(it.size + 10) } }
        val m = TournamentModel(api, this)
        m.win(1, 2, "A"); advanceUntilIdle()
        m.unwin(1, 2); advanceUntilIdle()
        m.undo(); advanceUntilIdle()
        m.setCurrent(0, 3); advanceUntilIdle()
        m.clearCurrent(); advanceUntilIdle()
        m.reset(); advanceUntilIdle()
        assertEquals(mapOf("action" to "win", "round" to 1, "match" to 2, "team" to "A"), seen[0])
        assertEquals(mapOf("action" to "unwin", "round" to 1, "match" to 2), seen[1])
        assertEquals(mapOf("action" to "undo"), seen[2])
        assertEquals(mapOf("action" to "current", "round" to 0, "match" to 3), seen[3])
        assertEquals(mapOf("action" to "clearCurrent"), seen[4])
        assertEquals(mapOf("action" to "reset"), seen[5])
    }

    // endregion
}
