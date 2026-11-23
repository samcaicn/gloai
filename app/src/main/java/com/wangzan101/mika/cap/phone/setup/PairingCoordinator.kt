package com.wangzan101.mika.cap.phone.setup

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.core.device.shell.BuiltinShellControl
import com.wangzan101.mika.core.device.shell.PairingCode
import com.wangzan101.mika.settings.ui.PhoneSetupCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次配对的全过程:找配对窗口、无障碍开着时自己读配对码([PairingCodeReader])、
 * 读不到就弹带输入框的通知、把码交给内置助手 [control]、显示结果。
 * 跟着进程走,不跟界面(用户在系统设置里看配对码时 Mika 的界面已经不在前台):由 [com.wangzan101.mika.cap.phone.PhoneServices] 建一份。
 */
class PairingCoordinator(context: Context, private val control: BuiltinShellControl) {
    private val app = context.applicationContext
    /** 配对、重试、解除配对这类动作也放这里跑:界面关了也要做完。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watch: Job? = null
    private val deadline = SlidingDeadline(WINDOW_MS) { SystemClock.elapsedRealtime() }
    /** 通知上此刻显示的;再次提醒前对一下,已经换成别的(比如刚填了码)就不再弹旧的。 */
    @Volatile private var shown: PairingNotice? = null
    private val _repairing = MutableStateFlow(false)
    /** 用户点了「重新配对」且新的配对还没开始:助手即便在运行也要等新配对。 */
    val repairing: StateFlow<Boolean> = _repairing
    /**
     * 填了码、配对还没开始([submit] 起,到 [pair] 把状态改成「正在配对」为止):这段时间通知一律是「正在配对」。
     * [submit] 里的 [begin] 让找窗口的状态当场回到「在找」,不这样的话通知会按填码前的状态再算一条
     * (配对失效时是「配对失效了」,填错码后重填时是又响一次的输入框)。跟着状态更新通知也不在这时结束,见 [PairingNotice.follow]。
     */
    @Volatile private var submitted = false

    fun startRepair() { _repairing.value = true }

    fun cancelRepair() { _repairing.value = false }

    /** 开始一次配对:状态一变成「正在配对」就结束重新配对的等待(免得被运行中的旧状态抢先判成已完成)和填码后的等待([submitted])。 */
    suspend fun pair(code: String, port: Int?) = coroutineScope {
        Log.i(TAG, "pairing with ${if (port != null) "port $port" else "the found port"}")
        val job = launch(start = CoroutineStart.UNDISPATCHED) { control.startPairing(code, port) }
        _repairing.value = false
        submitted = false
        job.join()
    }

    /** 开始看着配对;已经在看就把时限从现在起重新算 [WINDOW_MS],不重来一遍(免得通知又从头弹)。 */
    @Synchronized
    fun begin() {
        deadline.extend()
        control.startDiscovery()
        if (watch?.isActive == true) {
            Log.i(TAG, "already watching; deadline extended")
            return
        }
        Log.i(TAG, "watching for pairing (status ${control.status.value}, accessibility ${ScreenAccessibility.instance.value != null})")
        watch = scope.launch {
            val reader = launch { PairingCodeReader.run(app, control, this@PairingCoordinator) }
            val notices = launch {
                // 三样各由一个协程送进 combine,送到的先后不一定是改的先后(先改的「正在配对」可能后到):每次都按此刻的值算。
                // 按写入的反序读,读到后写的值时先写的也一定读得到:submit 先写 submitted 再让找窗口的状态回到「在找」,
                // pair 先写「正在配对」再清 repairing 和 submitted
                combine(control.status, control.pairingDiscovery, _repairing) { _, _, _ ->
                    val d = control.pairingDiscovery.value
                    val pending = submitted
                    val r = _repairing.value
                    PairingNotice.follow(control.status.value, d, r, pending)
                }
                    .distinctUntilChanged()
                    .onEach { (notice, done) -> Log.i(TAG, "notice ${notice?.let { it::class.simpleName } ?: "none"} (discovery ${control.pairingDiscovery.value}, status ${control.status.value})${if (done) "; done" else ""}") }
                    .transformWhile { emit(it.first); !it.second }
                    // 状态一变就丢掉还没到点的弹出和再次提醒
                    .collectLatest { notice -> notice?.let { post(it) } }
            }
            if (!deadline.await(notices)) {
                Log.i(TAG, "stopped watching: no pairing within ${WINDOW_MS / 60_000} min")
                notices.cancel()
                cancelNotice()
            }
            reader.cancel()
            control.stopDiscovery()
        }
    }

    /** 发出 [notice];没人理就隔一阵再弹,次数有上限。 */
    private suspend fun post(notice: PairingNotice) {
        delay(PairingNotice.delayBefore(notice, autoRead = ScreenAccessibility.instance.value != null))
        show(notice)
        var sent = 0
        while (true) {
            delay(PairingNotice.realertAfter(notice, sent) ?: return)
            if (shown != notice) return
            show(notice)
            sent++
        }
    }

    private fun show(notice: PairingNotice) {
        Log.i(TAG, "show notification ${notice::class.simpleName}")
        shown = notice
        PairingNotifier.show(app, notice)
    }

    private fun cancelNotice() {
        shown = null
        PairingNotifier.cancel(app)
    }

    @Synchronized
    fun end() {
        if (watch?.isActive == true) Log.i(TAG, "stopped watching")
        watch?.cancel()
        watch = null
        _repairing.value = false
        control.stopDiscovery()
        cancelNotice()
    }

    /**
     * 通知或引导页里填的码。不是 6 位数字就原地提示重填、不去试,返回 null;对的话马上换成「正在配对」,免得输入框一直转圈,
     * 返回这次配对(通知那边据此等它做完再结束广播,见 [PairingCodeInput])。
     */
    fun submit(raw: String, port: Int?): Job? {
        val code = PairingCode.normalize(raw)
        if (code == null) {
            Log.i(TAG, "entered code is not 6 digits")
            show(PairingNotice.EnterCode(port, PhoneSetupCopy.NOTIFY_CODE_INVALID))
            return null
        }
        submitted = true
        begin()
        show(PairingNotice.Working)
        return scope.launch { pair(code, port) }
    }

    private companion object {
        const val TAG = "MikaPairing"
        /** 配对窗口开着的时间有限;从最后一次 [begin] 起超过这么久没配上就不再找,下次 [begin] 再开始。 */
        const val WINDOW_MS = 10 * 60_000L
    }
}

/**
 * 通知输入框的落点。系统可能冻结后台的 Mika:收到广播时先解冻它,广播一结束又可能马上冻住,
 * 配对停在半路。所以等配对做完(或最多 [HOLD_MS])再结束广播,这段时间进程算在处理广播,不会被当成后台冻住。
 */
open class PairingCodeInput : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotifier.KEY_CODE)?.toString() ?: return
        val port = intent.getIntExtra(PairingNotifier.EXTRA_PORT, -1).takeIf { it > 0 }
        val pairing = PhoneServices.of(context).pairing
        val job = pairing.submit(code, port) ?: return
        val pending = goAsync()
        pairing.scope.launch {
            try {
                withTimeoutOrNull(HOLD_MS) { job.join() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** 广播最短 10 秒不结束就算无响应,留余量。够找配对端口(5 秒)再配对;之后的连接被冻住也只是晚一点,配对已经记下了。 */
        const val HOLD_MS = 8_000L
    }
}
