package com.wangzan101.mika.settings.ui

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.settings.ThemeAccent
import com.wangzan101.mika.settings.ThemeMode
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.settings.SkinBlur
import com.wangzan101.mika.settings.SkinSpec
import com.wangzan101.mika.ui.components.MikaSwitch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlin.math.roundToInt
import com.wangzan101.mika.browser.ui.WebSitesSection
import com.wangzan101.mika.browser.ui.WebSitesState
import com.wangzan101.mika.ui.Route

// ---------- 手机操作 ----------

/** 设置 › 网站登录。 */
@Composable
fun WebSitesScreen(state: WebSitesState, onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.settings_web_logins), onBack) { WebSitesSection(state, labeled = false) }
}

// ---------- 高级 ----------

/** 设置 › 文件与计算环境:允许脚本联网、网络代理、已用空间(按对话清理工作区)、重置运行环境。 */
@Composable
fun SandboxScreen(onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.settings_sandbox), onBack) { AdvancedSettings() }
}

/** 设置 › 记住的操作路线:[count] 条,可以全部清除(先确认);[count] 为空表示还没读出来。 */
@Composable
fun RoutesScreen(count: Int?, onClear: () -> Unit, onBack: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    PageScaffold(stringResource(R.string.settings_routes), onBack) {
        GroupCard {
            ListRow(
                MikaIcons.Route, stringResource(R.string.routes_saved),
                if (count == null) stringResource(R.string.loading) else trPlural(R.plurals.routes_count, count), first = true,
            ) {
                if (count != null && count > 0) Text(
                    stringResource(R.string.routes_clear_all), Modifier.clickable { confirmClear = true }.padding(4.dp),
                    style = MikaType.Value, color = MikaTheme.colors.bad,
                )
            }
        }
    }
    if (confirmClear) {
        MikaDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.routes_clear_title)) },
            text = { Text(stringResource(R.string.routes_clear_body)) },
            confirmButton = { TextButton({ confirmClear = false; onClear() }) { Text(stringResource(R.string.clear)) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** 设置 › 恢复与修复:手机操作的几个授权开关,下面是兼容性自检的入口。 */
@Composable
fun RecoveryScreen(onBack: () -> Unit, onOpenSelfCheck: () -> Unit = {}) {
    PageScaffold(stringResource(R.string.settings_recovery), onBack) {
        RecoverySwitches()
        GroupCard {
            ListRow(
                MikaIcons.Bug, stringResource(R.string.selfcheck_title), stringResource(R.string.selfcheck_row_sub), first = true,
                onClick = onOpenSelfCheck,
            ) { RowChevron() }
        }
    }
}

/** 外观的当前说法(设置页和这一行都用)。 */
fun appearanceLabel(mode: ThemeMode): String = tr(
    when (mode) {
        ThemeMode.System -> R.string.appearance_system
        ThemeMode.Light -> R.string.appearance_light
        ThemeMode.Dark -> R.string.appearance_dark
    },
)

/**
 * 背景图片的调节:遮罩浓度、图片位置、模糊、强调色跟不跟图片、换回纯色。
 * 页面底下就是这张图,拖的时候当场看到效果;值变了才写设置(整数百分比、位置按 5% 一档),不是每一帧都写。
 */
@Composable
internal fun SkinControls(skin: SkinSpec, onChange: (SkinSpec) -> Unit, onClear: () -> Unit) {
    val scrimLabel = stringResource(R.string.skin_scrim)
    ListRow(null, scrimLabel) { RowValue("${skin.scrim}%") }
    Slider(
        value = skin.scrim.toFloat(),
        onValueChange = { v -> v.roundToInt().let { if (it != skin.scrim) onChange(skin.copy(scrim = it)) } },
        valueRange = SkinSpec.MIN_SCRIM.toFloat()..SkinSpec.MAX_SCRIM.toFloat(),
        modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = scrimLabel }.testTag("skin-scrim"),
    )
    val positionLabel = stringResource(R.string.skin_position)
    ListRow(null, positionLabel)
    Slider(
        value = skin.focus,
        onValueChange = { v -> ((v * 20).roundToInt() / 20f).let { if (it != skin.focus) onChange(skin.copy(focus = it)) } },
        valueRange = -1f..1f,
        modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = positionLabel }.testTag("skin-position"),
    )
    ListRow(null, stringResource(R.string.skin_blur))
    SegmentedControl(
        listOf(stringResource(R.string.skin_blur_off), stringResource(R.string.skin_blur_light), stringResource(R.string.skin_blur_strong)),
        selected = skin.blur.ordinal,
        onSelect = { onChange(skin.copy(blur = SkinBlur.entries[it])) },
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    )
    ListRow(
        null, stringResource(R.string.skin_accent_follow), stringResource(R.string.skin_accent_follow_sub),
        onClick = { onChange(skin.copy(accentFromImage = !skin.accentFromImage)) },
    ) {
        MikaSwitch(skin.accentFromImage, { onChange(skin.copy(accentFromImage = it)) }, Modifier.testTag("skin-accent-follow"))
    }
    ListRow(null, stringResource(R.string.skin_clear), onClick = onClear)
}

fun accentLabel(accent: ThemeAccent): String = tr(
    when (accent) {
        ThemeAccent.Blue -> R.string.accent_blue
        ThemeAccent.Indigo -> R.string.accent_indigo
        ThemeAccent.Purple -> R.string.accent_purple
        ThemeAccent.Pink -> R.string.accent_pink
        ThemeAccent.Teal -> R.string.accent_teal
        ThemeAccent.Graphite -> R.string.accent_graphite
    },
)
