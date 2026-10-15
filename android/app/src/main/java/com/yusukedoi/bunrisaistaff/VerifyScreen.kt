package com.yusukedoi.bunrisaistaff

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun VerifyScreen(model: VerifyModel) {
    val phase by model.phase.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    // 設定から戻ったときに許可を見直す
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasCameraPermission(context) }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val p = phase) {
            VerifyPhase.Scanning -> Scanner(model, granted, asked, context, onAsk = { launcher.launch(Manifest.permission.CAMERA) })
            VerifyPhase.Working -> Column(
                Modifier.fillMaxWidth().heightIn(min = 240.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Text("確かめています…", modifier = Modifier.padding(top = 12.dp))
            }
            is VerifyPhase.Result -> ResultPanel(p.result, model)
        }
    }
}

private fun hasCameraPermission(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
private fun Scanner(model: VerifyModel, granted: Boolean, asked: Boolean, context: android.content.Context, onAsk: () -> Unit) {
    Text(
        "ビンゴした人のスマートフォンの QR を、枠に入れてください",
        fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
    )
    val hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    when {
        granted && hasCamera -> QrScanner(
            onCode = { model.handle(it) },
            modifier = Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(16.dp))
                .border(BorderStroke(2.dp, Color.White.copy(alpha = 0.6f)), RoundedCornerShape(16.dp)),
        )
        granted -> Notice("この端末にはカメラがありません。下の「IDを入力」を使ってください。")
        !asked -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Notice("カメラが許可されていません。設定でカメラを許可してください。")
            Button(onClick = {
                // 「許可しない」を重ねると、もうダイアログは出ない。設定の画面へ送る。
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("設定を開く") }
            OutlinedButton(onClick = onAsk) { Text("もう一度許可を求める") }
        }
    }
    var manualId by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("読めないときは、IDを入力", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = manualId, onValueChange = { manualId = it },
                placeholder = { Text("来場者ID(UUID)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val id = manualId
                    manualId = ""
                    model.handle(id)
                },
                enabled = manualId.isNotBlank(),
            ) { Text("検索") }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(12.dp)).padding(16.dp),
    )
}

private data class Look(val icon: ImageVector, val color: Color, val title: String, val detail: String)

private fun look(result: VerifyResult): Look = when (result) {
    is VerifyResult.ReadyToClaim -> Look(Icons.Default.Star, StaffColors.Green, "ビンゴ達成",
        "景品をお渡しして、下のボタンを押してください。押すまでは記録されません。")
    is VerifyResult.ClaimedNow -> Look(Icons.Default.CheckCircle, StaffColors.Green, "受け取り済みにしました", "景品受取を記録しました。")
    is VerifyResult.AlreadyClaimed -> Look(Icons.Default.Warning, StaffColors.Orange, "すでに受け取り済み",
        "このプレイヤーは既に景品を受け取っています。" + claimedTime(result.player.claimedAt))
    is VerifyResult.NotBingo -> Look(Icons.Default.Close, StaffColors.Orange, "まだビンゴではありません",
        "このプレイヤーはまだビンゴを達成していません。当たったマスをタップして開けているか、本人に確かめてください。")
    VerifyResult.Stale -> Look(Icons.Default.Refresh, StaffColors.Orange, "過去のセッションです", "過去のセッションの参加者です(景品対象外)。")
    VerifyResult.NotFound -> Look(Icons.Default.Search, StaffColors.Red, "見つかりません", "プレイヤーが見つかりません。")
    VerifyResult.InvalidQr -> Look(Icons.Default.Info, StaffColors.Red, "無効な QR",
        "ビンゴの QR ではありません。来場者のビンゴ画面に出る QR を読んでください。")
    is VerifyResult.Failed -> Look(Icons.Default.Warning, StaffColors.Red, "確認できませんでした", result.text)
}

internal fun claimedTime(iso: String?, zone: ZoneId = ZoneId.of("Asia/Tokyo")): String {
    if (iso == null) return ""
    return try {
        val time = Instant.parse(iso).atZone(zone)
        "(${DateTimeFormatter.ofPattern("H:mm").format(time)}に受け取り)"
    } catch (_: Exception) {
        ""
    }
}

@Composable
private fun ResultPanel(result: VerifyResult, model: VerifyModel) {
    val look = look(result)
    Column(
        Modifier.fillMaxWidth().background(look.color.copy(alpha = 0.12f), RoundedCornerShape(18.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(look.icon, contentDescription = null, tint = look.color, modifier = Modifier.size(72.dp))
        Text(look.title, fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Text(look.detail, fontSize = 16.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (result is VerifyResult.ReadyToClaim) {
            Button(
                onClick = { model.claim(result.player) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StaffColors.Green, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) { Text("景品受取済みにする", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        }
        OutlinedButton(onClick = { model.next() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(12.dp)) {
            Text("次を読む", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}
