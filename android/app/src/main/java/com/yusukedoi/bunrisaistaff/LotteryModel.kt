package com.yusukedoi.bunrisaistaff

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LotteryUi(
    val snapshot: BingoSnapshot? = null,
    val connected: Boolean = true,
    val busy: Boolean = false,
    val drawCooling: Boolean = false,
    val message: String? = null,
) {
    val session: BingoSession? get() = snapshot?.session
    val draws: List<Int> get() = snapshot?.draws ?: emptyList()
    val latestNumber: Int? get() = draws.lastOrNull()
    val remaining: Int get() = 75 - draws.size

    val prizes: List<Int> get() = snapshot?.prizes ?: emptyList()
    val prizeTotal: Int get() = snapshot?.prizeTotal ?: BingoSnapshot.DEFAULT_PRIZE_TOTAL
    val latestPrize: Int? get() = prizes.lastOrNull()
    val prizeRemaining: Int get() = (prizeTotal - prizes.size).coerceAtLeast(0)

    /** 景品は回の状態に関係なく引ける(回があって、通信中でなく、残りがあるとき)。 */
    val canDrawPrize: Boolean get() = session != null && !busy && prizeRemaining > 0
    val canUndoPrize: Boolean get() = session != null && !busy && prizes.isNotEmpty()

    /** 状態に合うときだけ押せる。押した直後の約3秒と、通信中は押せない。 */
    val canDraw: Boolean get() = session?.status == SessionStatus.ACTIVE && !busy && !drawCooling && remaining > 0
}

/** 操作はメインスレッドから呼ぶ(busy の確認と設定が1つの流れで済み、二重送信にならない)。 */
class LotteryModel(
    private val api: StaffApi,
    private val scope: CoroutineScope,
    private val drawCooldownMs: Long = 3000,
) {
    private val _ui = MutableStateFlow(LotteryUi())
    val ui: StateFlow<LotteryUi> = _ui.asStateFlow()

    private var etag: String? = null

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    // region 取得

    suspend fun refresh() {
        try {
            when (val r = api.snapshot(etag)) {
                is SnapshotResult.Changed -> {
                    val cur = _ui.value.snapshot
                    if (cur == null || r.snapshot.updatedAt >= cur.updatedAt) {
                        _ui.update { it.copy(snapshot = r.snapshot) }
                        etag = r.etag
                    }
                }
                SnapshotResult.NotModified -> {}
            }
            _ui.update { it.copy(connected = true) }
        } catch (_: Exception) {
            _ui.update { it.copy(connected = false) }
        }
    }

    // endregion

    // region 操作

    fun createSession() = run { api.createSession() }

    fun start(session: BingoSession) = run { api.setStatus(session.id, SessionStatus.ACTIVE) }

    fun finish(session: BingoSession) = run { api.setStatus(session.id, SessionStatus.FINISHED) }

    /** 間違えて終了したときの立て直し。同じセッションを進行中に戻す(番号と参加者はそのまま)。 */
    fun reopen(session: BingoSession) = run { api.setStatus(session.id, SessionStatus.ACTIVE) }

    fun draw() {
        val current = _ui.value
        if (!current.canDraw) return
        val session = current.session ?: return
        _ui.update { it.copy(busy = true, drawCooling = true, message = null) }
        scope.launch {
            delay(drawCooldownMs)
            _ui.update { it.copy(drawCooling = false) }
        }
        scope.launch {
            var text: String? = null
            try {
                when (api.draw(session.id)) {
                    is DrawOutcome.Drawn -> {}
                    DrawOutcome.NotActive -> text = "いまは番号を引けません(進行中ではありません)。画面の状態を確かめてください。"
                    DrawOutcome.Exhausted -> text = "75個すべて引きました。"
                    DrawOutcome.NotFound -> text = "セッションが見つかりません。画面を確かめてください。"
                }
            } catch (e: ApiException) {
                text = Messages.describe(e.error)
                // 通信エラーでも、サーバー側では引けているかもしれない。
                if (e.error == ApiError.Network) text += "引けたかどうかは、番号の一覧で確かめてください。"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                text = Messages.describe(ApiError.Network)
            }
            _ui.update { it.copy(busy = false, message = text) }
            refresh()
        }
    }

    /** 景品を1つ引く。番号を引くのとは独立(状態に関係なく押せる)。通信中は押せない。 */
    fun drawPrize() {
        val current = _ui.value
        if (!current.canDrawPrize) return
        val session = current.session ?: return
        _ui.update { it.copy(busy = true, message = null) }
        scope.launch {
            var text: String? = null
            try {
                when (val outcome = api.drawPrize(session.id)) {
                    is PrizeOutcome.Drawn -> {
                        // 次の取得を待たずに、引いた番号を画面に出す。取得できたらサーバーの値に置き換わる。
                        val n = outcome.result.number
                        updatePrizes { if (n in it) it else it + n }
                    }
                    PrizeOutcome.Exhausted -> text = "景品は全部引き終わりました。"
                    PrizeOutcome.NotFound -> text = "セッションが見つかりません。画面を確かめてください。"
                }
            } catch (e: ApiException) {
                text = Messages.describe(e.error)
                // 通信エラーでも、サーバー側では引けているかもしれない。
                if (e.error == ApiError.Network) text += "引けたかどうかは、景品の「引いた数」で確かめてください。"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                text = Messages.describe(ApiError.Network)
            }
            _ui.update { it.copy(busy = false, message = text) }
            refresh()
        }
    }

    /** 最後に引いた景品を1つ取り消す(押し間違い用)。確認は画面側で取る。 */
    fun undoPrize() {
        val current = _ui.value
        if (!current.canUndoPrize) return
        val session = current.session ?: return
        _ui.update { it.copy(busy = true, message = null) }
        scope.launch {
            var text: String? = null
            try {
                when (val outcome = api.undoPrize(session.id)) {
                    is PrizeUndoOutcome.Undone -> {
                        val n = outcome.number
                        if (n == null) {
                            text = "取り消せる景品がありません。"
                        } else {
                            updatePrizes { list -> list.filter { it != n } }
                        }
                    }
                    PrizeUndoOutcome.NotFound -> text = "セッションが見つかりません。画面を確かめてください。"
                }
            } catch (e: ApiException) {
                text = Messages.describe(e.error)
                if (e.error == ApiError.Network) text += "取り消せたかどうかは、景品の「引いた数」で確かめてください。"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                text = Messages.describe(ApiError.Network)
            }
            _ui.update { it.copy(busy = false, message = text) }
            refresh()
        }
    }

    private fun updatePrizes(change: (List<Int>) -> List<Int>) {
        _ui.update { ui -> ui.snapshot?.let { s -> ui.copy(snapshot = s.copy(prizes = change(s.prizes))) } ?: ui }
    }

    private fun run(action: suspend () -> Unit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, message = null) }
        scope.launch {
            var text: String? = null
            try {
                action()
            } catch (e: ApiException) {
                text = Messages.describe(e.error)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                text = Messages.describe(ApiError.Network)
            }
            _ui.update { it.copy(busy = false, message = text) }
            refresh()
        }
    }

    // endregion
}
