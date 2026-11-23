package com.wangzan101.mika.settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons

@Composable
fun SmsLoginSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { PhoneServices.of(context).smsLogin }
    val access = remember { (context.applicationContext as com.wangzan101.mika.MikaApp).appAccess }
    DisposableEffect(access) { val protection = access.protectUi(); onDispose { protection.close() } }
    val consent by runtime.settings.state.collectAsState()
    var permitted by remember { mutableStateOf(runtime.permitted(context)) }
    var disclosure by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<SmsLoginProfile?>(null) }
    var error by remember { mutableStateOf(false) }
    fun save(enabled: Boolean) {
        error = runCatching { runtime.settings.setEnabled(enabled) }.isFailure
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        if (granted) save(true) else { save(false); error = true }
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permitted = runtime.permitted(context)
                if (!permitted && runtime.settings.state.value.enabled) save(false)
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    fun toggle(on: Boolean) { if (on) disclosure = true else save(false) }
    PageScaffold(stringResource(R.string.sms_login_title), onBack) {
        GroupCard {
            ListRow(MikaIcons.Shield, stringResource(R.string.sms_login_enable),
                stringResource(R.string.sms_login_default_off), first = true, onClick = { toggle(!consent.enabled) }) {
                MikaSwitch(consent.enabled, ::toggle, Modifier.testTag("sms-login-enabled"))
            }
        }
        FooterNote(stringResource(R.string.sms_login_limits))
        Text(stringResource(R.string.sms_profile_title))
        TextButton({ selected = null; editing = true }) { Text(stringResource(R.string.add)) }
        consent.profiles.forEach { p ->
            GroupCard {
                ListRow(MikaIcons.Shield, p.app, first = true, onClick = { selected = p; editing = true }) { RowChevron() }
                TextButton({ error = runCatching { runtime.settings.removeProfile(p.app) }.isFailure }) { Text(stringResource(R.string.confirm_delete)) }
            }
        }
        FooterNote(stringResource(R.string.sms_profile_help))
        GroupCard {
            ListRow(MikaIcons.Shield, stringResource(R.string.sms_login_permission),
                stringResource(if (permitted) R.string.state_on else R.string.state_off), first = true,
                onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { RowChevron() }
        }
        if (error) FooterNote(stringResource(R.string.sms_login_error))
    }
    if (editing) SmsProfileEditor(selected, onCancel = { editing = false }) { fields ->
        val signer = runtime.signer(context, fields.getValue("app")) ?: return@SmsProfileEditor false
        val p = SmsLoginProfile(fields.getValue("app"), signer, fields.getValue("sender"), fields.getValue("signature"),
            fields.getValue("account"), fields.getValue("marker"), fields.getValue("accountField"), fields.getValue("codeField"),
            fields.getValue("send"), fields.getValue("login"), fields.getValue("success"), fields.getValue("successText"), fields.getValue("successAccount"))
        val ok = runCatching { runtime.settings.saveProfile(p) }.isSuccess
        if (ok) editing = false
        ok
    }
    if (disclosure) AlertDialog(
        onDismissRequest = { disclosure = false },
        title = { Text(stringResource(R.string.sms_login_title)) },
        text = { Text(stringResource(R.string.sms_login_disclosure)) },
        confirmButton = { TextButton({
            disclosure = false
            if (runtime.permitted(context)) { permitted = true; save(true) }
            else request.launch(Manifest.permission.RECEIVE_SMS)
        }) { Text(stringResource(R.string.sms_login_enable)) } },
        dismissButton = { TextButton({ disclosure = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Advanced, one-time native mapping; the app signer is captured from Android, never typed or model supplied. */
@Composable
private fun SmsProfileEditor(initial: SmsLoginProfile?, onCancel: () -> Unit, onSave: (Map<String, String>) -> Boolean) {
    val labels = listOf(
        "app" to R.string.sms_profile_package, "sender" to R.string.sms_login_sender,
        "signature" to R.string.sms_login_signature, "account" to R.string.sms_profile_accountvalue,
        "marker" to R.string.sms_login_account, "accountField" to R.string.sms_profile_accountfield,
        "codeField" to R.string.sms_profile_codefield, "send" to R.string.sms_profile_sendbutton,
        "login" to R.string.sms_profile_loginbutton, "success" to R.string.sms_profile_successview,
        "successText" to R.string.sms_profile_successtext, "successAccount" to R.string.sms_profile_successaccount,
    )
    val values = remember(initial) { mutableStateMapOf<String, String>().apply {
        labels.forEach { put(it.first, "") }
        initial?.let { p -> putAll(mapOf("app" to p.app, "sender" to p.sender, "signature" to p.signature,
            "account" to p.account, "marker" to p.accountMarker, "accountField" to p.accountField,
            "codeField" to p.codeField, "send" to p.sendButton, "login" to p.loginButton,
            "success" to p.successView, "successText" to p.successText, "successAccount" to p.successAccountView)) }
    } }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onCancel, title = { Text(stringResource(R.string.sms_profile_title)) }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sms_profile_help))
            labels.forEach { (key, label) -> OutlinedTextField(values.getValue(key), { values[key] = it.trim().take(250) },
                label = { Text(stringResource(label)) }, singleLine = true,
                visualTransformation = if (key == "account") PasswordVisualTransformation() else VisualTransformation.None) }
            if (failed) Text(stringResource(R.string.sms_login_error))
        }
    }, confirmButton = { TextButton({ failed = !onSave(values.toMap()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.cancel)) } })
}
