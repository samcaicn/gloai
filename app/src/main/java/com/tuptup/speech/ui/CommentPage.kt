package com.tuptup.speech.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tuptup.speech.data.Settings
import com.tuptup.speech.live.LiveCommentClient
import com.tuptup.speech.ui.theme.*

private val PRESETS = listOf(
    "通用" to "[class*=comment], [class*=danmu], .chat-item",
    "抖音" to "[class*=comment], [class*=chat], [class*=danmu]",
    "快手" to "[class*=comment], [class*=barrage], .comment-item"
)

/** 直播渠道：点选即打开 WebView，用户在页面内登录并进入直播间后自动采集 */
private data class Channel(val name: String, val url: String, val selector: String)
private val CHANNELS = listOf(
    Channel("抖音直播", "https://live.douyin.com/", "[class*=chatroom] [class*=item], [class*=comment], [class*=danmu]"),
    Channel("快手直播", "https://live.kuaishou.com/", "[class*=comment-item], [class*=comment], [class*=danmu]"),
    Channel("B站直播", "https://live.bilibili.com/", ".chat-item, [class*=danmaku], [class*=comment]"),
    Channel("视频号", "https://channels.weixin.qq.com/", "[class*=comment], [class*=danmu], .chat-item"),
    Channel("淘宝直播", "https://taobaolive.taobao.com/", "[class*=comment], [class*=danmu], .chat-item")
)

/**
 * 直播间评论采集页：WebView 登录态 + DOM 注入采集 + 关键词回复 + 评论用复刻音色播讲。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CommentPage(vm: MainViewModel, mod: Modifier) {
    val ctx = LocalContext.current
    val cfg = remember { Settings(ctx) }
    val client = remember { LiveCommentClient.get(ctx) }
    val comments by client.comments.collectAsState()
    val status by client.status.collectAsState()

    var url by remember { mutableStateOf(cfg.liveUrl) }
    var selector by remember { mutableStateOf(cfg.commentSelector) }
    var showWebView by remember { mutableStateOf(false) }
    var speakComments by remember { mutableStateOf(cfg.commentSpeakEnabled) }
    var toast by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    if (toast != null) LaunchedEffect(toast) { kotlinx.coroutines.delay(1800); toast = null }

    val aiReply by vm.aiReply.collectAsState()
    LaunchedEffect(aiReply) {
        aiReply?.let {
            if (cfg.commentSpeakEnabled) {
                client.sendReplyToPage(it)
                toast = "AI 回复：${it.take(16)}"
                vm.consumeAiReply()
            }
        }
    }

    // 新评论 → 播讲编排（讲完再切、超20s跳过、无评论自动讲解）
    LaunchedEffect(comments) {
        val latest = comments.lastOrNull() ?: return@LaunchedEffect
        vm.onNewComment(latest.user, latest.text, latest.ts)
    }

    fun selfTest() {
        val wv = webViewRef
        if (wv == null) { toast = "请先加载直播间"; return@selfTest }
        wv.evaluateJavascript(
            """(function(){
              try {
                var d=document.createElement('div');
                d.className='comment-item';
                d.innerText='自检观众：欢迎来到直播间';
                document.body.appendChild(d);
              } catch(e){}
              window.TuptupCollector && window.TuptupCollector.onComment('自检', '这是一条自检评论，正在验证回流与播讲');
              return 'ok';
            })()""", null
        )
        toast = "已注入自检评论"
    }

    Column(mod.fillMaxSize().verticalScroll(rememberScrollState()).background(Bg)) {
        Spacer(Modifier.height(18.dp))
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("评论采集", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("打开直播间，自动接收并播讲新评论", color = Muted, fontSize = 11.sp)
            }
            Surface(color = if (status.contains("已")) AccentSoft else Card2, shape = RoundedCornerShape(20.dp)) {
                Text(if (status.contains("已")) "采集中" else "未连接",
                    color = if (status.contains("已")) AccentDeep else Muted, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        Spacer(Modifier.height(16.dp))

        Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            tonalElevation = 1.dp) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (status.contains("已")) Success else Muted))
                Spacer(Modifier.width(12.dp))
                Text("采集状态", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(status, color = TextMain, fontSize = 12.sp, maxLines = 1)
            }
        }

        Spacer(Modifier.height(18.dp))
        CommentSectionTitle("直播渠道", "CHANNELS")
        Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("直播渠道（点选打开页面，登录后自动采集）", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    CHANNELS.forEach { ch ->
                        FilterChip(selected = url.startsWith(ch.url), onClick = {
                            url = ch.url; selector = ch.selector
                            cfg.liveUrl = url; cfg.commentSelector = selector
                            showWebView = true
                            toast = "已打开${ch.name}，请在页面内登录并进入直播间"
                        }, label = { Text(ch.name, fontSize = 11.sp) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(url, { url = it; cfg.liveUrl = it },
                    label = { Text("直播间 URL（自己已登录的页面）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                Text("识别方式（一般不用改）", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PRESETS.forEach { (name, value) ->
                        FilterChip(selected = selector == value, onClick = { selector = value; cfg.commentSelector = value },
                            label = { Text(name, fontSize = 11.sp) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(selector, { selector = it; cfg.commentSelector = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (url.isBlank()) { toast = "请先填直播间 URL"; return@Button }
                        cfg.liveUrl = url; cfg.commentSelector = selector
                        showWebView = true
                        toast = "已加载，正在注入采集脚本"
                    }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                        Text("加载并采集", color = Color.White, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { selfTest() }, modifier = Modifier.weight(1f)) {
                        Text("回流自检", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = {
                        client.disconnect(); showWebView = false; webViewRef = null
                        toast = "已停止采集"
                    }) { Text("停止", fontSize = 12.sp) }
                }
            }
        }

        if (showWebView) {
            Spacer(Modifier.height(14.dp))
            Text("直播间页面（脚本已注入）", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp))
            Spacer(Modifier.height(6.dp))
            key(url, selector) {
                AndroidView(factory = { c ->
                    WebView(c).apply {
                        this.settings.javaScriptEnabled = true
                        this.settings.domStorageEnabled = true
                        this.settings.userAgentString = this.settings.userAgentString + " TuptupComment/1.0"
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewRef = this
                        // 必须在 loadUrl 之前注册 JS 桥
                        client.setupBridge(this)
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(view: WebView?, u: String?) {
                                super.onPageFinished(view, u)
                                client.attachWebView(this@apply, selector)
                                client.injectReplyScript("input, textarea, [contenteditable=true]")
                            }
                        }
                        loadUrl(url)
                    }
                }, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(260.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Card2)
                    .border(1.dp, Line, RoundedCornerShape(16.dp)))
            }
        }

        Spacer(Modifier.height(18.dp))
        CommentSectionTitle("评论播讲", "VOICE")
        Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("读出新评论 · AI 自动回复", color = TextMain, fontSize = 13.sp)
                    }
                    Switch(speakComments, { speakComments = it; cfg.commentSpeakEnabled = it })
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        CommentSectionTitle("实时评论", "${comments.size} 条")
        if (comments.isEmpty()) {
            Text("暂无评论，加载直播间或点击回流自检", color = Muted, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
        } else {
            Surface(color = Card, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                tonalElevation = 1.dp) {
                Column(Modifier.padding(14.dp)) {
                    comments.takeLast(30).reversed().forEach { c ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(c.user, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(66.dp))
                            Text(c.text, color = TextMain, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(70.dp))
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
private fun CommentSectionTitle(title: String, en: String) {
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(en, color = Muted, fontSize = 9.sp)
    }
}
