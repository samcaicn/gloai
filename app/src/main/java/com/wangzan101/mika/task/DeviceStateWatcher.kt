package com.wangzan101.mika.task

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.view.Display
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 设备状态:主屏是否不可操作(熄屏或锁屏),以及 Agent 的 App 任务是否被用户挪到了主屏。
 * 熄屏后系统会丢弃注入的触摸,所以熄屏也按「锁屏」处理。
 */
class DeviceStateWatcher(
    context: Context,
    private val topTaskId: (displayId: Int) -> Int,
) {
    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val keyguard = appContext.getSystemService(KeyguardManager::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = LockState({ LockState.Reading(power.isInteractive, keyguard.isKeyguardLocked, keyguard.isDeviceLocked) }, scope)
    val locked: StateFlow<Boolean> = lock.locked

    // 只用广播当作「该重新看一眼」的信号,状态本身每次都从系统读,不靠广播顺序推断
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            lock.refresh()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        lock.refresh() // 注册前后之间的变化
    }

    /**
     * 轮询两块屏最上面的任务:[taskId] 不在虚拟屏最上面、却在主屏最上面,就是被用户挪走了。
     * 每次「挪走」只发一次,挪回来后再被挪走会再发。查询失败(shell 断开)时跳过这一轮。
     */
    fun taskMovedAway(displayId: Int, taskId: Int): Flow<Unit> = flow {
        var away = false
        while (true) {
            val now = runCatching {
                isMovedAway(topTaskId(displayId), topTaskId(Display.DEFAULT_DISPLAY), taskId)
            }.getOrNull()
            if (now != null) {
                if (now && !away) emit(Unit)
                away = now
            }
            delay(POLL_MS)
        }
    }

    /** 本进程刚完成唤醒/解锁后立刻重读，不等下一条系统广播。 */
    fun refresh() = lock.refresh()

    fun close() {
        runCatching { appContext.unregisterReceiver(receiver) }
        scope.cancel()
    }

    companion object {
        const val POLL_MS = 500L

        internal fun isMovedAway(agentTop: Int, mainTop: Int, taskId: Int) =
            taskId >= 0 && agentTop != taskId && mainTop == taskId
    }
}

/**
 * 主屏是否不可操作(熄屏或锁屏)。广播来了重读一次;但解锁时系统先发 USER_PRESENT,
 * [android.app.KeyguardManager.isKeyguardLocked] 要等锁屏界面真的退下去才变,这一刻读到的还是「锁着」,
 * 之后不会再有广播。所以只要有人在看这份状态、且上一次读到的还是「锁着」(熄屏也是锁着的一种),
 * 就每 [DeviceStateWatcher.POLL_MS] 重读一次,直到读到解锁。熄屏期间同样重读:SCREEN_ON / USER_PRESENT
 * 在部分 ROM 上会漏发,若只等广播,亮屏解锁后没有任何事件触发重读,任务就永远停在「等你解锁手机」(#638)。
 * 轮询的判据用 [Reading.locked](= 熄屏或 deviceLocked),而不是 keyguardLocked:部分 ROM 上锁屏界面
 * 先于设备解锁状态退下去(keyguardLocked 已变 false、deviceLocked 仍 true),若只看 keyguardLocked 会提前停轮询,
 * 任务就卡在「等你解锁手机」(#590 / #627)。
 */
internal class LockState(private val read: () -> Reading, scope: CoroutineScope) {
    data class Reading(val interactive: Boolean, val keyguardLocked: Boolean, val deviceLocked: Boolean) {
        /**
         * 锁屏判定用 [android.app.KeyguardManager.isDeviceLocked](设备是否真被 PIN/图案/密码锁着),
         * 而不是 [android.app.KeyguardManager.isKeyguardLocked]。后者在部分 ROM(如 HyperOS)解锁后仍会报 true,
         * 导致任务一直停在「等你解锁手机」(#590)。滑动解锁(无凭据)不算「锁着」,任务可以自己滑开。
         * [keyguardLocked] 仍保留,只用于判断锁屏界面是否还在退下去(要不要继续轮询重读)。
         */
        val locked get() = !interactive || deviceLocked
    }

    @Volatile private var last = read()
    private val _locked = MutableStateFlow(last.locked)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun refresh() {
        last = read()
        _locked.value = last.locked
    }

    init {
        scope.launch {
            _locked.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { watched ->
                if (watched) {
                    refresh() // 订阅刚开始就重读一次,别让停更的旧值(漏掉的亮屏/解锁)多活一个轮询周期
                    while (watched) {
                        delay(DeviceStateWatcher.POLL_MS)
                        if (last.locked) refresh()
                    }
                }
            }
        }
    }
}
