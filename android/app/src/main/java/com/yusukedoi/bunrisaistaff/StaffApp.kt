package com.yusukedoi.bunrisaistaff

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** API とモデルをプロセスの間ずっと持つ(画面の回転で通信やクールダウンが途切れないように)。 */
class StaffApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val api: StaffApi = AdminApi(passwordProvider = { Credentials.adminPassword() })
    val lottery = LotteryModel(api, scope)
    val tournament = TournamentModel(api, scope)
    val verify = VerifyModel(api, scope)

    private val _loginProblem = MutableStateFlow<String?>(null)
    /** 上の赤い帯に出す文。ログインできているときは null。 */
    val loginProblem: StateFlow<String?> = _loginProblem.asStateFlow()

    /** 起動時の自動ログイン(と、赤い帯の「やり直す」)。失敗しても各画面の操作で、必要なときにもう一度ためす(401 のとき)。 */
    fun login(force: Boolean = false) {
        scope.launch {
            try {
                if (force) api.login() else api.ensureLoggedIn()
                _loginProblem.value = null
            } catch (e: ApiException) {
                _loginProblem.value = Messages.describe(e.error)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _loginProblem.value = Messages.describe(ApiError.Network)
            }
        }
    }
}
