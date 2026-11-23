package com.wangzan101.mika.browser

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewDatabase
import com.wangzan101.mika.cap.web.LoginProbe
import com.wangzan101.mika.cap.web.LoginState
import com.wangzan101.mika.cap.web.SiteRegistry
import com.wangzan101.mika.cap.web.WebSites
import com.wangzan101.mika.cap.web.WebTab
import com.wangzan101.mika.core.cap.web.ExternalLink
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.core.screen.uistate.PageDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * 浏览器页此刻打开的网页。[desktop]:标签此刻用的是电脑版网页;[loginFor]:这次是「给 Mika 登录」,为要登录的网站
 * (照 Mika 办事时的身份打开,见 [BrowserIdentity]),平常浏览为 null。
 */
data class BrowserPage(
    val url: String,
    val title: String,
    val progress: Int,
    val loading: Boolean,
    val canBack: Boolean,
    val canForward: Boolean,
    val desktop: Boolean = false,
    val loginFor: String? = null,
)

/** 用户浏览器里网页弹出的文件选择框，选好的 URI 只交给发起请求的标签。 */
class FilePickRequest(val tab: WebTab, val callback: ValueCallback<Array<Uri>>, val params: WebChromeClient.FileChooserParams) {
    val id: String = UUID.randomUUID().toString()
    @Volatile var started = false
}

/** 标签页列表里的一项:[active] 是此刻看着的那一个。 */
data class BrowserTab(val id: String, val title: String, val url: String, val host: String?, val active: Boolean)

/** 全屏视频:网页给的视图和它要求的回调。 */
class FullscreenVideo(val view: View, val callback: WebChromeClient.CustomViewCallback)

/** 页内查找:是否开着、搜的词、共几处、现在是第几处(都从 1 数)。 */
data class FindState(val active: Boolean = false, val query: String = "", val matches: Int = 0, val index: Int = 0)

/** WebView 给的当前匹配序号从 0 数;界面显示从 1 数,没有匹配时为 0。 */
internal fun findMatchIndex(active: Int, matches: Int): Int =
    if (matches > 0) (active + 1).coerceIn(1, matches) else 0

/**
 * 用户自己用的浏览器(侧栏「浏览器」):一个看得见的 [WebTab],和 Agent 的标签同一个网页配置(Cookie、网页存储)。
 * 在这里登录了,Mika 办事时也是登录的。用哪一版网页由 [BrowserIdentity] 定:平常默认手机版、按网站可换成电脑版
 * (记在 [modes]),「给 Mika 登录」时照 Mika 办事时的那一版;规则只装在这个标签上(WebDevtools.setModePolicy),
 * Agent 的标签不受影响。
 *
 * 不进任何任务的标签池,也不走 [com.wangzan101.mika.cap.web.WebHandover] 的那一次接管:Agent 的标签、接管照常进行,互不影响。
 * 标签放在进程里(转屏、页面重建时同一个 WebView 接着用),离开浏览器页时关掉。界面不止一个实例时,浏览器页可能同时有几处:
 * 视图挂在哪一处、哪一处走了才关网页,见 [PageHosts]。
 * WebView 的环境是 [MutableContextWrapper]:挂到界面上时换成那个 Activity(下拉框、长按菜单要它),摘下时换回 App。
 * 页面打开前过一遍 [WebUrlPolicy.checkNavigable];访问过的页面记进 [recent];页面停下来后看一眼登录状态,记进站点登记表。
 * 用户在页面上问 Mika 时,看着的这一页借给那个对话的任务当当前网页标签(见 [loans]):页面开关弹窗时借的跟着换,
 * 关掉页面时交给任务接着用、不销毁;用户亲手摸页面([userTouched])时告诉借着它的任务。
 * 只在主线程调用。
 */
class UserBrowser private constructor(private val app: Context) {
    val sites: SiteRegistry = WebSites.registry(app)
    private val dir = File(app.filesDir, "web")
    val recent = RecentPages(dir)
    val favorites = BrowserFavorites(dir)
    val bookmarks = PageBookmarks(dir)
    val looks = SiteLooks(dir)
    /** 用户浏览器的下载:交给系统下载,列表也问系统要。 */
    val downloads = BrowserDownloads(app, dir)
    /** 起始页和地址栏用的搜索引擎;用户可以在起始页换,也可以自己加。 */
    val searchEngine = SearchEngineStore(dir)
    private val modes = UserSiteModes(dir)
    /** 看着的这一页借给任务(见 [PageLoans])。 */
    val loans = PageLoans<WebTab>()
    /** 标签编号:每个都不一样(页面和它弹出的窗口可能先后借给同一个任务)。 */
    private var tabCount = 0

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wrapper = MutableContextWrapper(app)
    /** 普通标签(弹出的窗口不在里面),按打开顺序。 */
    private val openTabs = ArrayList<WebTab>()
    private var tab: WebTab? = null
        set(value) {
            if (field !== value) denyPendingPermissions()
            field = value
        }
    /** 页面弹出的窗口开着时([tab] 是弹窗),打开它的那一页;没有弹窗为 null。 */
    private var opener: WebTab? = null
    private val hosts = PageHosts<ViewGroup, Context>()
    /** 每个标签各自的「给 Mika 登录」网站;平常浏览为 null。 */
    private val loginFor = HashMap<WebTab, String?>()
    /** 每个标签各自的登录探测任务。 */
    private val probes = HashMap<WebTab, Runnable>()

    private val _page = MutableStateFlow<BrowserPage?>(null)
    /** 为 null 时没有打开的网页(显示起始页)。 */
    val page: StateFlow<BrowserPage?> = _page.asStateFlow()

    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    /** 打开的普通标签,按打开顺序;[BrowserTab.active] 是看着的那一个。 */
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _find = MutableStateFlow(FindState())
    /** 页内查找的状态;关闭时为 [FindState.active] = false。 */
    val find: StateFlow<FindState> = _find.asStateFlow()

    private val _fullscreen = MutableStateFlow<FullscreenVideo?>(null)
    /** 正在全屏播放的视频;没有为 null。 */
    val fullscreen: StateFlow<FullscreenVideo?> = _fullscreen.asStateFlow()

    private val _dialog = MutableStateFlow<PageDialog?>(null)
    /** 页面弹出、等用户回话的框。 */
    val dialog: StateFlow<PageDialog?> = _dialog.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** 给用户的一句提示(拦下了什么、页面崩了);显示后调 [noticeShown]。 */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _filePick = MutableStateFlow<FilePickRequest?>(null)
    /** 网页要选文件,等界面弹系统选择器;选好后调 [answerFileChooser]。 */
    val filePick: StateFlow<FilePickRequest?> = _filePick.asStateFlow()

    private val mediaPermissions = BrowserPermissionRequests<PermissionRequest>()
    /** 网页要摄像头、麦克风等,等界面问用户;回答时调 [answerPagePermission]。 */
    internal val pagePermission = mediaPermissions.pending

    private val locationPermissions = BrowserPermissionRequests<GeolocationPermissions.Callback>()
    /** 网页要定位,等界面问用户;回答时调 [answerGeolocation]。 */
    internal val geoPermission = locationPermissions.pending

    /** 系统选择器回来了:交回网页;取消(空)交 null。 */
    fun answerFileChooser(id: String, uris: List<Uri>?) {
        val r = _filePick.takeFileRequest(id, FilePickRequest::id) ?: return
        r.callback.onReceiveValue(uris?.takeIf { tab === r.tab && it.isNotEmpty() }?.toTypedArray())
    }

    /** 用户同意或拒绝网页用摄像头、麦克风。 */
    fun answerPagePermission(id: String, grant: Boolean) {
        val request = mediaPermissions.take(id)?.value ?: return
        if (grant) {
            // 只授摄像头和麦克风,请求里夹带的 MIDI、受保护媒体不放行
            val allowed = request.resources
                .filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
                .toTypedArray()
            request.grant(allowed)
        } else {
            request.deny()
        }
    }

    /** 用户同意或拒绝网页用定位。 */
    fun answerGeolocation(id: String, grant: Boolean) {
        val r = locationPermissions.take(id) ?: return
        r.value.invoke(r.origin, grant, false)
    }

    private fun answerPendingRequests() {
        _filePick.value?.callback?.onReceiveValue(null)
        _filePick.value = null
        denyPendingPermissions()
    }

    private fun denyPendingPermissions() {
        pagePermission.value?.let { answerPagePermission(it.id, false) }
        geoPermission.value?.let { answerGeolocation(it.id, false) }
    }

    /**
     * 打开地址栏或搜索框里的一句话(见 [AddressInput])。打不开时返回给用户看的原因,不动当前页面。
     */
    fun submit(input: String): String? {
        val url = AddressInput.resolve(input, searchEngine.choices.value.selected) ?: return null
        return open(url)
    }

    /** 平常浏览打开网址;打不开时返回原因。 */
    fun open(url: String): String? = load(url, null)

    /** 在新标签打开一页;[select] 为 false 时留在后台,不切过去。打不开时返回原因。 */
    fun openInNewTab(url: String, select: Boolean): String? {
        refusal(url)?.let { return it }
        val prev = tab
        newTab()
        val error = open(url)
        if (error != null) {
            tab?.let { closeTab(it.id) }
            prev?.let { selectTab(it.id) }
            return error
        }
        if (!select && prev != null) selectTab(prev.id)
        return null
    }

    /** 打开常用网站:还没登录的走「给 Mika 登录」,照 Mika 办事时的那一版打开。 */
    fun openFavorite(site: FavoriteSite): String? =
        load("https://${site.host}/", site.host.takeIf { BrowserIdentity.loginFlow(site) })

    /**
     * 当前网站换成电脑版([desktop])或换回手机版,记住,下次也这样;不再算「给 Mika 登录」。
     * 按新的版本重新打开当前页。
     */
    fun setDesktop(desktop: Boolean) {
        val t = tab ?: return
        val url = currentUrl() ?: return
        val host = WebUrlPolicy.host(url) ?: return
        modes.set(host, desktop)
        loginFor[t] = null
        navigate(t, url)
    }

    /** 「给 Mika 登录」此刻要登录的网站;平常浏览为 null。只在主线程调用。 */
    fun loginHost(): String? = tab?.let { loginFor[it] }

    /**
     * 「给 Mika 登录」里点了「登录好了」:看一眼页面。看出还没登录时提示一句;看出已登录、或看不出来(信用户的话)时
     * 把这个网站记成已登录,回到起始页。页面还在加载等看不了时,等 [CONFIRM_WAIT_MS] 后按看不出来算。
     */
    fun confirmLogin() {
        val t = tab ?: return
        val site = loginFor[t] ?: return
        var done = false
        fun finish(state: LoginState) {
            if (done || t !== tab || t.destroyed) return
            done = true
            if (state == LoginState.SignedOut) {
                _notice.value = tr(R.string.browser_not_logged_in)
                return
            }
            sites.markLoggedIn(site)
            currentUrl()?.let(WebUrlPolicy::host)?.takeIf { it != site && BrowserFavorites.siteKey(it) == BrowserFavorites.siteKey(site) }?.let(sites::markLoggedIn)
            WebTab.cookieManager().flush()
            Log.i(TAG, "browser login confirmed for $site ($state)")
            _notice.value = LOGGED_IN_NOTICE
            closeActiveTab()
        }
        val timeout = Runnable { finish(LoginState.Unknown) }
        main.postDelayed(timeout, CONFIRM_WAIT_MS)
        LoginProbe.probe(t) { _, state ->
            main.post {
                main.removeCallbacks(timeout)
                finish(state)
            }
        }
    }

    private fun load(url: String, login: String?): String? {
        refusal(url)?.let { return it }
        stopFind()
        val t = tab ?: newTopTab()
        loginFor[t] = login
        _page.value = BrowserPage(url, "", 0, true, false, false, desktop = !t.devtools.isMobile(t), loginFor = login)
        navigate(t, url)
        return null
    }

    private fun navigate(t: WebTab, url: String) {
        scope.launch {
            // 和 Agent 的标签一样:导航前按这个标签的规则把桌面版或手机版的设置与仿真换好(至多等一小会儿)
            t.devtools.beforeLoad(t, url)
            if (!t.destroyed) t.view.loadUrl(url)
        }
    }

    private fun currentUrl(): String? = tab?.view?.url?.takeIf { it.startsWith("http") } ?: _page.value?.url

    fun back() { tab?.view?.takeIf { it.canGoBack() }?.goBack() }
    fun forward() { tab?.view?.takeIf { it.canGoForward() }?.goForward() }
    fun reload() { tab?.view?.reload() }
    fun stop() {
        tab?.view?.stopLoading()
        refresh()
    }

    /** 系统返回键:网页能后退就后退,不能时开着弹窗就关掉弹窗,否则关掉当前标签。没有打开的网页时返回 false。 */
    fun systemBack(): Boolean {
        val v = tab?.view ?: return false
        if (v.canGoBack()) v.goBack() else if (opener != null) closePopup() else closeActiveTab()
        return true
    }

    /** 关掉所有标签回到起始页。借给任务的标签交给任务接着用(任务收下了就不销毁)。 */
    fun closePage() {
        answerPendingRequests()
        stopFind()
        clearFullscreen()
        probes.values.forEach(main::removeCallbacks)
        probes.clear()
        loginFor.clear()
        opener = null
        tab?.takeIf { it !in openTabs }?.let { popup ->
            if (!loans.closing(popup)) popup.destroy() else handedOff(popup)
        }
        val all = openTabs.toList()
        openTabs.clear()
        all.forEach { t ->
            if (!loans.closing(t)) t.destroy() else handedOff(t)
        }
        loans.withdraw()
        tab = null
        _page.value = null
        _dialog.value = null
        publishTabs()
    }

    /** 开一个空标签并显示起始页。 */
    fun newTab() {
        if (opener != null) closePopup()
        stopFind()
        clearFullscreen()
        newTopTab()
        _page.value = null
        _dialog.value = null
    }

    /** 换到标签 [id];找不到不动。 */
    fun selectTab(id: String) {
        val target = openTabs.firstOrNull { it.id == id } ?: return
        if (target === tab && opener == null) return
        if (opener != null) closePopup()
        if (target === tab) return
        stopFind()
        exitFullscreen()
        val prev = tab
        if (prev != null && loans.closing(prev)) {
            openTabs.remove(prev)
            loginFor.remove(prev)
            probes.remove(prev)?.let(main::removeCallbacks)
            handedOff(prev)
        }
        tab = target
        showActive()
        publishTabs()
    }

    /** 关掉标签 [id];关的是看着的,就换到旁边一个,没有就回起始页。 */
    fun closeTab(id: String) {
        val t = openTabs.firstOrNull { it.id == id } ?: return
        // 关的是弹窗的父页:先把弹窗收起来,否则弹窗关掉时会回到一个已经没了的页面
        if (t === opener) closePopup()
        if (t === tab) denyPendingPermissions()
        val index = openTabs.indexOf(t)
        openTabs.removeAt(index)
        loginFor.remove(t)
        probes.remove(t)?.let(main::removeCallbacks)
        // 只借出/看着的标签才走借出逻辑;关后台标签不该把当前页答应借出的那份作废
        val handed = if (t === tab || loans.current?.tab === t) loans.closing(t) else false
        if (!handed) t.destroy() else handedOff(t)
        if (t === tab) {
            stopFind()
            clearFullscreen()
            tab = openTabs.getOrNull(index) ?: openTabs.lastOrNull()
            if (tab == null) {
                _page.value = null
                _dialog.value = null
                wrapper.baseContext = app
            } else {
                showActive()
            }
        }
        publishTabs()
    }

    /** 关掉看着的标签(有弹窗时先关弹窗)。 */
    fun closeActiveTab() {
        if (opener != null) {
            closePopup()
            return
        }
        tab?.let { closeTab(it.id) } ?: run {
            _page.value = null
            publishTabs()
        }
    }

    private fun newTopTab(): WebTab = createTab().also {
        openTabs += it
        tab = it
        loginFor[it] = null
        place()
        publishTabs()
    }

    private fun publishTabs() {
        val active = tab
        _tabs.value = openTabs.map { t ->
            val url = t.view.url?.takeIf { it.startsWith("http") }.orEmpty()
            val title = t.view.title?.takeIf { it.isNotBlank() && it != url } ?: url
            BrowserTab(t.id, title, url, WebUrlPolicy.host(url), t === active)
        }
    }

    /** 把看着的标签挂到界面上;新开还没加载的标签回到起始页。 */
    private fun showActive() {
        val t = tab ?: run {
            _page.value = null
            return
        }
        place()
        val url = t.view.url
        if (url == null || url.isEmpty() || url == "about:blank") _page.value = null else refresh()
        _dialog.value = t.dialogs.current
    }

    // ---------- 页内查找 ----------

    /** 打开页内查找条。 */
    fun startFind() {
        if (tab == null) return
        _find.value = FindState(active = true)
    }

    fun setFindQuery(query: String) {
        val v = tab?.view ?: return
        val q = query.trim()
        if (q.isEmpty()) {
            v.clearMatches()
            _find.value = FindState(active = true)
        } else {
            v.findAllAsync(q)
            _find.value = FindState(active = true, query = query, matches = 0, index = 0)
        }
    }

    fun findNext(forward: Boolean) {
        tab?.view?.findNext(forward)
    }

    fun stopFind() {
        tab?.view?.clearMatches()
        _find.value = FindState()
    }

    // ---------- 全屏视频 ----------

    /** 退出全屏,让网页恢复内嵌播放。 */
    fun exitFullscreen() {
        val f = _fullscreen.value ?: return
        _fullscreen.value = null
        f.callback.onCustomViewHidden()
    }

    private fun clearFullscreen() {
        val f = _fullscreen.value ?: return
        _fullscreen.value = null
        (f.view.parent as? ViewGroup)?.removeView(f.view)
    }

    // ---------- 清除浏览数据 ----------

    /** 清掉选中的浏览数据。网站数据会退出所有网站,包括 Mika 办事时用的那些。 */
    fun clearBrowsingData(history: Boolean, siteData: Boolean, cache: Boolean) {
        if (history) recent.clear()
        if (siteData) {
            runCatching {
                WebTab.cookieManager().removeAllCookies(null)
                WebTab.cookieManager().flush()
                WebTab.webStorage().deleteAllData()
                WebViewDatabase.getInstance(app).clearHttpAuthUsernamePassword()
                sites.clear()
            }
            loginFor.keys.forEach { loginFor[it] = null }
            refresh()
        }
        if (cache) {
            openTabs.forEach { runCatching { it.view.clearCache(true) } }
            tab?.let { runCatching { it.view.clearCache(true) } }
        }
    }

    // 交给任务的标签:不再是这里的(视图已由任务挂到它的网页显示上),环境换回 App(不留着界面)
    private fun handedOff(t: WebTab) {
        wrapper.baseContext = app
        Log.i(TAG, "browser page ${t.id} handed to the task that borrowed it")
    }

    // ---------- 在这一页上问 Mika ----------

    /** 此刻看着的这一页(给 Mika 的说明用);没有打开的网页为 null。 */
    fun pageInfo(): com.wangzan101.mika.core.agent.AttachedPage.Page? {
        val url = currentUrl()?.takeIf { it.startsWith("http") } ?: return null
        val title = tab?.view?.title?.takeIf { it != url }.orEmpty().ifEmpty { _page.value?.title.orEmpty() }
        return com.wangzan101.mika.core.agent.AttachedPage.Page(url, title)
    }

    /** 读用户在页面上选中的字,交给 [onText](没选、读不到为 null)。 */
    fun readSelection(onText: (String?) -> Unit) {
        val t = tab ?: return onText(null)
        t.view.evaluateJavascript(SELECTION_SCRIPT) { raw ->
            val text = runCatching { org.json.JSONArray("[$raw]").optString(0) }.getOrNull()
            onText(text?.takeIf { raw != "null" && it.isNotBlank() })
        }
    }

    /** 用户在这一页上问了,页面借给对话 [conversationId] 接下来开跑的任务。 */
    fun lend(conversationId: String) = loans.offer(conversationId)

    /** 对话 [conversationId] 的任务 [runKey] 开跑时来取答应借给它的页面;没答应过、页面没了、已借出时为 null。 */
    fun claim(conversationId: String, runKey: String): PageLoans.Loan<WebTab>? =
        loans.claim(conversationId, runKey, tab?.takeIf { !it.destroyed && !it.crashed })

    /** 用户亲手摸了看着的页面(真实的触摸,不是 Mika 自己发的输入)。 */
    fun userTouched() {
        tab?.let(loans::touched)
    }

    /** 回答页面弹框。 */
    fun answerDialog(accept: Boolean, text: String?) {
        tab?.dialogs?.answer(accept, text)
        _dialog.value = tab?.dialogs?.current
    }

    fun noticeShown() {
        _notice.value = null
    }

    /** 一处浏览器页(界面的一个实例上)要用的位置;交给 [openHost]、[setContainer]、[hostStarted]、[closeHost]。 */
    fun newHost(activity: Context): PageHosts.Host<ViewGroup, Context> = PageHosts.Host(activity)

    /** 这一处进了浏览器页。 */
    fun openHost(host: PageHosts.Host<ViewGroup, Context>) {
        hosts.open(host)
        place()
    }

    /** 这一处回到了前台:网页视图挂到它这里。 */
    fun hostStarted(host: PageHosts.Host<ViewGroup, Context>) {
        hosts.started(host)
        place()
    }

    /** 这一处给网页留的容器有了([container])或没了(null)。 */
    fun setContainer(host: PageHosts.Host<ViewGroup, Context>, container: ViewGroup?) {
        val old = host.container
        hosts.setContainer(host, container)
        if (old !== container) old?.removeAllViews()
        place()
    }

    /**
     * 这一处离开了浏览器页。是最后一处、又不是转屏([keepPage] 为 false)时关掉网页;
     * 不是最后一处时网页留给别处(视图换到别处去)。
     */
    fun closeHost(host: PageHosts.Host<ViewGroup, Context>, keepPage: Boolean) {
        host.container?.removeAllViews()
        if (hosts.close(host) && !keepPage) closePage() else place()
    }

    /**
     * 把网页视图挂到该挂的那一处([PageHosts.target]),环境换成那一处的界面;没有能挂的地方时摘下,环境换回 App
     * (不留着旧界面)。
     */
    private fun place() {
        val v = tab?.view
        val target = hosts.target()
        val container = target?.container
        if (v == null || container == null) {
            v?.let { (it.parent as? ViewGroup)?.removeView(it) }
            wrapper.baseContext = app
            return
        }
        if (v.parent === container) return
        (v.parent as? ViewGroup)?.removeView(v)
        wrapper.baseContext = target.activity
        container.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun refusal(url: String): String? {
        WebUrlPolicy.checkNavigable(url) ?: return null
        return if (WebUrlPolicy.external(url) != null) tr(R.string.browser_external_link) else tr(R.string.browser_bad_scheme)
    }

    private fun createTab(): WebTab = WebTab(wrapper, "browser${++tabCount}", OWNER).also { t ->
        t.view.settings.apply {
            builtInZoomControls = true
            displayZoomControls = false
        }
        // 用户亲手看、亲手点:电脑版也不定死缩放,能双指放大
        t.devtools.setHandOperated(t)
        t.devtools.setModePolicy(t) { host ->
            BrowserIdentity.mobile(loginForMika = loginFor[t] != null, agentMobile = t.devtools.agentWantsMobile(host), userDesktop = modes.isDesktop(host))
        }
        t.view.setFindListener { active, matches, _ ->
            if (t === tab) {
                // activeMatchOrdinal 从 0 数;界面显示从 1 数,0 处时显示 0
                _find.value = _find.value.copy(
                    matches = matches.coerceAtLeast(0),
                    index = findMatchIndex(active, matches),
                )
            }
        }
        t.listener = listener
    }

    private fun refresh() {
        val t = tab ?: return
        val v = t.view
        val url = v.url?.takeIf { it.isNotEmpty() && it != "about:blank" } ?: _page.value?.url ?: return
        _page.value = BrowserPage(
            url, v.title.orEmpty().takeIf { it != url }.orEmpty(), t.progress, t.loading, v.canGoBack(), v.canGoForward(),
            desktop = !t.devtools.isMobile(t), loginFor = loginFor[t],
        )
    }

    private val listener = object : WebTab.Listener {
        override fun onPageChanged(tab: WebTab) {
            publishTabs()
            val url = tab.view.url
            if (url != null && url.startsWith("http")) tab.view.title?.let { recent.retitle(url, it) }
            if (tab === this@UserBrowser.tab) refresh()
        }

        override fun onPageFinished(tab: WebTab) {
            publishTabs()
            val url = tab.view.url ?: return
            if (!url.startsWith("http")) return
            recent.add(url, tab.view.title?.takeIf { it != url })
            bookmarks.retitle(url, tab.view.title.orEmpty())
            WebTab.cookieManager().flush()
            readName(tab, url)
            scheduleProbe(tab, 0)
            if (tab === this@UserBrowser.tab) refresh()
        }

        override fun onIcon(tab: WebTab, icon: Bitmap) {
            val host = tab.view.url?.takeIf { it.startsWith("http") }?.let(WebUrlPolicy::host) ?: return
            if (icon.width < MIN_ICON_PX || icon.height < MIN_ICON_PX) return
            val side = minOf(maxOf(icon.width, icon.height), MAX_ICON_PX)
            val scaled = runCatching { Bitmap.createScaledBitmap(icon, side, side, true) }.getOrNull() ?: return
            scope.launch {
                val targets = looks.iconTargets(host)
                val saved = withContext(Dispatchers.IO) {
                    targets.filter { h ->
                        runCatching {
                            val f = looks.iconFile(h)
                            f.parentFile?.mkdirs()
                            f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }.getOrDefault(false)
                    }
                }
                saved.forEach(looks::iconSaved)
            }
        }

        /** 用户自己看的这个标签要选文件:交给界面弹系统选择器。 */
        override fun onFileChooser(tab: WebTab, callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
            if (tab !== this@UserBrowser.tab) return false
            val previous = _filePick.value
            _filePick.value = FilePickRequest(tab, callback, params)
            previous?.callback?.onReceiveValue(null)
            return true
        }

        /** 用户自己看的这个标签要摄像头、麦克风等:交给界面问用户。 */
        override fun onPermissionRequest(tab: WebTab, request: PermissionRequest): Boolean {
            if (tab !== this@UserBrowser.tab) return false
            // 只接摄像头和麦克风;MIDI、受保护媒体这些照旧拒绝
            if (request.resources.none { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }) return false
            mediaPermissions.replace(request.origin.toString(), request)?.value?.deny()
            return true
        }

        override fun onPermissionRequestCanceled(tab: WebTab, request: PermissionRequest) {
            pagePermission.value?.takeIf { it.value === request }?.let { mediaPermissions.take(it.id) }
        }

        /** 用户自己看的这个标签要定位:交给界面问用户。 */
        override fun onGeolocationRequest(tab: WebTab, origin: String?, callback: GeolocationPermissions.Callback): Boolean {
            if (tab !== this@UserBrowser.tab || origin.isNullOrBlank()) return false
            geoPermission.value?.let { answerGeolocation(it.id, false) }
            locationPermissions.replace(origin, callback)
            return true
        }

        override fun onGeolocationPermissionsHidePrompt(tab: WebTab) {
            if (tab === this@UserBrowser.tab) geoPermission.value?.let { locationPermissions.take(it.id) }
        }

        override fun onDialog(tab: WebTab) {
            if (tab === this@UserBrowser.tab) _dialog.value = tab.dialogs.current
        }

        override fun onFullscreen(tab: WebTab, view: View, callback: WebChromeClient.CustomViewCallback): Boolean {
            if (tab !== this@UserBrowser.tab) return false
            clearFullscreen()
            _fullscreen.value = FullscreenVideo(view, callback)
            return true
        }

        override fun onExitFullscreen(tab: WebTab) {
            if (tab === this@UserBrowser.tab) clearFullscreen()
        }

        override fun onExternalLink(tab: WebTab, link: ExternalLink) {
            if (tab === this@UserBrowser.tab) _notice.value = tr(R.string.browser_blocked_app_link)
        }

        override fun onDownload(tab: WebTab, url: String, userAgent: String, contentDisposition: String?, mime: String?) {
            scope.launch {
                val name = withContext(Dispatchers.IO) { downloads.enqueue(url, userAgent, contentDisposition, mime) }
                _notice.value = if (name != null) tr(R.string.browser_download_started, name) else tr(R.string.browser_download_failed_start)
            }
        }

        override fun onCrashed(tab: WebTab) {
            // 在回调之外销毁
            main.post {
                if (opener != null && tab === this@UserBrowser.tab) closePopup() else closeTab(tab.id)
                _notice.value = tr(R.string.browser_crashed)
            }
        }

        /**
         * 页面要开新窗口(target=_blank、window.open):只认用户点出来的,只开一层。新窗口盖在当前页上显示,
         * 当前页留着([opener]):「用第三方账号登录」这类弹窗登录完要把结果交回打开它的页面,页面没了就停在白屏。
         * 弹窗关掉自己,或在弹窗里退无可退时,回到打开它的那一页。
         */
        override fun onCreateWindow(tab: WebTab, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture || tab !== this@UserBrowser.tab || opener != null) return false
            if (tab !in openTabs) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            stopFind()
            val popup = createTab()
            transport.webView = popup.view
            resultMsg.sendToTarget()
            (tab.view.parent as? ViewGroup)?.removeView(tab.view)
            opener = tab
            loginFor[popup] = null
            this@UserBrowser.tab = popup
            place()
            // 借给任务的是看得见的那一个:跟着换成弹出的窗口
            loans.swapped(tab, popup)
            publishTabs()
            return true
        }

        override fun onCloseWindow(tab: WebTab) {
            if (tab === this@UserBrowser.tab) closePopup()
        }
    }

    /** 关掉弹出的窗口,回到打开它的那一页;没有弹窗时返回 false。 */
    private fun closePopup(): Boolean {
        val o = opener ?: return false
        denyPendingPermissions()
        opener = null
        stopFind()
        clearFullscreen()
        tab?.let { popup ->
            loginFor.remove(popup)
            probes.remove(popup)?.let(main::removeCallbacks)
            loans.swapped(popup, o)
            popup.destroy()
        }
        tab = o
        showActive()
        publishTabs()
        return true
    }

    /**
     * 页面脚本多在加载完后才画出账号区:停一会儿再看登录状态;还在加载或地址变了就再等,至多几次。
     * 登记表里有的网站按看到的记上或去掉;没有的只在看出已登录时记上(用户在这里登录了新网站)。
     */
    private fun scheduleProbe(t: WebTab, attempt: Int) {
        probes.remove(t)?.let(main::removeCallbacks)
        val urlThen = t.view.url
        val run = Runnable {
            probes.remove(t)
            if (t.destroyed || (t !in openTabs && t !== tab)) return@Runnable
            if (t.loading || t.view.url != urlThen) {
                if (attempt < PROBE_RETRIES) scheduleProbe(t, attempt + 1)
                return@Runnable
            }
            LoginProbe.probe(t) { host, state ->
                val known = sites.list().any { it.host == host }
                val login = loginFor[t]
                val wasIn = login != null && BrowserFavorites.loggedIn(sites.list(), login)
                when {
                    known -> sites.loginObserved(host, state)
                    state == LoginState.SignedIn -> sites.markLoggedIn(host)
                    else -> Unit
                }
                // 「给 Mika 登录」时自己看出登录好了:告诉用户一声(横幅随登录状态收起)
                if (login != null && !wasIn && state == LoginState.SignedIn && BrowserFavorites.siteKey(host) == BrowserFavorites.siteKey(login)) {
                    _notice.value = LOGGED_IN_NOTICE
                }
                Log.i(TAG, "browser login probe $host: $state (known=$known)")
            }
        }
        probes[t] = run
        main.postDelayed(run, PROBE_DELAY_MS)
    }

    /** 页面加载完后读网站名(页面声明的网站名或标题),记进 [looks]。 */
    private fun readName(t: WebTab, url: String) {
        val host = WebUrlPolicy.host(url) ?: return
        if (AddressInput.searchQuery(url, searchEngine.choices.value.all) != null) return
        t.view.evaluateJavascript(SiteNames.SCRIPT) { raw ->
            if (t.destroyed) return@evaluateJavascript
            val (declared, title) = SiteNames.parse(raw) ?: return@evaluateJavascript
            val (name, rank) = SiteNames.pick(declared, title, SiteNames.isHomePage(url)) ?: return@evaluateJavascript
            looks.named(host, name, rank)
        }
    }

    companion object {
        private const val TAG = "MikaUserBrowser"
        /** 这个标签不属于任何任务。 */
        const val OWNER = "user-browser"
        private const val PROBE_DELAY_MS = 1_500L
        private const val PROBE_RETRIES = 3
        private const val CONFIRM_WAIT_MS = 2_500L
        /** 收下的网站图标至少这么大,存的时候至多这么大(像素)。 */
        private const val MIN_ICON_PX = 16
        private const val MAX_ICON_PX = 128
        val LOGGED_IN_NOTICE: String get() = tr(R.string.browser_logged_in)
        /** 页面上选中的字(含输入框里选中的那段)。 */
        private const val SELECTION_SCRIPT = "(function(){var s=window.getSelection();var t=s?String(s):'';" +
            "var a=document.activeElement;if(!t&&a&&typeof a.selectionStart==='number'&&a.type!=='password')" +
            "t=String(a.value||'').substring(a.selectionStart,a.selectionEnd);return t;})()"

        /** 进程里已经有的那一份;还没打开过浏览器页为 null(不为它建一份)。 */
        fun existing(): UserBrowser? = instance

        @Volatile private var instance: UserBrowser? = null

        fun of(context: Context): UserBrowser =
            instance ?: synchronized(this) { instance ?: UserBrowser(context.applicationContext).also { instance = it } }
    }
}
