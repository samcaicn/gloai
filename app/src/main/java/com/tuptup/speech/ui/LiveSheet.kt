package com.tuptup.speech.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tuptup.speech.data.Platforms
import com.tuptup.speech.data.Settings
import com.tuptup.speech.live.LiveCommentClient
import com.tuptup.speech.ui.theme.*

/**
 * 开播浮层：登录后打开，自动加载已登录平台的直播页并采集评论。
 * 用户无需填 URL / 选择器 / 规则——全部由平台配置 + cookie 登录态完成。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LiveSheet(vm: MainViewModel, settings: Settings, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val client = remember { LiveCommentClient.get(ctx) }
    val comments by client.comments.collectAsState()
    val status by client.status.collectAsState()
    val aiReply by vm.aiReply.collectAsState()

    val logged = remember { Platforms.ALL.filter { settings.isPlatformLoggedIn(it.name) } }
    var selected by remember { mutableStateOf(logged.firstOrNull()?.name ?: "") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    if (toast != null) LaunchedEffect(toast) { kotlinx.coroutines.delay(1800); toast = null }

    // 评论回流 → 播讲编排（讲完再切、超20s跳过、无评论自动讲解）
    LaunchedEffect(comments) {
        val latest = comments.lastOrNull() ?: return@LaunchedEffect
        vm.onNewComment(latest.user, latest.text, latest.ts)
    }
    // AI 回复 → 自动发回直播间
    LaunchedEffect(aiReply) {
        aiReply?.let {
            client.sendReplyToPage(it)
            toast = "AI 回复：${it.take(16)}"
            vm.consumeAiReply()
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize().systemBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("开播中", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("自动采集评论 · AI 回复 · 声音播讲", color = Muted, fontSize = 11.sp)
                    }
                    Surface(color = if (status.contains("已")) AccentSoft else Card2, shape = RoundedCornerShape(20.dp)) {
                        Text(if (status.contains("已")) "采集中" else "未连接",
                            color = if (status.contains("已")) AccentDeep else Muted, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                    IconButton(onClick = { client.disconnect(); onClose() }) {
                        Icon(Icons.Default.Close, "关闭", tint = Muted)
                    }
                }
                Spacer(Modifier.height(10.dp))

                if (logged.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Card2),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("还没有登录任何平台", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("先到 设置 → 平台登录，登录并保存后再开播", color = Muted, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                        Text("去登录", color = Color.White)
                    }
                    Spacer(Modifier.height(12.dp))
                } else {
                    // 已登录平台 chips（可切换；切换即重新加载直播页）
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        logged.forEach { p ->
                            FilterChip(selected = selected == p.name, onClick = { selected = p.name },
                                label = { Text(p.name, fontSize = 12.sp) })
                        }
                    }
                    Spacer(Modifier.height(10.dp))

                    val platform = Platforms.byName(selected) ?: logged.first()
                    // WebView 占满剩余空间：加载直播页，复用登录 cookie，注入采集脚本
                    Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Card2)) {
                        key(selected) {
                            AndroidView(factory = { c ->
                                WebView(c).apply {
                                    this.settings.javaScriptEnabled = true
                                    this.settings.domStorageEnabled = true
                                    this.settings.userAgentString = this.settings.userAgentString + " TuptupComment/1.0"
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    webViewRef = this
                                    client.setupBridge(this)
                                    webViewClient = object : android.webkit.WebViewClient() {
                                        override fun onPageFinished(view: WebView?, u: String?) {
                                            super.onPageFinished(view, u)
                                            client.attachWebView(this@apply, platform.selector)
                                            client.injectReplyScript("input, textarea, [contenteditable=true]")
                                        }
                                    }
                                    loadUrl(platform.liveUrl)
                                }
                            }, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("请在页面里进入自己的直播间，评论会自动采集并播讲", color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            webViewRef?.evaluateJavascript("""(function(){
                              window.TuptupCollector && window.TuptupCollector.onComment('自检', '这是一条自检评论，正在验证回流与播讲');
                              return 'ok';
                            })()""", null)
                            toast = "已注入自检评论"
                        }, modifier = Modifier.weight(1f)) { Text("回流自检", fontSize = 12.sp) }
                        Button(onClick = { client.disconnect(); onClose() }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                            Text("结束开播", color = Color.White, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (toast != null) {
                Box(Modifier.fillMaxSize().padding(bottom = 20.dp), contentAlignment = Alignment.BottomCenter) {
                    Surface(color = TextMain, shape = RoundedCornerShape(20.dp)) {
                        Text(toast!!, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(10.dp, 16.dp))
                    }
                }
            }
        }
    }
}
