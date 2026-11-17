@file:Suppress("UNCHECKED_CAST", "USELESS_CAST", "INAPPLICABLE_JVM_NAME", "UNUSED_ANONYMOUS_PARAMETER", "NAME_SHADOWING", "UNNECESSARY_NOT_NULL_ASSERTION")
package uts.sdk.modules.LGLoginWebview
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.View.OnClickListener
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.dcloud.uniapp.*
import io.dcloud.uniapp.extapi.*
import io.dcloud.uts.*
import io.dcloud.uts.Map
import io.dcloud.uts.Set
import kotlin.properties.Delegates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import io.dcloud.uts.UTSAndroid
val VIEW_W: Number = 300
val VIEW_H: Number = 600
val VIEW_OFFSET: Number = -2000
val BAR_DP: Number = 44
val DEFAULT_UA = "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/95.0.4638.69 Safari/537.36"
val VIEWPORT_JS = "(function(){try{var m=document.querySelector('meta[name=\"viewport\"]');if(!m){return '';}var c=String(m.getAttribute('content')||'');c=c.split('user-scalable=no').join('user-scalable=yes');c=c.split('user-scalable=0').join('user-scalable=yes');if(c.indexOf('user-scalable')<0){c=c+',user-scalable=yes';}if(c.indexOf('maximum-scale')<0){c=c+',maximum-scale=5.0';}m.setAttribute('content',c);return c;}catch(e){return '';}})()"
val MEITUAN_LIVEINFO_URL = "https://mlive.meituan.com/live/centercontrol/liveinfo"
val ZHIFUBAO_GETAPPENV_URL = "https://contentweb.alipay.com/life/getAppEnv.json?sourceId"
val views = Map<String, WebView>()
val boxes = Map<String, ViewGroup>()
val anchorIds = Map<String, String>()
val getAppEnvUrls = Map<String, String>()
var rootView: ViewGroup? = null
var seq: Number = 0
fun runOnUi(fn: () -> Unit): Unit {
    val h = Handler(Looper.getMainLooper())
    h.post(fn)
}
fun dp(ctx: Context, v: Number): Int {
    val d = ctx.getResources().getDisplayMetrics().density
    return (v.toFloat() * d).toInt()
}
fun statusBarHeight(ctx: Context): Int {
    try {
        val res = ctx.getResources()
        val rid = res.getIdentifier("status_bar_height", "dimen", "android")
        if (rid > 0) {
            return res.getDimensionPixelSize(rid)
        }
    }
     catch (e: Throwable) {
        console.log("LG-LoginWebview 取状态栏高度失败: " + e)
    }
    return dp(ctx, 24)
}
fun handleUrl(view: WebView?, url: String?): Boolean {
    if (view != null && url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
        try {
            view.loadUrl(url)
        }
         catch (e: Throwable) {
            console.log("LG-LoginWebview 继续加载失败: " + e)
        }
    }
    return true
}
open class JsValueCallback : ValueCallback<String> {
    open lateinit var cb: ((v: String) -> Unit)
    constructor(cb: (v: String) -> Unit){
        this.cb = cb
    }
    override fun onReceiveValue(value: String?): Unit {
        this.cb(if (value == null) {
            ""
        } else {
            value
        }
        )
    }
}
open class CloseClickListener : OnClickListener {
    open var vid: String = ""
    constructor(vid: String){
        this.vid = vid
    }
    override fun onClick(v: View?): Unit {
        close(this.vid)
    }
}
fun captureAnchorId(id: String, url: String): Unit {
    val cur = anchorIds.get(id)
    if (cur != null && cur != "") {
        return
    }
    val arr = url.split("&")
    run {
        var i: Number = 0
        while(i < arr.length){
            if (arr[i].indexOf("anchorId") >= 0) {
                val kv = arr[i].split("=")
                if (kv.length > 1 && kv[1] != "") {
                    anchorIds.set(id, kv[1])
                }
                return
            }
            i++
        }
    }
}
fun captureGetAppEnvUrl(id: String, url: String): Unit {
    if (getAppEnvUrls.get(id) != null) {
        return
    }
    getAppEnvUrls.set(id, url)
}
open class LoginWebViewClient : WebViewClient {
    open var vid: String = ""
    constructor(vid: String) : super() {
        this.vid = vid
    }
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (request != null && this.vid != "") {
            try {
                val u = request.getUrl().toString()
                if (u.indexOf(MEITUAN_LIVEINFO_URL) >= 0) {
                    captureAnchorId(this.vid, u)
                } else if (u.indexOf(ZHIFUBAO_GETAPPENV_URL) >= 0) {
                    captureGetAppEnvUrl(this.vid, u)
                }
            }
             catch (e: Throwable) {
                console.log("LG-LoginWebview 拦截请求失败: " + e)
            }
        }
        return null
    }
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (request == null) {
            return true
        }
        return handleUrl(view, request.getUrl().toString())
    }
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        return handleUrl(view, url)
    }
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?): Unit {
        if (handler != null) {
            handler.proceed()
        }
    }
    override fun onPageFinished(view: WebView?, url: String?): Unit {
        if (view == null) {
            return
        }
        try {
            view.evaluateJavascript(VIEWPORT_JS, JsValueCallback(fun(v: String): Unit {}))
        }
         catch (e: Throwable) {
            console.log("LG-LoginWebview 注入视口补丁失败: " + e)
        }
    }
}
fun applySettings(s: WebSettings, ua: String): Unit {
    s.setJavaScriptEnabled(true)
    s.setDomStorageEnabled(true)
    s.setJavaScriptCanOpenWindowsAutomatically(true)
    s.setUseWideViewPort(true)
    s.setLoadWithOverviewMode(true)
    s.setSupportZoom(true)
    s.setBuiltInZoomControls(true)
    s.setDisplayZoomControls(false)
    s.setAllowFileAccess(true)
    s.setAllowFileAccessFromFileURLs(true)
    s.setAllowUniversalAccessFromFileURLs(true)
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW)
    s.setCacheMode(WebSettings.LOAD_NO_CACHE)
    s.setUserAgentString(if (ua != "") {
        ua
    } else {
        DEFAULT_UA
    }
    )
}
fun buildVisible(ctx: Context, wv: WebView, id: String, title: String): LinearLayout {
    val box = LinearLayout(ctx)
    box.setOrientation(LinearLayout.VERTICAL)
    box.setBackgroundColor(Color.WHITE)
    val sb = statusBarHeight(ctx)
    val barH = dp(ctx, BAR_DP)
    box.setPadding(0, sb, 0, 0)
    val bar = LinearLayout(ctx)
    bar.setOrientation(LinearLayout.HORIZONTAL)
    bar.setBackgroundColor(Color.parseColor("#F5F6F8"))
    bar.setGravity(Gravity.CENTER_VERTICAL)
    bar.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
    val backText = "‹ 返回"
    val backSize: Number = 15
    val backView = TextView(ctx)
    backView.setText(backText)
    backView.setTextColor(Color.parseColor("#FFFFFF"))
    backView.setTextSize(backSize.toFloat())
    backView.setGravity(Gravity.CENTER)
    backView.setPadding(dp(ctx, 16), dp(ctx, 7), dp(ctx, 16), dp(ctx, 7))
    val bd = GradientDrawable()
    bd.setShape(GradientDrawable.RECTANGLE)
    bd.setColor(Color.parseColor("#07C160"))
    bd.setCornerRadius(dp(ctx, 18).toFloat())
    backView.setBackground(bd)
    backView.setOnClickListener(CloseClickListener(id))
    bar.addView(backView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    val titleView = TextView(ctx)
    titleView.setText(if (title != "") {
        title
    } else {
        "平台登录"
    }
    )
    titleView.setTextColor(Color.parseColor("#1A1A1A"))
    val ts: Number = 16
    titleView.setTextSize(ts.toFloat())
    titleView.setGravity(Gravity.CENTER)
    titleView.setSingleLine(true)
    val titleLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT)
    titleLp.weight = 1.0.toFloat()
    bar.addView(titleView, titleLp)
    val spacerView = TextView(ctx)
    spacerView.setText(backText)
    spacerView.setTextSize(backSize.toFloat())
    spacerView.setGravity(Gravity.CENTER)
    spacerView.setPadding(dp(ctx, 16), dp(ctx, 7), dp(ctx, 16), dp(ctx, 7))
    spacerView.setVisibility(View.INVISIBLE)
    bar.addView(spacerView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    val barLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barH)
    box.addView(bar, barLp)
    val dm = ctx.getResources().getDisplayMetrics()
    val wvH = dm.heightPixels - sb - barH
    box.addView(wv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, wvH))
    return box
}
fun clearCookieJar(): Unit {
    try {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.removeAllCookie()
        cm.flush()
    }
     catch (e: Throwable) {
        console.log("LG-LoginWebview 清空 cookie 失败: " + e)
    }
}
fun injectCookieJar(cookies: String, domains: UTSArray<String>, append: String): Unit {
    val cm = CookieManager.getInstance()
    if (cookies == "" || domains.length == 0) {
        return
    }
    val arr = cookies.split(";")
    run {
        var i: Number = 0
        while(i < arr.length){
            val item = arr[i].trim()
            if (item == "") {
                i++
                continue
            }
            val value = if (append != "") {
                item + append
            } else {
                item
            }
            run {
                var j: Number = 0
                while(j < domains.length){
                    try {
                        cm.setCookie(domains[j], value)
                    }
                     catch (e: Throwable) {
                        console.log("LG-LoginWebview setCookie 失败: " + e)
                    }
                    j++
                }
            }
            i++
        }
    }
    cm.flush()
}
fun create(url: String, visible: Boolean, ua: String, title: String, isolate: Boolean, injectCookies: String, injectDomains: UTSArray<String>, injectAppend: String): String {
    seq = seq + 1
    val id = "lg-login-" + seq.toString(10)
    if (isolate) {
        closeAll()
    }
    runOnUi(fun(): Unit {
        try {
            val activity = UTSAndroid.getUniActivity()
            if (activity == null) {
                return
            }
            val wv = WebView(activity)
            applySettings(wv.getSettings(), ua)
            wv.setWebViewClient(LoginWebViewClient(id))
            if (injectDomains.length > 0) {
                clearCookieJar()
                injectCookieJar(injectCookies, injectDomains, injectAppend)
            } else if (isolate) {
                clearCookieJar()
            }
            if (isolate) {
                try {
                    wv.clearCache(true)
                    wv.clearHistory()
                }
                 catch (e: Throwable) {
                    console.log("LG-LoginWebview 清缓存失败: " + e)
                }
            }
            views.set(id, wv)
            val root = activity.getWindow().getDecorView() as ViewGroup
            rootView = root
            if (visible) {
                val box = buildVisible(activity, wv, id, title)
                boxes.set(id, box)
                root.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else {
                val box = FrameLayout(activity)
                box.addView(wv, FrameLayout.LayoutParams(VIEW_W.toInt(), VIEW_H.toInt()))
                boxes.set(id, box)
                val lp = FrameLayout.LayoutParams(VIEW_W.toInt(), VIEW_H.toInt())
                lp.leftMargin = VIEW_OFFSET.toInt()
                lp.topMargin = VIEW_OFFSET.toInt()
                root.addView(box, lp)
            }
            wv.loadUrl(url)
        }
         catch (e: Throwable) {
            console.log("LG-LoginWebview create 失败: " + e)
        }
    }
    )
    return id
}
fun evaluateJavascript(id: String, js: String): UTSPromise<String> {
    return UTSPromise<String>(fun(resolve, _reject): Unit {
        val wv = views.get(id)
        if (wv == null) {
            resolve("")
            return
        }
        runOnUi(fun(): Unit {
            try {
                wv.evaluateJavascript(js, JsValueCallback(fun(v: String): Unit {
                    resolve(v)
                }
                ))
            }
             catch (e: Throwable) {
                console.log("LG-LoginWebview evaluateJavascript 失败: " + e)
                resolve("")
            }
        }
        )
    }
    )
}
fun exists(id: String): Boolean {
    return views.get(id) != null
}
fun interceptedAnchorId(id: String): String {
    val v = anchorIds.get(id)
    return if (v == null) {
        ""
    } else {
        v
    }
}
fun interceptedGetAppEnvUrl(id: String): String {
    val v = getAppEnvUrls.get(id)
    return if (v == null) {
        ""
    } else {
        v
    }
}
fun close(id: String): Unit {
    val wv = views.get(id)
    if (wv == null) {
        return
    }
    views.`delete`(id)
    anchorIds.`delete`(id)
    getAppEnvUrls.`delete`(id)
    val box = boxes.get(id)
    boxes.`delete`(id)
    runOnUi(fun(): Unit {
        try {
            wv.stopLoading()
            val root = rootView
            if (root != null && box != null) {
                try {
                    root.removeView(box)
                }
                 catch (e: Throwable) {
                    console.log("LG-LoginWebview 移出容器失败: " + e)
                }
            }
            wv.destroy()
        }
         catch (e: Throwable) {
            console.log("LG-LoginWebview close 失败: " + e)
        }
    }
    )
}
fun closeAll(): Unit {
    val ids: UTSArray<String> = _uA()
    views.forEach(fun(_wv: WebView, key: String): Unit {
        ids.push(key)
    }
    )
    run {
        var i: Number = 0
        while(i < ids.length){
            close(ids[i])
            i++
        }
    }
}
fun createByJs(url: String, visible: Boolean, ua: String, title: String, isolate: Boolean, injectCookies: String, injectDomains: UTSArray<String>, injectAppend: String): String {
    return create(url, visible, ua, title, isolate, injectCookies, injectDomains, injectAppend)
}
suspend fun evaluateJavascriptByJs(id: String, js: String): Deferred<String> {
    return toDeferred(evaluateJavascript(id, js))
}
fun existsByJs(id: String): Boolean {
    return exists(id)
}
fun interceptedAnchorIdByJs(id: String): String {
    return interceptedAnchorId(id)
}
fun interceptedGetAppEnvUrlByJs(id: String): String {
    return interceptedGetAppEnvUrl(id)
}
fun closeByJs(id: String): Unit {
    return close(id)
}
fun closeAllByJs(): Unit {
    return closeAll()
}
