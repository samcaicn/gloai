package com.wangzan101.mika.capsule.ui

import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.settings.FeatureFlags
import androidx.compose.foundation.layout.size
import com.wangzan101.mika.ui.components.MikaSwitch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.capsule.CapsuleCardScale
import com.wangzan101.mika.capsule.CapsuleIdleOpacity
import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.capsule.CapsuleTileService
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlin.math.roundToInt

/** 设置 › 悬浮胶囊的文字。 */
object CapsuleSettingsCopy {
    val TITLE: String get() = tr(R.string.capsule_settings_title)
    val SUBTITLE: String get() = tr(R.string.capsule_settings_sub)
    val FOOTNOTE: String get() = tr(R.string.capsule_settings_foot)
    /** 系统可能关掉后台的 App,胶囊跟着不见:请用户在系统设置里放行。 */
    val BACKGROUND: String get() = tr(R.string.capsule_settings_background)
    val IDLE_OPACITY: String get() = tr(R.string.capsule_idle_opacity)
    val IDLE_OPACITY_DETAIL: String get() = tr(R.string.capsule_idle_opacity_detail)
    val CARD_SIZE: String get() = tr(R.string.capsule_card_size)
    val CARD_SIZE_DETAIL: String get() = tr(R.string.capsule_card_size_detail)

    fun scaleLabel(scale: CapsuleCardScale): String = when (scale) {
        CapsuleCardScale.Small -> tr(R.string.capsule_card_size_small)
        CapsuleCardScale.Normal -> tr(R.string.capsule_card_size_normal)
        CapsuleCardScale.Large -> tr(R.string.capsule_card_size_large)
        CapsuleCardScale.XLarge -> tr(R.string.capsule_card_size_xlarge)
    }

    fun label(mode: CapsuleMode): String = when (mode) {
        CapsuleMode.Off -> tr(R.string.capsule_mode_off)
        CapsuleMode.DuringTasks -> tr(R.string.capsule_mode_tasks)
        CapsuleMode.Always -> tr(R.string.capsule_mode_always)
    }

    fun detail(mode: CapsuleMode): String = when (mode) {
        CapsuleMode.Off -> tr(R.string.capsule_mode_off_detail)
        CapsuleMode.DuringTasks -> tr(R.string.capsule_mode_tasks_detail)
        CapsuleMode.Always -> tr(R.string.capsule_mode_always_detail)
    }
}

/** 设置 › 悬浮胶囊:关闭 / 有任务时显示(默认) / 一直显示,选了当场生效;静置不透明度与展开卡片的大小。 */
@Composable
fun CapsuleScreen(
    mode: CapsuleMode,
    onMode: (CapsuleMode) -> Unit,
    onBack: () -> Unit,
    idleOpacity: CapsuleIdleOpacity = CapsuleIdleOpacity.DEFAULT,
    onIdleOpacity: (CapsuleIdleOpacity) -> Unit = {},
    cardScale: CapsuleCardScale = CapsuleCardScale.DEFAULT,
    onCardScale: (CapsuleCardScale) -> Unit = {},
) {
    val context = LocalContext.current
    val flags = remember { FeatureFlags.get(context) }
    val hideAtEdge by remember(flags) { flags.capsuleHideAtEdgeChanges() }.collectAsState(flags.capsuleHideAtEdge())
    val hideFullscreen by remember(flags) { flags.capsuleHideFullscreenChanges() }.collectAsState(flags.capsuleHideFullscreen())
    var quickSettings by remember { mutableStateOf(CapsuleTileService.isAvailable(context)) }
    fun setQuickSettings(on: Boolean) {
        CapsuleTileService.setAvailable(context, on)
        quickSettings = CapsuleTileService.isAvailable(context)
    }
    PageScaffold(CapsuleSettingsCopy.TITLE, onBack) {
        GroupCard {
            CapsuleMode.entries.forEachIndexed { i, m ->
                RadioRow(
                    CapsuleSettingsCopy.label(m), selected = m == mode, onClick = { onMode(m) },
                    subtitle = CapsuleSettingsCopy.detail(m), first = i == 0,
                )
            }
        }
        GroupLabel(CapsuleSettingsCopy.IDLE_OPACITY)
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                ListRow(
                    MikaIcons.Photo, CapsuleSettingsCopy.IDLE_OPACITY,
                    first = true, titleColor = if (mode == CapsuleMode.Off) MikaTheme.colors.ink3 else androidx.compose.ui.graphics.Color.Unspecified,
                ) { RowValue("${idleOpacity.percent}%") }
                Slider(
                    value = CapsuleIdleOpacity.entries.indexOf(idleOpacity).toFloat(),
                    onValueChange = {
                        val index = it.roundToInt().coerceIn(CapsuleIdleOpacity.entries.indices)
                        onIdleOpacity(CapsuleIdleOpacity.entries[index])
                    },
                    valueRange = 0f..3f,
                    steps = 2,
                    enabled = mode != CapsuleMode.Off,
                    modifier = Modifier.testTag("capsule-idle-opacity"),
                )
            }
        }
        GroupLabel(CapsuleSettingsCopy.CARD_SIZE)
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                ListRow(
                    MikaIcons.Photo, CapsuleSettingsCopy.CARD_SIZE,
                    first = true, titleColor = if (mode == CapsuleMode.Off) MikaTheme.colors.ink3 else androidx.compose.ui.graphics.Color.Unspecified,
                ) { RowValue(CapsuleSettingsCopy.scaleLabel(cardScale)) }
                Slider(
                    value = CapsuleCardScale.entries.indexOf(cardScale).toFloat(),
                    onValueChange = {
                        val index = it.roundToInt().coerceIn(CapsuleCardScale.entries.indices)
                        onCardScale(CapsuleCardScale.entries[index])
                    },
                    valueRange = 0f..3f,
                    steps = 2,
                    enabled = mode != CapsuleMode.Off,
                    modifier = Modifier.testTag("capsule-card-size"),
                )
            }
        }
        GroupCard {
            ListRow(MikaIcons.EyeOff, stringResource(R.string.capsule_hide_at_edge), first = true,
                onClick = { flags.setCapsuleHideAtEdge(!hideAtEdge) }) {
                MikaSwitch(hideAtEdge, flags::setCapsuleHideAtEdge, Modifier.testTag("capsule-hide-at-edge"))
            }
            ListRow(MikaIcons.Screens, stringResource(R.string.capsule_hide_fullscreen),
                onClick = { flags.setCapsuleHideFullscreen(!hideFullscreen) }) {
                MikaSwitch(hideFullscreen, flags::setCapsuleHideFullscreen, Modifier.testTag("capsule-hide-fullscreen"))
            }
            ListRow(MikaIcons.Apps, stringResource(R.string.capsule_tile_enabled),
                onClick = { setQuickSettings(!quickSettings) }) {
                MikaSwitch(quickSettings, ::setQuickSettings, Modifier.testTag("capsule-quick-settings"))
            }
        }
        FooterNote(CapsuleSettingsCopy.FOOTNOTE)
        FooterNote(CapsuleSettingsCopy.BACKGROUND)
    }
}
