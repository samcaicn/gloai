package com.wangzan101.mika.voicecall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * 语音通话的麦克风型前台服务:通话期间撑住进程,退后台麦克风不被掐断;ongoing 通知(CATEGORY_CALL)
 * 带挂断动作。锁屏时不打开通话页——通知点击只把 App 带到前台。
 *
 * 单会话互斥:同一时刻只托管一通 [VoiceCallSession]。[attach] 在已有活动会话(未 Ended)时**拒**第二通
 * (返回 false,调用方须自行销毁新会话)——「拒」语义保证任一时刻只有一个会话持有通话音频
 * (VoiceCallAudio.requestCallAudio/abandonCallAudio 的多会话互斥靠这层兜底)。
 *
 * 装配(通话页,Task 7):建会话 → `attach(context, session)` 返回 true 才算占上;false 立即销毁新会话。
 * Android 14+ 麦克风型前台服务只能在 App 前台起,且 startForeground 时 RECORD_AUDIO 已授予——
 * 通话页在请求权限通过后才建会话,顺序天然满足。
 */
class VoiceCallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hangupRequests = Channel<Unit>(Channel.CONFLATED)
    private var raceJob: Job? = null
    private var session: VoiceCallSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANGUP) {
            hangupRequests.trySend(Unit)
            if (session == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val call = synchronized(companionLock) {
            val pending = pendingSession
            pendingSession = null
            pending
        }
        if (call == null || session != null) {
            // 重复 start / 无待托管会话:没事可干;已有会话在跑则保持现状
            if (session == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        }.isSuccess
        if (!promoted) {
            // 提不前台就没资格保活:把会话还给调用方的生命周期(前台时通话照常,退后台由系统静音路径兜出 MicSilent)
            synchronized(companionLock) { if (activeSession === call) activeSession = null }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        session = call
        // 清掉上一通电话残留的挂断点击,别让旧信号挂断新会话
        while (hangupRequests.tryReceive().isSuccess) Unit
        raceSessionEnd(call)
        return START_NOT_STICKY
    }

    /** select 竞速:通话协程自己走到终态,与通知挂断,谁先到听谁的。 */
    private fun raceSessionEnd(call: VoiceCallSession) {
        raceJob = scope.launch {
            val endedNaturally = async { call.state.first { it is VoiceCallState.Ended } }
            val notified = async { hangupRequests.receive() }
            val fromNotification = select {
                endedNaturally.onAwait { false }
                notified.onAwait { true }
            }
            endedNaturally.cancel()
            notified.cancel()
            if (fromNotification) call.end(EndReason.Notification)
            detach(call)
        }
    }

    private fun detach(call: VoiceCallSession) {
        synchronized(companionLock) { if (activeSession === call) activeSession = null }
        // 旧会话的收尾晚于新会话接班:前台状态与通知已属于新会话,旧会话只还坑位,不动服务
        if (session !== call) return
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        raceJob?.cancel()
        val call = session
        session = null
        synchronized(companionLock) { if (activeSession === call) activeSession = null }
        // 服务被系统收掉而通话还活着:落终态释放音频,不留没人管的孤儿通话
        if (call != null && call.state.value !is VoiceCallState.Ended) call.end(EndReason.Hangup)
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = checkNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.voicecall_notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        // 锁屏不开通话页:点击只把 App 带到前台,通话页由 App 内的状态自己决定显隐
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangup = PendingIntent.getService(
            this, NOTIFICATION_ID, Intent(this, VoiceCallService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.voicecall_notification_ongoing))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                addAction(Notification.Action.Builder(null, getString(R.string.voicecall_hangup), hangup).build())
            }
            .build()
    }

    companion object {
        const val CHANNEL = "mika_voice_call"
        private const val ACTION_START = "com.wangzan101.mika.voicecall.START"
        private const val ACTION_HANGUP = "com.wangzan101.mika.voicecall.HANGUP"
        private const val NOTIFICATION_ID = 90_119

        private val companionLock = Any()

        /** 已占位的活动会话(可能还没被服务领走)。 */
        @Volatile private var activeSession: VoiceCallSession? = null

        /** 等服务 onStartCommand 领走的会话;attach 与服务启动之间的交接箱。 */
        @Volatile private var pendingSession: VoiceCallSession? = null

        /**
         * 占会话坑位并拉起前台服务。已有活动会话(未 Ended)时拒第二通,返回 false,
         * 调用方须立即销毁自己新建的会话(end(EndReason.Hangup) 或任其收尾)。
         * startForegroundService 抛异常(如后台启动被拒)时坑位回滚,异常原样上抛。
         */
        fun attach(context: Context, session: VoiceCallSession): Boolean {
            synchronized(companionLock) {
                val current = activeSession
                if (current != null && current.state.value !is VoiceCallState.Ended) return false
                activeSession = session
                pendingSession = session
            }
            try {
                context.startForegroundService(Intent(context, VoiceCallService::class.java).setAction(ACTION_START))
            } catch (error: RuntimeException) {
                synchronized(companionLock) {
                    if (activeSession === session) activeSession = null
                    if (pendingSession === session) pendingSession = null
                }
                throw error
            }
            return true
        }
    }
}
