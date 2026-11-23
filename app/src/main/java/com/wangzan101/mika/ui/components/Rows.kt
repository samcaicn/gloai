package com.wangzan101.mika.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 分组小标题:13 / 500,辅助文字色。 */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 8.dp, bottom = 8.dp), style = MikaType.Label, color = MikaTheme.colors.ink3)
}

/** 分组下面的说明,和卡片里的文字对齐。 */
@Composable
fun FooterNote(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text, modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        style = MikaType.Hint, color = color.takeOrElse { MikaTheme.colors.ink3 },
    )
}

/** 一行摘要 + 可展开的「详情」:默认只显示 [summary],点「详情」展开 [detail] 全文。间距由调用方用 [modifier] 给。 */
@Composable
fun DetailNote(summary: String, detail: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        Text(summary, style = MikaType.Hint, color = MikaTheme.colors.ink3)
        if (detail.isNotBlank()) {
            Row(
                Modifier.clickable { open = !open }.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DirectionalIcon(
                    MikaIcons.Chevron, null,
                    Modifier.size(MikaSize.SmallIcon).rotate(if (open) 90f else 0f),
                    tint = MikaTheme.colors.accent,
                )
                Text(
                    tr(R.string.details), Modifier.padding(start = 2.dp),
                    style = MikaType.Hint.copy(fontWeight = FontWeight.SemiBold), color = MikaTheme.colors.accent,
                )
            }
            if (open) {
                Text(detail, Modifier.padding(top = 6.dp), style = MikaType.Hint, color = MikaTheme.colors.ink2)
            }
        }
    }
}

/** 图标块的底色和图形色。默认中性;只有状态才上色。 */
enum class TileTone {
    Neutral, Accent, Ok, Warn, Bad;

    fun colors(c: MikaColors): Pair<Color, Color> = when (this) {
        Neutral -> c.neutralSoft to c.ink
        Accent -> c.accentSoft to c.accent
        Ok -> c.okSoft to c.ok
        Warn -> c.warnSoft to c.warn
        Bad -> c.badSoft to c.bad
    }
}

/**
 * 行左边的图标块:30dp、圆角 9,里面图形 18dp。
 * [brandTile] 非空时是自着色的品牌标:底色用它(两种模式一致),图形保留自身颜色。
 */
@Composable
fun IconTile(icon: ImageVector, tone: TileTone = TileTone.Neutral, brandTile: Color? = null) {
    val (bg, fg) = if (brandTile != null) (brandTile to Color.Unspecified) else tone.colors(MikaTheme.colors)
    Box(Modifier.size(MikaSize.Tile).background(bg, MikaShapes.Tile), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(MikaSize.TileIcon), tint = fg)
    }
}

/** 行与行之间的分隔线,从 [start] 画到右边;分组首行([first])不画。 */
private fun Modifier.topHairline(first: Boolean, start: Dp, color: Color): Modifier =
    if (first) this
    else drawBehind {
        val y = 0.25.dp.toPx()
        drawLine(color, Offset(start.toPx(), y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
    }

/** 有图标块时文字从这里开始:左边距 16 + 图标块 30 + 间距 14。 */
private val TextStartWithIcon = 60.dp
private val TextStartPlain = 16.dp

/**
 * 列表行:左边图标块可选,中间标题和说明,右边是 [trailing](值、胶囊、开关、箭头)。
 * [detail] 是说明下面再一行的小字(如「上次结果」),颜色由 [detailColor] 定。
 * [first] 为分组首行时不画上分隔线;[onClick] 为空时整行不可点。
 */
@Composable
fun ListRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    first: Boolean = false,
    titleColor: Color = Color.Unspecified,
    tone: TileTone = TileTone.Neutral,
    brandTile: Color? = null,
    detail: String? = null,
    detailColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = MikaTheme.colors
    Row(
        modifier.fillMaxWidth()
            .topHairline(first, if (icon != null) TextStartWithIcon else TextStartPlain, c.hair)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = MikaSize.RowMin)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) IconTile(icon, tone, brandTile)
        Column(Modifier.weight(1f).padding(vertical = 11.dp)) {
            Text(title, style = MikaType.Body, color = titleColor.takeOrElse { c.ink })
            if (!subtitle.isNullOrEmpty()) Text(subtitle, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
            if (!detail.isNullOrEmpty()) Text(detail, Modifier.padding(top = 2.dp), style = MikaType.Hint, color = detailColor.takeOrElse { c.ink3 })
        }
        trailing()
    }
}

enum class ValueTone { Plain, Ok, Warn, Bad, Link }

/** 行尾的值。 */
@Composable
fun RowValue(text: String, tone: ValueTone = ValueTone.Plain) {
    val c = MikaTheme.colors
    Text(
        text, style = MikaType.Value,
        fontWeight = if (tone == ValueTone.Link) FontWeight.Medium else FontWeight.Normal,
        color = when (tone) {
            ValueTone.Plain -> c.ink3
            ValueTone.Ok -> c.ok
            ValueTone.Warn -> c.warn
            ValueTone.Bad -> c.bad
            ValueTone.Link -> c.accent
        },
        maxLines = 1,
    )
}

/** 行尾的小箭头:点进去还有一页。 */
@Composable
fun RowChevron() {
    DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(MikaSize.SmallIcon), tint = MikaTheme.colors.ink3)
}

/** 单选行:左边圆圈,选中时实心强调色。分隔线从文字处开始。 */
@Composable
fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    first: Boolean = false,
    enabled: Boolean = true,
    titleStyle: TextStyle = MikaType.Body,
    /** 标题下面的附加内容(例如能力小标签)。 */
    below: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = MikaTheme.colors
    Row(
        modifier.fillMaxWidth()
            .topHairline(first, 50.dp, c.hair)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioMark(selected, enabled)
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Text(title, style = titleStyle, color = if (enabled) c.ink else c.ink3)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
            if (below != null) Box(Modifier.padding(top = 4.dp)) { below() }
        }
        trailing()
    }
}

@Composable
private fun RadioMark(selected: Boolean, enabled: Boolean = true) {
    val c = MikaTheme.colors
    if (selected && enabled) {
        Box(Modifier.size(22.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).background(c.onAccent, CircleShape))
        }
    } else {
        Box(Modifier.size(22.dp).border(1.6.dp, c.hair, CircleShape))
    }
}

/**
 * 卡片里的输入行:左边固定宽的标题,右边单行输入框(空时显示 [placeholder])。
 * [secret] 为真时按密码显示;[trailing] 放在输入框右边(例如「显示」)。
 * [onClear] 非空且输入框里有字时,在文字右边放一个清空按钮(和搜索框的叉同一套)。
 */
@Composable
fun InputRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    first: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    labelWidth: Dp = 84.dp,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    clearDescription: String = "",
    clearModifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().topHairline(first, TextStartPlain, c.hair).keepsKeyboard()
            .heightIn(min = MikaSize.RowMin).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.width(labelWidth), style = MikaType.Body, color = c.ink)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MikaType.Body, color = c.ink3, maxLines = 1)
            BasicTextField(
                value, onChange, modifier.fillMaxWidth(), singleLine = true,
                textStyle = MikaType.Body.copy(color = c.ink, textDirection = androidx.compose.ui.text.style.TextDirection.Content),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            )
        }
        if (onClear != null && value.isNotEmpty()) {
            FieldClearButton(clearDescription, onClear, clearModifier.padding(start = 4.dp))
        }
        if (trailing != null) Box(Modifier.padding(start = 8.dp)) { trailing() }
    }
}

/** 输入框末尾的叉:点一下清空。和搜索框的清空按钮同一套大小和颜色,只在有内容时由调用方放出来。 */
@Composable
fun FieldClearButton(description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(30.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Close, description, Modifier.size(14.dp), tint = MikaTheme.colors.ink3)
    }
}

/** 卡片里自己排版的块之间的分隔线,从 [start] 画到右边。 */
@Composable
fun Hairline(start: Dp = TextStartWithIcon) {
    Box(Modifier.fillMaxWidth().padding(start = start).height(0.5.dp).background(MikaTheme.colors.hair))
}
