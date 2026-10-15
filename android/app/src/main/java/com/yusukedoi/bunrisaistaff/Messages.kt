package com.yusukedoi.bunrisaistaff

/** 失敗を、現場の人が次にすることが分かる文にする。 */
object Messages {
    fun describe(error: ApiError): String = when (error) {
        ApiError.Network -> "通信できませんでした。電波を確かめてください。"
        is ApiError.Login -> when (val f = error.failure) {
            LoginFailure.NoPassword -> "このアプリにはログイン情報が入っていません。リードに知らせてください。"
            LoginFailure.BadPassword -> "ログインできませんでした(パスワードが合いません)。リードに知らせてください。"
            LoginFailure.Locked -> "ログインが一時的に止められています。10分ほど待ってから、もう一度ためしてください。"
            is LoginFailure.Unexpected -> "ログインできませんでした(${f.status})。もう一度ためしてください。"
        }
        ApiError.Unauthorized -> "ログインが切れました。もう一度押してください。"
        is ApiError.Server -> "サーバーがエラーを返しました(${error.status})。もう一度ためしてください。"
        ApiError.Decoding -> "サーバーの返事を読めませんでした。もう一度ためしてください。"
    }
}

object PlayerCode {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /**
     * QR の中身(`https://…/admin/bingo/verify?player=<UUID>` または UUID そのもの)から来場者 ID を取り出す。
     * UUID の形でなければ null(API を呼ばずに「無効な QR」にする)。
     */
    fun extract(input: String): String? {
        val text = input.trim()
        val query = text.substringAfter('?', "").substringBefore('#')
        for (pair in query.split('&')) {
            if (pair.substringBefore('=') == "player") {
                uuid.find(pair.substringAfter('=', ""))?.let { return it.value }
            }
        }
        return uuid.find(text)?.value
    }
}

/** 管理者パスワードの読み出し。値はビルド時の環境変数 → BuildConfig(16 進)で入る。ソース・ログ・画面には出さない。 */
object Credentials {
    fun adminPassword(): String? = decodeHex(BuildConfig.ADMIN_PASSWORD_HEX)

    /** 16 進の文字列を UTF-8 の文字列に戻す。空・奇数桁・16 進でない文字は null。 */
    fun decodeHex(hex: String): String? {
        val s = hex.trim()
        if (s.isEmpty() || s.length % 2 != 0) return null
        val bytes = ByteArray(s.length / 2)
        for (i in bytes.indices) {
            val hi = Character.digit(s[2 * i], 16)
            val lo = Character.digit(s[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            bytes[i] = ((hi shl 4) or lo).toByte()
        }
        val text = String(bytes, Charsets.UTF_8)
        return text.ifEmpty { null }
    }
}
