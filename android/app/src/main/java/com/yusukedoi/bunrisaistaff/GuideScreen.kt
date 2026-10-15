package com.yusukedoi.bunrisaistaff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 使い方。画面の中だけで完結する(ネットにつながっていなくても読める)。
 * ボタン名は LotteryScreen・VerifyScreen・TournamentScreen と同じ言葉にそろえてある。
 */
@Composable
fun GuideScreen() {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("このタブは、ネットにつながっていなくても読めます。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        GuideCard("はじめに") {
            GuideText("アプリを開くと、自動でログインします。パスワードを入れる必要はありません。")
            GuideText("画面の上に赤い帯が出たら、ログインできていません。「やり直す」を押してください。")
        }

        GuideCard("1. 抽選の流れ(「抽選」タブ)") {
            GuideSteps(
                "「新しいセッション」を押します。状態が「待機中」になります。",
                "「開始」を押します。状態が「進行中」になります。来場者の画面にカードが出ます。",
                "「次の番号を引く」を押します。番号が大きく出ます。ホールで読み上げます。",
                "手順3を、ビンゴが終わるまで繰り返します。押したあと、3秒ほどは押せません。",
                "おわりは、画面の一番下の「終了」を押します。確認が出るので「終了する」を押します。",
            )
            Warning(
                "「開始」と「終了」を間違えないで",
                "会場のテストで、「開始」のつもりで「終了」を押しました。作ったばかりのセッションが、5秒で終わりました。",
                "押す前に、ボタンの名前を読んでください。",
                "このアプリでは、「終了」は一番下にあります。色は赤です。押すと確認が出ます。",
            )
            GuideText("間違えて終了したとき", bold = true)
            GuideSteps(
                "あわてなくて大丈夫です。番号と参加者は消えていません。",
                "「抽選」タブで「終了を取り消す(同じセッションを再開)」を押します。確認で「進行中に戻す」を押します。",
                "同じセッションが「進行中」に戻ります。続きから引けます。",
            )
            GuideText("「新しいセッション」を押すのは、おすすめしません。前のセッションの参加者は「過去のセッション」になって、景品の対象外になります。")
        }

        GuideCard("2. 景品の確認(「QR確認」タブ)") {
            GuideSteps(
                "ビンゴした人に、画面のQRを出してもらいます。",
                "「QR確認」タブを開き、カメラの枠にQRを入れます。",
                "「ビンゴ達成」と出たら、景品をお渡しします。",
                "「景品受取済みにする」を押します。「受け取り済みにしました」と出れば記録できています。",
                "「次を読む」を押すと、次の人を読めます。",
            )
            GuideText("出る表示", bold = true)
            GuideBullets(
                "「すでに受け取り済み」:もう景品を渡した人です。同じQRをもう一度読むと、この表示になります。渡さないでください。",
                "「まだビンゴではありません」:ビンゴになっていません。当たったマスを本人がタップして開けているか、確かめてもらいます。",
                "「過去のセッションです」:前のセッションの人です。景品の対象外です。",
                "「無効な QR」:ビンゴのQRではありません。",
                "「見つかりません」:その人の記録がありません。",
            )
            GuideText("読めないとき", bold = true)
            GuideBullets(
                "QRを枠の真ん中に入れて、手前や奥に動かして、ピントを合わせます。",
                "相手の画面を明るくしてもらいます。画面に光が映りこんでいたら、少し傾けます。",
                "カメラが動かないときは、端末の「設定」でこのアプリのカメラを許可します(画面の「設定を開く」からも行けます)。",
                "それでも読めないときは、画面の下の「読めないときは、IDを入力」にIDを入れて「検索」を押します。",
            )
        }

        GuideCard("トーナメント(「トーナメント」タブ)") {
            GuideSteps(
                "上の回戦の名前(「1回戦」など)を押すと、その回戦の試合が並びます。",
                "試合が終わったら、勝ったチームの大きいボタンを押します。",
                "確認が出るので、チーム名を読んで「勝ちにする」を押します。自動で次の回戦に上がり、ホールの画面にも1〜2秒で出ます。",
            )
            GuideText("押し間違えたとき", bold = true)
            GuideBullets(
                "「1手戻す」を押すと、直前の操作が取り消されます。",
                "別のチームに直すときは、正しいほうのチームのボタンを押し直します。この試合に絡む後の勝ち上がりは、取り消されます。",
                "「勝者を取り消す」で、その試合を未決着に戻せます。",
            )
            GuideText("「いまの試合にする」を押すと、ホールの画面で両チームの枠が光ります。勝者を入れると、次の試合に移ります。")
            GuideText("「シード(不戦勝)」の枠は、相手が自動で勝ち上がります。ボタンはありません。")
            GuideText("チームの入れ替え・枠・回戦名は、このアプリでは変えられません。必要なときはリードに知らせてください。")
            Warning("一番下の「リセット」は押さないで", "チーム・勝ち上がりが、すべて初期状態に戻ります。本番中は押しません。")
        }

        GuideCard("3. ステージの投影") {
            GuideSteps(
                "ホールのパソコンで、次のページを開きます。",
                "キーボードの F11 を押して、全画面にします。",
            )
            SelectionContainer { Text("https://bunrisai.jp/bingo/stage", fontFamily = FontFamily.Monospace, fontSize = 15.sp) }
            GuideText("引いた番号は、自動で投影の画面にも出ます。")
        }

        GuideCard("4. 来場者のしくみ") {
            GuideText("来場者は、読み上げられた番号のマスを、自分でタップして開けます。")
            GuideText("タップしないと、そろっていてもビンゴになりません。")
            Warning(
                "「ビンゴなのに出ない」と言われたら",
                "当たったマスを、タップして開けたか聞いてください。",
                "開けていなければ、タップしてもらいます。一列そろうと、QRが出ます。",
            )
        }

        GuideCard("5. 困ったとき") {
            GuideBullets(
                "「接続待ち…」と出たとき:電波が弱いです。電波のよい場所へ行くか、少し待ちます。つながると消えます。",
                "「通信できませんでした」と出たとき:もう一度押す前に、番号の一覧を見てください。引けていることがあります。同じ番号は引かれません。",
                "景品の記録で通信できなかったとき:もう一度QRを読みます。「すでに受け取り済み」と出れば、記録できています。",
                "「ログインが切れました」と出たとき:自動でログインし直します。もう一度ボタンを押してください。",
                "赤い帯が消えないとき:アプリを一度閉じて、開き直します。それでもだめなら、リードに知らせてください。",
                "ログインを何度もやり直さないでください。続けて失敗すると、10分ほど止められます。",
            )
        }
    }
}

@Composable
private fun GuideCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun GuideText(text: String, bold: Boolean = false) {
    Text(text, fontSize = 16.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
}

@Composable
private fun GuideSteps(vararg items: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Text(item, fontSize = 16.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GuideBullets(vararg items: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (item in items) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("・", fontSize = 16.sp)
                Text(item, fontSize = 16.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Warning(title: String, vararg lines: String) {
    Column(
        Modifier.fillMaxWidth()
            .background(StaffColors.Red.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .border(BorderStroke(1.5.dp, StaffColors.Red.copy(alpha = 0.5f)), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("⚠ $title", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = StaffColors.Red)
        for (line in lines) Text(line, fontSize = 16.sp)
    }
}
