package com.yusukedoi.bunrisaistaff

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AdminApiTest {
    private lateinit var server: MockWebServer
    private var password: String? = "pass wörd&=%"

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun api() = AdminApi(server.url("/").toString(), passwordProvider = { password })

    private fun loginOk(token: String = "tok1") = MockResponse().setResponseCode(303).setHeader("Location", "/admin")
        .addHeader("Set-Cookie", "admin_token=$token; Path=/; HttpOnly; Max-Age=86400; SameSite=Lax")

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun next(): RecordedRequest = server.takeRequest()

    private fun errorOf(block: suspend () -> Unit): ApiError {
        try {
            runBlocking { block() }
        } catch (e: ApiException) {
            return e.error
        }
        fail("ApiException が出なかった")
        throw IllegalStateException()
    }

    private val playerJson = """{"guestId":"g","sessionId":"s","sessionStatus":"active","isLatestSession":true,"hasBingo":true,"claimed":false,"claimedAt":null,"joinedAt":"2026-10-18T05:00:00.000Z","card":[1,2,3],"drawsCount":4}"""

    // region ログイン

    @Test fun loginTakesCookieFromRedirectWithoutFollowing() {
        server.enqueue(loginOk("abc123"))
        server.enqueue(json(200, """{"cleaned":0}"""))
        runBlocking { api().apply { ensureLoggedIn() } }
        val login = next()
        assertEquals("POST", login.method)
        assertEquals("/api/admin/login", login.path)
        assertTrue(login.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        // パスワードは URL エンコードされて1つの値になる(& や = や % で壊れない)
        assertEquals("password=pass%20w%C3%B6rd%26%3D%25", login.body.readUtf8())
        assertEquals("リダイレクト先を追わない", 1, server.requestCount)
    }

    @Test fun loginCookieIsSentOnAdminCalls() = runBlocking {
        server.enqueue(loginOk("abc123"))
        server.enqueue(json(200, """{"id":"s","status":"waiting","created_at":"x"}"""))
        api().createSession()
        next()
        val req = next()
        assertEquals("POST", req.method)
        assertEquals("/api/admin/bingo/session", req.path)
        assertEquals("admin_token=abc123", req.getHeader("Cookie"))
    }

    @Test fun loginFailures() {
        password = null
        assertEquals(ApiError.Login(LoginFailure.NoPassword), errorOf { api().ensureLoggedIn() })
        assertEquals("パスワード無しでは通信しない", 0, server.requestCount)
        password = ""
        assertEquals(ApiError.Login(LoginFailure.NoPassword), errorOf { api().ensureLoggedIn() })

        password = "x"
        server.enqueue(MockResponse().setResponseCode(303).setHeader("Location", "/admin?error=1"))
        assertEquals(ApiError.Login(LoginFailure.BadPassword), errorOf { api().ensureLoggedIn() })
        server.enqueue(MockResponse().setResponseCode(303).setHeader("Location", "/admin?error=locked"))
        assertEquals(ApiError.Login(LoginFailure.Locked), errorOf { api().ensureLoggedIn() })
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(ApiError.Login(LoginFailure.Unexpected(200)), errorOf { api().ensureLoggedIn() })
        server.enqueue(MockResponse().setResponseCode(303).setHeader("Location", "/admin"))   // Cookie が無い
        assertEquals(ApiError.Login(LoginFailure.Unexpected(303)), errorOf { api().ensureLoggedIn() })
    }

    @Test fun loginNetworkError() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(ApiError.Network, errorOf { api().ensureLoggedIn() })
    }

    @Test fun loginIsNotRetriedAfterFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(303).setHeader("Location", "/admin?error=1"))
        val a = api()
        try { a.createSession() } catch (_: ApiException) {}
        assertEquals("失敗したログインを自動で繰り返さない", 1, server.requestCount)
    }

    // endregion

    // region 401 のやり直し

    @Test fun retriesOnceAfter401() = runBlocking {
        server.enqueue(loginOk("old"))
        server.enqueue(json(401, """{"error":"unauthorized"}"""))
        server.enqueue(loginOk("new"))
        server.enqueue(json(200, """{"number":9,"remaining":66,"totalDrawn":9}"""))
        val r = api().draw("s")
        assertEquals(DrawOutcome.Drawn(DrawResult(9, 66, 9)), r)
        next()
        assertEquals("admin_token=old", next().getHeader("Cookie"))
        next()
        assertEquals("admin_token=new", next().getHeader("Cookie"))
    }

    @Test fun second401IsUnauthorized() {
        server.enqueue(loginOk("a"))
        server.enqueue(json(401, "{}"))
        server.enqueue(loginOk("b"))
        server.enqueue(json(401, "{}"))
        assertEquals(ApiError.Unauthorized, errorOf { api().draw("s") })
        assertEquals(4, server.requestCount)
    }

    @Test fun networkErrorOnWriteIsNotRetried() {
        server.enqueue(loginOk())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(ApiError.Network, errorOf { api().draw("s") })
        assertEquals("ログイン1回+引く1回だけ", 2, server.requestCount)
    }

    @Test fun reloginUsesTokenAlreadyRefreshedByAnotherCall() = runBlocking {
        server.enqueue(loginOk("t1"))
        server.enqueue(json(200, """{"cleaned":0}"""))
        val a = api()
        a.createSession()
        server.enqueue(json(401, "{}"))
        server.enqueue(loginOk("t2"))
        server.enqueue(json(200, """{"id":"s","status":"active","created_at":"x"}"""))
        a.setStatus("s", SessionStatus.ACTIVE)
        val last = (0 until server.requestCount).map { next() }.last()
        assertEquals("PATCH", last.method)
        assertEquals("admin_token=t2", last.getHeader("Cookie"))
        assertEquals("""{"sessionId":"s","status":"active"}""", last.body.readUtf8())
    }

    // endregion

    // region 状態の取得(ETag)

    private val snapJson = """{"session":{"id":"s1","status":"active","created_at":"c"},"draws":[5,70],"playerCount":3,"bingoCount":1,"updatedAt":1789000000000.5}"""

    @Test fun snapshotEtagAnd304() = runBlocking {
        server.enqueue(json(200, snapJson).setHeader("ETag", "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"v1\""))
        val a = api()
        val first = a.snapshot(null) as SnapshotResult.Changed
        assertEquals(listOf(5, 70), first.snapshot.draws)
        assertEquals(SessionStatus.ACTIVE, first.snapshot.session!!.status)
        assertEquals("\"v1\"", first.etag)
        assertEquals(SnapshotResult.NotModified, a.snapshot(first.etag))
        assertNull(next().getHeader("If-None-Match"))
        assertEquals("\"v1\"", next().getHeader("If-None-Match"))
        assertEquals("公開 API はログインしない", 2, server.requestCount)
    }

    @Test fun snapshotWithNoSessionAndErrors() {
        server.enqueue(json(200, """{"session":null,"draws":[],"playerCount":0,"bingoCount":0,"updatedAt":1}"""))
        assertNull(((runBlocking { api().snapshot(null) }) as SnapshotResult.Changed).snapshot.session)
        server.enqueue(json(200, "not json"))
        assertEquals(ApiError.Decoding, errorOf { api().snapshot(null) })
        server.enqueue(json(503, "{}"))
        assertEquals(ApiError.Server(503, null), errorOf { api().snapshot(null) })
    }

    // endregion

    // region 抽選

    @Test fun drawOutcomes() = runBlocking {
        server.enqueue(loginOk())
        val a = api()
        server.enqueue(json(404, """{"error":"not_found"}"""))
        assertEquals(DrawOutcome.NotFound, a.draw("s"))
        server.enqueue(json(409, """{"error":"Session is not active"}"""))
        assertEquals(DrawOutcome.NotActive, a.draw("s"))
        server.enqueue(json(400, """{"error":"All 75 numbers have been drawn","remaining":0}"""))
        assertEquals(DrawOutcome.Exhausted, a.draw("s"))
        server.enqueue(json(400, """{"error":"sessionId is required"}"""))
        try { a.draw("s"); fail() } catch (e: ApiException) { assertEquals(ApiError.Server(400, "sessionId is required"), e.error) }
        server.enqueue(json(500, "oops"))
        try { a.draw("s"); fail() } catch (e: ApiException) { assertEquals(ApiError.Server(500, null), e.error) }
    }

    // endregion

    // region QR 確認

    @Test fun playerLookup() = runBlocking {
        server.enqueue(loginOk())
        val a = api()
        server.enqueue(json(200, """{"player":$playerJson}"""))
        val found = a.player("123e4567-e89b-12d3-a456-426614174000") as PlayerLookup.Found
        assertEquals(true, found.player.hasBingo)
        assertNull(found.player.claimedAt)
        assertEquals(listOf(1, 2, 3), found.player.card)
        next()
        assertEquals("/api/admin/bingo/player?guest=123e4567-e89b-12d3-a456-426614174000", next().path)
        server.enqueue(json(404, """{"error":"not_found"}"""))
        assertEquals(PlayerLookup.NotFound, a.player("x"))
        server.enqueue(json(400, """{"error":"invalid_guest"}"""))
        assertEquals(PlayerLookup.Invalid, a.player("x"))
    }

    @Test fun claimOutcomes() = runBlocking {
        server.enqueue(loginOk())
        val a = api()
        server.enqueue(json(200, """{"player":${playerJson.replace("\"claimed\":false", "\"claimed\":true")}}"""))
        val ok = a.claim("g", "s") as ClaimOutcome.Claimed
        assertEquals(true, ok.player.claimed)
        next()
        val sent = next()
        assertEquals("/api/admin/bingo/claim", sent.path)
        val sentBody = JSONObject(sent.body.readUtf8())
        assertEquals("g", sentBody.getString("guestId")); assertEquals("s", sentBody.getString("sessionId"))
        server.enqueue(json(404, """{"error":"not_found"}"""))
        assertEquals(ClaimOutcome.NotFound, a.claim("g", "s"))
        for ((code, reason) in listOf(
            "already_claimed" to ClaimRefusal.AlreadyClaimed,
            "no_bingo" to ClaimRefusal.NoBingo,
            "stale_session" to ClaimRefusal.StaleSession,
            "weird" to ClaimRefusal.Other("weird"),
        )) {
            server.enqueue(json(409, """{"error":"$code","player":$playerJson}"""))
            val r = a.claim("g", "s") as ClaimOutcome.Refused
            assertEquals(reason, r.reason)
            assertEquals("g", r.player!!.guestId)
        }
        server.enqueue(json(409, """{"error":"already_claimed"}"""))
        assertNull((a.claim("g", "s") as ClaimOutcome.Refused).player)
        server.enqueue(json(500, "x"))
        try { a.claim("g", "s"); fail() } catch (e: ApiException) { assertEquals(ApiError.Server(500, null), e.error) }
    }

    // endregion

    // region トーナメント

    private val tourJson = """{"state":{"title":"サークル対抗戦","slots":["A","B",null,"D","E","F","G","H"],"roundNames":["","","準決勝","決勝"],"gameNames":["","","","g"],"winners":{"0-0":"A"},"current":"0-1","canUndo":true},"version":7,"updatedAt":"2026-10-18T05:00:00.000Z"}"""

    @Test fun tournamentStateParsingAndEtag() = runBlocking {
        server.enqueue(json(200, tourJson).setHeader("ETag", "W/\"7\""))
        server.enqueue(MockResponse().setResponseCode(304))
        val a = api()
        val r = a.tournament(null) as TournamentSnapshotResult.Changed
        assertEquals(7, r.snapshot.version)
        assertEquals("W/\"7\"", r.etag)
        assertEquals(listOf("A", "B", null, "D", "E", "F", "G", "H"), r.snapshot.state.slots)
        assertEquals(mapOf("0-0" to "A"), r.snapshot.state.winners)
        assertEquals("0-1", r.snapshot.state.current)
        assertEquals(true, r.snapshot.state.canUndo)
        assertEquals(TournamentSnapshotResult.NotModified, a.tournament(r.etag))
        assertEquals("/api/tournament/state", next().path)
        assertEquals("W/\"7\"", next().getHeader("If-None-Match"))
    }

    @Test fun tournamentOpSendsJsonWithCookieAndReadsReason() = runBlocking {
        server.enqueue(loginOk("tt"))
        server.enqueue(json(200, tourJson))
        val a = api()
        val snap = a.tournamentOp(mapOf("action" to "win", "round" to 0, "match" to 1, "team" to "B"))
        assertEquals(7, snap.version)
        next()
        val req = next()
        assertEquals("POST", req.method)
        assertEquals("/api/admin/tournament", req.path)
        assertEquals("admin_token=tt", req.getHeader("Cookie"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("win", body.getString("action")); assertEquals(0, body.getInt("round")); assertEquals(1, body.getInt("match")); assertEquals("B", body.getString("team"))
        server.enqueue(json(400, """{"error":"この試合はまだ対戦相手が決まっていません"}"""))
        try { a.tournamentOp(mapOf("action" to "win")); fail() } catch (e: ApiException) {
            assertEquals(ApiError.Server(400, "この試合はまだ対戦相手が決まっていません"), e.error)
        }
    }

    // endregion
}
