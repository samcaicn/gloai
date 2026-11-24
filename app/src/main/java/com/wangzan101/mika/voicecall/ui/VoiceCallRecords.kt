package com.wangzan101.mika.voicecall.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationCall
import com.wangzan101.mika.chat.ui.ChatCopy
import com.wangzan101.mika.chat.ui.callTime
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 这个对话的通话记录,新的在上:接通时刻,下面一行时长(还在通的是「通话中」,没说话的注明)。
 * 点一条到聊天里那一通开头,不另开回放页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallRecordsSheet(calls: List<ConversationCall>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = MikaShapes.SheetTop,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp).testTag("voicecall-records-sheet")) {
            Text(stringResource(R.string.voicecall_records), Modifier.padding(horizontal = 16.dp), style = MikaType.BodyStrong, color = c.ink)
            Text(
                stringResource(if (calls.isEmpty()) R.string.voicecall_records_empty else R.string.voicecall_records_hint),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MikaType.Caption, color = c.ink3,
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                itemsIndexed(calls.asReversed(), key = { _, it -> it.id }) { i, call ->
                    val live = call.endMs == null && call.id == current
                    val detail = when {
                        live -> stringResource(R.string.voicecall_log_live)
                        call.endMs != null && !call.spoke -> "${ChatCopy.clock(call.durationMs ?: 0L)} · ${stringResource(R.string.voicecall_records_silent)}"
                        else -> call.durationMs?.let(ChatCopy::clock)
                    }
                    ListRow(
                        MikaIcons.Handset, callTime(context, call.startMs), subtitle = detail, first = i == 0,
                        onClick = { onPick(call.id) },
                        modifier = Modifier.testTag("voicecall-record"),
                    )
                }
            }
        }
    }
}
