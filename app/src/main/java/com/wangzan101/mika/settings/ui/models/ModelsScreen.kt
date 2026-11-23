package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 设置 › 模型:和上手时的配置模型是同一页([ModelSetupScreen]),不在引导流程里。 */
@Composable
fun ModelsScreen(
    store: ProviderStore, onBack: () -> Unit,
    onOpenSpeech: (() -> Unit)? = null,
    onOpenVoice: (() -> Unit)? = null,
    onOpenImage: (() -> Unit)? = null,
    onOpenUsage: (() -> Unit)? = null,
    onOpenVoiceCatalog: (() -> Unit)? = null,
    onOpenCallMode: (() -> Unit)? = null,
) {
    ModelSetupScreen(store, onboarding = false, onBack = onBack, onDone = onBack, onOpenSpeech = onOpenSpeech, onOpenVoice = onOpenVoice, onOpenImage = onOpenImage, onOpenUsage = onOpenUsage, onOpenVoiceCatalog = onOpenVoiceCatalog, onOpenCallMode = onOpenCallMode)
}

/** 子页面共用的骨架:顶栏 + 可滚动内容。 */
@Composable
internal fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val scrollState = rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(0)
    }
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(title, MikaIcons.Back, onBack, stringResource(R.string.back))
            Column(Modifier.weight(1f).verticalScroll(scrollState).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                content()
            }
        }
    }
}

/** 用途卡:大图标块、「名字 · 做什么」、选了什么(模型名用等宽字)、说明。 */
@Composable
internal fun RoleCard(icon: ImageVector, label: String, line: ModelsCopy.RoleLine, first: Boolean = false, tag: String, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Column {
        if (!first) Hairline(start = 0.dp)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(40.dp).background(c.neutralSoft, RoleTileShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(21.dp), tint = c.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = MikaType.Hint, color = c.ink3)
                Text(
                    line.title, Modifier.padding(top = 1.dp), style = MikaType.BodyStrong,
                    fontFamily = if (line.isModel) FontFamily.Monospace else null, color = if (line.unset) c.ink3 else c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (line.subtitle.isNotEmpty()) {
                    Text(
                        line.subtitle, Modifier.padding(top = 1.dp), style = MikaType.Caption,
                        color = if (line.subtitleOk) c.ok else c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RowChevron()
        }
    }
}

private val RoleTileShape = MikaShapes.Chip

/** 服务商一行:左边是名字首字的图标块;[warning] 是要提醒的一句(用提醒色另起一行)。 */
@Composable
internal fun ProviderRow(p: Provider, line: String, first: Boolean, warning: String? = null, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Column {
        if (!first) Hairline()
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("provider-${p.name}").padding(horizontal = 16.dp).padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(MikaSize.Tile).background(c.neutralSoft, MikaShapes.Tile), contentAlignment = Alignment.Center) {
                Text(p.name.take(1).uppercase(), style = MikaType.Caption.copy(fontWeight = FontWeight.Bold), color = c.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(ModelsCopy.providerName(p), style = MikaType.Body, color = c.ink, maxLines = 1)
                val poolCount = com.wangzan101.mika.core.model.KeyPoolManager.shared.keyCount(p.id)
                val poolTag = if (poolCount > 1) " · Key Pool ($poolCount keys)" else ""
                Text(line + poolTag, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = if (poolCount > 1) c.ok else c.ink3)
                warning?.let { Text(it, Modifier.padding(top = 2.dp).testTag("provider-warning-${p.name}"), style = MikaType.Caption, color = c.warn) }
            }
            RowChevron()
        }
    }
}

// ---------- 选某个用途用哪个模型 ----------
