package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import kotlinx.coroutines.delay

private val RowShape = MikaShapes.Button

/** 一轮下面留页的那一行该怎么显示:还开着(绿色那一行,能关)、已经关了(一句灰字)、或这一轮没留页。 */
sealed interface KeptRow {
    data class Live(val page: KeptPage) : KeptRow
    data class Closed(val reason: String?) : KeptRow

    companion object {
        /**
         * [ex] 那一轮下面显示什么:此刻留着的 [live] 就是这一轮留的,是 [Live];这一轮记着留过页、而它已经不在了,是 [Closed]
         * (没记下原因的按 Mika 退出了说);别的为 null。
         */
        fun of(ex: Exchange, live: KeptPage?): KeptRow? = when {
            live != null && ex.runId != null && live.runId == ex.runId -> Live(live)
            ex.keptPage != null -> Closed(ex.keptPageClosed)
            else -> null
        }
    }
}

/** 一轮下面留页的那一行(见 [KeptRow])。 */
internal fun LazyListScope.keptPageRow(index: Int, ex: Exchange, live: KeptPage?, onClose: () -> Unit) {
    val row = KeptRow.of(ex, live) ?: return
    item("k$index") {
        when (row) {
            is KeptRow.Live -> KeptLine(row.page, onClose)
            is KeptRow.Closed -> Text(
                KeptPageCopy.closedNote(row.reason), Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 10.dp).testTag("kept-closed"),
                style = MikaType.Hint, color = MikaTheme.colors.ink3,
            )
        }
    }
}

/** 绿色那一行:「这一页保持在后台运行」,下面「站点 · 还剩多久后自动关」,右边「关闭」。剩下的时间每分钟走一次。 */
@Composable
fun KeptLine(page: KeptPage, onClose: () -> Unit) {
    val c = MikaTheme.colors
    val now by produceState(System.currentTimeMillis(), page.id) {
        while (true) {
            delay(TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).testTag("kept-row").clip(RowShape).background(c.okSoft, RowShape)
            .border(1.dp, c.ok.copy(alpha = 0.25f), RowShape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(MikaIcons.Globe, null, Modifier.size(18.dp), tint = c.ok)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.kept_row_title), style = MikaType.Label.copy(fontWeight = FontWeight.SemiBold), color = c.ok)
            Text(
                stringResource(R.string.kept_row_detail, page.host, KeptPageCopy.closesIn(page.closesAt - now)),
                style = MikaType.Hint, color = c.ok, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier.testTag("kept-close").clip(MikaShapes.Tile).background(c.surface)
                .clickable(role = Role.Button, onClick = onClose).padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(stringResource(R.string.kept_row_close), style = MikaType.Label.copy(fontWeight = FontWeight.SemiBold), color = c.bad)
        }
    }
}

private const val TICK_MS = 30_000L
