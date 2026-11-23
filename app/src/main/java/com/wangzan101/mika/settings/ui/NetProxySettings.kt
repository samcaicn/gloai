package com.wangzan101.mika.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.wangzan101.mika.ui.components.DetailNote
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.WebProxy
import com.wangzan101.mika.core.net.ProxyAddress
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.NetProxyStore
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 设置 › 网络代理 的文案(纯函数,便于测试)。地址只写协议、主机、端口,不带账号密码(这一行随时看得到)。
 * 只在设置首页这一处改;文件与计算环境页那一行只是引过来看。
 */
object NetProxyCopy {
    val TITLE: String get() = tr(R.string.net_proxy)
    val SUMMARY: String get() = tr(R.string.net_proxy_summary)
    val HINT: String get() = tr(R.string.net_proxy_hint)
    val INVALID: String get() = tr(R.string.net_proxy_invalid)

    /** 设置首页那一行右边:没设写「未设置」,设了写地址。 */
    fun value(proxy: String): String = NetProxyStore.display(proxy).ifEmpty { tr(R.string.settings_not_set) }

    /**
     * 那一行下面的说明:平时说它管哪些连接;只给容器用的写法、浏览器用不上时照实说。
     * [browser] 为空表示还没查出浏览器这边的情况。
     */
    fun subtitle(proxy: String, browser: WebProxy.Mode?): String {
        val address = ProxyAddress.parse(proxy)
        return when {
            address != null && !address.forApp -> tr(R.string.net_proxy_container_only)
            browser == WebProxy.Mode.Unsupported -> tr(R.string.net_proxy_browser_unsupported)
            browser == WebProxy.Mode.SocksWithCredentials -> tr(R.string.net_proxy_browser_socks_auth)
            else -> tr(R.string.net_proxy_row_sub)
        }
    }
}

/** 改网络代理:一个输入框,空着表示不用;写得不对时就地说怎么写,不存。 */
@Composable
fun NetProxyDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val ok = NetProxyStore.valid(text)
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(NetProxyCopy.TITLE) },
        text = {
            Column {
                DetailNote(NetProxyCopy.SUMMARY, NetProxyCopy.HINT, Modifier.padding(bottom = 4.dp))
                OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("net-proxy-input"),
                    singleLine = true, isError = !ok, placeholder = { Text("http://127.0.0.1:7890") },
                )
                if (!ok) Text(NetProxyCopy.INVALID, Modifier.padding(top = 4.dp), style = MikaType.Hint, color = MikaTheme.colors.bad)
            }
        },
        confirmButton = { TextButton({ onSave(text) }, enabled = ok) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
