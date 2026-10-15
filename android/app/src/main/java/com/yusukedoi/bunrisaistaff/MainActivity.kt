package com.yusukedoi.bunrisaistaff

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as StaffApp
        setContent {
            StaffTheme { RootView(app) }
        }
    }
}

private enum class StaffTab(val label: String, val icon: ImageVector) {
    LOTTERY("抽選", Icons.Default.Refresh),
    TOURNAMENT("トーナメント", Icons.Default.Star),
    VERIFY("QR確認", Icons.Default.Search),
    GUIDE("使い方", Icons.Default.Info),
}

@Composable
private fun RootView(app: StaffApp) {
    var tab by rememberSaveable { mutableStateOf(StaffTab.LOTTERY) }
    val loginProblem by app.loginProblem.collectAsStateWithLifecycle()

    // 起動時の自動ログイン
    LaunchedEffect(Unit) { app.login() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        loginProblem?.let { text ->
            Row(
                Modifier.fillMaxWidth().background(StaffColors.Red).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Button(
                    onClick = { app.login(force = true) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = StaffColors.Red),
                ) { Text("やり直す") }
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NavigationBar {
                    for (t in StaffTab.entries) {
                        NavigationBarItem(
                            selected = tab == t, onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label, fontSize = 11.sp, maxLines = 1) },
                        )
                    }
                }
            },
        ) { inner ->
            Box(Modifier.padding(inner).fillMaxSize()) {
                when (tab) {
                    StaffTab.LOTTERY -> LotteryScreen(app.lottery)
                    StaffTab.TOURNAMENT -> TournamentScreen(app.tournament)
                    StaffTab.VERIFY -> VerifyScreen(app.verify)
                    StaffTab.GUIDE -> GuideScreen()
                }
            }
        }
    }
}
