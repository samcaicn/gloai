package com.wangzan101.mika.wechat

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.run.ChannelGateway
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.MemoryOwnerIdentity
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.cap.wechat.WechatAccount
import com.wangzan101.mika.core.cap.wechat.WechatApi
import com.wangzan101.mika.core.cap.wechat.WechatHttpException
import com.wangzan101.mika.core.cap.wechat.WechatQrStart
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.min

sealed interface WechatConnectionState {
    data object Off : WechatConnectionState
    data object NeedsBinding : WechatConnectionState
    data object Connecting : WechatConnectionState
    data object Connected : WechatConnectionState
    data class Failed(val message: String) : WechatConnectionState
}

/** 单账号、私聊文字和图片的微信运行时。监听存活由独立前台服务负责，任务仍只进 TaskService。 */
class WechatRuntime(
    private val context: Context,
    val store: WechatStore,
    client: OkHttpClient? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = client ?: NetProxy.shared.client(OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS))
    private val api = WechatApi(http, BuildConfig.VERSION_NAME)
    private val owners = MemoryOwnerIdentity()
    private val app get() = context.applicationContext as MikaApp
    private val gateway = ChannelGateway(owners,
        { threadId -> app.taskState.run(threadId)?.activeAsk },
        object : ChannelGateway.Route {
            override suspend fun newTask(peer: ChannelPeer, messageId: String, text: String) =
                TaskService.startChannel(context, peer, messageId, text)

            override suspend fun answer(threadId: String, askId: String, text: String): Boolean =
                app.taskState.run(threadId)?.answerFromChannel(text, askId) == true

            override suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice) = false
            override suspend fun stop(runId: RunId) = TaskService.cancel(context, runId.value)
            override suspend fun cancelAsk(threadId: String) {
                val run = app.taskState.run(threadId) ?: return
                run.activeAsk?.askId?.let(run::cancelAsk)
            }
        })

    val channel = WechatChannel(store, api,
        activeAsk = { threadId -> app.taskState.run(threadId)?.activeAsk },
        imageFiles = androidWechatImages(context, api),
        imageFailureText = { tr(R.string.wechat_image_receive_failed) },
        reportImageFailure = ::reportImageFailure)

    private suspend fun reportImageFailure(peer: ChannelPeer, notice: Delivery.Notice) {
        app.outbox.deliver(channel, peer, notice)
    }
    private var registration: AutoCloseable? = null
    private var monitor: Job? = null
    private val network = WechatNetwork(context) { restartMonitor() }
    private val _state = MutableStateFlow<WechatConnectionState>(
        if (store.account() == null) WechatConnectionState.NeedsBinding else WechatConnectionState.Off)
    val state: StateFlow<WechatConnectionState> = _state.asStateFlow()
    val allowUnlock: StateFlow<Boolean> = store.allowUnlock

    fun channelFor(id: String) = channel.takeIf { id == WechatChannel.ID }
    fun acknowledge(messageId: String) = store.acknowledge(messageId)

    @Synchronized fun sync(): Boolean {
        if (FeatureFlags.get(context).quitRequested()) return false
        val account = store.account()
        if (!store.enabled() || account == null) {
            _state.value = WechatConnectionState.NeedsBinding.takeIf { account == null } ?: WechatConnectionState.Off
            return false
        }
        if (registration == null) {
            owners.bind(ChannelPeer(WechatChannel.ID, account.ownerId, ""))
            registration = gateway.start(channel)
        }
        network.start()
        if (monitor?.isActive != true) {
            _state.value = WechatConnectionState.Connecting
            monitor = scope.launch { monitorLoop() }
        }
        return true
    }

    @Synchronized fun stop() {
        monitor?.cancel()
        monitor = null
        registration?.close()
        registration = null
        network.stop()
        _state.value = if (store.account() == null) WechatConnectionState.NeedsBinding else WechatConnectionState.Off
    }

    fun ensureRunning() {
        if (FeatureFlags.get(context).quitRequested() || !store.enabled() || store.account() == null) return
        _state.value = WechatConnectionState.Connecting
        runCatching { context.startForegroundService(Intent(context, WechatService::class.java)) }
            .onFailure { _state.value = WechatConnectionState.Failed(it.message ?: it::class.simpleName.orEmpty()) }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) check(store.account() != null) { "Bind WeChat before enabling it" }
        store.setEnabled(enabled)
        if (enabled) ensureRunning() else {
            context.stopService(Intent(context, WechatService::class.java))
            stop()
        }
    }

    fun setUnlockAllowed(allowed: Boolean) = store.setUnlockAllowed(allowed)

    suspend fun startLogin(): WechatQrStart = withContext(Dispatchers.IO) { api.startLogin() }

    /** 调用方取消时只停止本次扫码等待，不影响已绑定账号的监听。 */
    suspend fun awaitLogin(qrcode: String): WechatAccount? {
        require(qrcode.isNotBlank()) { "Missing QR code key" }
        while (currentCoroutineContext().isActive) {
            val status = withContext(Dispatchers.IO) { api.pollLogin(qrcode) }
            status.account()?.let { account ->
                store.account()?.let { old -> owners.unbind(ChannelPeer(WechatChannel.ID, old.ownerId, "")) }
                store.saveAccount(account)
                store.setEnabled(true)
                owners.bind(ChannelPeer(WechatChannel.ID, account.ownerId, ""))
                // 旧账号的长轮询必须在这里取消；新请求从已清空的账号游标开始。
                restartMonitor()
                ensureRunning()
                return account
            }
            if (status.status == "expired") return null
            if (status.status == "confirmed") throw IllegalStateException("Incomplete confirmed login response")
            if (status.status != "wait") delay(1_000)
        }
        return null
    }

    fun unbind() {
        context.stopService(Intent(context, WechatService::class.java))
        store.account()?.let { old -> owners.unbind(ChannelPeer(WechatChannel.ID, old.ownerId, "")) }
        stop()
        store.clear()
        _state.value = WechatConnectionState.NeedsBinding
    }

    @Synchronized private fun restartMonitor() {
        if (registration == null || !store.enabled()) return
        monitor?.cancel()
        monitor = scope.launch { monitorLoop() }
    }

    private suspend fun monitorLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive && store.enabled()) {
            try {
                channel.pollOnce()
                failures = 0
                _state.value = WechatConnectionState.Connected
            } catch (e: CancellationException) {
                throw e
            } catch (e: WechatHttpException) {
                if (e.httpCode == 401 || e.httpCode == 403) {
                    store.setEnabled(false)
                    _state.value = WechatConnectionState.NeedsBinding
                    context.stopService(Intent(context, WechatService::class.java))
                    return
                }
                failures++
                _state.value = WechatConnectionState.Failed(e.message.orEmpty())
                delay(backoff(failures))
            } catch (e: Exception) {
                failures++
                Log.w(TAG, "WeChat polling failed", e)
                _state.value = WechatConnectionState.Failed(e.message ?: e::class.simpleName.orEmpty())
                delay(backoff(failures))
            }
        }
    }

    private fun backoff(failures: Int) = min(30_000L, 1_000L * (1L shl (failures - 1).coerceAtMost(5)))

    companion object { private const val TAG = "MikaWechat" }
}

private class WechatNetwork(context: Context, private val changed: () -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var started = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
    }

    @Synchronized fun start() {
        if (started) return
        runCatching { manager.registerDefaultNetworkCallback(callback) }.onSuccess { started = true }
    }

    @Synchronized fun stop() {
        if (!started) return
        runCatching { manager.unregisterNetworkCallback(callback) }
        started = false
    }
}
