package com.wangzan101.mika.settings.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.setup.RootControl
import com.wangzan101.mika.core.device.shell.RootGrant
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.settings.ui.models.refresh

/** 设置 › 高级与其他 › Root。只有高级页检测到 su 才能进入;打开时再刷新一次能力。 */
@Composable
fun RootScreen(helperRow: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val root = remember { PhoneServices.of(context).shellManager.root }
    LaunchedEffect(Unit) { root.refresh() }
    val state by root.state.collectAsState()
    PageScaffold(PhoneSetupCopy.ROOT_GROUP, onBack) {
        if (state.suPresent) RootSection(state, helperRow, root::requestStart, root::setCommands)
    }
}

/** 自动启动没有开关;首次或失败后点那一行明确请求。Root 命令开关开启前先展示高风险确认。 */
@Composable
fun RootSection(
    state: RootControl.State,
    helperRow: Boolean,
    onRequestStart: () -> Unit,
    onCommands: (Boolean) -> Unit,
) {
    var warn by remember { mutableStateOf(false) }
    val commandsTo = { on: Boolean -> if (on) warn = true else onCommands(false) }
    GroupCard {
        ListRow(
            MikaIcons.Key, PhoneSetupCopy.ROOT_STATUS_TITLE, PhoneSetupCopy.rootStatusLine(state.startGrant), first = true,
        ) { RowValue(PhoneSetupCopy.ROOT_AVAILABLE, if (state.startGrant == RootGrant.Granted) ValueTone.Ok else ValueTone.Plain) }
    }
    if (helperRow) {
        GroupLabel(stringResource(R.string.settings_phone_ops))
        GroupCard {
            val request = state.startGrant != RootGrant.Granted && state.startGrant != RootGrant.Pending
            ListRow(
                MikaIcons.Refresh, PhoneSetupCopy.ROOT_START, PhoneSetupCopy.rootStartLine(state.startGrant), first = true,
                tone = if (state.startGrant == RootGrant.Denied || state.startGrant == RootGrant.NoAnswer) TileTone.Warn else TileTone.Neutral,
                onClick = if (request) onRequestStart else null,
            ) { RowValue(PhoneSetupCopy.ROOT_AUTO, if (state.startGrant == RootGrant.Granted) ValueTone.Ok else ValueTone.Plain) }
        }
        FooterNote(PhoneSetupCopy.ROOT_NOTE)
    }
    GroupLabel(PhoneSetupCopy.ROOT_COMMANDS_GROUP)
    GroupCard {
        ListRow(
            MikaIcons.Terminal, PhoneSetupCopy.ROOT_COMMANDS, PhoneSetupCopy.rootCommandsLine(state.commands, state.commandsGrant), first = true,
            tone = if (state.commands) TileTone.Warn else TileTone.Neutral,
            onClick = if (state.commandsGrant == RootGrant.Pending) null else ({ commandsTo(!state.commands) }),
        ) {
            MikaSwitch(
                state.commands,
                if (state.commandsGrant == RootGrant.Pending) ({ _ -> }) else commandsTo,
                Modifier.testTag("root-commands"),
            )
        }
    }
    FooterNote(PhoneSetupCopy.ROOT_COMMANDS_NOTE)
    if (warn) {
        MikaDialog(
            onDismissRequest = { warn = false },
            title = { Text(PhoneSetupCopy.ROOT_COMMANDS_TITLE) },
            text = { Text(PhoneSetupCopy.ROOT_COMMANDS_WARNING) },
            confirmButton = {
                TextButton({ warn = false; onCommands(true) }) { Text(PhoneSetupCopy.ROOT_COMMANDS_CONFIRM, color = MikaTheme.colors.bad) }
            },
            dismissButton = { TextButton({ warn = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
