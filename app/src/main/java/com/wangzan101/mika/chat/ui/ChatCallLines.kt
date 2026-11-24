package com.wangzan101.mika.chat.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationCall
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 把 [marks] 画成列表里的几行;[highlightKey] 是从通话记录跳过来时要亮一下的那行。 */
internal fun LazyListScope.callMarkRows(marks: List<CallMark>, highlightKey: String?, reduceMotion: Boolean) {
    marks.forEach { mark ->
        item(mark.key) { CallMarkLine(mark, highlightKey == mark.key, reduceMotion) }
    }
}

/** 一行浅色小字,居中:听筒 + 「语音通话 · 9:41」/「通话结束 · 3:12」/「语音通话 · 0:05 · 没有说话」。 */
@Composable
private fun CallMarkLine(mark: CallMark, highlighted: Boolean, reduceMotion: Boolean) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val call = mark.call
    val duration = ChatCopy.clock(call.durationMs ?: 0L)
    val text = when (mark) {
        is CallMark.Start -> stringResource(R.string.voicecall_log_start, callTime(context, call.startMs))
        is CallMark.End ->
            if (call.endReason == ConversationCall.END_IDLE) stringResource(R.string.voicecall_log_end_idle, duration)
            else stringResource(R.string.voicecall_log_end, duration)
        is CallMark.Silent -> stringResource(R.string.voicecall_log_silent, duration)
    }
    JumpHighlight(highlighted, reduceMotion) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag(mark.key),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MikaIcons.Handset, null, Modifier.size(13.dp), tint = c.ink3)
            Text(text, style = MikaType.Hint, color = c.ink3, maxLines = 1)
        }
    }
}

/** 通话接通的时刻:今天的只给时间,更早的带上日期。 */
internal fun callTime(context: Context, ms: Long): String {
    val flags = DateUtils.FORMAT_SHOW_TIME or
        if (DateUtils.isToday(ms)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    return DateUtils.formatDateTime(context, ms, flags)
}
