package com.yusukedoi.bunrisaistaff

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TournamentUi(
    val snapshot: TournamentSnapshot? = null,
    val connected: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val state: TournamentState? get() = snapshot?.state
}

class TournamentModel(
    private val api: StaffApi,
    private val scope: CoroutineScope,
) {
    private val _ui = MutableStateFlow(TournamentUi())
    val ui: StateFlow<TournamentUi> = _ui.asStateFlow()

    private var etag: String? = null

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    suspend fun refresh() {
        try {
            when (val r = api.tournament(etag)) {
                is TournamentSnapshotResult.Changed -> {
                    val cur = _ui.value.snapshot
                    if (cur == null || r.snapshot.version >= cur.version) {
                        _ui.update { it.copy(snapshot = r.snapshot) }
                        etag = r.etag
                    }
                }
                TournamentSnapshotResult.NotModified -> {}
            }
            _ui.update { it.copy(connected = true) }
        } catch (_: Exception) {
            _ui.update { it.copy(connected = false) }
        }
    }

    fun win(round: Int, match: Int, team: String) =
        send(mapOf("action" to "win", "round" to round, "match" to match, "team" to team))

    fun unwin(round: Int, match: Int) = send(mapOf("action" to "unwin", "round" to round, "match" to match))

    fun undo() = send(mapOf("action" to "undo"))

    /** 抜けたチームを次の空枠に入れる */
    fun place(team: String) = send(mapOf("action" to "place", "team" to team))

    fun setCurrent(round: Int, match: Int) = send(mapOf("action" to "current", "round" to round, "match" to match))

    fun clearCurrent() = send(mapOf("action" to "clearCurrent"))

    fun reset() = send(mapOf("action" to "reset"))

    private fun send(op: Map<String, Any?>) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, message = null) }
        scope.launch {
            var text: String? = null
            try {
                val next = api.tournamentOp(op)
                if (next.version >= (_ui.value.snapshot?.version ?: 0)) {
                    etag = null   // 次のポーリングで ETag つきの正しい値を取り直す
                    _ui.update { it.copy(snapshot = next) }
                }
            } catch (e: ApiException) {
                val err = e.error
                text = if (err is ApiError.Server && err.status == 400 && err.code != null) {
                    err.code   // サーバーの文(日本語)をそのまま出す
                } else {
                    Messages.describe(err) + if (err == ApiError.Network) "押せたかどうかは、表で確かめてください。" else ""
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                text = Messages.describe(ApiError.Network)
            }
            _ui.update { it.copy(busy = false, message = text) }
            refresh()
        }
    }
}
