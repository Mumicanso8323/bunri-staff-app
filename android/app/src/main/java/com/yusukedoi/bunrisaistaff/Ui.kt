package com.yusukedoi.bunrisaistaff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

object StaffColors {
    val Blue = Color(0xFF1E4FA8)
    val Green = Color(0xFF2E7D32)
    val Orange = Color(0xFFEF6C00)
    val Red = Color(0xFFC62828)
    val Gray = Color(0xFF757575)
    val Gold = Color(0xFFF9A825)
}

@Composable
fun StaffTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(primary = Color(0xFF8AB4F8)) else lightColorScheme(primary = StaffColors.Blue)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 画面が前面(STARTED 以上)にあるあいだだけ、一定間隔で block を呼ぶ。 */
@Composable
fun PollWhileVisible(intervalMs: Long = 2500, block: suspend () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                block()
                delay(intervalMs)
            }
        }
    }
}

@Composable
fun Banner(text: String, color: Color, onDismiss: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(color, RoundedCornerShape(10.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(text, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (onDismiss != null) {
            IconButton(onClick = onDismiss, modifier = Modifier.padding(0.dp)) {
                Icon(Icons.Default.Close, contentDescription = "閉じる", tint = Color.White)
            }
        }
    }
}

@Composable
fun BigButton(title: String, color: Color, enabled: Boolean = true, minHeight: Int = 64, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight.dp),
    ) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

/** 確認ダイアログ。確認ボタンの名前は押す操作そのもの(「終了する」など)にする。 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirmLabel, color = if (destructive) StaffColors.Red else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}
