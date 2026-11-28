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
import androidx.compose.material.icons.filled.Check
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
import com.tuptup.speech.ui.theme.*

/**
 * 设置内的「平台登录」全屏浮层：自适应屏幕，WebView 占满剩余空间，按钮常驻底部。
 * 用户在页面里自行登录 → 点「登录完成」flush cookie 并标记平台，开播页即可显示。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginSheet(settings: Settings, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    if (toast != null) LaunchedEffect(toast) { kotlinx.coroutines.delay(1800); toast = null }

    LaunchedEffect(Unit) {
        CookieManager.getInstance().apply { setAcceptCookie(true); flush() }
    }

    // 桌面 UA：移动 UA 下抖音/快手只显示「下载APP」、视频号空白、淘宝直播 500，
    // 完全没有登录入口。实测桌面 UA 下五个平台都能出现「登录」按钮或跳转登录页。
    val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
            Column(Modifier.fillMaxSize().systemBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
                // ---- 顶部：标题 + 关闭 + 说明（固定高度） ----
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("平台登录", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭", tint = Muted) }
                }
                Hint("选平台 → 页面里登录 → 点保存，开播页显示已登录平台")
                Spacer(Modifier.height(10.dp))

                // ---- 平台 chips：横向滚动，不换行挤占高度 ----
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Platforms.ALL.forEach { p ->
                        val done = settings.isPlatformLoggedIn(p.name)
                        FilterChip(
                            selected = selected == p.name,
                            onClick = { selected = p.name },
                            leadingIcon = if (done) {
                                { Icon(Icons.Default.Check, null, tint = Success, modifier = Modifier.size(14.dp)) }
                            } else null,
                            label = { Text(p.name + if (done) " ✓" else "", fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                // ---- WebView：占满剩余空间，自适应任意屏幕 ----
                if (selected == null) {
                    Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Card2),
                        contentAlignment = Alignment.Center) {
                        Text("请先选一个平台", color = Muted, fontSize = 13.sp)
                    }
                } else {
                    val platform = Platforms.byName(selected!!)!!
                    Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Card2)) {
                        key(selected) {
                            AndroidView(factory = { c ->
                                WebView(c).apply {
                                    this.settings.javaScriptEnabled = true
                                    this.settings.domStorageEnabled = true
                                    this.settings.useWideViewPort = true
                                    this.settings.loadWithOverviewMode = true
                                    this.settings.builtInZoomControls = true
                                    this.settings.displayZoomControls = false
                                    // 关键：桌面 UA 才会渲染出「登录」按钮
                                    this.settings.userAgentString = desktopUa
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    webView = this
                                    webViewClient = object : android.webkit.WebViewClient() {
                                        override fun onPageFinished(view: WebView?, u: String?) {
                                            super.onPageFinished(view, u)
                                            CookieManager.getInstance().flush()
                                            // 页面加载完把登录按钮滚到可见处并高亮，避免用户找不到
                                            view?.evaluateJavascript(
                                                """(function(){
                                                  var kws=['登录','登陆','立即登录','扫码登录','登录视频号助手'];
                                                  var els=document.querySelectorAll('button,a,div,span,[role=button]');
                                                  for(var i=0;i<els.length;i++){
                                                    var e=els[i]; var t=(e.innerText||'').trim();
                                                    if(t.length>10) continue;
                                                    for(var j=0;j<kws.length;j++){
                                                      if(t.indexOf(kws[j])>=0){
                                                        try{e.scrollIntoView({block:'center'});}catch(_){}
                                                        e.style.outline='3px solid #ff6b35';
                                                        return;
                                                      }
                                                    }
                                                  }
                                                })()""", null)
                                        }
                                    }
                                    loadUrl(platform.loginUrl)
                                }
                            }, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                // ---- 底部按钮：常驻可见 ----
                if (selected != null) {
                    val platform = Platforms.byName(selected!!)!!
                    // 页面偶尔加载不全（视频号二维码、快手骨架屏），提供刷新入口重载登录页
                    OutlinedButton(onClick = { webView?.reload() }, modifier = Modifier.fillMaxWidth()) {
                        Text("刷新登录页（加载不全时点这里）", fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = {
                        CookieManager.getInstance().flush()
                        settings.markPlatformLoggedIn(platform.name)
                        toast = "${platform.name} 登录态已保存到本机"
                    }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                        Text("登录完成，保存 cookie", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = {
                        settings.clearPlatformLoggedIn(platform.name)
                        toast = "已清除 ${platform.name} 登录标记"
                    }, modifier = Modifier.fillMaxWidth()) { Text("清除该平台登录标记", fontSize = 12.sp) }
                }
                Spacer(Modifier.height(12.dp))
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
