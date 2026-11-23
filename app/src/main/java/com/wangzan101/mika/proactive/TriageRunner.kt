package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.NoticeInbox
import com.wangzan101.mika.core.proactive.NoticeTriage
import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.proactive.TargetedModel
import com.wangzan101.mika.core.proactive.TriageConsent
import com.wangzan101.mika.core.proactive.TriageCounts
import com.wangzan101.mika.core.proactive.TriageGate
import com.wangzan101.mika.core.proactive.TriageMark
import com.wangzan101.mika.core.proactive.TriagePlan
import com.wangzan101.mika.core.proactive.TriagePlanner
import com.wangzan101.mika.core.proactive.TriageResult
import com.wangzan101.mika.core.proactive.TriageTarget
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 判定要用的、App 别处的东西,都由 MikaApp 经 lambda 给:判定关着时一样都不碰(服务商配置、用量账本)。
 * [target] 是现在会用的服务商和模型(不建模型,给界面和门口的检查);[model] 建模型,连同建它用的那个目标
 * (同一次读配置,发请求前对着它查同意);[busy] 是「有任务在跑」。
 */
class TriageHooks(
    val target: () -> TriageTarget?,
    /** 建模型。参数是这次要发哪一种请求(回复上限跟着它定)。 */
    val model: (ProactiveCall) -> TargetedModel?,
    val tokensToday: () -> Long,
    val callsToday: () -> Long,
    val usageVersion: () -> StateFlow<Long>,
    val busy: StateFlow<Boolean>,
)

enum class TriageStatus {
    Off,
    /** 判定开着,但主动能力没在看守(完全退出期间):什么都不做。 */
    Paused,
    NoModel,
    NeedsConsent,
    /** 「应用访问」设置读不出来:不知道哪些 App 不让碰,停着;待判定的留着。 */
    Suspended,
    /** 连不上模型(或等到超时也没回应),整个停一阵再试;待判定的留着。 */
    Unreachable,
    Locked,
    /** 有任务在跑,等它结束。 */
    Busy,
    /** 判定那份 Token 今天用完了(或请求时被拒、按 Token 算):明天继续。 */
    TokensUsedUp,
    /** 判定那份请求次数今天用完了:明天继续。 */
    CallsUsedUp,
    /** 有待判定的在等这个 App 凑一批,或判过的通知有了新内容、在等冷却到点。 */
    Waiting,
    Sending,
    Idle,
}

/**
 * 给界面的:现在的状态、各条的结果(按通知键;发出时间对上的是这次的结果,比记录旧的是冷却前那次的结果)、几个数。
 */
data class TriageState(
    val status: TriageStatus = TriageStatus.Off,
    val marks: Map<String, TriageMark> = emptyMap(),
    val counts: TriageCounts = TriageCounts(),
)

/**
 * 判定的调度:在 [ProactiveRuntime] 那条串行协程上跑,只在看守期间([watch])活着。
 *
 * 省电:不轮询。收件箱变了、锁屏状态变了、[TriageHooks.busy] 变了、外部改了开关或预算([poke])时才看一遍([evaluate]);
 * 计时(凑批、判过或判失败的通知冷却到点)只在解锁时才有,一看到锁着就取消,锁着时到点的解锁后再看;锁着时正在发的请求也取消,那一批照旧待判定(不算失败),解锁后再发。
 * 一次只发一批,一批只有一个 App。不自动重试:失败的那批记成失败,按连续失败的次数停一段,停着期间的更新不发,
 * 停完有新内容才再判;没有新内容就等用户点「再试一次」([retryFailed])才再发一次。
 * 预算用完(请求时被拒,或账本那边已到上限)就当天安静停下。
 * 结果只在内存里([TriagePlanner]);日志([log])只记结果类别和条数,不记通知文字和理由。
 */
internal class TriageRunner(
    private val inbox: NoticeInbox,
    private val hooks: TriageHooks,
    private val on: () -> Boolean,
    private val consent: () -> TriageConsent?,
    private val budgetLimit: () -> Long,
    private val language: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val planner: TriagePlanner = TriagePlanner(),
    private val log: (String) -> Unit = {},
    /** 每个 App 每天最多提醒几个不同的会话(跟着激进程度变)。 */
    private val surfaceCap: () -> Int = { TriagePlanner.SURFACE_CAP_PER_APP },
    /** 「应用访问」读不出来:停着,不发请求;待判定的照旧留着,读得出来了接着判。 */
    private val suspended: () -> Boolean = { false },
    /** 和归纳共用的请求位(一次只发一个后台请求);不给就是自己一个。 */
    private val slot: RequestSlot = RequestSlot(),
) {
    private val mutable = MutableStateFlow(TriageState())
    val state: StateFlow<TriageState> = mutable.asStateFlow()

    private var host: CoroutineScope? = null
    private var readLocked: () -> Boolean = { true }
    private var timer: Job? = null
    private var inFlight: Job? = null

    /**
     * 连不上模型(网络不通、代理不通),或连上了却等到超时也没回应时,整个停一阵,不是只停这一批的那几条通知:这和通知的内容无关,
     * 只按会话各自冷却的话,每来一个新会话都会再连一次(新会话不在冷却里),来得多的时候就是不停地重连、白耗请求次数。
     * 连续连不上几次停得越久([TriagePlanner.failureHoldMs])。判成一批、用户点了重试、换了服务商或模型、重新开始看守,都清掉。
     * 没连上的那一批照旧待判定,不算失败;超时的那一批算失败(可能是这一批本身的问题,不自动重发,用户可以手动再试)。
     */
    private var unreachable = 0
    private var unreachableUntilMs = 0L
    /** 停下时用的是哪一家的哪个模型:换了就不再停。按服务商 id 和模型名认(和同意一样),不看显示名、不比对象。 */
    private var unreachableTarget: TriageConsent? = null

    private fun reachableAgain() {
        unreachable = 0
        unreachableUntilMs = 0L
        unreachableTarget = null
    }

    /** 测试看:计时(凑批或冷却到点)在不在。 */
    internal val timerActive: Boolean get() = timer?.isActive == true
    internal val requestActive: Boolean get() = inFlight?.isActive == true

    /**
     * 看守期间一直跑;被取消(锁屏不取消它,关掉功能、完全退出才取消)时计时和请求随之取消,正在发的那批照旧待判定。
     * [readLocked] 每次从系统读一次锁屏状态(发请求前也读一次,不只看 [locked] 的旧值)。
     */
    suspend fun watch(locked: StateFlow<Boolean>, readLocked: () -> Boolean) {
        var mine: CoroutineScope? = null
        try {
            coroutineScope {
                mine = this
                // 上一次看守留下的(它的收尾可能还没轮到)不算数:那一批照旧待判定
                timer?.cancel()
                timer = null
                inFlight?.cancel()
                inFlight = null
                slot.release(this@TriageRunner)
                planner.abort()
                // 重新开始看守(开关关了又开、回到前台):上一次因为连不上而停着的不接着停
                reachableAgain()
                host = this
                slot.listen(this@TriageRunner) { evaluate() }
                this@TriageRunner.readLocked = readLocked
                launch { inbox.state.collect { evaluate() } }
                launch { locked.collect { evaluate() } }
                launch { hooks.busy.collect { evaluate() } }
                awaitCancellation()
            }
        } finally {
            // 停了又马上重新开始看守时,新的那次可能已经接手:只收拾自己的
            if (host === mine) {
                host = null
                timer = null
                inFlight = null
                planner.abort()
                publish(if (on()) TriageStatus.Paused else TriageStatus.Off)
                slot.unlisten(this)
                slot.release(this)
                slot.wakeOthers(this)
            }
        }
    }

    /** 开关、同意、预算之类变了:重新看一遍。要在串行协程上调。 */
    fun poke() {
        if (host != null) evaluate() else publish(if (on()) TriageStatus.Paused else TriageStatus.Off)
    }

    /**
     * 不再收的 App(「应用访问」里禁用了):正在发的是它的那一批就取消(照旧待判定;记录随后从收件箱里去掉,也就不再发),
     * 再看一遍。要在串行协程上调,在收件箱去掉记录之后。
     */
    fun dropApps(keep: (String) -> Boolean) {
        val pkg = planner.inFlightApp ?: return
        if (keep(pkg)) return
        stopRequest()
        poke()
    }

    /** 「判定失败的再试一次」:点一次只多试一次。要在串行协程上调。 */
    fun retryFailed() {
        planner.retryFailed(clock())
        // 用户在等结果:连不上而停着的也不再停,马上再试
        reachableAgain()
        poke()
    }

    /** 用户改了预算:不再认今天请求时被拒的那次,按账本重新算。 */
    fun budgetChanged() {
        planner.clearBudgetLatch()
        poke()
    }

    /** 主动能力关掉时:结果一并清掉(和收件箱同生同灭)。 */
    fun clear() {
        stopRequest()
        timer?.cancel()
        timer = null
        planner.clear()
        publish(if (on()) TriageStatus.Paused else TriageStatus.Off)
    }

    /**
     * 看一遍现在该做什么。用到的外部东西(模型配置、用量账本)抛了异常也不能带垮看守(它和锁屏的看守在同一个作业里):
     * 记下类别,当作这次什么都不做。
     */
    private fun evaluate() {
        try {
            evaluateOrThrow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("triage evaluate failed: ${e.javaClass.simpleName}")
            // 已开了一批、请求却没发出去:算这批失败,免得一直卡在「正在发」
            if (inFlight == null && planner.inFlightNow) planner.fail(clock())
            // 已经在发的请求不动:读一下配置出错不说明它该停(停了它已经算过钱,回头还得再发一遍)。只是这一回不发新的。
            // 但只在确实没锁屏时才留着:锁屏状态读不出来、或锁着,照旧停——锁屏不发是硬规矩
            val unlocked = try { !readLocked() } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            if (inFlight != null && unlocked) publish(TriageStatus.Sending) else stopAndPublish(TriageStatus.Idle)
        }
    }

    private fun evaluateOrThrow() {
        val scope = host ?: return
        timer?.cancel()
        timer = null
        val now = clock()
        planner.sync(inbox.state.value, now)
        if (!on()) return stopAndPublish(TriageStatus.Off)
        if (suspended()) return stopAndPublish(TriageStatus.Suspended)
        if (readLocked()) return stopAndPublish(TriageStatus.Locked)
        when (TriageGate.of(true, consent(), hooks.target())) {
            TriageGate.NoModel -> return stopAndPublish(TriageStatus.NoModel)
            TriageGate.NeedsConsent -> return stopAndPublish(TriageStatus.NeedsConsent)
            else -> {}
        }
        // 连不上模型、正停着:到点再看(钟被往回拨了、剩下的比最长的一级还长的不算;换了服务商或模型的不再停)
        if (unreachable > 0 && unreachableTarget?.covers(hooks.target()) != true) reachableAgain()
        val offlineFor = unreachableUntilMs - now
        if (unreachable > 0 && offlineFor > 0 && offlineFor <= TriagePlanner.BACKOFF_MS.last()) {
            timer = scope.launch {
                delay(offlineFor)
                timer = null
                evaluate()
            }
            return publish(TriageStatus.Unreachable)
        }
        when (val plan = planner.plan(unlocked = true, busy = hooks.busy.value, budgetLeft = budgetLeft(), nowMs = now)) {
            // 归纳那边正在发:等它发完(它发完会叫这边再看一遍)
            is TriagePlan.Send -> if (slot.heldByOther(this)) publish(TriageStatus.Waiting) else send(scope, plan.packageName)
            is TriagePlan.WaitUntil -> {
                timer = scope.launch {
                    delay((plan.atMs - now).coerceAtLeast(0))
                    timer = null
                    evaluate()
                }
                publish(TriageStatus.Waiting)
            }
            is TriagePlan.Idle -> publish(when (plan.why) {
                TriagePlan.Why.Locked -> TriageStatus.Locked
                TriagePlan.Why.Nothing -> TriageStatus.Idle
                TriagePlan.Why.BudgetUsedUp -> budgetStatus()
                TriagePlan.Why.Busy -> TriageStatus.Busy
                TriagePlan.Why.InFlight -> TriageStatus.Sending
            })
        }
    }

    private fun send(scope: CoroutineScope, packageName: String) {
        val items = planner.begin(packageName)
        if (items.isEmpty()) return publish(TriageStatus.Idle)
        // 同意是对着这一家的这个模型给的:对着建模型用的那个目标再对一次,回来时再对一次
        val consented = consent()
        // 建模型(要解密 Key)出错算这一批失败,不留着待判定:否则每来一条都再出一次错
        val built = try {
            hooks.model(ProactiveCall.Triage)
        } catch (e: Exception) {
            planner.fail(clock())
            log("triage model unavailable: ${e.javaClass.simpleName}")
            return publish(TriageStatus.Idle)
        }
        if (built == null || consented == null) {
            planner.abort()
            return publish(TriageStatus.NoModel)
        }
        // 门口查过之后配置可能变了:建模型用的不是同意过的那个,就不发(照旧待判定,等用户重新确认)
        if (!consented.covers(built.target)) {
            planner.abort()
            log("triage not sent: target is not the consented one")
            return publish(TriageStatus.NeedsConsent)
        }
        val model = built.model
        val lang = language()
        publish(TriageStatus.Sending)
        // 先记下作业再开始跑:请求体里要拿它和 inFlight 比
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // 这里抛出去会带垮整个看守(锁屏、归纳都在里面):读回复时出了意外也只算这批失败
            val result = try {
                NoticeTriage(model, lang).triage(items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("triage request failed: ${e.javaClass.simpleName}")
                TriageResult.Failed()
            }
            // 已经不是在发的那一个(被取消了,模型却还是回来了;新的一批可能已经在发):什么都不动
            if (inFlight !== coroutineContext.job) return@launch
            inFlight = null
            slot.release(this@TriageRunner)
            // 用上结果时也一样:出了意外就当这批失败
            try {
                val now = clock()
                planner.sync(inbox.state.value, now)
                val stillConsented = runCatching { on() && consent() == consented && consented.covers(hooks.target()) }.getOrDefault(false)
                if (!stillConsented) {
                    planner.abort()
                    log("triage result dropped: consent or switch changed")
                } else when (result) {
                    is TriageResult.Judged -> {
                        planner.surfaceCap = surfaceCap()
                        planner.finish(result.verdicts, now)
                        reachableAgain()
                    }
                    is TriageResult.Failed -> {
                        // 没连上:和这一批的内容无关,这一批照旧待判定。别的失败记在这一批上
                        if (result.unreachable) planner.abort() else planner.fail(now)
                        // 没连上、或等到超时也没回应:整个停一阵再试(见 unreachable)
                        if (result.unreachable || result.unanswered) {
                            unreachable++
                            unreachableUntilMs = now + TriagePlanner.failureHoldMs(unreachable)
                            unreachableTarget = TriageConsent.of(built.target)
                        }
                    }
                    TriageResult.BudgetExhausted -> planner.budgetExhausted(now)
                }
                // 失败的写明是哪一种(没连上 / 回复读不懂),只有这几个字,没有内容
                val why = (result as? TriageResult.Failed)
                    ?.let { if (it.unreachable) " unreachable" else if (it.unanswered) " unanswered" else if (it.unreadable) " unreadable" else "" }.orEmpty()
                log("triage batch: ${result.javaClass.simpleName}$why, ${items.size} items")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("triage apply failed: ${e.javaClass.simpleName}")
                if (planner.inFlightNow) planner.fail(clock())
            }
            // 先看自己还有没有要发的(判定先),再叫等着的归纳
            evaluate()
            slot.wakeOthers(this@TriageRunner)
        }
        slot.take(this)
        inFlight = job
        job.start()
    }

    /** 判定那份([ProactiveBudget.TRIAGE_SHARE_PERCENT]%)还有没有;整份里余下的留给主动能力的其他用途。 */
    private fun budgetLeft(): Boolean = triageExhausted() == null

    private fun triageExhausted(): ProactiveBudget.Limit? =
        ProactiveBudget.triageExhausted(hooks.tokensToday(), hooks.callsToday(), budgetLimit())

    /**
     * 停下是因为哪一项:先看判定那份;那份还有、却是请求时被拒(当天记着,别的用途把整份用完了)的,看整份里哪项用完;
     * 都看不出(账本还没跟上)按 Token 算。
     */
    private fun budgetStatus(): TriageStatus {
        val limit = triageExhausted()
            ?: ProactiveBudget.exhausted(hooks.tokensToday(), hooks.callsToday(), budgetLimit())
            ?: ProactiveBudget.Limit.Tokens
        return if (limit == ProactiveBudget.Limit.Calls) TriageStatus.CallsUsedUp else TriageStatus.TokensUsedUp
    }

    /** 取消正在发的请求(锁屏、关掉、换了服务商):那一批照旧待判定。 */
    private fun stopRequest() {
        val job = inFlight ?: return
        inFlight = null
        job.cancel()
        planner.abort()
        log("triage request cancelled")
        slot.release(this)
        slot.wakeOthers(this)
    }

    private fun stopAndPublish(status: TriageStatus) {
        stopRequest()
        publish(status)
    }

    private fun publish(status: TriageStatus) {
        mutable.value = TriageState(status, planner.marks(), planner.counts())
    }
}
