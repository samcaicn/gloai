package com.wangzan101.mika.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionService

/**
 * 在识别期间保持一份公开的服务连接,让识别服务能够使用调用方的前台能力。
 * 这里只等连接通知,不调用服务的 Binder 协议;识别本身仍交给 SpeechRecognizer。
 */
internal class SpeechServiceLease(
    private val binder: Binder,
    private val onReady: () -> Unit,
    private val onUnavailable: () -> Unit,
) : AutoCloseable {
    /** 平台连接的薄接口;生命周期可以不依赖系统对象测试。 */
    interface Binder {
        fun bind(onConnected: () -> Unit, onUnavailable: () -> Unit): Boolean
        fun unbind()
    }

    private val lock = Any()
    private var started = false
    private var binding = false
    private var accepted = false
    private var closed = false
    private var unbound = false
    private var ready = false
    private var pendingReady = false
    private var pendingUnavailable = false

    /**
     * 每份连接只发起一次。false 表示没有绑上,由调用方使用普通识别路径,这里不另发失效通知。
     * bind 内同步到来的回调等它返回成功后再交给调用方,避免失败的绑定先启动识别。
     */
    fun start(): Boolean {
        synchronized(lock) {
            if (closed) return false
            if (started) return binding || accepted
            started = true
            binding = true
        }
        val bound = try {
            binder.bind(::connected, ::unavailable)
        } catch (_: Exception) {
            false
        }
        val cleanup = synchronized(lock) {
            binding = false
            accepted = bound
            if (!bound) closed = true
            closed && takeUnbind()
        }
        if (cleanup) unbindQuietly()
        if (bound) {
            val failed = synchronized(lock) { pendingUnavailable }
            if (failed) unavailable() else {
                val notifyReady = synchronized(lock) { pendingReady }
                if (notifyReady) connected()
            }
        }
        return bound
    }

    private fun connected() = synchronized(lock) {
        if (closed || pendingUnavailable || ready) return@synchronized
        if (binding) {
            pendingReady = true
            return@synchronized
        }
        ready = true
        // 回调和 close 串行;调用方在回调里 close 也可以重入。
        try {
            onReady()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun unavailable() {
        val cleanup = synchronized(lock) {
            if (closed) return
            if (binding) {
                pendingUnavailable = true
                return
            }
            closed = true
            takeUnbind()
        }
        if (cleanup) unbindQuietly()
        onUnavailable()
    }

    override fun close() {
        val cleanup = synchronized(lock) {
            if (closed) return
            closed = true
            // bind 还没返回时不能提前解绑:它可能尚未登记连接。返回后由 start 补做。
            takeUnbind()
        }
        if (cleanup) unbindQuietly()
    }

    /** 只在锁内调用;开始过的绑定即使返回 false 或抛错,也清一次连接登记。 */
    private fun takeUnbind(): Boolean {
        if (!started || binding || unbound) return false
        unbound = true
        return true
    }

    private fun unbindQuietly() {
        try {
            binder.unbind()
        } catch (_: Exception) {
            // 没有登记成功、服务已经消失等情形也算收拾完了,不重复解绑。
        }
    }

    companion object {
        /** 仅给调用方本来就有权绑定的公开服务持有连接;不申请权限,不更改系统配置。 */
        fun forService(
            context: Context,
            service: ComponentName,
            onReady: () -> Unit,
            onUnavailable: () -> Unit,
        ): SpeechServiceLease? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            return try {
                val app = context.applicationContext
                val info = app.packageManager.getServiceInfo(service, 0)
                if (!info.exported) return null
                val permission = info.permission
                if (!permission.isNullOrEmpty() && app.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return null
                SpeechServiceLease(ServiceBinder(app, service), onReady, onUnavailable)
            } catch (_: Exception) {
                null
            }
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private class ServiceBinder(private val context: Context, private val service: ComponentName) : Binder {
        private var connected: (() -> Unit)? = null
        private var unavailable: (() -> Unit)? = null
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { connected?.invoke() }
            override fun onServiceDisconnected(name: ComponentName?) { unavailable?.invoke() }
            override fun onNullBinding(name: ComponentName?) { unavailable?.invoke() }
            override fun onBindingDied(name: ComponentName?) { unavailable?.invoke() }
        }

        override fun bind(onConnected: () -> Unit, onUnavailable: () -> Unit): Boolean {
            connected = onConnected
            unavailable = onUnavailable
            return context.bindService(Intent(RecognitionService.SERVICE_INTERFACE).setComponent(service), connection,
                Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES)
        }

        override fun unbind() {
            // 即使系统的解绑抛错,也不让迟到回调再持有会话。
            connected = null
            unavailable = null
            context.unbindService(connection)
        }
    }
}
