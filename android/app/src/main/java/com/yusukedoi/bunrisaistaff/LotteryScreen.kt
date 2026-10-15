package com.yusukedoi.bunrisaistaff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LotteryScreen(model: LotteryModel) {
    val ui by model.ui.collectAsStateWithLifecycle()
    var confirmFinish by remember { mutableStateOf(false) }
    var confirmRebuild by remember { mutableStateOf(false) }
    var confirmReopen by remember { mutableStateOf(false) }

    PollWhileVisible { model.refresh() }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (!ui.connected) Banner("接続待ち…(電波を確かめてください)", StaffColors.Orange)
        ui.message?.let { Banner(it, StaffColors.Red, onDismiss = model::dismissMessage) }
        if (ui.snapshot == null) {
            Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            StatusCard(ui)
            LatestNumber(ui)
            MainControls(ui, model, onReopen = { confirmReopen = true })
            NumberGrid(ui.draws)
            DangerZone(ui, onFinish = { confirmFinish = true }, onRebuild = { confirmRebuild = true })
        }
    }

    if (confirmFinish) ConfirmDialog(
        title = "ビンゴを終了しますか?",
        message = "番号を引けなくなります。間違えたときは「終了を取り消す」で戻せます。",
        confirmLabel = "終了する", destructive = true,
        onConfirm = { ui.session?.let(model::finish) },
        onDismiss = { confirmFinish = false },
    )
    if (confirmRebuild) ConfirmDialog(
        title = "セッションを作り直しますか?",
        message = "いまのセッションは終わり、新しいセッションになります。",
        confirmLabel = "作り直す", destructive = true,
        onConfirm = { model.createSession() },
        onDismiss = { confirmRebuild = false },
    )
    if (confirmReopen) ConfirmDialog(
        title = "終了を取り消しますか?",
        message = "同じセッションを進行中に戻します。引いた番号と参加者はそのままです。",
        confirmLabel = "進行中に戻す",
        onConfirm = { ui.session?.let(model::reopen) },
        onDismiss = { confirmReopen = false },
    )
}

private fun statusColor(s: SessionStatus): Color = when (s) {
    SessionStatus.WAITING -> StaffColors.Orange
    SessionStatus.ACTIVE -> StaffColors.Green
    SessionStatus.FINISHED -> StaffColors.Gray
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(14.dp)).padding(16.dp),
    ) { content() }
}

@Composable
private fun StatusCard(ui: LotteryUi) {
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ステータス", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val s = ui.session
                if (s != null) {
                    val c = statusColor(s.status)
                    Text(
                        s.status.label, color = c, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.background(c.copy(alpha = 0.2f), CircleShape).padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text("ID: ${s.id.take(8)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("セッションがありません", fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Stat("参加者数", ui.snapshot?.playerCount ?: 0, Modifier.weight(1f))
                Stat("ビンゴ達成者", ui.snapshot?.bingoCount ?: 0, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(title: String, value: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", fontSize = 30.sp, fontWeight = FontWeight.Black)
        Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LatestNumber(ui: LotteryUi) {
    Panel {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val n = ui.latestNumber
            if (n != null) {
                Text(BingoLetter.of(n), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$n", fontSize = 88.sp, fontWeight = FontWeight.Black, lineHeight = 96.sp)
                Text("引いた数 ${ui.draws.size}/75  残り ${ui.remaining}個", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("?", fontSize = 72.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
                Text("まだ番号を引いていません", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 状態に合うボタンだけを出す。 */
@Composable
private fun MainControls(ui: LotteryUi, model: LotteryModel, onReopen: () -> Unit) {
    val session = ui.session
    when (session?.status) {
        null -> BigButton("新しいセッション", StaffColors.Blue, enabled = !ui.busy) { model.createSession() }
        SessionStatus.WAITING -> BigButton("開始", StaffColors.Green, enabled = !ui.busy) { model.start(session) }
        SessionStatus.ACTIVE -> BigButton(
            title = if (ui.drawCooling) "少し待ってください…" else if (ui.remaining == 0) "全て引きました" else "次の番号を引く",
            color = StaffColors.Blue, enabled = ui.canDraw, minHeight = 96,
        ) { model.draw() }
        SessionStatus.FINISHED -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BigButton("新しいセッション", StaffColors.Blue, enabled = !ui.busy) { model.createSession() }
            TextButton(onClick = onReopen, enabled = !ui.busy) { Text("終了を取り消す(同じセッションを再開)", fontSize = 15.sp) }
        }
    }
}

/** 取り違えないよう、開始・引くボタンから離し、最下部に置く。 */
@Composable
private fun DangerZone(ui: LotteryUi, onFinish: () -> Unit, onRebuild: () -> Unit) {
    when (ui.session?.status) {
        SessionStatus.ACTIVE -> Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalDivider()
            Text("ここから下は、ビンゴを終わらせる操作です", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onFinish, enabled = !ui.busy) { Text("終了", color = StaffColors.Red, fontWeight = FontWeight.Bold) }
        }
        SessionStatus.WAITING -> Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalDivider()
            OutlinedButton(onClick = onRebuild, enabled = !ui.busy) { Text("作り直す", color = StaffColors.Red) }
        }
        else -> {}
    }
}

/** 1〜75 を B I N G O の5行で並べ、引いた番号に色をつける。 */
@Composable
fun NumberGrid(draws: List<Int>) {
    val drawn = draws.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("引いた番号(${draws.size}/75)", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in 0 until 5) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("BINGO"[row].toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp))
                    for (i in 1..15) {
                        val n = row * 15 + i
                        val on = n in drawn
                        Box(
                            Modifier.weight(1f).height(26.dp).background(
                                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                                RoundedCornerShape(4.dp),
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$n", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
