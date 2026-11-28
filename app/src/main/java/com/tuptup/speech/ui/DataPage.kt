package com.tuptup.speech.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuptup.speech.data.Product
import com.tuptup.speech.data.Settings
import com.tuptup.speech.live.LiveCommentClient
import com.tuptup.speech.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DataPage(mod: Modifier, ui: UiState, settings: Settings, products: List<Product>, onTab: (Int) -> Unit) {
    val ctx = LocalContext.current
    val cs = remember { ConnectState(ctx) }
    val client = remember { LiveCommentClient.get(ctx) }
    val comments by client.comments.collectAsState()
    val liveStatus by client.status.collectAsState()
    var connected by remember { mutableStateOf(cs.connected) }
    var toast by remember { mutableStateOf<String?>(null) }
    if (toast != null) LaunchedEffect(toast) { kotlinx.coroutines.delay(1600); toast = null }

    Column(mod.fillMaxSize().verticalScroll(rememberScrollState()).background(Bg)) {
        Spacer(Modifier.height(18.dp))
        Text("数据中心", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp))
        Text("本机实时运行状态，数据仅保存在此设备。", color = Muted, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 4.dp))
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DataTile("评论条数", comments.size.toString(), "条", Accent, Modifier.weight(1f))
            DataTile("已生成话术", ui.lines.size.toString(), "条", Success, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DataTile("商品条目", products.size.toString(), "项", Color(0xFF4A78C4), Modifier.weight(1f))
            DataTile("平台标记", connected.size.toString(), "个", AccentDeep, Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))

        SectionTitle("运行状态", "LIVE STATUS")
        Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp)) {
                StatusRow("评论采集", liveStatus, liveStatus.contains("已"))
                StatusRow("运行状态", ui.status, ui.generating)
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("快捷管理", "MANAGE")
        Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            tonalElevation = 1.dp) {
            Column(Modifier.padding(8.dp)) {
                ManageRow("评论采集", Icons.Default.Comment) { onTab(2) }
                ManageRow("话术生成", Icons.Default.AutoAwesome) { onTab(1) }
                ManageRow("音色设置", Icons.Default.RecordVoiceOver) { onTab(4) }
                ManageRow("系统设置", Icons.Default.Settings) { onTab(4) }
            }
        }

        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        Text("本机时间 $now · 数据不上传", color = Muted, fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(60.dp))
    }
    if (toast != null) {
        Box(Modifier.fillMaxSize().padding(bottom = 80.dp), contentAlignment = Alignment.BottomCenter) {
            Surface(color = TextMain, shape = RoundedCornerShape(20.dp)) {
                Text(toast!!, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(10.dp, 18.dp))
            }
        }
    }
}

@Composable
private fun DataTile(label: String, value: String, unit: String, color: Color, mod: Modifier) {
    Surface(color = Card, shape = RoundedCornerShape(14.dp), modifier = mod, tonalElevation = 1.dp) {
        Column(Modifier.padding(16.dp)) {
            Text(label, color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Text(unit, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, en: String) {
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(en, color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun InfoCard(lines: List<Pair<String, String>>) {
    Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        tonalElevation = 1.dp) {
        Column(Modifier.padding(16.dp)) {
            lines.forEach { (l, v) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(l, color = Muted, fontSize = 11.sp)
                    Text(v, color = TextMain, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(if (ok) Success else Muted))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (ok) TextMain else Muted, fontSize = 12.sp, maxLines = 1,
            modifier = Modifier.weight(1.5f))
    }
}

@Composable
private fun ManageRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = label, tint = Accent, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text("›", color = Muted, fontSize = 18.sp)
    }
}
