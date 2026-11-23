package com.wangzan101.mika.browser.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.browser.AddressInput
import com.wangzan101.mika.browser.SearchEngine
import com.wangzan101.mika.browser.SearchEngineSpec
import com.wangzan101.mika.browser.BrowserPage
import com.wangzan101.mika.browser.PageBookmark
import com.wangzan101.mika.browser.SiteFace
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wangzan101.mika.settings.ui.models.refresh

val BROWSER_DESKTOP_TAG: String get() = tr(R.string.browser_desktop)
val BROWSER_LOGIN_DONE: String get() = tr(R.string.browser_login_done)

internal val MiniShape = MikaShapes.Tile
internal val InitialSmall = MikaType.Caption.copy(fontWeight = FontWeight.Bold)

/**
 * 网站的方块:有存下的网站图标就画在白底上;没有时是按主机名定的底色加名字的第一个字。
 * 图标文件在后台读,读过的留在进程里的小缓存里(按文件和保存时间)。
 */
@Composable
fun SiteTile(face: SiteFace, size: Dp, shape: Shape, initialStyle: TextStyle) {
    val c = MikaTheme.colors
    val icon = rememberSiteIcon(face)
    if (icon != null) {
        Box(Modifier.size(size).background(Color.White, shape).border(0.5.dp, c.edge, shape).clip(shape), contentAlignment = Alignment.Center) {
            Image(icon, null, Modifier.size(size * 0.62f))
        }
    } else {
        Box(Modifier.size(size).background(Color(face.color), shape), contentAlignment = Alignment.Center) {
            Text(face.initial, style = initialStyle, color = Color.White, maxLines = 1)
        }
    }
}

private object SiteIcons {
    val cache = LruCache<String, ImageBitmap>(64)
}

@Composable
private fun rememberSiteIcon(face: SiteFace): ImageBitmap? {
    val file = face.icon
    val key = file?.let { "${it.path}@${face.iconAtMs}" }
    val icon by produceState(key?.let { SiteIcons.cache.get(it) }, key) {
        if (file == null || key == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() }
            ?.also { SiteIcons.cache.put(key, it) }
    }
    return icon
}

/**
 * 网页的顶栏:关闭(回起始页)、地址栏、刷新(加载时是停止),下面一条 2dp 的进度条。
 * 地址栏平时只显示锁和网站名(搜索结果页是搜的词),电脑版时带一个「电脑版」小标签;点了展开成完整网址可改,右边「取消」收回。
 */
@Composable
fun BrowserPageBar(
    page: BrowserPage,
    face: SiteFace?,
    onClose: () -> Unit,
    onSubmit: (String) -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    engines: List<SearchEngineSpec> = SearchEngine.entries,
) {
    val c = MikaTheme.colors
    val focus = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(c.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().height(MikaSize.TopBar).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (editing) {
                AddressEditor(page.url, Modifier.weight(1f).padding(start = 8.dp)) { input ->
                    editing = false
                    focus.clearFocus()
                    onSubmit(input)
                }
                Text(
                    tr(R.string.cancel), Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button) { editing = false; focus.clearFocus() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MikaType.Body, color = c.accent,
                )
            } else {
                BarIconButton(MikaIcons.Close, tr(R.string.browser_close_page), onClose)
                AddressPill(page, face, engines, Modifier.weight(1f)) { editing = true }
                if (page.loading) BarIconButton(MikaIcons.Stop, tr(R.string.chat_stop), onStop)
                else BarIconButton(MikaIcons.Refresh, tr(R.string.refresh), onReload)
            }
        }
        val progress = page.takeIf { it.loading }?.progress
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (progress != null) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(5, 100) / 100f).background(c.accent).testTag("browser-progress"))
            }
        }
    }
}

@Composable
private fun AddressPill(page: BrowserPage, face: SiteFace?, engines: List<SearchEngineSpec>, modifier: Modifier, onClick: () -> Unit) {
    val c = MikaTheme.colors
    val query = AddressInput.searchQuery(page.url, engines)
    val shown = query ?: face?.name ?: page.url
    Row(
        modifier.height(40.dp).clip(MikaShapes.Pill).background(c.fill)
            .clickable(onClickLabel = tr(R.string.browser_edit_url), role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp).testTag("browser-address-pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        when {
            query != null -> Icon(MikaIcons.Search, null, Modifier.size(14.dp), tint = c.ink3)
            page.url.startsWith("https://") -> Icon(MikaIcons.Lock, tr(R.string.browser_secure), Modifier.size(14.dp), tint = c.ink3)
        }
        Text(shown, Modifier.weight(1f, fill = false), style = MikaType.Body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (page.desktop) {
            Text(
                BROWSER_DESKTOP_TAG, Modifier.background(c.warnSoft, RoundedCornerShape(MikaRadius.BubbleTail)).padding(horizontal = 6.dp, vertical = 1.dp),
                style = TagText, color = c.warn, maxLines = 1,
            )
        }
    }
}

private val TagText = MikaType.Micro.copy(fontWeight = FontWeight.SemiBold)

/** 展开的地址栏:完整网址全选着,键盘上是「前往」。 */
@Composable
private fun AddressEditor(url: String, modifier: Modifier, onGo: (String) -> Unit) {
    val c = MikaTheme.colors
    val focus = remember { FocusRequester() }
    var text by remember { mutableStateOf(TextFieldValue(url, TextRange(0, url.length))) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier.height(40.dp).background(c.surface, MikaShapes.Pill).border(1.5.dp, c.accent, MikaShapes.Pill).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(MikaIcons.Lock, null, Modifier.size(14.dp), tint = c.ink3)
        BasicTextField(
            text, { text = it },
            Modifier.weight(1f).focusRequester(focus).testTag("browser-address"),
            singleLine = true,
            textStyle = MikaType.Body.copy(color = c.ink, textDirection = androidx.compose.ui.text.style.TextDirection.Content),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { onGo(text.text) }),
        )
    }
}

/** 网页下面的工具栏:后退、前进、起始页、分享、更多。 */
@Composable
fun BrowserToolbar(
    page: BrowserPage,
    onBackPage: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit,
) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().background(c.bg).windowInsetsPadding(WindowInsets.navigationBars)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.hair))
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarIconButton(MikaIcons.Back, tr(R.string.back), onBackPage, enabled = page.canBack)
            BarIconButton(MikaIcons.Forward, tr(R.string.browser_forward), onForward, enabled = page.canForward)
            BarIconButton(MikaIcons.Home, tr(R.string.browser_home), onHome)
            BarIconButton(MikaIcons.Share, tr(R.string.share), onShare)
            BarIconButton(MikaIcons.More, tr(R.string.more), onMore)
        }
    }
}

/** 「给 Mika 登录」的横幅:说明这次用的是 Mika 办事时的那一版,登录完点「登录好了」。 */
@Composable
fun LoginBanner(desktop: Boolean, onDone: () -> Unit) {
    val c = MikaTheme.colors
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(tr(R.string.browser_login_for_mika)) }
        append(tr(R.string.browser_login_for_mika_sub, if (desktop) tr(R.string.browser_desktop) else tr(R.string.browser_mobile)))
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)
            .background(c.accentSoft, MikaShapes.Button).padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("browser-login-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text, Modifier.weight(1f), style = MikaType.Caption, color = c.ink)
        MikaButton(BROWSER_LOGIN_DONE, onDone, size = ButtonSize.Small)
    }
}

/**
 * 「更多」面板:顶上是这个网站(图标、名字、主机名),下面刷新、复制链接、在其他浏览器里打开、加入常用网站(已在常用时是从常用移除)、
 * 换成电脑版 / 换回手机版(这个网站下次也这样)。点任何一项先关面板再做。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserMoreSheet(
    face: SiteFace?,
    host: String?,
    favorite: Boolean,
    desktop: Boolean,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onCopy: () -> Unit,
    onOpenOutside: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleDesktop: () -> Unit,
    bookmarked: Boolean = false,
    onToggleBookmark: () -> Unit = {},
    onDownloads: () -> Unit = {},
    onTabs: () -> Unit = {},
    tabsCount: Int = 0,
    onHistory: () -> Unit = {},
    onFind: () -> Unit = {},
    onClearData: () -> Unit = {},
) {
    val c = MikaTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface, shape = MikaShapes.SheetTop) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp).testTag("browser-more")) {
            if (face != null && host != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SiteTile(face, 28.dp, MiniShape, InitialSmall)
                    Column(Modifier.weight(1f)) {
                        Text(face.name, style = MikaType.BodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(host, style = MikaType.Caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            fun act(f: () -> Unit): () -> Unit = { onDismiss(); f() }
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                ListRow(MikaIcons.Refresh, tr(R.string.refresh), first = true, onClick = act(onReload))
                ListRow(MikaIcons.Search, tr(R.string.browser_find), onClick = act(onFind))
                ListRow(MikaIcons.Copy, tr(R.string.browser_copy_link), onClick = act(onCopy))
                ListRow(MikaIcons.External, tr(R.string.browser_open_outside), onClick = act(onOpenOutside))
                ListRow(
                    MikaIcons.Bookmark,
                    if (bookmarked) tr(R.string.browser_bookmark_remove) else tr(R.string.browser_bookmark_add),
                    onClick = act(onToggleBookmark),
                )
                ListRow(MikaIcons.Download, tr(R.string.browser_downloads), onClick = act(onDownloads))
                ListRow(
                    MikaIcons.Apps, tr(R.string.browser_tabs), detail = tabsCount.takeIf { it > 0 }?.toString(),
                    onClick = act(onTabs),
                )
                ListRow(MikaIcons.Clock, tr(R.string.browser_history), onClick = act(onHistory))
                if (host != null) {
                    ListRow(MikaIcons.Star, if (favorite) tr(R.string.browser_unfavorite) else tr(R.string.browser_favorite), onClick = act(onToggleFavorite))
                    ListRow(
                        if (desktop) MikaIcons.Phone else MikaIcons.Desktop,
                        if (desktop) tr(R.string.browser_to_mobile) else tr(R.string.browser_to_desktop),
                        subtitle = if (desktop) tr(R.string.browser_keep_mobile) else tr(R.string.browser_keep_desktop),
                        onClick = act(onToggleDesktop),
                    )
                }
                ListRow(MikaIcons.Trash, tr(R.string.browser_clear_data), onClick = act(onClearData))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * 书签面板:全部书签,最近加入的在前;「整理」进整理,点一行改标题,× 移除,「完成」退出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<PageBookmark>,
    face: (String) -> SiteFace,
    onDismiss: () -> Unit,
    onOpen: (PageBookmark) -> Unit,
    onRemove: (PageBookmark) -> Unit,
    onRename: (PageBookmark, String) -> Unit,
) {
    val c = MikaTheme.colors
    var editing by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PageBookmark?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = MikaShapes.SheetTop,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp).testTag("browser-bookmarks")) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr(R.string.browser_bookmarks), Modifier.weight(1f), style = MikaType.Title, color = c.ink)
                if (bookmarks.isNotEmpty()) {
                    Text(
                        if (editing) tr(R.string.done) else tr(R.string.browser_edit),
                        Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button) { editing = !editing }.padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MikaType.BodyStrong, color = c.accent,
                    )
                }
            }
            if (bookmarks.isEmpty()) {
                Text(
                    tr(R.string.browser_bookmark_empty), Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MikaType.Body, color = c.ink3,
                )
            }
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                bookmarks.forEachIndexed { i, b ->
                    if (i > 0) Hairline(start = 56.dp)
                    val host = WebUrlPolicy.host(b.url)
                    val site = host?.let { face(it).name } ?: b.url
                    SmallRow(
                        b.title.ifBlank { site }, host ?: b.url,
                        onClick = { if (editing) renaming = b else onOpen(b) },
                        lead = {
                            if (host != null) SiteTile(face(host), 28.dp, MiniShape, InitialSmall)
                            else Box(Modifier.size(28.dp).background(c.neutralSoft, MiniShape), contentAlignment = Alignment.Center) {
                                Icon(MikaIcons.Bookmark, null, Modifier.size(15.dp), tint = c.ink3)
                            }
                        },
                        trailing = {
                            if (editing) {
                                val remove = tr(R.string.browser_remove_site, b.title.ifBlank { site })
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = remove) { onRemove(b) },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(MikaIcons.Close, remove, Modifier.size(16.dp), tint = c.ink3) }
                            }
                        },
                    )
                }
            }
        }
    }
    renaming?.let { b ->
        RenameBookmarkDialog(
            bookmark = b,
            onSave = { title -> onRename(b, title); renaming = null },
            onDismiss = { renaming = null },
        )
    }
}

/** 改书签标题:预填现在的标题,空标题不保存。 */
@Composable
private fun RenameBookmarkDialog(bookmark: PageBookmark, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable(bookmark.url) { mutableStateOf(bookmark.title) }
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.browser_bookmark_rename)) },
        text = {
            TextField(title, { title = it }, singleLine = true, placeholder = { Text(tr(R.string.browser_bookmark_name)) })
        },
        confirmButton = {
            TextButton(onClick = { title.trim().takeIf { it.isNotEmpty() }?.let(onSave) }) { Text(tr(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}
