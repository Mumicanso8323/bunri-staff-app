package com.yusukedoi.bunrisaistaff

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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

private data class PendingWin(val round: Int, val match: Int, val team: String, val replacing: Boolean)

@Composable
fun TournamentScreen(model: TournamentModel) {
    val ui by model.ui.collectAsStateWithLifecycle()
    var round by remember { mutableStateOf<Int?>(null) }
    var pendingWin by remember { mutableStateOf<PendingWin?>(null) }
    var confirmUndo by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmUnwin by remember { mutableStateOf<MatchView?>(null) }

    PollWhileVisible { model.refresh() }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!ui.connected) Banner("接続待ち…(電波を確かめてください)", StaffColors.Orange)
        ui.message?.let { Banner(it, StaffColors.Red, onDismiss = model::dismissMessage) }
        val state = ui.state
        if (state == null) {
            Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val bracket = state.bracket
            val selected = (round ?: defaultRound(bracket)).coerceIn(0, bracket.rounds - 1)

            Text(state.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())

            bracket.champion?.let { champion ->
                Column(
                    Modifier.fillMaxWidth().background(StaffColors.Gold.copy(alpha = 0.25f), RoundedCornerShape(14.dp)).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("優勝", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(champion, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in 0 until bracket.rounds) {
                    FilterChip(selected = r == selected, onClick = { round = r }, label = { Text(state.roundName(r), maxLines = 1) })
                }
            }

            val game = state.gameName(selected)
            if (game.isNotEmpty()) Text("ゲーム:$game", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            for (mv in bracket.matches[selected]) {
                if (!mv.dead) MatchCard(
                    mv, state, busy = ui.busy,
                    onPick = { team -> pendingWin = PendingWin(mv.round, mv.match, team, replacing = mv.winner != null) },
                    onCurrent = { model.setCurrent(mv.round, mv.match) },
                    onClearCurrent = model::clearCurrent,
                    onUnwin = { confirmUnwin = mv },
                )
            }

            Button(
                onClick = { confirmUndo = true },
                enabled = !ui.busy && state.canUndo,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StaffColors.Orange, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("1手戻す", fontSize = 17.sp, fontWeight = FontWeight.Bold) }

            // リセットは最下部・赤・確認つき
            Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                Text("ここから下は、全部を消す操作です", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { confirmReset = true }, enabled = !ui.busy, border = BorderStroke(1.dp, StaffColors.Red)) {
                    Text("リセット(初期状態に戻す)", color = StaffColors.Red, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    pendingWin?.let { p ->
        ConfirmDialog(
            title = "「${p.team}」の勝ちにしますか?",
            message = if (p.replacing) "この試合の勝者を付け替えます。この試合に絡む、後の勝ち上がりは取り消されます。"
            else "次の回戦に上がります。押し間違えたときは「1手戻す」で戻せます。",
            confirmLabel = "勝ちにする",
            onConfirm = { model.win(p.round, p.match, p.team) },
            onDismiss = { pendingWin = null },
        )
    }
    if (confirmUndo) ConfirmDialog(
        title = "1手戻しますか?", message = "直前の操作を取り消します。", confirmLabel = "1手戻す",
        onConfirm = { model.undo() }, onDismiss = { confirmUndo = false },
    )
    confirmUnwin?.let { mv ->
        ConfirmDialog(
            title = "勝者を取り消しますか?", message = "この試合の勝者と、この試合に絡む後の勝ち上がりを取り消します。",
            confirmLabel = "取り消す", destructive = true,
            onConfirm = { model.unwin(mv.round, mv.match) }, onDismiss = { confirmUnwin = null },
        )
    }
    if (confirmReset) ConfirmDialog(
        title = "すべて初期状態に戻しますか?",
        message = "チーム・勝ち上がり・回戦名・ゲーム名がすべて初期状態に戻ります。勝ち上がりも消えます。",
        confirmLabel = "初期状態に戻す", destructive = true,
        onConfirm = { model.reset() }, onDismiss = { confirmReset = false },
    )
}

private fun defaultRound(bracket: Bracket): Int {
    for (row in bracket.matches) {
        row.firstOrNull { it.ready && it.winner == null }?.let { return it.round }
    }
    return bracket.rounds - 1
}

@Composable
private fun MatchCard(
    mv: MatchView, state: TournamentState, busy: Boolean,
    onPick: (String) -> Unit, onCurrent: () -> Unit, onClearCurrent: () -> Unit, onUnwin: () -> Unit,
) {
    val isCurrent = state.current == mv.id
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .then(if (isCurrent) Modifier.border(BorderStroke(2.dp, StaffColors.Red), RoundedCornerShape(14.dp)) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("第${mv.match + 1}試合", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (isCurrent) {
                Text(
                    "いまの試合", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(StaffColors.Red, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                when {
                    mv.winner != null -> Text(if (mv.auto) "不戦勝" else "決着", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = StaffColors.Green)
                    mv.ready -> Text("勝者を選ぶ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Text("前の試合待ち", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        TeamRow(mv, mv.a, busy, onPick)
        TeamRow(mv, mv.b, busy, onPick)
        if (mv.ready) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mv.winner == null) {
                    OutlinedButton(onClick = onCurrent, enabled = !busy && !isCurrent) {
                        Text(if (isCurrent) "いまの試合です" else "いまの試合にする", fontSize = 14.sp)
                    }
                } else if (!mv.auto) {
                    OutlinedButton(onClick = onUnwin, enabled = !busy) { Text("勝者を取り消す", color = StaffColors.Red, fontSize = 14.sp) }
                }
                if (isCurrent && mv.winner == null) {
                    OutlinedButton(onClick = onClearCurrent, enabled = !busy) { Text("解除", fontSize = 14.sp) }
                }
            }
        }
    }
}

@Composable
private fun TeamRow(mv: MatchView, side: Participant, busy: Boolean, onPick: (String) -> Unit) {
    when (side) {
        is Participant.Team -> {
            val name = side.name
            val won = mv.winner == name
            val lost = mv.winner != null && !won
            if (mv.ready) {
                val container = if (won) StaffColors.Green else if (lost) StaffColors.Gray else MaterialTheme.colorScheme.primary
                Button(
                    onClick = { onPick(name) },
                    enabled = !busy && !won,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = container, contentColor = Color.White,
                        disabledContainerColor = container.copy(alpha = if (won) 1f else 0.5f), disabledContentColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (won) Icon(Icons.Default.CheckCircle, contentDescription = "勝ち")
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (won) Icon(Icons.Default.CheckCircle, contentDescription = "勝ち", tint = StaffColors.Green)
                }
            }
        }
        Participant.Pending -> Text("(前の試合の勝者)", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(top = 10.dp))
        Participant.None -> Text("シード(不戦勝)", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(top = 10.dp))
    }
}
