package com.wangzan101.mika.capsule




import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.StepItem
import com.wangzan101.mika.ui.components.StepState
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.flow.first

/**
 * 干活中的步骤(旧的在上):按内容高、最多 [max] 高。放不下时最新一步留在底下,旧的在上面截掉、顶上淡出,往上滚看更早的;
 * 来了新的一步时,本来就在底部才跟着往下,往上翻着看时不拽回去(同 [ThreadList])。
 * [tags] 和 [steps] 一一对应:那一步在哪(「网页」「App」),写成步骤前面的小标签;没有为 null。
 */
@Composable
internal fun Steps(steps: List<String>, max: Dp, tags: List<String?> = emptyList()) {
    val c = MikaTheme.colors
    // 倒着排:第 0 项是最新一步,贴在底下
    val state = rememberLazyListState()
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        var wasScrolling = false
        snapshotFlow { state.isScrollInProgress to !state.canScrollBackward }.collect { (scrolling, atEnd) ->
            if (scrolling || wasScrolling) following = atEnd
            wasScrolling = scrolling
        }
    }
    LaunchedEffect(steps.size) {
        if (following && !state.isScrollInProgress) state.scrollToItem(0)
    }
    val fade = with(LocalDensity.current) { FadeEdge.toPx() }
    LazyColumn(
        Modifier.testTag("capsule-steps").fillMaxWidth().heightIn(max = max)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                // 上面还有更早的步骤:顶上淡出
                if (state.canScrollForward) drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, endY = fade), blendMode = BlendMode.DstIn)
            },
        state = state,
        reverseLayout = true,
    ) {
        // 按从旧到新的序号作 key:往上翻着看时来了新的一步,看着的那几步留在原处
        items(steps.size, key = { steps.lastIndex - it }) { j ->
            val i = steps.lastIndex - j
            val current = j == 0
            StepItem(if (current) StepState.Current else StepState.Done, first = i == 0, last = current, dotY = 11.dp) {
                Row(verticalAlignment = Alignment.Top) {
                    tags.getOrNull(i)?.let { t ->
                        Text(
                            t, Modifier.padding(top = 3.dp, end = 5.dp).background(c.accentSoft, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp),
                            style = TextStyle(fontSize = 10.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium), color = c.accent, maxLines = 1,
                        )
                    }
                    Text(
                        steps[i], Modifier.padding(vertical = 2.dp), style = if (current) Step.copy(fontWeight = FontWeight.Medium) else Step,
                        color = if (current) c.ink else c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val FadeEdge = 28.dp

@Composable
internal fun Action(text: String, kind: ButtonKind, onClick: () -> Unit) {
    MikaButton(text, onClick, Modifier.fillMaxWidth(), kind = kind, size = ButtonSize.Medium)
}

@Composable
internal fun TextLink(text: String, color: Color = MikaTheme.colors.accent, onClick: () -> Unit) {
    Text(
        text, Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        style = Sub.copy(fontSize = 13.sp), color = color,
    )
}

/** 虚拟屏小画面,点一下放大。 */
@Composable
internal fun MiniScreen(frame: Bitmap, onZoom: () -> Unit) {
    val c = MikaTheme.colors
    val image = remember(frame) { frame.asImageBitmap() }
    Box(
        Modifier.testTag("capsule-screen").width(ScreenWidth).aspectRatio(ratio(frame)).clip(ScreenShape)
            .border(1.dp, c.glassEdge, ScreenShape).background(c.surface)
            .clickable(role = Role.Image, onClickLabel = CardCopy.SCREEN, onClick = onZoom)
            .semantics { contentDescription = CardCopy.SCREEN },
    ) {
        Image(image, null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
        Text(
            CardCopy.SCREEN_LABEL, Modifier.align(Alignment.BottomStart).padding(6.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            style = TextStyle(fontSize = 10.sp, lineHeight = 14.sp), color = Color.White,
        )
    }
}

/** 放大的画面:点一下回到卡片。 */
@Composable
internal fun Zoomed(frame: Bitmap, actions: CardActions) {
    val image = remember(frame) { frame.asImageBitmap() }
    val width = minOf(ZoomWidth, (LocalConfiguration.current.screenWidthDp * 0.72f).dp)
    Box(
        Modifier.testTag("capsule-zoom").width(width).aspectRatio(ratio(frame)).glass(MikaTheme.colors, CardShape, need = false)
            .clickable(role = Role.Image, onClickLabel = CardCopy.ZOOMED) { actions.zoom(false) }
            .semantics { contentDescription = CardCopy.ZOOMED },
    ) {
        Image(image, null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
        Text(
            CardCopy.ZOOM_HINT, Modifier.align(Alignment.BottomCenter).padding(10.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            style = Sub, color = Color.White,
        )
    }
}

/** 画面的宽高比,夹在合理范围里(防止异常尺寸撑坏卡片)。 */
internal fun ratio(b: Bitmap): Float = ratio(b.width, b.height)

internal fun ratio(width: Int, height: Int): Float = if (height <= 0) 0.45f else (width.toFloat() / height).coerceIn(0.3f, 1.8f)
