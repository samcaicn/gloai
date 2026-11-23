package com.wangzan101.mika.calls.ui

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallConfig
import com.wangzan101.mika.calls.CallNotifications
import com.wangzan101.mika.calls.CallRuntime
import com.wangzan101.mika.calls.CallRuntimeState
import com.wangzan101.mika.calls.CallStandbyService
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSpace
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.launch
import com.wangzan101.mika.chat.ui.readiness
import com.wangzan101.mika.memory.ui.Link
import com.wangzan101.mika.settings.ui.models.Page
import com.wangzan101.mika.settings.ui.models.refresh

private val phonePermissions = listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE)

internal enum class CallNotificationBlock { Permission, App, CallChannel, StandbyChannel, Unknown, None }
internal enum class CallBatteryPolicy { Restricted, Managed, Unrestricted, Unknown }

internal fun notificationSettingsChannel(block: CallNotificationBlock): String? = when (block) {
    CallNotificationBlock.CallChannel -> CallNotifications.CHANNEL
    CallNotificationBlock.StandbyChannel -> CallStandbyService.CHANNEL
    else -> null
}

internal data class CallDeviceReadiness(
    val missingPhonePermissions: List<String>,
    val notificationBlock: CallNotificationBlock,
    val batteryPolicy: CallBatteryPolicy,
) {
    val phoneAllowed: Boolean get() = missingPhonePermissions.isEmpty()
    val callNotificationsAllowed: Boolean get() = notificationBlock == CallNotificationBlock.None ||
        notificationBlock == CallNotificationBlock.StandbyChannel
    val standbyNotificationsAllowed: Boolean get() = notificationBlock == CallNotificationBlock.None
}

internal data class CallDeviceControls(
    val readiness: CallDeviceReadiness,
    val phoneActionLabel: Int,
    val notificationActionLabel: Int,
    val phoneAction: () -> Unit,
    val notificationAction: () -> Unit,
    val callChannelAction: () -> Unit,
    val batteryAction: () -> Unit,
)

/** Returning from any system page reads actual state; it never opts in or starts standby. */
@Composable
internal fun rememberCallDeviceControls(runtime: CallRuntime, onSettingsError: () -> Unit): CallDeviceControls {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var status by remember(context) { mutableStateOf(readCallDeviceReadiness(context)) }
    var phoneDenied by rememberSaveable { mutableStateOf(false) }
    var notificationDenied by rememberSaveable { mutableStateOf(false) }

    fun readBack() {
        status = readCallDeviceReadiness(context)
        if (status.phoneAllowed) phoneDenied = false
        if (status.notificationBlock != CallNotificationBlock.Permission) notificationDenied = false
        scope.launch { runtime.refresh() }
    }

    val requestPhone = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        readBack()
        phoneDenied = !status.phoneAllowed
    }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        readBack()
        notificationDenied = status.notificationBlock == CallNotificationBlock.Permission
    }

    DisposableEffect(lifecycle, context, runtime) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) readBack()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun openSettings(vararg intents: Intent) {
        for (intent in intents) {
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Some OEMs do not expose the exact page. Try the next public system page.
            } catch (_: SecurityException) {
                // The exported settings page can vary across devices.
            }
        }
        onSettingsError()
    }

    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    val appNotifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    fun openChannel(channel: String) {
        openSettings(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channel), appNotifications, appDetails)
    }
    val requestNotification = status.notificationBlock == CallNotificationBlock.Permission && !notificationDenied
    return CallDeviceControls(
        readiness = status,
        phoneActionLabel = if (phoneDenied) R.string.calls_device_open_app_settings else R.string.calls_device_allow_phone,
        notificationActionLabel = if (requestNotification) R.string.calls_device_allow_notifications else R.string.calls_notification_settings,
        phoneAction = {
            val missing = readCallDeviceReadiness(context).missingPhonePermissions
            when {
                missing.isEmpty() -> readBack()
                phoneDenied -> openSettings(appDetails)
                else -> requestPhone.launch(missing.toTypedArray())
            }
        },
        notificationAction = {
            when (val block = readCallDeviceReadiness(context).notificationBlock) {
                CallNotificationBlock.Permission -> if (notificationDenied) openSettings(appNotifications, appDetails)
                    else requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                CallNotificationBlock.CallChannel, CallNotificationBlock.StandbyChannel ->
                    openChannel(checkNotNull(notificationSettingsChannel(block)))
                CallNotificationBlock.App, CallNotificationBlock.Unknown -> openSettings(appNotifications, appDetails)
                CallNotificationBlock.None -> readBack()
            }
        },
        callChannelAction = { openChannel(CallNotifications.CHANNEL) },
        batteryAction = { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails) },
    )
}

private fun readCallDeviceReadiness(context: Context): CallDeviceReadiness {
    val missing = phonePermissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    val notificationBlock = runCatching {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return@runCatching CallNotificationBlock.Unknown
        when {
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                CallNotificationBlock.Permission
            !manager.areNotificationsEnabled() -> CallNotificationBlock.App
            manager.getNotificationChannel(CallNotifications.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE ->
                CallNotificationBlock.CallChannel
            manager.getNotificationChannel(CallStandbyService.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE ->
                CallNotificationBlock.StandbyChannel
            else -> CallNotificationBlock.None
        }
    }.getOrDefault(CallNotificationBlock.Unknown)
    val batteryPolicy = runCatching {
        val activity = context.getSystemService(ActivityManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        when {
            activity?.isBackgroundRestricted == true -> CallBatteryPolicy.Restricted
            power == null -> CallBatteryPolicy.Unknown
            power.isIgnoringBatteryOptimizations(context.packageName) -> CallBatteryPolicy.Unrestricted
            else -> CallBatteryPolicy.Managed
        }
    }.getOrDefault(CallBatteryPolicy.Unknown)
    return CallDeviceReadiness(missing, notificationBlock, batteryPolicy)
}

@Composable
internal fun CallPermissionRows(device: CallDeviceControls, helperConnected: Boolean, enabled: Boolean, onHelperSetup: () -> Unit) {
    val checks = device.readiness
    val phoneAction = device.phoneAction.takeIf { enabled && !checks.phoneAllowed }
    ListRow(
        MikaIcons.Phone, stringResource(R.string.calls_device_phone_permissions),
        stringResource(if (checks.phoneAllowed) R.string.calls_device_permission_granted else R.string.calls_device_phone_permissions_note),
        tone = if (checks.phoneAllowed) TileTone.Ok else TileTone.Warn,
        detail = phoneAction?.let { stringResource(device.phoneActionLabel) }, detailColor = MikaTheme.colors.accent,
        onClick = phoneAction,
    ) { if (phoneAction != null) RowChevron() }

    val notificationAction = device.notificationAction.takeIf { enabled && !checks.standbyNotificationsAllowed }
    ListRow(
        MikaIcons.Bell, stringResource(R.string.calls_device_notifications),
        stringResource(when (checks.notificationBlock) {
            CallNotificationBlock.Permission -> R.string.calls_device_notifications_permission_missing
            CallNotificationBlock.App -> R.string.calls_device_notifications_app_off
            CallNotificationBlock.CallChannel -> R.string.calls_device_notifications_calls_off
            CallNotificationBlock.StandbyChannel -> R.string.calls_device_notifications_standby_off
            CallNotificationBlock.Unknown -> R.string.calls_device_notifications_unknown
            CallNotificationBlock.None -> R.string.calls_device_permission_granted
        }),
        tone = if (checks.standbyNotificationsAllowed) TileTone.Ok else TileTone.Warn,
        detail = notificationAction?.let { stringResource(device.notificationActionLabel) }, detailColor = MikaTheme.colors.accent,
        onClick = notificationAction,
    ) { if (notificationAction != null) RowChevron() }

    val helperAction = onHelperSetup.takeIf { enabled }
    ListRow(
        MikaIcons.Link, stringResource(R.string.calls_helper_name),
        stringResource(if (helperConnected) R.string.calls_device_helper_connected else R.string.calls_helper_disconnected),
        tone = if (helperConnected) TileTone.Ok else TileTone.Warn,
        detail = helperAction?.let { stringResource(if (helperConnected) R.string.calls_helper_manage else R.string.calls_helper_set_up) },
        detailColor = MikaTheme.colors.accent,
        onClick = helperAction,
    ) { if (helperAction != null) RowChevron() }
}

internal fun backgroundStandbyStatus(config: CallConfig, state: CallRuntimeState): Int = when {
    !config.autoAnswer -> R.string.calls_background_auto_off
    state.backgroundStandbyStarting -> R.string.calls_background_starting
    state.backgroundStandbyRunning -> R.string.calls_background_running
    else -> R.string.calls_background_not_running
}

@Composable
internal fun CallBackgroundStandbyRow(
    config: CallConfig, state: CallRuntimeState, canStart: Boolean,
    onStart: () -> Unit, onBlocked: (() -> Unit)? = null,
) {
    val stopped = !state.backgroundStandbyStarting && !state.backgroundStandbyRunning
    val canAct = stopped && config.autoAnswer
    val action = if (canAct && canStart) onStart else onBlocked.takeIf { canAct }
    ListRow(
        MikaIcons.Shield, stringResource(R.string.calls_background_service),
        stringResource(backgroundStandbyStatus(config, state)),
        tone = if (state.backgroundStandbyRunning) TileTone.Ok else TileTone.Neutral,
        detail = action?.let { stringResource(if (canStart) R.string.calls_background_start else R.string.calls_device_and_permissions) },
        detailColor = MikaTheme.colors.accent, onClick = action,
    ) { if (action != null) RowChevron() }
}

@Composable
internal fun CallBatterySettingsRow(device: CallDeviceControls, enabled: Boolean) {
    val battery = device.readiness.batteryPolicy
    ListRow(
        MikaIcons.Gear, stringResource(R.string.calls_background_battery),
        stringResource(when (battery) {
            CallBatteryPolicy.Restricted -> R.string.calls_background_battery_restricted
            CallBatteryPolicy.Managed -> R.string.calls_background_battery_managed
            CallBatteryPolicy.Unrestricted -> R.string.calls_background_battery_unrestricted
            CallBatteryPolicy.Unknown -> R.string.calls_background_battery_unknown
        }),
        tone = if (battery == CallBatteryPolicy.Restricted) TileTone.Warn else TileTone.Neutral,
        detail = if (enabled) stringResource(R.string.calls_background_open_battery) else null,
        detailColor = MikaTheme.colors.accent, onClick = device.batteryAction.takeIf { enabled },
    ) { if (enabled) RowChevron() }
}

@Composable
internal fun CallLockedDialPendingRow(state: CallRuntimeState, enabled: Boolean, onCancel: () -> Unit) {
    val status = when {
        state.lockedDialPreparing -> stringResource(R.string.calls_device_locked_dial_preparing)
        state.lockedDialRemainingSeconds != null -> stringResource(
            R.string.calls_device_locked_dial_remaining, state.lockedDialRemainingSeconds,
        )
        else -> null
    }
    GroupCard(Modifier.padding(horizontal = MikaSpace.Page).testTag("call-locked-dial-pending")) {
        ListRow(MikaIcons.Phone, stringResource(R.string.calls_device_locked_dial_pending), status, first = true) {
            TextButton(onClick = onCancel, enabled = enabled, modifier = Modifier.testTag("call-locked-dial-cancel")) {
                Text(stringResource(R.string.calls_device_locked_dial_cancel))
            }
        }
    }
}
