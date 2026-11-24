package com.wangzan101.mika.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.LocalSkinBackdrop
import com.wangzan101.mika.ui.theme.SkinBackdrop
import com.wangzan101.mika.ui.theme.rememberSkinBackdrop
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy

/**
 * 页面底:干净的近白(深色下近黑),不加渐变。
 * 设了背景图片时铺满图片、再盖一层同色遮罩(见 [SkinBackdrop]),卡片和文字照旧。
 */
@Composable
fun PageBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val backdrop = rememberSkinBackdrop()
    // 有没有图都是同一个结构:图是异步读的,换结构会让里面的页面整个重建、丢掉状态
    Box(
        modifier.fillMaxSize().background(MikaTheme.colors.bg).then(
            if (backdrop == null) Modifier else Modifier
                .onGloballyPositioned { backdrop.pageOrigin = it.positionInRoot(); backdrop.pageSize = it.size }
                .drawBehind { backdrop.draw(this, backdrop.pageOrigin) },
        ),
    ) {
        CompositionLocalProvider(LocalSkinBackdrop provides backdrop) { content() }
    }
}

/**
 * 页面底色:顶栏、输入栏底下的渐隐这些要挡住滚动内容的地方用它。
 * 有背景图片且 [color] 就是页面底色时,照着同一位置画一块页面背景,和页面底连成一片;
 * [fade] 是从透明到页面底色的渐变,有背景图片时当作透明度的遮罩用。
 */
fun Modifier.pageBackground(color: Color, fade: Brush? = null): Modifier = composed {
    val backdrop = LocalSkinBackdrop.current
    if (backdrop == null || color != MikaTheme.colors.bg) return@composed if (fade != null) background(fade) else background(color)
    var origin by remember { mutableStateOf(Offset.Zero) }
    val placed = onGloballyPositioned { origin = it.positionInRoot() }
    if (fade == null) {
        placed.drawBehind { backdrop.draw(this, origin) }
    } else {
        // 先画背景,再按渐变的透明度裁掉:上面透明、下面实
        placed.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                backdrop.draw(this, origin)
                drawRect(fade, blendMode = BlendMode.DstIn)
            }
    }
}

/** 卡片的底:白底 + 黑 5% 描边 + 极淡阴影;深色下只有描边。 */
fun Modifier.cardSurface(colors: MikaColors, shape: Shape = MikaShapes.Card): Modifier =
    (if (colors.cardShadow.alpha > 0f) {
        shadow(6.dp, shape, clip = false, ambientColor = colors.cardShadow.copy(alpha = 0.45f), spotColor = colors.cardShadow.copy(alpha = 0.16f))
    } else this)
        .background(colors.surface, shape)
        .border(0.5.dp, colors.edge, shape)

/** 悬浮元素的阴影(输入栏、浮在内容上的按钮)。 */
fun Modifier.floatShadow(colors: MikaColors, shape: Shape): Modifier =
    shadow(14.dp, shape, clip = false, ambientColor = colors.floatShadow.copy(alpha = 0.3f), spotColor = colors.floatShadow.copy(alpha = 0.35f))

/** 一张卡片,内容裁在圆角里(行的按压效果不出角)。 */
@Composable
fun MikaCard(modifier: Modifier = Modifier, shape: Shape = if (LocalPlainList.current) PlainCardShape else MikaShapes.Card, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().cardSurface(MikaTheme.colors, shape).clip(shape), content = content)
}

/**
 * 分组卡片:一组 [ListRow]。下面自带 12dp 间距,和下一组的 [GroupLabel](上 8)合起来是分组之间的 20。
 * 行与行的分隔线由行自己画,从文字处开始。
 */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (!LocalPlainList.current) {
        MikaCard(modifier.padding(bottom = 12.dp), content = content)
        return
    }
    // 简约列表:行的说明([ListRow] / [RadioRow] 的 note)不进行里,收到卡片下面当脚注
    val notes = remember { CardNotes() }
    Column(modifier.padding(bottom = 12.dp)) {
        CompositionLocalProvider(LocalCardNotes provides notes) { MikaCard(content = content) }
        val text = notes.footnote()
        if (text != null) {
            Text(text, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                style = MikaType.Hint, color = MikaTheme.colors.ink3)
        }
    }
}

/** 一张卡片里各行交上来的说明。 */
class CardNotes {
    internal class Note(val title: String, val text: String, val selected: Boolean?)
    internal val items = androidx.compose.runtime.mutableStateMapOf<Any, Note>()
    /** 各行的图标(没有图标记 null)。 */
    internal val icons = androidx.compose.runtime.mutableStateMapOf<Any, Any?>()

    /** 同一组里同一个图标出现三次以上,或有的行有图标有的没有,整组都不放图标:图标只在能帮你区分时出现。 */
    internal val hideIcons: Boolean
        get() {
            val all = icons.values.toList()
            if (all.size < 2) return false
            val shown = all.filterNotNull()
            return shown.isNotEmpty() && (shown.size < all.size || shown.groupingBy { it }.eachCount().values.any { it >= 3 })
        }

    /** 单选组只说选中那项;只有一条就直接说;多条按「标题:说明」逐行列出。 */
    internal fun footnote(): String? {
        val all = items.values.toList()
        if (all.isEmpty()) return null
        all.firstOrNull { it.selected == true }?.let { return it.text }
        if (all.any { it.selected != null }) return null
        if (all.size == 1) return all[0].text
        return all.joinToString("\n") { "${it.title}:${it.text}" }
    }
}

val LocalCardNotes = androidx.compose.runtime.staticCompositionLocalOf<CardNotes?> { null }

/** 把这一行的说明交给所在卡片;不在简约列表的卡片里时返回 false,由行自己显示。 */
@Composable
internal fun rememberCardNote(title: String, note: String?, selected: Boolean? = null): Boolean {
    val sink = LocalCardNotes.current ?: return false
    if (note.isNullOrEmpty()) return true
    val key = remember { Any() }
    androidx.compose.runtime.DisposableEffect(sink, key) { onDispose { sink.items.remove(key) } }
    androidx.compose.runtime.SideEffect { sink.items[key] = CardNotes.Note(title, note, selected) }
    return true
}

/** 状态卡:左边圆形图标,右边标题和说明。用于页面顶上的总状态。 */
@Composable
fun StateCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tone: TileTone = TileTone.Neutral,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = MikaTheme.colors
    MikaCard(modifier.padding(bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val (bg, fg) = tone.colors(c)
            if (LocalPlainList.current) {
                // 简约列表:不画圆底;正常状态用正文色,只有要你处理的才上色
                val tint = if (tone == TileTone.Neutral || tone == TileTone.Ok) c.ink else fg
                Icon(icon, null, Modifier.size(22.dp), tint = tint)
            } else Box(Modifier.size(36.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = fg)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MikaType.BodyStrong, color = c.ink)
                if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MikaType.Caption, color = c.ink3)
            }
            trailing()
        }
    }
}

// ---------- 悬浮玻璃 ----------

/** 能做背景模糊(安卓 12 起有 RenderEffect);不能时玻璃退回不透明的卡片底。 */
val GlassBlurSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** 玻璃后面那层内容的画面:内容用 [glassSource] 画进来,[GlassBar] 取它模糊后垫在自己底下。 */
@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

/** 不支持模糊时为 null,[GlassBar] 就用不透明底。 */
@Composable
fun rememberGlassBackdrop(): GlassBackdrop? {
    if (!GlassBlurSupported) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** 把这块内容记成玻璃的背景。玻璃本身不能在这块内容里面。 */
fun Modifier.glassSource(backdrop: GlassBackdrop?): Modifier =
    if (backdrop == null) this
    else onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
        }

private val GlassBlur = BlurEffect(30f, 30f, TileMode.Clamp)

/**
 * 玻璃输入栏的外壳:半透明底 + 背景模糊 + 悬浮阴影,高至少 52。
 * [backdrop] 为 null(不支持模糊、或后面没有内容)时用不透明底。
 */
@Composable
fun GlassBar(
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
    shape: Shape = MikaShapes.Composer,
    content: @Composable RowScope.() -> Unit,
) {
    val c = MikaTheme.colors
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier.floatShadow(c, shape).clip(shape)
            .then(if (backdrop != null) Modifier.onGloballyPositioned { origin = it.positionInRoot() } else Modifier),
    ) {
        if (backdrop != null) {
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { renderEffect = GlassBlur; clip = true }
                    .drawBehind {
                        val d = backdrop.origin - origin
                        translate(d.x, d.y) { drawLayer(backdrop.layer) }
                    },
            )
        }
        Row(
            Modifier.fillMaxWidth().background(if (backdrop != null) c.glass else c.surface)
                .border(0.5.dp, c.glassEdge, shape)
                .heightIn(min = MikaSize.Composer).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

/**
 * 一小块悬浮磨砂玻璃:白 72% + 背景模糊 + 细描边 + 悬浮阴影,尺寸随内容(顶栏的菜单/标题/新建按钮用)。
 * [backdrop] 为 null(不支持模糊、或后面没有内容)时用不透明底。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
    shape: Shape = MikaShapes.Pill,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = MikaTheme.colors
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier.floatShadow(c, shape).clip(shape)
            .then(if (backdrop != null) Modifier.onGloballyPositioned { origin = it.positionInRoot() } else Modifier),
    ) {
        if (backdrop != null) {
            Box(
                Modifier.matchParentSize().graphicsLayer { renderEffect = GlassBlur; clip = true }
                    .drawBehind {
                        val d = backdrop.origin - origin
                        translate(d.x, d.y) { drawLayer(backdrop.layer) }
                    },
            )
        }
        Box(
            Modifier.background(if (backdrop != null) c.glass else c.surface)
                .border(0.5.dp, c.glassEdge, shape),
            content = content,
        )
    }
}

/** 简约列表风格开着:分组是大圆角纯白卡片,图标不垫底块,状态胶囊写成灰字。 */
val LocalPlainList = androidx.compose.runtime.staticCompositionLocalOf { false }

/** 简约列表里分组卡片的圆角。 */
val PlainCardShape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)

/** 设置、AI 电话这类列表页:换成简约列表的配色和样式。 */
@Composable
fun PlainListTheme(content: @Composable () -> Unit) {
    val base = MikaTheme.colors
    val colors = androidx.compose.runtime.remember(base) { base.forPlainList() }
    androidx.compose.runtime.CompositionLocalProvider(
        com.wangzan101.mika.ui.theme.LocalMikaColors provides colors,
        LocalPlainList provides true,
        content = content,
    )
}

/** 交上这一行的图标,返回这一行实际该画的图标(所在组决定不放图标时为 null)。 */
@Composable
internal fun rememberCardIcon(icon: androidx.compose.ui.graphics.vector.ImageVector?): androidx.compose.ui.graphics.vector.ImageVector? {
    val sink = LocalCardNotes.current ?: return icon
    val key = remember { Any() }
    androidx.compose.runtime.DisposableEffect(sink, key) { onDispose { sink.icons.remove(key) } }
    androidx.compose.runtime.SideEffect { sink.icons[key] = icon }
    return if (sink.hideIcons) null else icon
}
