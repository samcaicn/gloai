package com.wangzan101.mika.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.wangzan101.mika.R
import com.wangzan101.mika.core.device.CredentialKind
import com.wangzan101.mika.core.device.DeviceCredential
import com.wangzan101.mika.settings.DeviceAccessSettings
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowActions
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 电脑连接页中的主屏准备设置;[localTasks] 为 true 时变体为「任务运行」页的本机任务开关:
 * 组名/开关行/脚注换本机任务文案,绑定的是独立的 localEnabled 开关;凭据编辑区两个变体整段共用。
 * 新凭据不进可恢复状态,不显示旧凭据;编辑时禁止截屏。
 */
@Composable
fun DeviceAccessSection(settings: DeviceAccessSettings, localTasks: Boolean = false) {
    val config by settings.config.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(CredentialKind.Pin) }
    var value by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val window = LocalContext.current.activity()?.window
    DisposableEffect(editing, window) {
        val alreadySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (editing) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (editing && !alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    fun change(action: () -> Unit, done: () -> Unit = {}) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try { withContext(Dispatchers.IO) { action() }; done() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    fun closeEditor() { value = ""; repeat = ""; editing = false }

    GroupLabel(stringResource(if (localTasks) R.string.device_local_group else R.string.device_access_group))
    GroupCard {
        ListRow(if (localTasks) MikaIcons.Phone else MikaIcons.Desktop,
            stringResource(if (localTasks) R.string.device_local_title else R.string.device_access_title),
            stringResource(if (localTasks) R.string.device_local_sub else R.string.device_access_sub), first = true) {
            MikaSwitch(if (localTasks) config.localEnabled else config.enabled,
                { on -> change({ if (localTasks) settings.setLocalEnabled(on) else settings.setEnabled(on) }) },
                Modifier.testTag(if (localTasks) "device-access-local-enabled" else "device-access-enabled"), enabled = !busy)
        }
        val subtitle = when {
            config.blocked -> R.string.device_access_blocked
            config.kind == CredentialKind.Pin -> R.string.device_access_pin_saved
            config.kind == CredentialKind.Password -> R.string.device_access_password_saved
            else -> R.string.device_access_no_credential
        }
        ListRow(MikaIcons.Key, stringResource(R.string.device_access_credential), stringResource(subtitle),
            tone = if (config.blocked) TileTone.Bad else TileTone.Neutral,
            onClick = { if (!busy) { kind = config.kind ?: CredentialKind.Pin; editing = true } })
        if (config.kind != null && !editing) RowActions {
            MikaButton(stringResource(R.string.device_access_clear), { change({ settings.clearCredential() }) }, kind = ButtonKind.Tonal, size = ButtonSize.Small, enabled = !busy)
        }
    }
    FooterNote(stringResource(if (localTasks) R.string.device_local_foot else R.string.device_access_foot))
    if (editing) {
        GroupCard {
            RadioRow(stringResource(R.string.device_access_pin), kind == CredentialKind.Pin, first = true,
                onClick = { kind = CredentialKind.Pin; value = ""; repeat = "" }, enabled = !busy)
            RadioRow(stringResource(R.string.device_access_password), kind == CredentialKind.Password,
                onClick = { kind = CredentialKind.Password; value = ""; repeat = "" }, enabled = !busy)
            InputRow(stringResource(R.string.device_access_value), value, { if (!busy) value = it.take(64) },
                stringResource(R.string.device_access_value_hint), secret = true,
                keyboard = if (kind == CredentialKind.Pin) KeyboardType.NumberPassword else KeyboardType.Password,
                modifier = Modifier.testTag("device-access-credential"))
            InputRow(stringResource(R.string.device_access_repeat), repeat, { if (!busy) repeat = it.take(64) },
                stringResource(R.string.device_access_repeat_hint), secret = true,
                keyboard = if (kind == CredentialKind.Pin) KeyboardType.NumberPassword else KeyboardType.Password,
                modifier = Modifier.testTag("device-access-repeat"))
            RowActions {
                MikaButton(stringResource(R.string.device_access_save), {
                    val k = kind; val v = value
                    change({ settings.setCredential(k, v) }, ::closeEditor)
                }, size = ButtonSize.Small, enabled = !busy && value == repeat && DeviceCredential.valid(kind, value.toCharArray()))
                MikaButton(stringResource(R.string.cancel), ::closeEditor, kind = ButtonKind.Tonal, size = ButtonSize.Small, enabled = !busy)
            }
        }
        FooterNote(stringResource(if (kind == CredentialKind.Pin) R.string.device_access_pin_hint else R.string.device_access_password_hint))
    }
    if (failed) FooterNote(stringResource(R.string.device_access_save_failed))
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.activity()
    else -> null
}
