package com.yusukedoi.bunrisaistaff

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface VerifyResult {
    data class ReadyToClaim(val player: PlayerView) : VerifyResult
    data class ClaimedNow(val player: PlayerView) : VerifyResult
    data class AlreadyClaimed(val player: PlayerView) : VerifyResult
    data class NotBingo(val player: PlayerView) : VerifyResult
    data object Stale : VerifyResult
    data object NotFound : VerifyResult
    data object InvalidQr : VerifyResult
    data class Failed(val text: String) : VerifyResult
}

sealed interface VerifyPhase {
    data object Scanning : VerifyPhase
    data object Working : VerifyPhase
    data class Result(val result: VerifyResult) : VerifyPhase
}

class VerifyModel(
    private val api: StaffApi,
    private val scope: CoroutineScope,
) {
    private val _phase = MutableStateFlow<VerifyPhase>(VerifyPhase.Scanning)
    val phase: StateFlow<VerifyPhase> = _phase.asStateFlow()

    /** QR を読んだ(または ID を手入力した)。処理中・結果表示中は、あとから来る読み取りを無視する。 */
    fun handle(code: String) {
        if (_phase.value != VerifyPhase.Scanning) return
        _phase.value = VerifyPhase.Working
        val guestId = PlayerCode.extract(code)
        if (guestId == null) {
            finish(VerifyResult.InvalidQr)
            return
        }
        scope.launch {
            try {
                when (val r = api.player(guestId)) {
                    is PlayerLookup.Found -> {
                        val p = r.player
                        finish(when {
                            !p.isLatestSession -> VerifyResult.Stale
                            !p.hasBingo -> VerifyResult.NotBingo(p)
                            p.claimed -> VerifyResult.AlreadyClaimed(p)
                            else -> VerifyResult.ReadyToClaim(p)
                        })
                    }
                    PlayerLookup.NotFound -> finish(VerifyResult.NotFound)
                    PlayerLookup.Invalid -> finish(VerifyResult.InvalidQr)
                }
            } catch (e: ApiException) {
                finish(VerifyResult.Failed(Messages.describe(e.error)))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                finish(VerifyResult.Failed(Messages.describe(ApiError.Network)))
            }
        }
    }

    /** 「景品受取済みにする」。 */
    fun claim(player: PlayerView) {
        val phase = _phase.value
        if (phase !is VerifyPhase.Result || phase.result !is VerifyResult.ReadyToClaim) return
        _phase.value = VerifyPhase.Working
        scope.launch {
            try {
                when (val r = api.claim(player.guestId, player.sessionId)) {
                    is ClaimOutcome.Claimed -> finish(VerifyResult.ClaimedNow(r.player))
                    ClaimOutcome.NotFound -> finish(VerifyResult.NotFound)
                    is ClaimOutcome.Refused -> when (val reason = r.reason) {
                        ClaimRefusal.AlreadyClaimed -> finish(VerifyResult.AlreadyClaimed(r.player ?: player))
                        ClaimRefusal.NoBingo -> finish(VerifyResult.NotBingo(r.player ?: player))
                        ClaimRefusal.StaleSession -> finish(VerifyResult.Stale)
                        is ClaimRefusal.Other -> finish(VerifyResult.Failed("記録できませんでした(${reason.code})。もう一度読み取ってください。"))
                    }
                }
            } catch (e: ApiException) {
                var text = Messages.describe(e.error)
                // 通信エラーでも、サーバー側では記録できているかもしれない。
                if (e.error == ApiError.Network) text += "記録できたかどうかは、もう一度QRを読むと分かります。"
                finish(VerifyResult.Failed(text))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                finish(VerifyResult.Failed(Messages.describe(ApiError.Network)))
            }
        }
    }

    /** 「次を読む」。 */
    fun next() {
        _phase.value = VerifyPhase.Scanning
    }

    private fun finish(result: VerifyResult) {
        _phase.value = VerifyPhase.Result(result)
    }
}
