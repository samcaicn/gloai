package com.wangzan101.mika.browser.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.rememberCoroutineScope
import com.wangzan101.mika.browser.PageAskMode
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.browser.BrowserFavorites
import com.wangzan101.mika.browser.ChooserMimeTypes
import com.wangzan101.mika.browser.RecentPages
import com.wangzan101.mika.browser.SiteLooks
import com.wangzan101.mika.browser.StartPageModel
import com.wangzan101.mika.browser.UserBrowser
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.core.screen.uistate.PageDialog
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 浏览器页(侧栏「浏览器」):没有打开的网页时是起始页([BrowserStart]:搜索框、常用网站、最近打开),
 * 打开了是网页([BrowserPageBar] + 网页 + [BrowserToolbar],「更多」是 [BrowserMoreSheet])。
 * 网页底下是「问问 Mika」([PageAskBar]):对着这一页问,这一页借给那个任务当它的当前网页标签;干活时网页下半截盖一块对话面板
 * ([PagePanel]),页面四周亮一圈光边([PageGlow]);你一摸页面 Mika 就暂停,面板收成小卡片([PageCard]),顶上一条「继续」([PausedBanner])。
 * 选中网页上的文字时,文字选择菜单里多一项「问 Mika」,选中的字带进去。状态在 [PageChat]。
 * 系统返回键先在网页里后退,退不了回到起始页,起始页上再按回到进来的地方。
 * 网页视图放在进程里([UserBrowser]),转屏时接着用;离开这一页(不是转屏、也没有别处的浏览器页)时关掉。
 */
@Composable
fun BrowserScreen(browser: UserBrowser, onBack: () -> Unit) {
    val context = LocalContext.current
    var filePickerId by rememberSaveable { mutableStateOf<String?>(null) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = filePickerId
        filePickerId = null
        if (id != null) browser.answerFileChooser(id, uri?.let { listOf(it) })
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val id = filePickerId
        filePickerId = null
        if (id != null) browser.answerFileChooser(id, uris)
    }
    var mediaPermissionId by rememberSaveable { mutableStateOf<String?>(null) }
    var locationPermissionId by rememberSaveable { mutableStateOf<String?>(null) }
    val askMediaPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val id = mediaPermissionId
        mediaPermissionId = null
        if (id != null) browser.answerPagePermission(id, granted.isNotEmpty() && granted.values.all { it })
    }
    val askLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val id = locationPermissionId
        locationPermissionId = null
        if (id != null) browser.answerGeolocation(id, granted.values.any { it })
    }
    val page by browser.page.collectAsState()
    val dialog by browser.dialog.collectAsState()
    val notice by browser.notice.collectAsState()
    val filePick by browser.filePick.collectAsState()
    val pagePermission by browser.pagePermission.collectAsState()
    val geoPermission by browser.geoPermission.collectAsState()
    val registry by browser.sites.entries.collectAsState()
    val recentPages by browser.recent.entries.collectAsState()
    val edits by browser.favorites.edits.collectAsState()
    val bookmarks by browser.bookmarks.entries.collectAsState()
    val looks by browser.looks.entries.collectAsState()
    val tabs by browser.tabs.collectAsState()
    val find by browser.find.collectAsState()
    val fullscreen by browser.fullscreen.collectAsState()
    val searchEngines by browser.searchEngine.choices.collectAsState()
    val activity = remember(context) { context.findActivity() }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var bookmarksOpen by rememberSaveable { mutableStateOf(false) }
    var downloadsOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var tabsOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var clearOpen by rememberSaveable { mutableStateOf(false) }

    val face = remember(looks) { { host: String -> SiteLooks.faceOf(host, looks, browser.looks::iconFile) } }
    val favorites = remember(edits, registry) { BrowserFavorites.combine(edits, registry) }
    val allRecent = remember(recentPages, looks, searchEngines) { StartPageModel.recentRows(recentPages, { face(it).name }, searchEngines.all) }
    val recent = remember(allRecent) { allRecent.take(RecentPages.START_LIMIT) }
    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    val scope = rememberCoroutineScope()
    val chat = remember(browser) { PageChat(context.applicationContext, browser, scope) }
    // 换了一页(还没在这一页上问过时)重新找接着的对话;关了网页回到什么都没有
    val pageUrl = page?.url
    LaunchedEffect(pageUrl) { chat.onPage(pageUrl) }
    // 系统选择器返回前先保留原请求 ID，新的请求等原选择器结束再弹。
    LaunchedEffect(filePick, filePickerId) {
        val request = filePick ?: return@LaunchedEffect
        if (filePickerId != null || request.started || browser.filePick.value?.id != request.id) return@LaunchedEffect
        request.started = true
        filePickerId = request.id
        val params = request.params
        val types = chooserMimeTypes(params)
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) pickFiles.launch(types) else pickFile.launch(types)
    }

    // 这一处浏览器页:进来时登记,回到前台时把网页视图要过来(界面有两个实例时挂在看得见的那个上),
    // 离开时交还;最后一处离开(不是转屏)才关网页
    val place = remember(browser, activity) { browser.newHost(activity ?: context.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(browser, place, lifecycle) {
        browser.openHost(place)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) browser.hostStarted(place) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            browser.closeHost(place, keepPage = activity?.isChangingConfigurations == true)
        }
    }
    BackHandler(enabled = page != null || tabs.isNotEmpty()) { browser.systemBack() }
    BackHandler(enabled = find.active) { browser.stopFind() }
    LaunchedEffect(notice) {
        notice?.let {
            toast(it)
            browser.noticeShown()
        }
    }

    PageBackground {
        val p = page
        if (p == null) {
            LaunchedEffect(Unit) { moreOpen = false }
            BrowserStart(
                sites = favorites, recent = recent, face = face,
                onBack = onBack,
                onSubmit = { input -> browser.submit(input)?.let(toast) },
                onOpenUrl = { url -> browser.open(url)?.let(toast) },
                onOpenSite = { site -> browser.openFavorite(site)?.let(toast) },
                onRemoveSite = { site ->
                    browser.favorites.remove(site.host)
                    toast(tr(R.string.browser_removed, face(site.host).name))
                },
                onAddHint = { toast(BROWSER_ADD_HINT) },
                onClearRecent = browser.recent::clear,
                bookmarks = bookmarks,
                onOpenBookmark = { b -> browser.open(b.url)?.let(toast) },
                onShowBookmarks = { bookmarksOpen = true },
                onDownloads = { downloadsOpen = true },
                tabsCount = tabs.size,
                onMenu = { menuOpen = true },
                onHistory = { historyOpen = true },
                engine = searchEngines.selected,
                custom = searchEngines.custom,
                onPickEngine = browser.searchEngine::set,
                onAddEngine = browser.searchEngine::addCustom,
                onRemoveEngine = { browser.searchEngine.removeCustom(it.id) },
            )
        } else {
            val host = WebUrlPolicy.host(p.url)
            val loginBanner = p.loginFor != null && !BrowserFavorites.loggedIn(registry, p.loginFor)
            Column(Modifier.fillMaxSize()) {
                BrowserPageBar(
                    p, host?.let(face),
                    onClose = browser::closeActiveTab,
                    onSubmit = { input -> browser.submit(input)?.let(toast) },
                    onReload = browser::reload, onStop = browser::stop,
                    engines = searchEngines.all,
                )
                if (find.active) {
                    BrowserFindBar(
                        find, browser::setFindQuery,
                        onPrev = { browser.findNext(false) }, onNext = { browser.findNext(true) }, onClose = browser::stopFind,
                    )
                }
                if (loginBanner) LoginBanner(desktop = p.desktop, onDone = browser::confirmLogin)
                val mode = chat.mode
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // 网页视图由 UserBrowser 放进这个容器(换了标签也放进同一个);手指按下告诉借着页面的任务,选中文字时菜单里有「问 Mika」
                    AndroidView(
                        factory = { ctx ->
                            PageFrame(ctx, onTouch = chat::touched, onAsk = { done ->
                                browser.readSelection { text ->
                                    done()
                                    text?.let(chat::askAbout)
                                }
                            }).also { browser.setContainer(place, it) }
                        },
                        modifier = Modifier.fillMaxSize().testTag("browser-page"),
                        onRelease = { browser.setContainer(place, null) },
                    )
                    PageAskOverlay(chat, site = host?.let { face(it).name } ?: p.url, pageTitle = p.title)
                }
                if (mode == PageAskMode.Bar && p.loginFor == null) PageAskBar(chat, Modifier.imePadding())
                BrowserToolbar(
                    p, onBackPage = browser::back, onForward = browser::forward, onHome = browser::closeActiveTab,
                    onShare = { share(context, p.url, p.title)?.let(toast) },
                    onMore = { moreOpen = true },
                )
            }
            if (moreOpen) {
                val favorite = host != null && BrowserFavorites.contains(favorites, host)
                val bookmarked = bookmarks.any { it.url.trim().substringBefore('#') == p.url.trim().substringBefore('#') }
                BrowserMoreSheet(
                    face = host?.let(face), host = host, favorite = favorite, desktop = p.desktop,
                    onDismiss = { moreOpen = false },
                    onReload = browser::reload,
                    onCopy = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(p.title.ifBlank { p.url }, p.url))
                        toast(tr(R.string.browser_link_copied))
                    },
                    onOpenOutside = { openOutside(context, p.url)?.let(toast) },
                    bookmarked = bookmarked,
                    onToggleBookmark = {
                        if (bookmarked) {
                            browser.bookmarks.remove(p.url)
                            toast(tr(R.string.browser_unbookmarked))
                        } else if (browser.bookmarks.add(p.url, p.title)) {
                            toast(tr(R.string.browser_bookmarked))
                        }
                    },
                    onDownloads = { downloadsOpen = true },
                    onToggleFavorite = {
                        if (host == null) return@BrowserMoreSheet
                        if (favorite) {
                            browser.favorites.remove(host)
                            toast(tr(R.string.browser_unfavorited))
                        } else {
                            browser.favorites.add(host)
                            toast(tr(R.string.browser_favorited))
                        }
                    },
                    onToggleDesktop = {
                        browser.setDesktop(!p.desktop)
                        toast(if (p.desktop) tr(R.string.browser_switched_mobile) else tr(R.string.browser_switched_desktop))
                    },
                    onTabs = { tabsOpen = true },
                    tabsCount = tabs.size,
                    onHistory = { historyOpen = true },
                    onFind = browser::startFind,
                    onClearData = { clearOpen = true },
                )
            }
        }
    }

    if (bookmarksOpen) {
        BookmarksSheet(
            bookmarks = bookmarks, face = face,
            onDismiss = { bookmarksOpen = false },
            onOpen = { b -> bookmarksOpen = false; browser.open(b.url)?.let(toast) },
            onRemove = { b -> browser.bookmarks.remove(b.url); toast(tr(R.string.browser_unbookmarked)) },
            onRename = { b, title -> browser.bookmarks.rename(b.url, title) },
        )
    }
    if (downloadsOpen) {
        DownloadsSheet(browser.downloads, onDismiss = { downloadsOpen = false })
    }
    val askingAndroid = mediaPermissionId != null || locationPermissionId != null
    pagePermission?.takeUnless { askingAndroid }?.let { req ->
        WebPermissionDialog(
            site = req.origin,
            what = mediaPermissionText(req.value.resources),
            onAllow = allow@{
                if (mediaPermissionId != null || locationPermissionId != null) return@allow
                if (browser.pagePermission.value?.id != req.id) return@allow
                val missing = mediaAndroidPermissions(req.value.resources)
                    .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) browser.answerPagePermission(req.id, true) else {
                    mediaPermissionId = req.id
                    askMediaPermissions.launch(missing.toTypedArray())
                }
            },
            onDeny = { browser.answerPagePermission(req.id, false) },
        )
    }
    geoPermission?.takeUnless { askingAndroid || pagePermission != null }?.let { req ->
        WebPermissionDialog(
            site = req.origin,
            what = tr(R.string.browser_permission_location),
            onAllow = allow@{
                if (mediaPermissionId != null || locationPermissionId != null) return@allow
                if (browser.geoPermission.value?.id != req.id) return@allow
                val missing = LOCATION_PERMISSIONS.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) browser.answerGeolocation(req.id, true) else {
                    locationPermissionId = req.id
                    askLocationPermission.launch(missing.toTypedArray())
                }
            },
            onDeny = { browser.answerGeolocation(req.id, false) },
        )
    }
    if (menuOpen) {
        BrowserMenuSheet(
            tabsCount = tabs.size,
            onDismiss = { menuOpen = false },
            onTabs = { menuOpen = false; tabsOpen = true },
            onHistory = { menuOpen = false; historyOpen = true },
            onClearData = { menuOpen = false; clearOpen = true },
        )
    }
    if (tabsOpen) {
        TabsSheet(
            tabs = tabs,
            onDismiss = { tabsOpen = false },
            onSelect = { id -> tabsOpen = false; browser.selectTab(id) },
            onClose = browser::closeTab,
            onNew = { tabsOpen = false; browser.newTab() },
        )
    }
    if (historyOpen) {
        HistorySheet(
            pages = allRecent, now = System.currentTimeMillis(),
            onDismiss = { historyOpen = false },
            onOpen = { r -> historyOpen = false; browser.open(r.url)?.let(toast) },
            onRemove = { r -> browser.recent.remove(r.url) },
            onClear = browser.recent::clear,
        )
    }
    if (clearOpen) {
        ClearDataSheet(onDismiss = { clearOpen = false }) { history, siteData, cache ->
            browser.clearBrowsingData(history, siteData, cache)
            clearOpen = false
            toast(tr(R.string.browser_data_cleared))
        }
    }

    fullscreen?.let { f ->
        key(f) {
            Dialog(
                onDismissRequest = { browser.exitFullscreen() },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                val view = LocalView.current
                val controller = remember(view) { ViewCompat.getWindowInsetsController(view) }
                DisposableEffect(view) {
                    controller?.hide(WindowInsetsCompat.Type.systemBars())
                    controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
                }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).also {
                                (f.view.parent as? ViewGroup)?.removeView(f.view)
                                it.addView(f.view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { it.removeView(f.view) },
                    )
                }
            }
        }
    }

    dialog?.let { d -> PageDialogBox(d, browser::answerDialog) }
}

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun mediaAndroidPermissions(resources: Array<String>): List<String> = buildList {
    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources) add(Manifest.permission.RECORD_AUDIO)
    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources) add(Manifest.permission.CAMERA)
}

private fun mediaPermissionText(resources: Array<String>): String = when {
    PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources && PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources ->
        tr(R.string.browser_permission_camera_mic)
    PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources -> tr(R.string.browser_permission_camera)
    else -> tr(R.string.browser_permission_microphone)
}

/** 网页给的文件类型换成系统选择器认的 MIME;扩展名查系统表,没有就用全部类型。 */
private fun chooserMimeTypes(params: WebChromeClient.FileChooserParams): Array<String> =
    ChooserMimeTypes.of(params.acceptTypes.toList()) { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }.toTypedArray()

@Composable
private fun WebPermissionDialog(site: String, what: String, onAllow: () -> Unit, onDeny: () -> Unit) {
    MikaDialog(
        onDismissRequest = onDeny,
        title = { Text(site) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr(R.string.browser_permission_ask))
                Text(what, style = MikaType.BodyStrong)
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text(tr(R.string.browser_permission_allow)) } },
        dismissButton = { TextButton(onClick = onDeny) { Text(tr(R.string.browser_permission_block)) } },
    )
}

/** 系统分享面板:网址,标题作主题。打不开时返回原因。 */
private fun share(context: Context, url: String, title: String): String? {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    if (title.isNotBlank()) send.putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TITLE, title)
    return try {
        context.startActivity(Intent.createChooser(send, null))
        null
    } catch (e: ActivityNotFoundException) {
        tr(R.string.browser_no_share_app)
    }
}

/** 交给系统默认的浏览器打开。打不开时返回原因。 */
private fun openOutside(context: Context, url: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    null
} catch (e: ActivityNotFoundException) {
    tr(R.string.browser_no_other_browser)
}

/** 页面弹框:提示只有「好」;确认和离开页面有「取消」;输入框弹框带一个输入框。 */
@Composable
private fun PageDialogBox(d: PageDialog, answer: (Boolean, String?) -> Unit) {
    var input by remember(d) { mutableStateOf(d.defaultText.orEmpty()) }
    val leave = d.kind == PageDialog.BEFORE_UNLOAD
    MikaDialog(
        onDismissRequest = { answer(false, null) },
        title = { Text(if (leave) tr(R.string.browser_leave_q) else tr(R.string.browser_page_says)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.message.isNotBlank()) Text(d.message)
                if (leave && d.message.isBlank()) Text(tr(R.string.browser_leave_body))
                if (d.kind == PageDialog.PROMPT) TextField(input, { input = it }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({ answer(true, if (d.kind == PageDialog.PROMPT) input else null) }) { Text(if (leave) tr(R.string.browser_leave) else tr(R.string.ok_yes)) }
        },
        dismissButton = if (d.kind == PageDialog.ALERT) null else ({ TextButton({ answer(false, null) }) { Text(tr(R.string.cancel)) } }),
    )
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
