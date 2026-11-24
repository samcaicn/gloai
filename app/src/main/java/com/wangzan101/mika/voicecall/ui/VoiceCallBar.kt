package com.wangzan101.mika.voicecall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ui.ChatCopy
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voicecall.VoiceCallState

/** 还没结束的那一通(在通、在连、出错等重试);没有或已经挂了为 null。跟着会话状态变。 */
@Composable
fun VoiceCallController.liveCall(): VoiceCallHolder? {
    val call = (stage as? VoiceCallStage.InCall)?.call ?: return null
    val state by call.session.state.collectAsState()
    return call.takeIf { state !is VoiceCallState.Ended }
}

/**
 * 通话页不在最上面时,所有页面顶上的一条绿条:「通话中 · 回到通话」和计时,点了回到通话页。
 * 它占了状态栏那一块,下面的页面从它下面开始。
 */
@Composable
fun VoiceCallBar(call: VoiceCallHolder, onClick: () -> Unit) {
    val c = MikaTheme.colors
    val state by call.session.state.collectAsState()
    val startedAt = (state as? VoiceCallState.Active)?.startedAt
    val clock by produceState("", startedAt) {
        if (startedAt == null) return@produceState
        while (true) {
            value = ChatCopy.clock(System.currentTimeMillis() - startedAt)
            kotlinx.coroutines.delay(500)
        }
    }
    Row(
        Modifier.fillMaxWidth().background(c.ok).clickable(role = Role.Button, onClick = onClick)
            .statusBarsPadding().height(36.dp).padding(horizontal = 16.dp).testTag("voicecall-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(MikaIcons.Handset, null, Modifier.size(15.dp), tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.voicecall_bar), style = MikaType.Label, color = Color.White, maxLines = 1)
        if (clock.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(clock, style = MikaType.Label, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
        }
    }
}
