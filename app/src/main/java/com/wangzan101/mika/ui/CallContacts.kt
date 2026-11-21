package com.wangzan101.mika.ui

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CallContact(val id: Long, val name: String, val number: String, val key: String)

private fun readCallContacts(resolver: ContentResolver): List<CallContact> {
    val columns = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER)
    val cursor = checkNotNull(resolver.query(Phone.CONTENT_URI, columns, null, null, Phone.SORT_KEY_PRIMARY + " ASC"))
    return cursor.use {
        buildList {
            while (it.moveToNext()) {
                val number = CallPolicy.incomingNumber(it.getString(2)) ?: continue
                val key = CallPolicy.incomingMatchKey(number) ?: continue
                add(CallContact(it.getLong(0), it.getString(1).orEmpty(), number, key))
            }
        }.distinctBy { contact -> contact.id to contact.key }
    }
}

internal data class CallContacts(
    val rows: List<CallContact> = emptyList(),
    val loading: Boolean = false,
    val message: Int? = null,
    val actionLabel: Int? = null,
    val action: () -> Unit = {},
)

@Composable
internal fun rememberCallContacts(loadRows: Boolean = true): CallContacts {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permission = Manifest.permission.READ_CONTACTS
    fun hasAccess() = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(hasAccess()) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasAccess()
                if (granted) denied = false
                reload++
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!granted) {
        val useSettings = denied && context.findActivity()?.shouldShowRequestPermissionRationale(permission) == false
        return CallContacts(message = R.string.calls_contacts_permission,
            actionLabel = if (useSettings) R.string.chat_go_settings else R.string.proactive_open_access) {
            if (useSettings) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
            } else launcher.launch(permission)
        }
    }
    if (!loadRows) return CallContacts()
    var result by remember { mutableStateOf(CallContacts(loading = true)) }
    LaunchedEffect(context, reload) {
        result = try {
            val rows = withContext(Dispatchers.IO) { readCallContacts(context.contentResolver) }
            CallContacts(rows, message = R.string.calls_contacts_empty.takeIf { rows.isEmpty() })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            granted = hasAccess()
            CallContacts(message = R.string.calls_contacts_failed, actionLabel = R.string.retry) { reload++ }
        }
    }
    return result
}
