package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.OpenAiServiceTier
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.SearchField
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 「<服务商> · 全部模型」:搜索框固定在顶栏下面,列表只在下面滚(模型多时按需排版);右上角刷新重新列一次。
 * 点模型同样弹出「用作」。
 */
@Composable
fun AllModelsContent(ui: ModelSetupUi, p: Provider, actions: ProviderDetailActions, onBack: () -> Unit) {
    val c = MikaTheme.colors
    var filter by rememberSaveable { mutableStateOf("") }
    var using by remember { mutableStateOf<String?>(null) }
    val state = ui.listing[p.id]
    val all = ui.sortedOptions(p)
    val q = filter.trim()
    val shown = all.filter { q.isEmpty() || it.id.contains(q, ignoreCase = true) }
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(ModelsCopy.allModelsTitle(ModelsCopy.providerName(p)), MikaIcons.Back, onBack, stringResource(R.string.back), right = {
                BarIconButton(MikaIcons.Refresh, ModelsCopy.REFRESH, actions.refresh, enabled = state != ListState.Running)
            })
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp)) {
                SearchField(filter, { filter = it }, ModelsCopy.FILTER_HINT, Modifier.testTag("all-models-filter"))
            }
            when (state) {
                ListState.Running -> FooterNote(ModelsCopy.LISTING)
                is ListState.Failed -> FooterNote(ModelSetupCopy.failed(state.message), color = c.bad)
                else -> Unit
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                itemsIndexed(shown, key = { _, o -> o.id }) { i, o ->
                    Box(Modifier.fillMaxWidth().clip(rowShape(i, shown.size)).background(c.surface)) {
                        ModelListRow(o, ui.rolesOf(p.id, o.id), first = i == 0) { using = o.id }
                    }
                }
                item {
                    FooterNote(
                        if (q.isNotEmpty() && shown.isEmpty()) ModelsCopy.NO_MATCH else ModelsCopy.allModelsNote(all.size),
                        Modifier.padding(top = 10.dp),
                    )
                    GroupCard { ManualModel(p, state = null, first = true) { using = it } }
                }
            }
        }
    }
    using?.let { model -> UseAsSheet(ui, p, model, actions, onDismiss = { using = null }) }
}

/** 一张卡拆成懒加载的行时,每行的圆角:首行上圆、末行下圆。 */
private fun rowShape(i: Int, n: Int): RoundedCornerShape {
    val r = MikaRadius.Card
    return RoundedCornerShape(topStart = if (i == 0) r else 0.dp, topEnd = if (i == 0) r else 0.dp,
        bottomStart = if (i == n - 1) r else 0.dp, bottomEnd = if (i == n - 1) r else 0.dp)
}

/** 模型一行:模型名(等宽)和标签,右边标着它在给哪些用途干活。 */
@Composable
internal fun ModelListRow(o: ModelOption, roles: List<ModelRole>, first: Boolean, onClick: () -> Unit) {
    Column {
        if (!first) Hairline(start = 16.dp)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("model-${o.id}").padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(o.id, style = ModelIdStyle, color = MikaTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.padding(top = 4.dp)) { Tags(o, accent = false) }
            }
            roles.forEach { StatusPill(ModelsCopy.roleName(it), PillTone.Accent) }
            RowChevron()
        }
    }
}

/**
 * 点一个模型后从底部打开「用作」面板(手填的模型也行)。上面是对话用途,点了就用:主模型先试调用一次
 * (试通过过的不再试,见 [ModelSetupViewModel.useFromProvider]),进度和原因显示在下面,没通过不改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UseAsSheet(ui: ModelSetupUi, p: Provider, model: String, actions: ProviderDetailActions, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    val option = ui.options(p).firstOrNull { it.id == model }
    val chatCapable = option != null
    val pick = ui.manualPick[p.id]
    val running = pick == ListState.Running
    // 上次在别处保存、试调用的结果不带进来
    LaunchedEffect(p.id, model) { actions.clearPick() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface, shape = MikaShapes.SheetTop) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 8.dp).testTag("use-as")) {
            Text(model, Modifier.padding(horizontal = 16.dp), style = ModelIdStyle, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(ModelsCopy.useAsSubtitle(ModelsCopy.providerName(p)), Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp), style = MikaType.Caption, color = c.ink3)
            if (chatCapable) {
                ModelRole.entries.forEachIndexed { i, role ->
                    val on = ui.picked(role, p.id, model)
                    RadioRow(
                        ModelsCopy.roleName(role), selected = on, first = i == 0, enabled = !running,
                        subtitle = if (on) ModelsCopy.IN_USE else ModelsCopy.roleWhat(role),
                        modifier = Modifier.testTag("model-use-${role.name.lowercase()}"),
                        onClick = { actions.use(role, model, onDismiss) },
                    )
                }
            } else {
                // 目录里写明只有别的接口的模型:对话用途不列(设了也办不了事)
                FooterNote(tr(R.string.model_not_chat_capable), Modifier.testTag("use-as-not-chat-capable"))
            }
            when {
                running -> FooterNote(ModelSetupCopy.TESTING, Modifier.testTag("use-as-testing"))
                pick is ListState.Failed -> FooterNote(pick.message, Modifier.testTag("use-as-failed"), color = c.bad)
            }
            option?.let { o ->
                val usedForVision = ModelRole.Vision in ui.rolesOf(p.id, model)
                val sees = ModelOptions.seesImages(o.capabilities.vision, o.visionByUser, usedForVision)
                ListRow(null, ModelsCopy.SEES_IMAGES, ModelsCopy.seesNote(o, usedForVision)) {
                    MikaSwitch(sees, { actions.setVision(model, it) }, Modifier.testTag("model-sees-images"))
                }
            }
            if (p.supportsServiceTier(model)) {
                ModelSpeedControl(p.serviceTiers[model]) { actions.setServiceTier(model, it) }
            }
            ListRow(null, ModelSetupCopy.CANCEL, titleColor = c.ink2, onClick = onDismiss)
        }
    }
}

@Composable
private fun ModelSpeedControl(selected: OpenAiServiceTier?, onSelect: (OpenAiServiceTier?) -> Boolean) {
    var open by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val title = stringResource(R.string.models_response_speed)
    ListRow(
        null, title, onClick = { saveFailed = false; open = true },
        modifier = Modifier.testTag("model-response-speed"),
    ) {
        RowValue(serviceTierLabel(selected))
        RowChevron()
    }
    if (open) MikaDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FooterNote(stringResource(R.string.models_response_speed_note))
                (listOf(null) + OpenAiServiceTier.entries).forEachIndexed { index, tier ->
                    RadioRow(serviceTierLabel(tier), selected = selected == tier, first = index == 0, onClick = {
                        if (onSelect(tier)) open = false else saveFailed = true
                    })
                }
                if (saveFailed) FooterNote(stringResource(R.string.model_context_save_failed), color = MikaTheme.colors.bad)
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun serviceTierLabel(tier: OpenAiServiceTier?): String = when (tier) {
    null -> stringResource(R.string.model_reasoning_default)
    OpenAiServiceTier.Fast -> "Fast"
    OpenAiServiceTier.Ultrafast -> "Ultrafast"
}
