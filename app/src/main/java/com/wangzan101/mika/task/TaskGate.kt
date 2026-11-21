package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * AgentLoop 在每个会改动屏幕的工具之前调用 [pass]。按先后检查:
 * 1. 手机操作助手不可用 → 暂停,等它恢复且用户点「继续」;
 * 2. 任务被挪到主屏(用户在主屏打开了这个 App)→ 暂停,用户点「继续」后才把它挪回虚拟屏;
 *    Mika 不自己判断用户是否用完,避免在用户眼前把 App 抽走;
 * 3. 屏幕熄灭或锁屏 → 定时任务及另行授权的微信任务尝试唤醒/解锁，其他任务等待解锁；亮屏并解锁后自动继续。
 * 前两条只在这一步用的是手机(phone 为 true)时才查:网页、沙箱上的步骤不需要助手,也不会被挪到主屏。
 * 另外,用户在浏览器页上亲手摸了借给任务的页面([onUserOnPage])→ 不论哪种屏都先停下,用户点「继续」再接着做。
 * 不碰界面的步骤(模型推理、读屏)不经过这里,照常进行。
 */
class TaskGate(
    private val locked: StateFlow<Boolean>,
    private val shellAvailable: StateFlow<Boolean>,
    /** 写操作前同步重读系统锁屏状态，避免熄屏广播尚未处理时放行。 */
    private val refreshLocked: (() -> Unit)? = null,
    /** 无人值守任务遇到锁屏时调用;返回解锁状态,返回前必须按系统状态刷新 [locked]。 */
    private val unattendedUnlock: (suspend () -> String)? = null,
    private val unlockFailureMessage: () -> String = { tr(R.string.trigger_unlock_failed) },
    /** 本地授权(本机存着的解锁授权)的任务:解锁失败后允许转为等用户手动解锁,而不是失败收尾。 */
    private val recoverUnlockFailure: () -> Boolean = { false },
    /** 转为等用户手动解锁时告知一句(带不含凭据的状态码),好发提醒。 */
    private val onRecoveryFailure: (String) -> Unit = {},
    private val moveBack: suspend () -> Boolean,
) {
    private val movedAway = MutableStateFlow(false)
    private val userOnPage = MutableStateFlow(false)
    private val resumes = MutableStateFlow(0)
    /** 任务是否已经至少做过一步(关卡放通过一次):开局那次解锁不算「中途锁屏」。 */
    private var hasPassed = false
    /** 发现被挪走那一刻的点击计数:之后点的「继续」都算数,哪怕 Agent 还没走到 gate。 */
    @Volatile private var resumesAtMove = 0

    fun onTaskMovedAway() {
        if (!movedAway.value) resumesAtMove = resumes.value
        movedAway.value = true
    }

    /** 已发现被挪走、还没挪回:任务服务据此立即显示暂停,不必等到下一个改动屏幕的工具。 */
    val movedAwayPending: Boolean get() = movedAway.value

    /**
     * 用户在浏览器页上亲手摸了借给任务的页面(他在操作,Mika 不能同时动):下一个会改动屏幕的工具之前停下,
     * 等用户点「继续」;任务服务据此立即显示暂停。
     */
    fun onUserOnPage() { userOnPage.value = true }

    /** 用户在借出的页面上操作、还没点「继续」。 */
    val userOnPagePending: Boolean get() = userOnPage.value

    /** 用户点了「继续」。只对点击之后才开始的、或正在进行的暂停有效;用户在页面上操作的那种暂停当场解除。 */
    fun resume() {
        resumes.update { it + 1 }
        userOnPage.value = false
    }

    /**
     * 返回 true 表示这次等待期间发生过锁屏周期(任务已开始之后):调用方据此作废按旧屏幕准备好的动作,
     * 先重读现在的屏幕。开局的第一次解锁不算。
     */
    suspend fun pass(phone: () -> Boolean = { true }, setWaiting: (TaskState?) -> Unit): Boolean {
        var recoveredMidRun = false
        while (true) {
            refreshLocked?.invoke()
            val onPhone = phone()
            when {
                userOnPage.value -> {
                    setWaiting(TaskState.Held(paused(USER_ON_PAGE)))
                    userOnPage.first { !it }
                }
                onPhone && !shellAvailable.value -> {
                    pauseUntilResume(SHELL_DOWN, setWaiting)
                }
                onPhone && movedAway.value -> {
                    pauseUntilResume(MOVED_AWAY, setWaiting, seen = resumesAtMove)
                    while (shellAvailable.value && !moveBack()) pauseUntilResume(MOVE_BACK_FAILED, setWaiting)
                    if (shellAvailable.value) movedAway.value = false
                }
                locked.value -> {
                    recoveredMidRun = recoveredMidRun || hasPassed
                    setWaiting(TaskState.Held(LOCKED))
                    if (unattendedUnlock == null) {
                        locked.first { !it }
                    } else {
                        val status = try { unattendedUnlock.invoke() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { refreshLocked?.invoke(); "internal_error" }
                        // 无人值守时不能无限等用户来解锁；只认刷新后的系统状态，不认流程自称成功。
                        if (locked.value) {
                            // 已开始的本地授权任务不杀、也不重复注入凭据:转为等用户手动解锁
                            if (!hasPassed || !recoverUnlockFailure()) throw UnattendedLockException(unlockFailureMessage(), status)
                            onRecoveryFailure(status)
                            locked.first { !it }
                        }
                    }
                }
                else -> {
                    hasPassed = true
                    setWaiting(null)
                    return recoveredMidRun
                }
            }
        }
    }

    /** 要用户在手机上亲手处理一步,处理好了点「继续」(界面上算「要你处理」)。 */
    suspend fun pauseForUser(why: String, setWaiting: (TaskState?) -> Unit) {
        val seen = resumes.value
        setWaiting(TaskState.Held(GateReason("paused", why, resumable = true, needsYou = true)))
        resumes.first { it != seen }
    }

    /** [seen]:从这个点击计数之后的「继续」才算;默认取当前值(先记下再显示暂停,显示后立刻点的也算)。 */
    private suspend fun pauseUntilResume(why: String, setWaiting: (TaskState?) -> Unit, seen: Int = resumes.value) {
        setWaiting(TaskState.Held(paused(why)))
        resumes.first { it != seen }
    }

    companion object {
        /** 锁屏或熄屏:解锁后自动继续(运行记录里记成 waiting_unlock)。 */
        val LOCKED = GateReason("waiting_unlock")

        /** 暂停,用户点「继续」后才接着做(运行记录里记成 paused,带上原因)。 */
        fun paused(why: String) = GateReason("paused", why, resumable = true)

        /** 等用户在手机上动一下手(比如在系统的选择框里选一个),动完自动继续(运行记录里记成 waiting_user)。 */
        fun userStep(why: String) = GateReason("waiting_user", why, needsYou = true)

        /** 系统在用户屏上问打开 [app] 的哪一个时请用户选的那句;名字读不到时不说名字。 */
        fun pickCopy(app: String?) = app?.let { tr(R.string.gate_pick_app, it) } ?: tr(R.string.gate_pick_system)

        /** 选出来的 [app] 要用户亲手处理时的那句。 */
        fun finishCopy(app: String?) = app?.let { tr(R.string.gate_finish_app, it) } ?: tr(R.string.gate_finish_unknown)

        val MOVED_AWAY: String get() = tr(R.string.gate_moved_away)
        val MOVE_BACK_FAILED: String get() = tr(R.string.gate_move_back_failed)
        val SHELL_DOWN: String get() = tr(R.string.gate_shell_down)
        /** 用户在浏览器页上亲手操作借给任务的页面时暂停的原因(界面据此认出这种暂停)。 */
        val USER_ON_PAGE: String get() = tr(R.string.gate_user_on_page)
    }
}

/** 无人值守任务无法通过系统锁屏；保留不含凭据的状态码,由任务服务转成有结果通知的失败终态。 */
internal class UnattendedLockException(message: String, val status: String) : Exception("$message [$status]")
