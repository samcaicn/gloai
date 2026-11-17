package com.wangzan101.mika.cap.web

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.content.Context
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import android.os.SystemClock
import android.util.Log
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.core.resource.ResourceScope
import com.wangzan101.mika.takeover.HandoverActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import com.wangzan101.mika.core.net.NetProxy
import java.io.File
import com.wangzan101.mika.core.workspace.SandboxTree

/**
 * 一个任务的内置浏览器:自己的一块后台网页显示、一池标签、网页端口与隔离脚本引擎。
 * 每个任务一套(并行时各自的当前标签都挂在自己的显示上照常渲染)。在主线程上建和关。
 */
class WebStack private constructor(
    private val context: Context,
    val taskId: String,
    val host: WebHost,
    val pool: WebTabPool,
    workspace: Workspace?,
    /** 任务结束后留下一页时交给它;为 null 时不能留页(web_keep_open 报不可用)。 */
    private val keeper: PageKeeper?,
) {
    /**
     * 本对话的工作区:[dir] 是宿主目录(work/<对话>,不存在就建,不要求沙箱已安装);
     * 下载写进去、上传从里面读,都经 [gate](打开一次、按句柄核实)。
     */
    class Workspace(val dir: File, val gate: WorkspaceFileGate, val tree: SandboxTree)

    // 下载在后台进行;任务结束一并取消,没下完的标 cancelled 并删掉半截文件
    private val transfers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 下载与上传;没有工作区时为空(两者都报不可用)。 */
    val files: WebFiles? = workspace?.let { w ->
        val downloads = WebDownloads(w.dir, w.gate, w.tree, NetProxy.shared.client(), cookies = { url -> WebTab.cookieManager().getCookie(url) }, scope = transfers)
        WebFiles(context, w.dir, downloads, w.gate, transfers)
    }

    val screen: WebScreen = WebScreen(pool, taskId, host, SystemClock::uptimeMillis, files, keptNow = keeper?.let { k -> { k.page.value } })

    /** 留下的那一页记在谁名下(对话、运行记录、原话);任务结束时取。没给时不留页。 */
    @Volatile var keepOrigin: (() -> KeptOrigin)? = null

    init {
        files?.let { f -> pool.downloadHandler = { tab, url, ua, cd, mime -> onDownload(f, tab, url, ua, cd, mime) } }
    }

    // 页面触发的下载:http(s) 直接取,blob: 让页面脚本读出来,data: 直接解;别的(如 intent:)不理。文件只保存,不打开。
    // 三种都先问页面链接上给的名字(<a download>):下载监听只带响应头里的名字,blob:/data: 连响应头都没有
    private fun onDownload(f: WebFiles, tab: WebTab, url: String, ua: String, cd: String?, mime: String?) {
        val d = f.downloads
        val pageName: suspend () -> String? = { screen.downloadName(tab, url) }
        when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> {
                val referer = tab.view.url
                // 自己因 TLS 连不上时改由页面用浏览器的网络栈去取:只给和当前页面同源的地址
                val viaPage: (suspend (Long) -> Pair<ByteArray, String?>?)? = if (!sameOrigin(url, referer)) null else { max ->
                    (screen.readBlob(tab, url, max) as? PageScript.Blob.Ok)?.let { it.bytes to it.mime }
                }
                d.withPageName(pageName) { name -> d.start(url, ua, cd, mime, referer, name, viaPage) }
            }
            url.startsWith("blob:", true) -> d.withPageName(pageName) { name ->
                d.startFrom(url, cd, mime, name) { max ->
                    when (val b = screen.readBlob(tab, url, max)) {
                        is PageScript.Blob.Ok -> b.bytes to b.mime
                        is PageScript.Blob.Failed -> throw WebDownloads.BlobRefused(b.error)
                    }
                }
            }
            url.startsWith("data:", true) -> d.withPageName(pageName) { name -> d.dataUrl(url, cd, name) }
            else -> Log.i(TAG, "download with an unsupported address ignored")
        }
    }

    /** 两个网址同源(协议、主机、端口都一样);有一个没有为 false。 */
    private fun sameOrigin(a: String, b: String?): Boolean {
        fun origin(u: String?) = u?.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }
        val x = origin(a) ?: return false
        return x == origin(b)
    }

    /** 正交给用户的标签(接管中);没有为 null。 */
    @Volatile private var handedTab: WebTab? = null

    /** 页面此刻在用户手上(接管中)。 */
    val handedOver: Boolean get() = handedTab != null

    /** 这个任务有没有当前标签(当前标签挂在网页显示上,看得见);只在后台临时标签里读过网页时没有。 */
    val hasVisibleTab: Boolean get() = pool.current(taskId) != null

    /** 当前标签是借来的、用户看着的那一页(见 [borrow]):它不在网页显示上,画中画不放画面。 */
    val onBorrowedTab: Boolean get() = pool.current(taskId)?.let { pool.isExternal(it.id) } == true

    // ---------- 借来的标签 ----------

    /**
     * 借来用户在浏览器页上看着的标签 [tab],当成这个任务的当前标签(见 [WebTabPool.attachExternal]);生死仍归原来的主人。
     * 借不进来(已关、崩了)返回 false。主线程。
     */
    fun borrow(tab: WebTab): Boolean = pool.attachExternal(taskId, tab).also { if (it) Log.i(TAG, "task $taskId borrowed tab ${tab.id}") }

    /** 还回借来的标签 [tab](任务结束、用户那边不再给了);不是借来的不动。主线程。 */
    fun giveBack(tab: WebTab) {
        if (pool.detachExternal(tab.id)) Log.i(TAG, "task $taskId returned tab ${tab.id}")
    }

    /**
     * 借来的标签换成了 [next](用户的页面开了或关了弹出的窗口,看得见的成了另一个):原来那个还回去,新的借进来;
     * 原来那个是当前标签时新的也是。主线程。
     */
    fun swapBorrowed(prev: WebTab, next: WebTab) {
        val wasCurrent = pool.current(taskId) === prev
        giveBack(prev)
        if (wasCurrent || pool.current(taskId) == null) borrow(next)
    }

    /**
     * 用户关掉了借出页面所在的浏览器页,标签交给这个任务([adopt]):还回借的那一份,在池里收下同一个 WebView 当普通标签,
     * 挂到网页显示上照常运行,页面状态(滚动、表单、单页应用的状态)不丢;任务结束时随别的标签一起关。收不下(已关、崩了)返回 false,
     * 调用方自己销毁。主线程。
     */
    fun adopt(tab: WebTab): Boolean {
        val wasCurrent = pool.current(taskId) === tab
        giveBack(tab)
        return pool.adopt(taskId, tab, current = wasCurrent).also { if (it) Log.i(TAG, "task $taskId adopted tab ${tab.id}") }
    }

    /**
     * 接管:把当前标签锁住,拿到主屏的接管界面里。没有当前标签、或界面打不开时返回 false。
     * [actions] 把界面上的按钮交给任务(处理好了、我自己做完了、取消、先放着);[currentKey] 是任务此刻的等待编号。
     */
    fun showForTakeover(reason: String, message: String?, waitKey: Int, actions: (WebHandover.Action) -> Unit, currentKey: () -> Int?): Boolean {
        val tab = pool.current(taskId) ?: return false
        pool.lock(tab.id)
        // 借来的标签本来就在用户眼前(浏览器页):不挪,锁住等用户在那里处理好了交回
        val shown = if (pool.isExternal(tab.id)) !tab.destroyed
            else WebHandover.show(context, tab, host, reason, message, waitKey, taskId, actions, currentKey)
        if (shown) handedTab = tab else pool.unlock(tab.id)
        // 人机验证或访问拦截真把任务拦住了(要交给用户):把任务所在的网站记为碰到过拦截
        if (shown && reason in SiteRegistry.CHALLENGE_KINDS) {
            tab.view.url?.takeIf { it.startsWith("http") }?.let(WebUrlPolicy::host)?.let { WebSites.registry(context).challenged(it, reason) }
        }
        return shown
    }

    /** 交回:结束接管界面、标签放回后台并解锁;[loggedIn] 时页面看得出已登录就把网站记为已登录。不在接管中为 null。 */
    fun returnFromTakeover(loggedIn: Boolean): HandoverActivity? {
        val tab = handedTab ?: return null
        handedTab = null
        if (pool.isExternal(tab.id)) {
            pool.unlock(tab.id)
            if (loggedIn && !tab.destroyed) LoginProbe.probe(tab) { host, state -> if (state == LoginState.SignedIn) WebSites.registry(context).markLoggedIn(host) }
            return HandoverActivity(borrowedTouches?.invoke(tab) ?: 0, tab.view.title?.takeIf { it.isNotBlank() })
        }
        val r = WebHandover.end(loggedIn, WebSites.registry(context), tab)
        pool.unlock(tab.id)
        return r
    }

    /** 借来的标签上用户摸了几下(接管交回时记进结果);由借出的一方给。 */
    @Volatile var borrowedTouches: ((WebTab) -> Int)? = null

    /**
     * 任务结束:还在接管就先收回,再关掉这个任务的标签和显示。模型用 web_keep_open 标过一页时,那一页连同这块显示交给 [keeper]
     * 留在后台接着运行(显示就不关了);交不出(那一页已关、崩了)时照常全关。
     */
    fun close() {
        runCatching { returnFromTakeover(loggedIn = false) }.onFailure { Log.w(TAG, "takeover not returned cleanly", it) }
        // 先从池里拿出要留的那一页,别的标签关掉时就不会把别的页挂到显示上顶掉它
        val kept = runCatching { pool.closeKeeping(taskId, screen.keepMark?.tabId) }.onFailure { Log.w(TAG, "kept page not released", it) }.getOrNull()
        runCatching { pool.close() }
        val handed = kept != null && runCatching { handOff(kept) }.onFailure { Log.w(TAG, "kept page not handed off", it) }.getOrDefault(false)
        if (kept != null && !handed) runCatching { kept.destroy() }
        if (!handed) runCatching { host.close() }
        runCatching { files?.close() }
        transfers.cancel()
    }

    /** 把留下的那一页交给 [keeper];收下了返回 true。 */
    private fun handOff(tab: WebTab): Boolean {
        val k = keeper ?: return false
        val origin = keepOrigin?.invoke() ?: return false
        return k.keep(tab, host, origin) != null
    }

    companion object {
        private const val TAG = "MikaWebStack"

        /** 并行子任务各用一套后台浏览器，第一次导航或读网址时才建，子任务结束即关。 */
        internal fun childPort(
            context: Context, childId: String, widthPx: Int, heightPx: Int, densityDpi: Int,
            resources: ResourceScope, assetLoaders: List<WebViewAssetLoader> = emptyList(),
        ): LazyWebPort = LazyWebPort {
            resources.acquire(Dispatchers.Main, WebStack::close) {
                create(context, childId, widthPx, heightPx, densityDpi, assetLoaders, keeper = null)
            }.screen
        }

        /** 建一套;系统 WebView 不可用等原因建不起来时抛出,调用方就只给手机界面。主线程调用。 */
        fun create(
            context: Context, taskId: String, widthPx: Int, heightPx: Int, densityDpi: Int,
            assetLoaders: List<WebViewAssetLoader> = emptyList(),
            workspace: Workspace? = null,
            keeper: PageKeeper? = WebOwner.of(context).pageKeeper,
        ): WebStack {
            val host = WebHost(context, widthPx, heightPx, densityDpi)
            return try {
                val pool = WebTabPool(context, host, assetLoaders = assetLoaders, registry = WebSites.registry(context))
                WebStack(context.applicationContext, taskId, host, pool, workspace, keeper)
            } catch (e: Exception) {
                host.close()
                throw e
            }
        }
    }
}
