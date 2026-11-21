package com.wangzan101.mika.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

internal val LocalOperationScreenDocked = staticCompositionLocalOf { false }

/** 实时操作画面与会话列表共用左侧区域；运行中的画面由调用方提供触控与接管按钮。 */
@Composable
internal fun OperationScreenPane(frame: Bitmap?, mode: PipMode, onConversations: () -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier, imageModifier: Modifier = Modifier, controls: (@Composable () -> Unit)? = null,
    titleModifier: Modifier = Modifier, statusText: String? = null, onViewport: (Int, Int) -> Unit = { _, _ -> }) {
    val colors = MikaTheme.colors
    val status = when (mode) {
        PipMode.Running -> R.string.task_running
        PipMode.NeedsYou -> R.string.screens_needs_you
        PipMode.Done -> R.string.task_done
    }
    Column(modifier.fillMaxSize().background(colors.bg).imePadding().testTag("operation-screen-pane")) {
        TopBar(
            stringResource(R.string.pip_screen),
            left = MikaIcons.Menu, leftLabel = stringResource(R.string.chat_menu), onLeft = onConversations,
            subtitle = statusText ?: stringResource(status),
            titleModifier = titleModifier,
            subtitleColor = if (mode == PipMode.NeedsYou) colors.warn else colors.ok,
            right = {
                BarIconButton(MikaIcons.EyeOff, stringResource(R.string.screens_hide_preview), onClose)
            },
        )
        Box(Modifier.weight(1f).fillMaxSize().padding(4.dp).onSizeChanged { onViewport(it.width, it.height) }.testTag("operation-screen-viewport"), contentAlignment = Alignment.Center) {
            if (frame != null) {
                val image = remember(frame) { frame.asImageBitmap() }
                Image(image, stringResource(R.string.pip_screen), Modifier.fillMaxSize().then(imageModifier).testTag("operation-screen-frame"),
                    contentScale = ContentScale.Fit)
            } else {
                Text(stringResource(R.string.screens_waiting), style = MikaType.Hint, color = colors.ink3)
            }
        }
        controls?.invoke()
    }
}
