package com.yusukedoi.bunrisaistaff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private val uuid = "123e4567-e89b-12d3-a456-426614174000"

    @Test fun extractFromUrl() {
        assertEquals(uuid, PlayerCode.extract("https://bunrisai.jp/admin/bingo/verify?player=$uuid"))
        assertEquals(uuid, PlayerCode.extract("  https://x.example/admin/bingo/verify?a=1&player=$uuid&b=2 \n"))
    }

    @Test fun extractBareUuidAndUppercase() {
        assertEquals(uuid, PlayerCode.extract(uuid))
        assertEquals(uuid.uppercase(), PlayerCode.extract(uuid.uppercase()))
    }

    @Test fun extractRejectsNonUuid() {
        assertNull(PlayerCode.extract("https://example.com/"))
        assertNull(PlayerCode.extract("hello"))
        assertNull(PlayerCode.extract(""))
        assertNull(PlayerCode.extract("https://bunrisai.jp/admin/bingo/verify?player=not-a-uuid"))
    }

    @Test fun hexDecode() {
        assertEquals("abc", Credentials.decodeHex("616263"))
        assertEquals("日本", Credentials.decodeHex("e697a5e69cac"))
        assertNull(Credentials.decodeHex(""))
        assertNull(Credentials.decodeHex("6162636"))
        assertNull(Credentials.decodeHex("zz"))
        assertNull(Credentials.decodeHex("\${X}"))
    }

    @Test fun letters() {
        assertEquals("B", BingoLetter.of(1)); assertEquals("B", BingoLetter.of(15))
        assertEquals("I", BingoLetter.of(16)); assertEquals("N", BingoLetter.of(31))
        assertEquals("G", BingoLetter.of(46)); assertEquals("O", BingoLetter.of(61)); assertEquals("O", BingoLetter.of(75))
    }

    @Test fun claimedTimeIsJapanTime() {
        assertEquals("(18:05に受け取り)", claimedTime("2026-10-18T09:05:00.000Z"))
        assertEquals("", claimedTime(null))
        assertEquals("", claimedTime("bad"))
    }

    @Test fun messages() {
        assertTrue(Messages.describe(ApiError.Network).startsWith("通信できませんでした"))
        assertTrue(Messages.describe(ApiError.Server(500, null)).contains("(500)"))
        assertTrue(Messages.describe(ApiError.Login(LoginFailure.Locked)).contains("10分"))
        assertTrue(Messages.describe(ApiError.Login(LoginFailure.Unexpected(200))).contains("(200)"))
    }

    // トーナメントの解決(src/lib/tournament.ts の resolve と同じ結果になること)

    private fun slots14(): List<String?> {
        // 14 チーム・16 枠。シードは各試合の 2 枠目
        val seedSlots = setOf(7, 9) // 試合3と試合4の2枠目
        var t = 0
        return List(16) { if (it in seedSlots) null else "T${t++}" }
    }

    @Test fun seedsAdvanceAutomatically() {
        val b = Bracket.resolve(slots14(), emptyMap())
        assertEquals(4, b.rounds)
        val m3 = b.matches[0][3]
        assertTrue(m3.auto); assertEquals(m3.a.teamName, m3.winner); assertFalse(m3.ready)
        assertTrue(b.matches[0][0].ready); assertNull(b.matches[0][0].winner)
        assertNull(b.champion)
    }

    @Test fun winnersAdvanceAndChampion() {
        val slots = listOf("A", "B", "C", "D", "E", "F", "G", "H")
        val winners = mapOf("0-0" to "A", "0-1" to "D", "0-2" to "E", "0-3" to "H", "1-0" to "D", "1-1" to "E", "2-0" to "E")
        val b = Bracket.resolve(slots, winners)
        assertEquals(3, b.rounds)
        assertEquals("E", b.champion)
        assertEquals(Participant.Team("D"), b.matches[1][0].b)
        // 成り立たない勝者(出場していないチーム)は無視される
        val bad = Bracket.resolve(slots, mapOf("0-0" to "Z"))
        assertNull(bad.matches[0][0].winner)
        assertEquals(Participant.Pending, bad.matches[1][0].a)
    }

    @Test fun roundNamesCountFromFinal() {
        val s = TournamentState("t", List(8) { "T$it" }, listOf("1回戦", "2回戦", "準決勝", "決勝"), listOf("", "", "g3", "g4"), emptyMap(), null, false)
        assertEquals("2回戦", s.roundName(0)); assertEquals("準決勝", s.roundName(1)); assertEquals("決勝", s.roundName(2))
        assertEquals("g3", s.gameName(1))
        val blank = s.copy(roundNames = listOf("", "", "", ""))
        assertEquals("1回戦", blank.roundName(0))
    }
}
