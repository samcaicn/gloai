package com.wangzan101.mika.ui.terminal

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.chat.WorkspaceResolver
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.terminal.TerminalSession
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import kotlinx.coroutines.flow.MutableStateFlow

/** 终端页的入口:打开(或附着)这个工作区或对话的会话,把状态交给 [TerminalScreen]。 */
@Composable
internal fun TerminalRoute(
    app: MikaApp,
    conversationId: String?,
    workspaceKey: WorkspaceKey?,
    title: String?,
    onDone: () -> Unit,
) {
    val targetKey = workspaceKey ?: WorkspaceResolver.resolveFor(app, requireNotNull(conversationId))
    var attempt by remember { mutableIntStateOf(0) }
    var session by remember {
        mutableStateOf(
            (if (workspaceKey != null) app.terminals.sessionForWorkspace(workspaceKey)
            else app.terminals.session(requireNotNull(conversationId)))
                ?.takeIf { it.phase.value == TerminalSession.Phase.Running },
        )
    }
    var opening by remember { mutableStateOf(session == null) }
    var error by remember { mutableStateOf<SandboxError?>(null) }

    LaunchedEffect(conversationId, workspaceKey, attempt) {
        if (session != null) { opening = false; return@LaunchedEffect }
        opening = true
        error = null
        try {
            session = if (workspaceKey != null) app.terminals.openWorkspace(workspaceKey, title)
            else app.terminals.open(requireNotNull(conversationId), title)
        } catch (e: SandboxError) {
            error = e
        } catch (e: Exception) {
            error = SandboxError("terminal_failed", e.message ?: "terminal failed", recoverable = true)
        } finally {
            opening = false
        }
    }

    val closed = remember { MutableStateFlow(TerminalSession.Phase.Closed) }
    val noExit = remember { MutableStateFlow<Int?>(null) }
    val phase by (session?.phase ?: closed).collectAsState()
    val exitCode by (session?.exitCode ?: noExit).collectAsState()
    val network = session?.network ?: app.sandboxState.networkAllowed
    val running = app.taskState.activeConversations.collectAsState().value.any { WorkspaceResolver.resolveFor(app, it) == targetKey }

    TerminalScreen(
        session = session,
        conversationTitle = title,
        network = network,
        taskRunning = running,
        opening = opening,
        error = error,
        phase = phase,
        exitCode = exitCode,
        onRetry = {
            session?.let {
                if (workspaceKey != null) app.terminals.closeWorkspace(workspaceKey)
                else app.terminals.close(requireNotNull(conversationId))
            }
            session = null
            attempt++
        },
        onClose = {
            if (workspaceKey != null) app.terminals.closeWorkspace(workspaceKey)
            else app.terminals.close(requireNotNull(conversationId))
            onDone()
        },
        onBack = onDone,
    )
}

@Composable
private fun TerminalScreen(
    session: TerminalSession?,
    conversationTitle: String?,
    network: Boolean,
    taskRunning: Boolean,
    opening: Boolean,
    error: SandboxError?,
    phase: TerminalSession.Phase,
    exitCode: Int?,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    var confirmClose by remember { mutableStateOf(false) }
    val finished = phase != TerminalSession.Phase.Running

    val handle = remember(session) { session?.let { createTerminalWebView(context, it) } }
    DisposableEffect(handle) {
        onDispose {
            (handle?.view?.parent as? ViewGroup)?.removeView(handle.view)
            handle?.close()
        }
    }

    BackHandler {
        if (finished) onBack() else confirmClose = true
    }

    fun send(bytes: ByteArray) {
        val s = session ?: return
        runCatching { s.writeBytes(bytes) }
        handle?.focus()
    }

    PageBackground {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopBar(
                title = tr(R.string.terminal_title),
                left = MikaIcons.Back,
                onLeft = { if (finished) onBack() else confirmClose = true },
                leftLabel = stringResource(R.string.back),
                subtitle = conversationTitle,
                right = {
                    Text(
                        if (network) tr(R.string.terminal_network_on) else tr(R.string.terminal_network_off),
                        style = MikaType.Hint,
                        color = if (network) c.ok else c.warn,
                        modifier = Modifier.padding(end = 2.dp),
                    )
                    BarIconButton(
                        MikaIcons.Copy,
                        tr(R.string.terminal_copy_all),
                        onClick = { handle?.copyAll() },
                        enabled = handle != null,
                    )
                    BarIconButton(
                        MikaIcons.Close,
                        tr(R.string.terminal_close),
                        onClick = { if (finished) onBack() else confirmClose = true },
                    )
                },
            )

            if (taskRunning) {
                Text(
                    tr(R.string.terminal_task_running),
                    Modifier.fillMaxWidth().background(c.warn.copy(alpha = 0.12f)).padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MikaType.Hint,
                    color = c.warn,
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth().background(ConsoleBg)) {
                when {
                    opening -> Message(tr(R.string.terminal_preparing))
                    error != null -> ErrorBox(error, onRetry)
                    session == null -> Message(tr(R.string.terminal_unavailable))
                    else -> {
                        val h = handle
                        if (h != null) {
                            key(h) {
                                AndroidView(factory = { h.view }, modifier = Modifier.fillMaxSize())
                            }
                        } else {
                            Message(tr(R.string.terminal_unavailable))
                        }
                        if (finished) {
                            ResultBanner(phase, exitCode, onRetry, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
                        }
                    }
                }
            }

            if (!opening && error == null && session != null && !finished) {
                ExtraKeys(
                    onEsc = { send(byteArrayOf(27)) },
                    onTab = { send(byteArrayOf(9)) },
                    onCtrlC = { send(byteArrayOf(3)) },
                    onLeft = { send(byteArrayOf(27, 91, 68)) },
                    onRight = { send(byteArrayOf(27, 91, 67)) },
                    onUp = { send(byteArrayOf(27, 91, 65)) },
                    onDown = { send(byteArrayOf(27, 91, 66)) },
                    onKeyboard = { handle?.focus() },
                    onStop = { confirmClose = true },
                )
            }
        }
    }

    if (confirmClose) {
        MikaDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text(tr(R.string.terminal_close_title)) },
            text = { Text(tr(R.string.terminal_close_body)) },
            confirmButton = {
                TextButton({ confirmClose = false; onClose() }) { Text(tr(R.string.terminal_close_confirm), color = c.bad) }
            },
            dismissButton = {
                TextButton({ confirmClose = false }) { Text(tr(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopStart) {
        Text(text, style = MikaType.Body, color = ConsoleDim)
    }
}

@Composable
private fun ErrorBox(error: SandboxError, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(errorText(error), style = MikaType.Body, color = ConsoleFg)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onRetry) { Text(tr(R.string.terminal_retry), color = MikaTheme.colors.accent) }
    }
}

@Composable
private fun ResultBanner(phase: TerminalSession.Phase, exitCode: Int?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    Row(
        modifier.fillMaxWidth().background(c.surface.copy(alpha = 0.96f)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(resultText(phase, exitCode), Modifier.weight(1f), style = MikaType.Body, color = c.ink)
        TextButton(onClick = onRetry) { Text(tr(R.string.terminal_retry), color = c.accent) }
    }
}

@Composable
private fun ExtraKeys(
    onEsc: () -> Unit,
    onTab: () -> Unit,
    onCtrlC: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onKeyboard: () -> Unit,
    onStop: () -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Key("Esc", onEsc)
        Key("Tab", onTab)
        Key("^C", onCtrlC)
        Key("←", onLeft)
        Key("↑", onUp)
        Key("↓", onDown)
        Key("→", onRight)
        Key("⌨", onKeyboard)
        Spacer(Modifier.weight(1f))
        Key(tr(R.string.terminal_stop), onStop, bad = true)
    }
}

@Composable
private fun Key(text: String, onClick: () -> Unit, bad: Boolean = false) {
    val c = MikaTheme.colors
    Text(
        text,
        Modifier
            .background(c.fill, RoundedCornerShape(MikaRadius.BubbleTail))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        style = MikaType.Caption,
        color = if (bad) c.bad else c.ink,
    )
}

private fun resultText(phase: TerminalSession.Phase, exitCode: Int?): String = when (phase) {
    TerminalSession.Phase.Revoked -> tr(R.string.terminal_revoked)
    TerminalSession.Phase.Exited -> exitCode?.let { tr(R.string.terminal_exited, it) } ?: tr(R.string.terminal_closed)
    else -> tr(R.string.terminal_closed)
}

private fun errorText(error: SandboxError): String = when (error.code) {
    "sandbox_unsupported" -> tr(R.string.terminal_unsupported)
    "storage_low" -> tr(R.string.terminal_storage_low)
    "terminal_limit" -> tr(R.string.terminal_limit)
    "sandbox_busy" -> tr(R.string.terminal_busy)
    "sandbox_network_isolation_unavailable" -> tr(R.string.sandbox_isolation_unavailable)
    else -> error.message ?: tr(R.string.terminal_unavailable)
}

private val ConsoleBg = Color(0xFF101210)
private val ConsoleFg = Color(0xFFE8E8DF)
private val ConsoleDim = Color(0xFF8A8F86)
