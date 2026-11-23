package com.wangzan101.mika.cap.phone.setup

import android.util.Log
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.core.device.shell.A11yObservation
import com.wangzan101.mika.core.device.shell.RecoveryCapability
import com.wangzan101.mika.core.device.shell.RecoveryConsent
import com.wangzan101.mika.core.device.shell.RestoreWish
import com.wangzan101.mika.core.device.shell.RestoreWishSender
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

/**
 * 把「Mika 被杀后要不要拉起来」([RestoreWish])交给内置助手:胶囊模式、有没有任务在跑、「替我打开无障碍」、
 * 系统列表里有没有 Mika 的无障碍服务、用户是不是点了完全退出,任一变了、或换了助手,就经 [IMikaShell.setRestoreWish] 再交一次。
 * 助手读不到 Mika 的设置,Mika 死后只按最后交到的这一份行事。
 */
class RestoreWishSync(
    private val hub: HelperHub,
    private val capsuleMode: Flow<CapsuleMode>,
    private val taskRunning: Flow<Boolean>,
    private val consent: Flow<RecoveryConsent>,
    private val accessibility: Flow<A11yObservation>,
    /** 用户点了「完全退出」:哪怕胶囊是「一直显示」、哪怕还有任务在跑,也不让助手把 Mika 拉起来。 */
    private val quit: Flow<Boolean>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sender = RestoreWishSender<IMikaShell> { shell, wish ->
        runCatching { shell.setRestoreWish(wish.flags) }
            .onFailure { Log.w(TAG, "cannot hand the restore wish to the helper", it) }
            .isSuccess
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        var lastOn: Boolean? = null
        // 列表里没了 Mika 的服务要过一会儿才交出去:进程被强行停止时系统会先拿掉它,不能让这一下把心愿改掉;
        // 一直没有(用户在系统设置里关掉了)才不替他写回
        val accessibilityOn = accessibility.map { it.enabled }.distinctUntilChanged().mapLatest { on ->
            if (!on && lastOn == true) delay(OFF_SETTLE_MS)
            lastOn = on
            on
        }
        val wishes = combine(capsuleMode, taskRunning, consent, accessibilityOn, quit) { mode, busy, c, on, quit ->
            RestoreWish.of(mode == CapsuleMode.Always && !quit, busy && !quit, c.wants(RecoveryCapability.Accessibility), on, quit)
        }.distinctUntilChanged()
        scope.launch { combine(hub.shell, wishes, ::Pair).collect { (shell, wish) -> sender.update(shell, wish) } }
    }

    private companion object {
        const val TAG = "MikaRestoreWish"
        const val OFF_SETTLE_MS = 3_000L
    }
}
