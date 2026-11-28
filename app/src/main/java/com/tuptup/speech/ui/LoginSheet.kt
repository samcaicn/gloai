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
                                    this.settings.userAgentString = this.settings.userAgentString + " TuptupLogin/1.0"
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    webView = this
                                    webViewClient = object : android.webkit.WebViewClient() {
                                        override fun onPageFinished(view: WebView?, u: String?) {
                                            super.onPageFinished(view, u)
                                            CookieManager.getInstance().flush()
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
