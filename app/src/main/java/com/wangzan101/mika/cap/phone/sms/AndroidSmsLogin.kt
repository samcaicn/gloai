package com.wangzan101.mika.cap.phone.sms

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.cap.phone.sms.*
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.settings.AppAccessStore
import com.wangzan101.mika.settings.SmsLoginSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex

/** Process-wide admission prevents two tasks consuming the same incoming SMS. */
class SmsLoginRuntime(context: Context) {
    val settings = SmsLoginSettings(java.io.File(context.filesDir, "sms-login-consent.txt"))
    internal val lock = Mutex()
    private val privatePackages = java.util.concurrent.ConcurrentHashMap<String, String>()
    val privateLoginActive: Boolean get() = privatePackages.isNotEmpty()
    fun isPrivate(pkg: String?) = pkg != null && privatePackages.containsValue(pkg)
    internal fun protect(pkg: String): AutoCloseable {
        val token = java.util.UUID.randomUUID().toString()
        privatePackages[token] = pkg
        return AutoCloseable { privatePackages.remove(token) }
    }
    fun signer(context: Context, pkg: String): String? = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val signature = info.signingInfo?.apkContentsSigners?.singleOrNull() ?: return null
        java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrNull()
    fun permitted(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
}

/** UI main task only. No inbox query, static receiver, notifications, clipboard, code logs or retries. */
class AndroidSmsLogin(
    private val context: Context,
    private val runtime: SmsLoginRuntime,
    private val access: AppAccessStore,
    private val guarded: ScreenPort,
    private val privacy: SmsPrivateScreen,
    private val screen: () -> AndroidScreen?,
) : SmsLoginPort, AutoCloseable {
    private var protection: AutoCloseable? = null
    private var attempted = false
    override fun close() { protection?.close(); protection = null; privacy.close() }
    override suspend fun login(packageName: String): SmsLoginStatus {
        if (attempted) return SmsLoginStatus.UNAVAILABLE
        val consent = runtime.settings.state.value
        if (!consent.enabled) return SmsLoginStatus.DISABLED
        if (!runtime.permitted(context)) return SmsLoginStatus.PERMISSION_REQUIRED
        val profile = consent.profiles.filter { it.app == packageName }.singleOrNull() ?: return SmsLoginStatus.NO_TRUSTED_PROFILE
        if (!profile.valid || runtime.signer(context, packageName) != profile.signer) return SmsLoginStatus.TARGET_UNVERIFIED
        if (!runtime.lock.tryLock()) return SmsLoginStatus.BUSY
        try {
            val device = screen() ?: return SmsLoginStatus.UNAVAILABLE
            guarded.checkAppAccess()
            val a11y = ScreenAccessibility.instance.value ?: return SmsLoginStatus.UNAVAILABLE
            attempted = true
            privacy.begin()
            protection = runtime.protect(packageName)
            val cancelled = CompletableDeferred<Unit>()
            fun authorized() = !cancelled.isCompleted && runtime.settings.state.value.let { it.enabled && it.revision == consent.revision } &&
                runtime.permitted(context) && runtime.signer(context, packageName) == profile.signer &&
                access.current().let { it.available && it.denial(packageName) == null }
            fun dispatch(action: () -> Boolean) = runtime.settings.dispatch(consent.revision) { authorized() && action() }
            return withTimeoutOrNull(180_000) {
                coroutineScope {
                    val operation = async {
                        withSmsListening(a11y, access, cancelled) {
                            SmsLoginAutomation(object : SmsLoginDevice {
                                override suspend fun prepare() = device.prepareSmsLogin(profile, ::dispatch)
                                override suspend fun receive(request: suspend () -> Boolean) = this@AndroidSmsLogin.receive(profile.scope, cancelled, request, ::authorized)
                                override suspend fun request() = device.requestSmsLoginCode(profile, ::dispatch)
                                override suspend fun fill(code: CharArray) = device.fillSmsLoginCode(profile, code, ::dispatch)
                                override suspend fun submit(code: CharArray) = device.submitSmsLogin(profile, code, ::dispatch)
                                override suspend fun outcome(code: CharArray) = device.smsLoginOutcome(profile, code)
                                override fun resumeVerified(code: CharArray) { privacy.resumeVerified(code) }
                            }, ::authorized).run()
                        }
                    }
                    val revoked = async {
                        runtime.settings.state.first { !it.enabled || it.revision != consent.revision }
                        cancelled.complete(Unit)
                        SmsLoginStatus.REVOKED
                    }
                    try { select { operation.onAwait { it }; revoked.onAwait { it } } }
                    finally { operation.cancelAndJoin(); revoked.cancelAndJoin() }
                }
            } ?: SmsLoginStatus.LOGIN_UNVERIFIED
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return SmsLoginStatus.UNAVAILABLE }
        finally { runtime.lock.unlock() }
    }

    private suspend fun receive(scope: SmsLoginScope, cancelled: CompletableDeferred<Unit>,
        requestCode: suspend () -> Boolean, authorized: () -> Boolean): CharArray? = coroutineScope {
        val pending = CompletableDeferred<Unit>()
        val owned = java.util.concurrent.atomic.AtomicReference<CharArray?>()
        val started = System.currentTimeMillis()
        val window = SmsLoginWindow(scope, started)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION || !pending.isActive || !authorized()) return
                try {
                    val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    if (parts.isEmpty() || parts.size > 10 || parts.any { it.timestampMillis < started / 1000 * 1000 || it.originatingAddress != scope.sender }) return
                    val code = window.accept(parts.first().originatingAddress.orEmpty(), parts.joinToString("") { it.messageBody.orEmpty() },
                        parts.minOf { it.timestampMillis }, System.currentTimeMillis()) ?: return
                    if (owned.compareAndSet(null, code)) pending.complete(Unit) else code.fill('\u0000')
                } catch (_: Exception) { /* malformed/unsupported message: ignore without logging */ }
            }
        }
        // Exported is needed for privileged telephony UIDs; signature permission blocks app spoofing.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION),
            Manifest.permission.BROADCAST_SMS, null, ContextCompat.RECEIVER_EXPORTED)
        var transferred = false
        try {
            if (!requestCode()) return@coroutineScope null
            val arrived = withTimeoutOrNull(90_000) { select { pending.onAwait { true }; cancelled.onAwait { false } } } == true
            val result = if (arrived) owned.get() else null
            transferred = result != null
            result
        } finally {
            window.close()
            try { context.unregisterReceiver(receiver) } finally {
                // Own and wipe a delivered code even if timeout/cancellation won the race.
                if (!transferred) owned.getAndSet(null)?.fill('\u0000')
                pending.cancel()
            }
        }
    }
}
