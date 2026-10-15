package com.yusukedoi.bunrisaistaff

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface LoginFailure {
    data object NoPassword : LoginFailure          // このビルドにログイン情報が無い
    data object BadPassword : LoginFailure         // パスワードが合わない
    data object Locked : LoginFailure              // サーバが一時的にログインを止めている(5 回失敗で 10 分)
    data class Unexpected(val status: Int) : LoginFailure
}

sealed interface ApiError {
    data class Login(val failure: LoginFailure) : ApiError
    data object Unauthorized : ApiError            // ログインし直しても 401
    data object Network : ApiError
    data class Server(val status: Int, val code: String?) : ApiError
    data object Decoding : ApiError
}

class ApiException(val error: ApiError) : Exception(error.toString())

sealed interface SnapshotResult {
    data class Changed(val snapshot: BingoSnapshot, val etag: String?) : SnapshotResult
    data object NotModified : SnapshotResult
}

sealed interface DrawOutcome {
    data class Drawn(val result: DrawResult) : DrawOutcome
    data object NotActive : DrawOutcome     // 409
    data object Exhausted : DrawOutcome     // 400: 75 個すべて引いた
    data object NotFound : DrawOutcome
}

sealed interface PlayerLookup {
    data class Found(val player: PlayerView) : PlayerLookup
    data object NotFound : PlayerLookup
    data object Invalid : PlayerLookup
}

sealed interface ClaimRefusal {
    data object NoBingo : ClaimRefusal
    data object AlreadyClaimed : ClaimRefusal
    data object StaleSession : ClaimRefusal
    data class Other(val code: String) : ClaimRefusal
}

sealed interface ClaimOutcome {
    data class Claimed(val player: PlayerView) : ClaimOutcome
    data class Refused(val reason: ClaimRefusal, val player: PlayerView?) : ClaimOutcome
    data object NotFound : ClaimOutcome
}

/** 画面のモデルが使う API の口。テストでは偽物に差し替える。失敗は ApiException で投げる。 */
interface StaffApi {
    suspend fun ensureLoggedIn()
    suspend fun login()
    suspend fun snapshot(etag: String?): SnapshotResult
    suspend fun tournament(etag: String?): TournamentSnapshotResult
    suspend fun tournamentOp(op: Map<String, Any?>): TournamentSnapshot
    suspend fun createSession()
    suspend fun setStatus(sessionId: String, status: SessionStatus)
    suspend fun draw(sessionId: String): DrawOutcome
    suspend fun player(guestId: String): PlayerLookup
    suspend fun claim(guestId: String, sessionId: String): ClaimOutcome
}

/**
 * bunrisai.jp の API クライアント。ログインの Cookie(admin_token)は自分で持ち、
 * 401 が返ったら自動でログインし直して1回だけ再試行する(2回目の 401 は失敗として返す)。
 * 3xx は追わない(login は 303 の Set-Cookie だけ取れればよい)。
 * 書き込み系は、通信エラーでは再試行しない(サーバ側で成功している可能性があり、二重に引いてしまうため)。
 */
class AdminApi(
    baseUrl: String = "https://bunrisai.jp/",
    private val passwordProvider: () -> String?,
    client: OkHttpClient = OkHttpClient(),
) : StaffApi {
    private class Raw(val code: Int, val body: String, val headers: Headers) {
        fun header(name: String): String? = headers[name]
    }

    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val http: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        // OkHttp は切れた接続で POST を黙って再送する。抽選が二重に走るので止める(再送するかは画面が決める)。
        .retryOnConnectionFailure(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile private var token: String? = null
    private val loginLock = Mutex()

    // region ログイン

    override suspend fun ensureLoggedIn() {
        if (token != null) return
        loginLock.withLock {
            if (token == null) performLogin()
        }
    }

    /** 「やり直す」用。同時に呼ばれても、ログインの通信は順番に1回ずつ。 */
    override suspend fun login() {
        loginLock.withLock { performLogin() }
    }

    /** 401 のあと。別の呼び出しがすでにログインし直していれば、それを使う。 */
    private suspend fun relogin(stale: String?) {
        loginLock.withLock {
            val current = token
            if (current != null && current != stale) return
            token = null
            performLogin()
        }
    }

    private suspend fun performLogin() {
        val password = passwordProvider()
        if (password.isNullOrEmpty()) throw ApiException(ApiError.Login(LoginFailure.NoPassword))
        val request = Request.Builder()
            .url(url("api/admin/login"))
            .post(FormBody.Builder().add("password", password).build())
            .build()
        val raw = execute(request)
        val location = raw.header("Location") ?: ""
        if (location.contains("error=locked")) throw ApiException(ApiError.Login(LoginFailure.Locked))
        if (location.contains("error")) throw ApiException(ApiError.Login(LoginFailure.BadPassword))
        val received = if (raw.code in 300..399) cookieValue(raw.headers, COOKIE_NAME) else null
        if (received.isNullOrEmpty()) throw ApiException(ApiError.Login(LoginFailure.Unexpected(raw.code)))
        token = received
    }

    private fun cookieValue(headers: Headers, name: String): String? {
        for (line in headers.values("Set-Cookie")) {
            val pair = line.substringBefore(';').trim()
            if (pair.substringBefore('=') == name) return pair.substringAfter('=', "")
        }
        return null
    }

    // endregion

    // region 通信の下回り

    private fun url(path: String): HttpUrl = base.resolve(path) ?: throw ApiException(ApiError.Decoding)

    private suspend fun execute(request: Request): Raw = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(ApiException(ApiError.Network))
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = try {
                    response.use { Raw(it.code, it.body?.string() ?: "", it.headers) }
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(ApiException(ApiError.Network))
                    return
                }
                if (cont.isActive) cont.resume(raw)
            }
        })
    }

    private suspend fun attempt(make: () -> Request): Raw {
        val request = make()
        val t = token
        val withCookie = if (t != null) request.newBuilder().header("Cookie", "$COOKIE_NAME=$t").build() else request
        return execute(withCookie)
    }

    private suspend fun authed(make: () -> Request): Raw {
        ensureLoggedIn()
        val sent = token
        var result = attempt(make)
        if (result.code == 401) {
            relogin(sent)
            result = attempt(make)
            if (result.code == 401) throw ApiException(ApiError.Unauthorized)
        }
        return result
    }

    private fun json(method: String, path: String, query: Map<String, String> = emptyMap(), body: Map<String, Any?>? = null,
                     etag: String? = null): Request {
        val builder = url(path).newBuilder()
        for ((k, v) in query) builder.addQueryParameter(k, v)
        val requestBody: RequestBody? = when {
            body != null -> JSONObject(body).toString().toRequestBody(JSON)
            method == "GET" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        val request = Request.Builder().url(builder.build()).method(method, requestBody)
        if (etag != null) request.header("If-None-Match", etag)
        return request.build()
    }

    private fun errorCode(raw: Raw): String? = try { JSONObject(raw.body).optString("error").ifEmpty { null } } catch (_: Exception) { null }

    private fun errorPlayer(raw: Raw): PlayerView? = try { JSONObject(raw.body).optJSONObject("player")?.let(PlayerView::fromJson) } catch (_: Exception) { null }

    private fun fail(raw: Raw): ApiException = ApiException(ApiError.Server(raw.code, errorCode(raw)))

    private fun <T> decode(raw: Raw, parse: (JSONObject) -> T): T =
        try { parse(JSONObject(raw.body)) } catch (_: Exception) { throw ApiException(ApiError.Decoding) }

    // endregion

    // region 公開 API(ログイン不要)

    /** ETag つきの取得。変わっていなければ 304 = NotModified。 */
    override suspend fun snapshot(etag: String?): SnapshotResult {
        val raw = execute(json("GET", "api/bingo/state", etag = etag))
        return when (raw.code) {
            304 -> SnapshotResult.NotModified
            200 -> SnapshotResult.Changed(decode(raw, BingoSnapshot::fromJson), raw.header("ETag"))
            else -> throw fail(raw)
        }
    }

    /** トーナメントの状態(ログイン不要)。ETag つき。 */
    override suspend fun tournament(etag: String?): TournamentSnapshotResult {
        val raw = execute(json("GET", "api/tournament/state", etag = etag))
        return when (raw.code) {
            304 -> TournamentSnapshotResult.NotModified
            200 -> TournamentSnapshotResult.Changed(decode(raw, TournamentSnapshot::fromJson), raw.header("ETag"))
            else -> throw fail(raw)
        }
    }

    // endregion

    // region 管理 API

    /** トーナメントの1操作(`POST api/admin/tournament`)。400 の error には、サーバーの日本語の理由が入る。 */
    override suspend fun tournamentOp(op: Map<String, Any?>): TournamentSnapshot {
        val raw = authed { json("POST", "api/admin/tournament", body = op) }
        if (raw.code != 200) throw fail(raw)
        return decode(raw, TournamentSnapshot::fromJson)
    }

    override suspend fun createSession() {
        val raw = authed { json("POST", "api/admin/bingo/session") }
        if (raw.code != 200) throw fail(raw)
    }

    override suspend fun setStatus(sessionId: String, status: SessionStatus) {
        val raw = authed { json("PATCH", "api/admin/bingo/session", body = mapOf("sessionId" to sessionId, "status" to status.raw)) }
        if (raw.code != 200) throw fail(raw)
    }

    override suspend fun draw(sessionId: String): DrawOutcome {
        val raw = authed { json("POST", "api/admin/bingo/draw", body = mapOf("sessionId" to sessionId)) }
        return when {
            raw.code == 200 -> DrawOutcome.Drawn(decode(raw) { DrawResult(it.getInt("number"), it.getInt("remaining"), it.getInt("totalDrawn")) })
            raw.code == 404 -> DrawOutcome.NotFound
            raw.code == 409 -> DrawOutcome.NotActive
            raw.code == 400 && (errorCode(raw) ?: "").contains("75") -> DrawOutcome.Exhausted
            else -> throw fail(raw)
        }
    }

    override suspend fun player(guestId: String): PlayerLookup {
        val raw = authed { json("GET", "api/admin/bingo/player", query = mapOf("guest" to guestId)) }
        return when (raw.code) {
            200 -> PlayerLookup.Found(decode(raw) { PlayerView.fromJson(it.getJSONObject("player")) })
            404 -> PlayerLookup.NotFound
            400 -> PlayerLookup.Invalid
            else -> throw fail(raw)
        }
    }

    override suspend fun claim(guestId: String, sessionId: String): ClaimOutcome {
        val raw = authed { json("POST", "api/admin/bingo/claim", body = mapOf("guestId" to guestId, "sessionId" to sessionId)) }
        return when (raw.code) {
            200 -> ClaimOutcome.Claimed(decode(raw) { PlayerView.fromJson(it.getJSONObject("player")) })
            404 -> ClaimOutcome.NotFound
            409 -> {
                val code = errorCode(raw)
                val reason = when (code) {
                    "already_claimed" -> ClaimRefusal.AlreadyClaimed
                    "no_bingo" -> ClaimRefusal.NoBingo
                    "stale_session" -> ClaimRefusal.StaleSession
                    else -> ClaimRefusal.Other(code ?: "")
                }
                ClaimOutcome.Refused(reason, errorPlayer(raw))
            }
            else -> throw fail(raw)
        }
    }

    // endregion

    companion object {
        const val COOKIE_NAME = "admin_token"
        private val JSON = "application/json".toMediaType()
    }
}
