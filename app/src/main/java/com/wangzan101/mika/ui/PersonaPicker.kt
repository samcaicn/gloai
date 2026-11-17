package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.Persona
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 输入栏上方的人格 chip:显示当前人格,点开选择面板。任务在跑时禁用,提示在面板里说。
 * 人格被删除时显示「已删除的人格」,不假装还是默认。
 */
@Composable
internal fun PersonaChip(current: String?, personas: List<Persona>, enabled: Boolean, onClick: () -> Unit) {
    val persona = personas.firstOrNull { it.id == current }
    val label = when {
        current == null -> stringResource(R.string.persona_chip, stringResource(R.string.persona_default))
        persona != null -> stringResource(R.string.persona_chip, persona.name)
        else -> stringResource(R.string.persona_chip, stringResource(R.string.persona_missing))
    }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp)) {
        MikaButton(
            label, onClick, kind = ButtonKind.Tonal, size = ButtonSize.Small, enabled = enabled,
            modifier = Modifier.testTag("persona-chip"),
        )
    }
}

/** 人格选择面板:默认、已保存的人格、管理入口。选了就关面板;任务在跑时 chip 本身是禁用的。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonaPicker(
    current: String?,
    personas: List<Persona>,
    onSelect: (String?) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MikaTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp).testTag("persona-picker"),
        ) {
            Text(stringResource(R.string.persona_title), style = MikaType.Title, color = c.ink)
            GroupCard(Modifier.padding(top = 10.dp)) {
                RadioRow(
                    stringResource(R.string.persona_default), selected = current == null,
                    subtitle = stringResource(R.string.persona_default_sub), first = true,
                    onClick = { onSelect(null); onDismiss() },
                )
                personas.forEach { p ->
                    RadioRow(
                        p.name, selected = current == p.id,
                        subtitle = p.description.takeIf { it.isNotBlank() },
                        onClick = { onSelect(p.id); onDismiss() },
                    )
                }
            }
            GroupCard(Modifier.padding(top = 10.dp)) {
                ListRow(MikaIcons.Edit, stringResource(R.string.persona_manage), first = true, onClick = onManage)
            }
            FooterNote(stringResource(R.string.persona_switch_note))
        }
    }
}
