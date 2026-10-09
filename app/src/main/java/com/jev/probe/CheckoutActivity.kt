package com.jev.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import com.jev.probe.core.LicenseClient
import com.jev.probe.core.Prefs
import kotlin.math.roundToInt

/**
 * App 内收银台：Creem 直连结账短链（[Prefs.CREEM_CHECKOUT_URLS]，落在 pay.jukuai.net）
 * **在本 App 的 WebView 里打开**，不再跳出外部浏览器/H5 容器，避免「付款后回不到 App /
 * 会话串号 / 状态丢失」。直连后不再经过 weauto.safeopc.cn 的 Worker，绕开其源站 525。
 *
 * 品牌白牌：Creem 页自带「Secure Checkout by Creem / Powered by Creem.io」字样，用三重手段藏掉
 *  1. **布局裁切**：WebView 整体上移 [TOP_CROP_DP]，底边再收 [BOTTOM_CROP_DP]（或固定
 *     [WINDOW_HEIGHT_DP]），把品牌所在的边缘顶到可视区外，容器负责裁剪；
 *  2. **禁止滚动**：注入 `overflow:hidden` + 关闭滚动条/回弹，用户滚不回去看到被切掉的部分；
 *  3. **DOM 兜底**：注入脚本把任何文案/alt/src/class 里带 "creem" 的节点直接 `display:none`，
 *     并用 MutationObserver 盯着（收银台是 Next.js 客户端渲染，品牌节点是后插入的）。
 * 三个量都在 [Companion] 里，真机上看着调即可。
 *
 * 关键约束：
 *  - **checkout 是一次性的买家私有资源**（Creem 会话短链）→ 这里强制 `LOAD_NO_CACHE`
 *    （以及请求头 no-cache），第二个买家不会拿到第一个人的会话。
 *  - 所有 http/https 导航（含 Creem → 支付宝/银联/3DS 的跳转）都留在 WebView 内；
 *    只有非 http 的原生 scheme（alipays://、weixin://、intent:// …）才交给外部 App。
 *  - `target=_blank` / `window.open`（收银台弹二维码、3DS 弹窗）由 [WebChromeClient.onCreateWindow]
 *    接管，新建一个 WebView 叠在同一容器里，仍然不出 App。
 *  - 付款成功靠 [LicenseClient.poll] 轮询确认（不依赖回调 URL），拿到卡密即写令牌并收尾。
 *  - 若底层仍出现 Cloudflare 5xx / 源站错误页（理论上直连后不会再触发，但留一手），
 *    [onPageFinished] 会检测到并覆盖一层「支付服务暂时不可用，请稍后重试」友好提示，
 *    不让用户看到原生 Cloudflare 错误页或任何内部域名。
 */
class CheckoutActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var web: WebView
    private lateinit var container: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var titleView: TextView
    private val main = Handler(Looper.getMainLooper())

    private var pollThread: Thread? = null
    private var tier: String = ""
    private var activated = false
    private var lastGoodUrl: String = ""
    /** 已经盖过 Cloudflare/源站错误友好层，避免 SPA 内部跳转重复弹。 */
    private var cloudErrorShown = false

    /** 页面回报的滚动锁：内容装得下才锁死滚动（JS 侧算，见 HIDE_BRAND_JS 的 lockScroll）。 */
    @Volatile
    private var pageScrollLock = false

    private val accent = Color.parseColor("#3A7AFE")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val startUrl = intent.getStringExtra(EXTRA_URL)
        if (startUrl.isNullOrBlank()) { finish(); return }
        tier = intent.getStringExtra(EXTRA_TIER) ?: ""

        // AGP 8 起 buildConfig 默认关闭，用 debuggable 标志判断，避免引入 BuildConfig
        if (0 != applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        window.decorView.setBackgroundColor(Color.WHITE)
        window.statusBarColor = Color.WHITE

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        root.addView(buildToolbar())
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
            visibility = View.GONE
        }
        root.addView(progress)
        container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(container)
        root.padForSystemBars()

        // WebView 在极少数 ROM（无 WebView 实现 / 被禁用）上会直接抛异常，
        // 这时退回外部浏览器，至少不让用户卡在白屏。
        val w = try {
            createWebView().also { web = it }
        } catch (e: Throwable) {
            Log.e(TAG, "webview unavailable: ${e.message}")
            fallbackToBrowser(startUrl)
            return
        }
        container.addView(w, windowLayoutParams())
        container.setClipChildren(true)

        setContentView(root)
        WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = true

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        lastGoodUrl = startUrl
        // checkout 是私有一次性资源：请求头也带上 no-cache，别让任何一层缓存它
        w.loadUrl(startUrl, mapOf("Cache-Control" to "no-cache, no-store", "Pragma" to "no-cache"))
        startPolling()
        Log.i(TAG, "checkout opened in-app tier=$tier")
    }

    override fun onDestroy() {
        pollThread?.interrupt()
        if (::web.isInitialized) {
            runCatching { container.removeAllViews() }
            runCatching { web.destroy() }
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun buildToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val back = TextView(this).apply {
            text = "‹ 返回"; textSize = 15f; setTextColor(accent)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { goBack() }
        }
        titleView = TextView(this).apply {
            text = "安全支付"; textSize = 16f; setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val done = TextView(this).apply {
            text = "已完成付款"; textSize = 14f; setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                Toast.makeText(this@CheckoutActivity, "正在确认付款结果…", Toast.LENGTH_SHORT).show()
                finishWithResult()
            }
        }
        bar.addView(back); bar.addView(titleView); bar.addView(done)
        return bar
    }

    private fun showError(msg: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        box.addView(TextView(this).apply {
            text = msg; textSize = 14f; setTextColor(sub); gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(12))
        })
        box.addView(TextView(this).apply {
            text = "重新加载"; textSize = 15f; setTextColor(accent); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(Color.WHITE); setStroke(dp(1), accent)
            }
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener {
                container.removeView(box)
                web.loadUrl(lastGoodUrl, mapOf("Cache-Control" to "no-cache"))
            }
        })
        container.addView(box)
    }

    /** 覆盖 Cloudflare / 源站错误页：用户只看到中性提示，看不到原生 5xx 页或任何内部域名。 */
    private fun showCloudError() {
        if (cloudErrorShown) return
        cloudErrorShown = true
        showError("支付服务暂时不可用，请稍后重试")
    }

    /** 检测当前页是否为 Cloudflare 5xx / 源站证书错误页（命中 body 文本特征串即覆盖）。 */
    private fun detectCloudError(view: WebView) {
        runCatching {
            view.evaluateJavascript(
                "(function(){try{var t=document.body?document.body.innerText||'':'';" +
                "return /cloudflare|error 5\\d\\d|ray id|回源失败|origin (ca )?certificate" +
                "|52[1-5]|连接.*失败|无法访问此网站/i.test(t);}catch(e){return false;}})()",
                android.webkit.ValueCallback<String> { v -> if (v == "true") showCloudError() }
            )
        }.onFailure { Log.w(TAG, "cloud error detect failed: ${it.message}") }
    }

    private fun goBack() {
        // 有弹窗（二维码 / 3DS）先关弹窗，否则回退网页，最后才退出
        if (container.childCount > 1) {
            val popup = container.getChildAt(container.childCount - 1)
            container.removeView(popup)
            (popup as? WebView)?.destroy()
            return
        }
        if (::web.isInitialized && web.canGoBack()) { web.goBack(); return }
        finishWithResult()
    }

    private fun finishWithResult() {
        if (isFinishing || isDestroyed) return
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ACTIVATED, activated))
        finish()
    }

    private fun fallbackToBrowser(url: String) {
        Toast.makeText(this, "无法在 App 内打开支付页，改用浏览器", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开支付页：${e.message}", Toast.LENGTH_LONG).show()
        }
        setResult(Activity.RESULT_OK)
        finish()
    }

    // -------------------------------------------------------------- WebView

    /**
     * 禁滚动的 WebView：收银台页面的 CSP（style-src 'self'）会拦掉注入的 <style>，
     * CSS 侧锁不死滚动，所以滚动直接在 View 层禁掉 —— 吞掉 MOVE（滑动/惯性）和
     * 滚轮事件，DOWN/UP 放行所以按钮、输入框照常点。
     *
     * 是否锁由页面回报（[pageScrollLock]）：内容装得下一屏时锁死（品牌页脚在底部，
     * 锁在顶部就永远看不见）；内容超一屏（支付表单那一步）时放开，否则够不到
     * 下面的支付按钮 —— 此时页面上已无任何 Creem 节点，滚到底也看不见。
     */
    private inner class NoScrollWebView(ctx: android.content.Context) : WebView(ctx) {
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (pageScrollLock && ev.actionMasked == MotionEvent.ACTION_MOVE) return true
            return super.onTouchEvent(ev)
        }

        override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
            if (pageScrollLock && ev.actionMasked == MotionEvent.ACTION_SCROLL) return true
            return super.onGenericMotionEvent(ev)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (pageScrollLock && (
                action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            ) return true
            return super.performAccessibilityAction(action, arguments)
        }
    }

    /** JS 把「内容是否装得下一屏」回报上来，View 层据此决定锁不锁滚动。 */
    private inner class ScrollLockBridge {
        @android.webkit.JavascriptInterface
        fun setLock(v: Boolean) { pageScrollLock = v }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = NoScrollWebView(this).apply {
        setBackgroundColor(Color.WHITE)
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        // 禁滚动：不留滚动条、不留回弹，配合注入的 overflow:hidden
        overScrollMode = View.OVER_SCROLL_NEVER
        val s = settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.textZoom = 100
        s.allowFileAccess = false
        s.setGeolocationEnabled(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // 一次性私有会话：任何层级的缓存都会导致下一位买家串号
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        // 去掉 WebView 默认的 "; wv)" 标记，避免个别支付页按 WebView 分支降级渲染
        s.userAgentString = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; Mobile) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        runCatching { CookieManager.getInstance().setAcceptCookie(true) }
        runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(this, true) }
        addJavascriptInterface(ScrollLockBridge(), "__wa")
        webViewClient = makeClient()
        webChromeClient = makeChromeClient()
    }

    /**
     * WebView 的窗口尺寸：整体上移 [TOP_CROP_DP]、底边再收 [BOTTOM_CROP_DP]，
     * 让页面边缘的品牌区落在容器可视区之外（容器 clipChildren 负责裁掉）。
     * [WINDOW_HEIGHT_DP] > 0 时不再撑满，而是固定高度 —— 用来控制「窗口高度」。
     */
    private fun windowLayoutParams(): FrameLayout.LayoutParams {
        val topCrop = dp(TOP_CROP_DP)
        val bottomCrop = dp(BOTTOM_CROP_DP)
        val h = if (WINDOW_HEIGHT_DP > 0) dp(WINDOW_HEIGHT_DP) + topCrop + bottomCrop
        else ViewGroup.LayoutParams.MATCH_PARENT
        return FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply {
            topMargin = -topCrop
            bottomMargin = -bottomCrop
        }
    }

    /** 藏品牌 + 禁滚动。每次页面加载完都打一遍（SPA 内部跳转靠页面里的 MutationObserver 续命）。 */
    private fun injectBrandGuard(view: WebView) {
        runCatching { view.evaluateJavascript(HIDE_BRAND_JS, android.webkit.ValueCallback<String> { }) }
            .onFailure { Log.w(TAG, "brand guard inject failed: ${it.message}") }
    }

    private fun makeClient(): WebViewClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // 子帧（卡组织 iframe、3DS iframe）一律放行，只拦主帧导航
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            Log.i(TAG, "nav ${url.take(160)}")
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                // http(s) 一律留在 App 内；只有明确是「付款已结束」的落地页才收尾
                if (looksCompleted(url)) {
                    Log.i(TAG, "checkout finished -> ${url.take(120)}")
                    finishWithResult()
                    return true
                }
                return false
            }
            return handleExternalScheme(url)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            pageScrollLock = false // 新页面先放开，等注入脚本回报后再决定
            if (view === web) {
                progress.visibility = View.VISIBLE
                cloudErrorShown = false // 新页面（含重试）重置错误覆盖层
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            injectBrandGuard(view)
            if (view !== web) return
            progress.visibility = View.GONE
            lastGoodUrl = url
            // 兜底：万一底层仍是 Cloudflare 5xx / 源站错误页（直连后理论上不会），
            // 盖一层友好提示，不把原生错误页/内部域名暴露给用户。
            detectCloudError(view)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            Log.w(TAG, "page error ${error.errorCode}: ${error.description}")
            progress.visibility = View.GONE
            showError("页面加载失败\n${error.description}（${error.errorCode}）")
        }
    }

    private fun makeChromeClient(): WebChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        // 刻意不接管 onReceivedTitle：收银台页面的 <title> 就是 "Creem"，
        // 拿到标题栏上等于把品牌又露了出来。标题固定用我们自己的。

        /** window.open / target=_blank：新建 WebView 叠在容器内，仍然不出 App。 */
        override fun onCreateWindow(
            view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
        ): Boolean {
            val popup = try { createWebView() } catch (e: Throwable) { return false }
            container.addView(popup, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(view: WebView) {
            container.removeView(view)
            runCatching { view.destroy() }
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            val origin = request?.origin?.toString()
            if (request != null && origin != null && origin.startsWith("https://")) {
                request.grant(request.resources)
            } else {
                request?.deny()
            }
        }

        /** 支付页常用 alert 抛错误，不接管的话 WebView 里是静默的，用户只看到白屏。 */
        override fun onJsAlert(
            view: WebView, url: String, message: String, result: android.webkit.JsResult
        ): Boolean {
            AlertDialog.Builder(this@CheckoutActivity)
                .setMessage(message)
                .setPositiveButton("知道了") { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }
    }

    /** 只有这些原生 scheme 才交给外部 App（支付宝/微信/云闪付唤起），http(s) 永不出 App。 */
    private fun handleExternalScheme(url: String): Boolean {
        val scheme = url.substringBefore(':').lowercase()
        if (scheme.isBlank() || scheme !in EXTERNAL_SCHEMES) return false
        return try {
            val intent = if (scheme == "intent") Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.setSelector(null)
            if (intent.resolveActivity(packageManager) == null) {
                Log.w(TAG, "no app for $scheme, keep in webview")
                return false
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "external scheme $scheme failed: ${e.message}")
            false
        }
    }

    /** Creem 付款结束后会跳回 Worker 落地页（非 /buy）或含 success/thank 的页面。 */
    private fun looksCompleted(url: String): Boolean {
        val u = Uri.parse(url)
        val host = u.host ?: return false
        val path = u.path.orEmpty()
        val workerHost = runCatching { Uri.parse(Prefs.WORKER_BASE).host }.getOrNull()
        if (workerHost != null && host.equals(workerHost, true) && !path.startsWith("/buy")) return true
        // 按「词」精确匹配（而不是子串），否则 abandoned 之类的词会被误判成已完成
        val tokens = (path + "?" + u.query.orEmpty()).split(Regex("[^A-Za-z]+"))
        return tokens.any { it.lowercase() in DONE_WORDS }
    }

    // ------------------------------------------------------------ 付款确认

    private fun startPolling() {
        if (pollThread?.isAlive == true) return
        pollThread = Thread {
            val deadline = System.currentTimeMillis() + 30 * 60_000L
            var failures = 0
            while (System.currentTimeMillis() < deadline && !isFinishing && !isDestroyed && !activated) {
                try {
                    val key = LicenseClient.poll(prefs)
                    if (!key.isNullOrBlank()) {
                        LicenseClient.activate(prefs, key, tier)
                        activated = true
                        Log.i(TAG, "license activated in checkout tier=$tier")
                        main.post {
                            Toast.makeText(this, "✓ 激活成功，判断接口已切换到云端", Toast.LENGTH_LONG).show()
                            finishWithResult()
                        }
                        return@Thread
                    }
                    failures = 0
                } catch (e: Exception) {
                    failures++
                    Log.w(TAG, "license poll failed x$failures: ${e.message}")
                }
                try { Thread.sleep(3000) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; start() }
    }

    companion object {
        private const val TAG = "JEVASSIST"

        const val EXTRA_URL = "checkout_url"
        const val EXTRA_TIER = "checkout_tier"
        const val EXTRA_ACTIVATED = "checkout_activated"

        private val EXTERNAL_SCHEMES = setOf(
            "alipay", "alipays", "alipayqr", "alipayss", "weixin", "wechat",
            "uppay", "unionpay", "unionpaywallet", "intent", "market",
            "tel", "sms", "mailto"
        )
        private val DONE_WORDS = setOf(
            "success", "succeeded", "successful", "thank", "thanks", "thankyou",
            "complete", "completed", "paid", "finish", "finished", "done"
        )

        // ---------------------------------------------------- 白牌调参区
        // 真机上看着调这三个值即可，改完直接出包，不用动其它逻辑。

        /** 页面整体上移多少 dp（把顶部品牌条顶出可视区）。 */
        private const val TOP_CROP_DP = 10

        /** 底边再收多少 dp：底部还有「Powered by Creem」时把它也切掉。 */
        private const val BOTTOM_CROP_DP = 0

        /** 窗口固定高度（dp）。0 = 撑满剩余空间；>0 时按这个高度开窗（控制窗口高度）。 */
        private const val WINDOW_HEIGHT_DP = 0

        /** 隐藏品牌节点 + 禁止页面滚动的注入脚本。 */
        private const val HIDE_BRAND_JS = """
(function () {
  // 可交互元素只按文案判定，避免把「Pay」按钮误杀（它的 class 里可能带 creem）
  var INTERACTIVE = { BUTTON: 1, INPUT: 1, SELECT: 1, TEXTAREA: 1, LABEL: 1, FORM: 1 };
  var timer = null;
  function attrOf(e, n) { try { return (e.getAttribute(n) || '') + ' '; } catch (err) { return ''; } }
  function sweep() {
    var els = document.querySelectorAll('*');
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      if (e.__waHidden) continue;
      var tag = (e.tagName || '').toUpperCase();
      // 只看叶子节点的文本，避免把整棵 body 干掉
      var own = (e.children && e.children.length === 0)
        ? String(e.textContent || '').toLowerCase() : '';
      var content = (attrOf(e, 'alt') + attrOf(e, 'aria-label') + attrOf(e, 'src') +
                     attrOf(e, 'href') + attrOf(e, 'title')).toLowerCase();
      var cls = (attrOf(e, 'class') + attrOf(e, 'id')).toLowerCase();
      var hit = own.indexOf('creem') >= 0 || content.indexOf('creem') >= 0 ||
                (cls.indexOf('creem') >= 0 && !INTERACTIVE[tag]);
      if (hit) {
        try {
          // 命中的是页脚里的链接/logo 时，把整个 <footer> 一起收掉，
          // 免得留下「Merchant of Record services by」这种孤零零的半句
          var host = (e.closest && e.closest('footer')) || e;
          host.style.setProperty('display', 'none', 'important');
          host.__waHidden = 1;
          var sub = host.querySelectorAll ? host.querySelectorAll('*') : [];
          for (var j = 0; j < sub.length; j++) sub[j].__waHidden = 1;
        } catch (err) {}
      }
    }
  }
  // 滚动锁走 CSSOM（页面的 CSP 禁 <style> 注入，但管不着 JS 改 style），
  // 同时把「是否装得下一屏」回报给原生层，由 WebView 在 View 层硬锁滑动。
  // 内容超一屏时不锁 —— 那一步是支付表单，锁死会够不到支付按钮；
  // 此时页面上已无任何 Creem 节点，滚到底也看不见。
  function lockScroll() {
    var fits = true;
    try {
      fits = document.documentElement.scrollHeight <= (window.innerHeight + 12);
      var v = fits ? 'hidden' : 'visible';
      document.documentElement.style.setProperty('overflow', v, 'important');
      if (document.body) document.body.style.setProperty('overflow', v, 'important');
      document.documentElement.style.setProperty('overscroll-behavior', 'none', 'important');
    } catch (e) {}
    try { if (window.__wa && window.__wa.setLock) window.__wa.setLock(fits); } catch (e) {}
  }
  function run() {
    sweep();
    lockScroll();
    // 页面 <title> 就是 "Creem"，改掉防止从任何地方漏出去
    try { if (document.title !== '支付') document.title = '支付'; } catch (e) {}
  }
  run();
  try {
    new MutationObserver(function () {
      if (timer) return;
      timer = setTimeout(function () { timer = null; run(); }, 150);
    }).observe(document.documentElement || document.body, { childList: true, subtree: true });
  } catch (e) {}
})();
"""
    }
}
