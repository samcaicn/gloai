package com.tuptup.speech.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import com.tuptup.speech.data.Platforms
import com.tuptup.speech.data.Settings
import com.tuptup.speech.ui.theme.*

/**
 * 设置内的「平台登录」浮层：WebView 加载各平台页面，用户自行登录；
 * 点「登录完成」后 flush cookie（持久化到本机），记录平台，开播页即可展示。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginSheet(settings: Settings, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    if (toast != null) LaunchedEffect(toast) { kotlinx.coroutines.delay(1800); toast = null }

    // 开启 cookie 持久化（App 重启后仍保留登录态）
    LaunchedEffect(Unit) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            flush()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = Card,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("平台登录", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Hint("选平台 → 在页面里登录 → 点「登录完成」保存，开播页会显示已登录平台")
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Platforms.ALL.forEach { p ->
                    val done = settings.isPlatformLoggedIn(p.name)
                    FilterChip(
                        selected = selected == p.name,
                        onClick = { selected = p.name },
                        leadingIcon = if (done) { { Icon(Icons.Default.Check, null, tint = Success, modifier = Modifier.size(14.dp)) } } else null,
                        label = { Text(p.name + if (done) " ✓" else "", fontSize = 12.sp) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (selected == null) {
                Box(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(16.dp)).background(Card2),
                    contentAlignment = Alignment.Center) {
                    Text("请先选一个平台", color = Muted, fontSize = 13.sp)
                }
            } else {
                val platform = Platforms.byName(selected!!)!!
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
                    }, modifier = Modifier.fillMaxWidth().height(380.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Card2))
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    CookieManager.getInstance().flush()
                    settings.markPlatformLoggedIn(platform.name)
                    toast = "${platform.name} 登录态已保存到本机"
                }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("登录完成，保存 cookie", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    settings.clearPlatformLoggedIn(platform.name)
                    toast = "已清除 ${platform.name} 登录标记"
                }, modifier = Modifier.fillMaxWidth()) { Text("清除该平台登录标记", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (toast != null) {
            Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                Surface(color = TextMain, shape = RoundedCornerShape(20.dp)) {
                    Text(toast!!, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(10.dp, 16.dp))
                }
            }
        }
    }
}
