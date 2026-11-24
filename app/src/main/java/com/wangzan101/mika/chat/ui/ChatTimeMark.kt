package com.wangzan101.mika.chat.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 隔了一阵的两轮之间,居中一行浅色小字的时间(见 [timeMarkAt])。 */
@Composable
internal fun TimeMarkLine(ms: Long, key: String) {
    val context = LocalContext.current
    Text(
        chatTime(context, ms),
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp).testTag(key),
        style = MikaType.Hint, color = MikaTheme.colors.ink3, textAlign = TextAlign.Center, maxLines = 1,
    )
}

/** 对话里的时刻:今天的只给时间,昨天的是「昨天 21:30」,更早的带上日期。 */
internal fun chatTime(context: Context, ms: Long): String =
    if (!DateUtils.isToday(ms) && DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS)) {
        context.getString(R.string.chat_time_yesterday, DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_TIME))
    } else {
        callTime(context, ms)
    }
