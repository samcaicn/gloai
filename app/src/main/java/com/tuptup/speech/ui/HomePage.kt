package com.tuptup.speech.ui

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuptup.speech.live.LiveCommentClient
import com.tuptup.speech.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Locale

/** 首页平台接入标记（仅本机记录，不代表真实登录态） */
class ConnectState(ctx: Context) {
    private val sp = ctx.getSharedPreferences("connect", Context.MODE_PRIVATE)
    var connected: Set<String>
        get() = sp.getStringSet("connected", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("connected", v).apply()
}

@Composable
fun HomePage(vm: MainViewModel, mod: Modifier, onTab: (Int) -> Unit) {
    val ctx = LocalContext.current
    val client = remember { LiveCommentClient.get(ctx) }
    val comments by client.comments.collectAsState()
    val liveStatus by client.status.collectAsState()
    val engine by vm.tts.engineState.collectAsState()
    val ui by vm.ui.collectAsState()

    val hour = SimpleDateFormat("HH", Locale.getDefault()).format(java.util.Date()).toIntOrNull() ?: 12
    val greet = when (hour) { in 0..5 -> "夜深了，主播"; in 6..10 -> "早上好，主播"; in 11..13 -> "中午好，主播"; in 14..18 -> "下午好，主播"; else -> "晚上好，主播" }

    Column(mod.fillMaxSize().verticalScroll(rememberScrollState()).background(Bg)) {
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greet, color = Muted, fontSize = 12.sp)
                Text("Tuptup 直播中控", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Surface(color = if (ui.jevReady) AccentSoft else Card2, shape = RoundedCornerShape(20.dp)) {
                Text(
                    if (ui.jevReady) "已就绪" else "准备中",
                    color = if (ui.jevReady) AccentDeep else Muted,
                    fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        // 两个核心任务
        HeroCard(
            icon = Icons.Default.Comment, title = "直播评论采集",
            sub = if (comments.isEmpty()) "打开已登录直播间，自动抓取评论" else "已采集 ${comments.size} 条 · $liveStatus",
            action = "进入采集", tint = Accent, mod = Modifier.padding(horizontal = 16.dp)
        ) { onTab(2) }
        Spacer(Modifier.height(12.dp))
        HeroCard(
            icon = Icons.Default.RecordVoiceOver, title = "我的声音",
            sub = "录 3 秒，用你的声音播讲评论",
            action = "去设置", tint = Success, mod = Modifier.padding(horizontal = 16.dp)
        ) { onTab(4) }

        Spacer(Modifier.height(22.dp))
        HomeSectionTitle("使用步骤", "STEPS")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StepCard("1", "打开直播间", "登录后进入直播间", Modifier.weight(1f))
            StepCard("2", "采集评论", "自动接收新评论", Modifier.weight(1f))
            StepCard("3", "声音播讲", "评论用你的声音读出", Modifier.weight(1f))
        }

        Spacer(Modifier.height(22.dp))
        HomeSectionTitle("运行状态", "STATUS")
        HomeCardBox {
            StatusLine("评论采集", liveStatus, liveStatus.contains("已"))
            StatusLine("评论条数", "${comments.size} 条", comments.isNotEmpty())
            StatusLine("当前音色", engine.take(28), engine != "未初始化")
        }

        Spacer(Modifier.height(18.dp))
        HomeSectionTitle("快捷入口", "SHORTCUTS")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Shortcut("直播讲解", Icons.Default.AutoAwesome, Modifier.weight(1f)) { onTab(1) }
            Shortcut("数据中心", Icons.Default.InsertChart, Modifier.weight(1f)) { onTab(3) }
            Shortcut("设置", Icons.Default.Settings, Modifier.weight(1f)) { onTab(4) }
        }
        Text(
            "所有数据仅保存在本机 · 克隆他人声音须本人授权",
            color = Muted, fontSize = 10.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun HeroCard(
    icon: ImageVector, title: String, sub: String, action: String, tint: Color,
    mod: Modifier, onClick: () -> Unit
) {
    Surface(
        color = Card, shape = RoundedCornerShape(18.dp),
        modifier = mod.fillMaxWidth().clickable(onClick = onClick),
        tonalElevation = 1.dp
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = title, tint = tint, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(sub, color = Muted, fontSize = 11.sp, maxLines = 2)
            }
            Surface(color = tint, shape = RoundedCornerShape(18.dp)) {
                Text(action, color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
    }
}

@Composable
private fun StepCard(no: String, title: String, sub: String, mod: Modifier) {
    Surface(color = Card2, shape = RoundedCornerShape(14.dp), modifier = mod) {
        Column(Modifier.padding(12.dp)) {
            Text(no, color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(title, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = Muted, fontSize = 9.sp, maxLines = 2)
        }
    }
}

@Composable
private fun Shortcut(label: String, icon: ImageVector, mod: Modifier, onClick: () -> Unit) {
    Surface(color = Card, shape = RoundedCornerShape(14.dp), modifier = mod.clickable(onClick = onClick),
        tonalElevation = 1.dp) {
        Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, color = TextMain, fontSize = 11.sp)
        }
    }
}

@Composable
private fun HomeSectionTitle(title: String, en: String) {
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(en, color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun HomeCardBox(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        tonalElevation = 1.dp) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(if (ok) Success else Muted))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (ok) TextMain else Muted, fontSize = 12.sp, maxLines = 1,
            modifier = Modifier.weight(1.5f))
    }
}
