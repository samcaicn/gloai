package com.wangzan101.mika.wechat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.CapabilityTag
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.StateCard
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.wechat.WechatConnectionState
import com.wangzan101.mika.wechat.WechatRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.ui.QrCodeImage
import com.wangzan101.mika.ui.encodeQr

/** 设置首页的状态胶囊；未绑定不影响页面的「一切就绪」检查。 */
@Composable
internal fun WechatEntryStatus(state: WechatConnectionState, bound: Boolean) {
    val (text, tone) = when (state) {
        WechatConnectionState.Connected -> R.string.wechat_status_connected to PillTone.Ok
        WechatConnectionState.Connecting -> R.string.wechat_status_connecting to PillTone.Accent
        WechatConnectionState.Off -> R.string.wechat_status_paused to PillTone.Neutral
        is WechatConnectionState.Failed -> R.string.wechat_status_failed to PillTone.Warn
        WechatConnectionState.NeedsBinding -> if (bound) {
            R.string.wechat_status_rebind to PillTone.Warn
        } else R.string.wechat_status_disconnected to PillTone.Neutral
    }
    StatusPill(stringResource(text), tone)
}

/** 设置 › 微信：绑定只负责扫码，消息监听和任务执行仍由同一个运行时负责。 */
@Composable
fun WechatSettingsScreen(runtime: WechatRuntime, onBack: () -> Unit) {
    val state by runtime.state.collectAsState()
    val allowUnlock by runtime.allowUnlock.collectAsState()
    val scope = rememberCoroutineScope()
    var bound by remember { mutableStateOf(runtime.store.account() != null) }
    var loginContent by remember { mutableStateOf<String?>(null) }
    var loginBusy by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var loginGeneration by remember { mutableIntStateOf(0) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var confirmUnbind by remember { mutableStateOf(false) }
    val loginFailedText = stringResource(R.string.wechat_login_failed)
    val expiredText = stringResource(R.string.wechat_qr_expired)
    val actionFailedText = stringResource(R.string.wechat_action_failed)

    fun changeUnlockAllowed(allowed: Boolean) {
        if (actionBusy || loginBusy) return
        actionBusy = true
        actionError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { runtime.setUnlockAllowed(allowed) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                actionError = actionFailedText
            } finally {
                actionBusy = false
            }
        }
    }

    // 服务端确认和本地取消可能同时发生；最终以已经持久化的账号为准。
    LaunchedEffect(state) { bound = runtime.store.account() != null }

    fun cancelLogin() {
        loginGeneration++
        loginJob?.cancel()
        loginJob = null
        loginContent = null
        loginBusy = false
        loginError = null
    }

    fun startLogin() {
        if (loginJob != null) return
        actionError = null
        loginError = null
        loginContent = null
        loginBusy = true
        val generation = ++loginGeneration
        loginJob = scope.launch {
            try {
                val start = runtime.startLogin()
                val key = start.qrcode?.takeIf { it.isNotBlank() }
                val content = start.qrContent?.takeIf { it.isNotBlank() }
                if (key == null || content == null) error("Incomplete QR login response")
                encodeQr(content)
                if (loginGeneration == generation) {
                    loginContent = content
                    loginBusy = false
                }
                val account = runtime.awaitLogin(key)
                if (loginGeneration == generation) {
                    loginContent = null
                    if (account == null) loginError = expiredText else bound = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (loginGeneration == generation) {
                    loginContent = null
                    loginError = loginFailedText
                }
            } finally {
                if (loginGeneration == generation) {
                    loginBusy = false
                    loginJob = null
                }
            }
        }
    }

    DisposableEffect(Unit) { onDispose { loginJob?.cancel() } }

    PageScaffold(stringResource(R.string.wechat_title), { cancelLogin(); onBack() }) {
        val status = when {
            loginContent != null -> Triple(
                stringResource(R.string.wechat_status_waiting),
                stringResource(R.string.wechat_state_waiting_detail), TileTone.Accent,
            )
            loginBusy -> Triple(
                stringResource(R.string.wechat_status_preparing),
                stringResource(R.string.wechat_state_preparing_detail), TileTone.Accent,
            )
            else -> wechatStateCopy(state, bound)
        }
        StateCard(MikaIcons.Chats, status.first, status.second, status.third, Modifier.testTag("wechat-status"))

        GroupLabel(stringResource(R.string.wechat_how))
        GroupCard {
            Text(
                stringResource(R.string.wechat_how_body), Modifier.padding(16.dp),
                style = MikaType.Body, color = MikaTheme.colors.ink2,
            )
        }

        if (!bound && loginContent == null) {
            MikaButton(
                stringResource(if (loginError == null) R.string.wechat_bind else R.string.wechat_bind_retry),
                ::startLogin, Modifier.fillMaxWidth(), enabled = !loginBusy,
            )
        }
        loginError?.let { FooterNote(it, color = MikaTheme.colors.bad) }

        loginContent?.let { content ->
            MikaCard(Modifier.padding(bottom = 12.dp).testTag("wechat-login")) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.wechat_qr_title), style = MikaType.BodyStrong, color = MikaTheme.colors.ink)
                    QrCodeImage(
                        content, stringResource(R.string.wechat_qr_title),
                        Modifier.padding(vertical = 16.dp).size(240.dp).testTag("wechat-qr"),
                    )
                    Text(
                        stringResource(R.string.wechat_qr_help), style = MikaType.Caption,
                        color = MikaTheme.colors.ink2, textAlign = TextAlign.Center,
                    )
                    MikaButton(
                        stringResource(R.string.cancel), ::cancelLogin, Modifier.fillMaxWidth().padding(top = 8.dp),
                        kind = ButtonKind.Text,
                    )
                }
            }
        }

        if (bound && loginContent == null) {
            GroupLabel(stringResource(R.string.wechat_connection))
            GroupCard {
                val receiving = state !is WechatConnectionState.Off && state !is WechatConnectionState.NeedsBinding
                ListRow(
                    MikaIcons.Message, stringResource(R.string.wechat_receive),
                    stringResource(R.string.wechat_receive_sub), first = true,
                    onClick = if (actionBusy) null else ({
                        val target = !receiving
                        actionBusy = true
                        actionError = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { runtime.setEnabled(target) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                actionError = actionFailedText
                            } finally {
                                actionBusy = false
                            }
                        }
                    }),
                ) {
                    MikaSwitch(receiving, null, Modifier.testTag("wechat-enabled"), enabled = !actionBusy)
                }
                ListRow(
                    MikaIcons.Key, stringResource(R.string.wechat_allow_unlock),
                    stringResource(R.string.wechat_allow_unlock_sub),
                ) {
                    MikaSwitch(
                        allowUnlock, ::changeUnlockAllowed, Modifier.testTag("wechat-allow-unlock"),
                        enabled = !actionBusy && !loginBusy,
                    )
                }
                ListRow(MikaIcons.Chats, stringResource(R.string.wechat_bound_account)) {
                    RowValue(stringResource(R.string.wechat_current_account))
                }
                ListRow(MikaIcons.Shield, stringResource(R.string.wechat_message_scope)) {
                    RowValue(stringResource(R.string.wechat_owner_private))
                }
            }
            FooterNote(stringResource(
                R.string.wechat_allow_unlock_foot,
                stringResource(R.string.mcp_title),
                stringResource(R.string.mcp_advanced),
                stringResource(R.string.device_access_title),
            ))
            MikaButton(
                stringResource(R.string.wechat_rebind), ::startLogin, Modifier.fillMaxWidth(),
                kind = ButtonKind.Secondary, enabled = !loginBusy && !actionBusy,
            )
            TextButton({ confirmUnbind = true }, Modifier.fillMaxWidth(), enabled = !actionBusy && !loginBusy) {
                Text(stringResource(R.string.wechat_unbind), color = MikaTheme.colors.bad)
            }
        }
        actionError?.let { FooterNote(it, color = MikaTheme.colors.bad) }

        GroupLabel(stringResource(R.string.wechat_capabilities))
        GroupCard {
            ListRow(null, stringResource(R.string.wechat_capability_text), first = true) {
                CapabilityTag(stringResource(R.string.wechat_supported))
            }
            ListRow(null, stringResource(R.string.wechat_capability_replies)) {
                CapabilityTag(stringResource(R.string.wechat_supported))
            }
            ListRow(null, stringResource(R.string.wechat_capability_phone), stringResource(R.string.wechat_capability_phone_sub)) {
                CapabilityTag(stringResource(R.string.wechat_phone_only))
            }
            ListRow(null, stringResource(R.string.wechat_capability_later)) {
                CapabilityTag(stringResource(R.string.wechat_later))
            }
        }
        FooterNote(stringResource(R.string.wechat_footer))
    }

    if (confirmUnbind) {
        MikaDialog(
            onDismissRequest = { confirmUnbind = false },
            title = { Text(stringResource(R.string.wechat_unbind_title)) },
            text = { Text(stringResource(R.string.wechat_unbind_body)) },
            confirmButton = {
                TextButton({
                    confirmUnbind = false
                    actionBusy = true
                    actionError = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { runtime.unbind() }
                            bound = false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            actionError = actionFailedText
                        } finally {
                            actionBusy = false
                        }
                    }
                }) { Text(stringResource(R.string.wechat_unbind), color = MikaTheme.colors.bad) }
            },
            dismissButton = { TextButton({ confirmUnbind = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun wechatStateCopy(state: WechatConnectionState, bound: Boolean): Triple<String, String, TileTone> = when (state) {
    WechatConnectionState.Connected -> Triple(
        stringResource(R.string.wechat_status_connected), stringResource(R.string.wechat_state_connected_detail), TileTone.Ok)
    WechatConnectionState.Connecting -> Triple(
        stringResource(R.string.wechat_status_connecting), stringResource(R.string.wechat_state_connecting_detail), TileTone.Accent)
    WechatConnectionState.Off -> Triple(
        stringResource(R.string.wechat_status_paused), stringResource(R.string.wechat_state_paused_detail), TileTone.Neutral)
    is WechatConnectionState.Failed -> Triple(
        stringResource(R.string.wechat_status_failed), stringResource(R.string.wechat_state_failed_detail), TileTone.Bad)
    WechatConnectionState.NeedsBinding -> if (bound) Triple(
        stringResource(R.string.wechat_status_rebind), stringResource(R.string.wechat_state_rebind_detail), TileTone.Warn)
    else Triple(
        stringResource(R.string.wechat_status_disconnected), stringResource(R.string.wechat_state_disconnected_detail), TileTone.Neutral)
}
