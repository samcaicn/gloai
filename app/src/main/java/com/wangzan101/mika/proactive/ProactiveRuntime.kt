package com.wangzan101.mika.proactive

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.format.DateUtils
import android.util.Log
import com.wangzan101.mika.R
import com.wangzan101.mika.core.proactive.CatchUp
import com.wangzan101.mika.core.proactive.GrantResult
import com.wangzan101.mika.core.proactive.ListenerGrant
import com.wangzan101.mika.core.proactive.LiveUpdates
import com.wangzan101.mika.core.proactive.NoticeDedup
import com.wangzan101.mika.core.proactive.NoticeFilter
import com.wangzan101.mika.core.proactive.NoticeHints
import com.wangzan101.mika.core.proactive.NoticeInbox
import com.wangzan101.mika.core.proactive.LearnLevel
import com.wangzan101.mika.core.proactive.SourceTier
import com.wangzan101.mika.core.proactive.NoticeRecord
import com.wangzan101.mika.core.proactive.NoticeShapes
import com.wangzan101.mika.core.proactive.NoticeView
import com.wangzan101.mika.core.proactive.NoticeSource
import com.wangzan101.mika.core.proactive.NoticeTalk
import com.wangzan101.mika.core.proactive.TriageConsent
import com.wangzan101.mika.core.proactive.TriageTarget
import com.wangzan101.mika.core.device.shell.ShellCommandRunner
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.settings.ui.classifierLanguage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 主动能力(入口在设置首页,默认关着):读用户选定的 App 的通知,筛选、去重后放进内存里的收件箱。
 * 用户确认过把通知文字发给哪个服务商、哪个模型(轻量,没有用主模型)之后:[TriageRunner] 按 App 分批判成忽略、记下或
 * 值得提醒,结果只在内存里;[LearnRunner] 从中归纳话题、联系人和各级消息归纳,加密存在本机。没确认过、或实际要用的
 * 服务商和模型与确认过的不一样时,什么都不发。
 *
 * 熄屏或锁屏时不读:看到锁着就记下时间并请系统解绑监听;解锁后请系统重绑,连上时把锁屏之后发出、还在通知栏里的补上。
 * 锁屏状态由 [ProactiveLockWatch] 看:广播只当「该重新看一眼」,状态每次从系统读;熄屏时不定时做任何事,不让手机为这个功能醒。
 * 所有处理都在一条串行的协程上做,系统回调只是把事情丢过去。
 */
class ProactiveRuntime(
    context: Context,
    private val flags: FeatureFlags,
    val allowed: NoticeAllowStore,
    /** 「应用访问」此刻的样子;读不出来时主动能力整个停着(见 [AppGate])。 */
    private val access: () -> AppGate,
    /** 「应用访问」变了就发一次(收集时先给现在的):禁用之后,已经收下的一并去掉;读不出来、又读得出来时重新看一遍。 */
    private val accessChanges: Flow<AppGate>,
    /** 「由 Mika 助手授权」用的 shell 命令通道;只在用户点了按钮时才取,功能关着时不碰手机能力。 */
    private val shellRunner: () -> ShellCommandRunner,
    /** 判定要用的模型、用量和「有任务在跑」;判定关着时不碰。 */
    private val triageHooks: TriageHooks,
) {
    private val app = context.applicationContext
    private val prefs = ProactivePrefs(app.getSharedPreferences(ProactivePrefs.FILE, Context.MODE_PRIVATE))
    // 上次选了「不读」、名单文件却没写成的 App:先照办,再做别的(下面没有哪一步在这之前读名单)
    init { AllowEdits.replay(allowed, prefs) }
    private val power = app.getSystemService(PowerManager::class.java)
    private val keyguard = app.getSystemService(KeyguardManager::class.java)
    private val component = ComponentName(app, NoticeListenerService::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val lock = ProactiveLockWatch({ ProactiveLockWatch.Reading(power.isInteractive, keyguard.isKeyguardLocked) }, scope)

    val inbox = NoticeInbox()
    private val dedup: NoticeDedup = NoticeDedup.decode(prefs.dedup)
    /** 被 App 反复重发的状态显示(导航播报之类)认出来就不收;只在内存里。 */
    private val live = LiveUpdates()
    private val policy = ProactiveLockPolicy(prefs.lastLockedAtMs)

    private val _enabled = MutableStateFlow(false)
    /** 总开关(在这个版本上能用、并且开着)。判定和归纳都只看它和 [consent]。 */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _consent = MutableStateFlow(prefs.consent)
    /** 用户同意把通知文字发给的那个服务商和模型(判定、归纳共用);没有它什么都不发。 */
    val consent: StateFlow<TriageConsent?> = _consent.asStateFlow()
    private val _triageLimit = MutableStateFlow(flags.proactiveDailyTokenBudget())
    /** 主动能力每天的 Token 上限。 */
    val triageLimit: StateFlow<Long> = _triageLimit.asStateFlow()
    /** 判定和归纳共用:主动能力的后台请求一次只发一个。 */
    private val requests = RequestSlot()
    private val triage = TriageRunner(
        inbox, triageHooks,
        on = { _enabled.value },
        consent = { _consent.value },
        budgetLimit = flags::proactiveDailyTokenBudget,
        language = ::triageLanguage,
        log = { Log.i(TAG, it) },
        surfaceCap = { _learnLevel.value.surfaceCap },
        suspended = { !access().known },
        slot = requests,
    )
    val triageState = triage.state

    private val _learnOptOut = MutableStateFlow(prefs.learnOptOut)
    /** 来源档位是「低」的 App:读它的通知,但不从它归纳。 */
    val learnOptOut: StateFlow<Set<String>> = _learnOptOut.asStateFlow()
    private val _learnSensitive = MutableStateFlow(prefs.learnSensitive)
    /** 「敏感类推断」开关;默认关。 */
    val learnSensitive: StateFlow<Boolean> = _learnSensitive.asStateFlow()
    private val _learnLevel = MutableStateFlow(LearnLevel.ofWire(prefs.learnLevel))
    /** 激进程度(默认最保守的一档)。 */
    val learnLevel: StateFlow<LearnLevel> = _learnLevel.asStateFlow()
    private val _learnHigh = MutableStateFlow(prefs.learnHigh)
    /** 来源档位调成「高」的 App。 */
    val learnHigh: StateFlow<Set<String>> = _learnHigh.asStateFlow()
    private val _learnDigest = MutableStateFlow(prefs.learnDigest)
    /** 「当日消息归纳」开关(默认关):开着时通知原文加密存在本机,最多两天。 */
    val learnDigest: StateFlow<Boolean> = _learnDigest.asStateFlow()
    private val learn = LearnRunner(
        triageHooks,
        on = { _enabled.value },
        consent = { _consent.value },
        budgetLimit = flags::proactiveDailyTokenBudget,
        language = ::triageLanguage,
        eligible = { pkg -> BlockedApps.learnEligible(pkg, allowed.current(), _learnOptOut.value, access().blocked) },
        suspended = { !access().known },
        slot = requests,
        sensitive = { _learnSensitive.value },
        store = TopicPoolFile(app.filesDir, KeystoreKeyCipher(TopicPoolFile.KEY_ALIAS)),
        digest = { _learnDigest.value },
        journalStore = NoticeJournalFile(app.filesDir, KeystoreKeyCipher(NoticeJournalFile.KEY_ALIAS)),
        digestStore = DigestFile(app.filesDir, KeystoreKeyCipher(DigestFile.KEY_ALIAS)),
        appLabel = { com.wangzan101.mika.task.ConfirmationNotifier.appLabel(app, it) },
        contactStore = ContactPoolFile(app.filesDir, KeystoreKeyCipher(ContactPoolFile.KEY_ALIAS)),
        purges = prefs,
        log = { Log.i(TAG, it) },
    )
    val learnState = learn.state
    val learnTopics = learn.topics
    val learnTopicsBlocked = learn.topicsBlocked
    val learnDigestRemoved = learn.digestRemoved
    val learnDigestRemovedDays = learn.digestRemovedDays
    val learnContactsView = learn.contactsView
    /** 当日消息归纳攒下的句子(分段归纳)。 */
    val learnDigestNotes = learn.digestNotes
    /** 各天的当日归纳,新的在前。 */
    val learnDigestDays = learn.digestDays
    val learnDigestWeeks = learn.digestWeeks
    val learnDigestMonths = learn.digestMonths

    @Volatile private var service: NoticeListenerService? = null
    private val connected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = connected.asStateFlow()

    /** 「应用访问」设置读不读得出来(设置页的状态卡用;没在看守时也是准的)。读不出来时主动能力整个停着。 */
    val accessKnown: Flow<Boolean> = accessChanges.map { it.known }.distinctUntilChanged()
    fun accessKnownNow(): Boolean = access().known
    /** 在跑(功能开着,且不在「完全退出」期间)时才有的看守:锁屏广播与状态收集。 */
    private var watching: Job? = null
    private var receiver: BroadcastReceiver? = null

    /** 总开关:入口在设置首页,每个版本都看得到、关得掉。 */
    private fun wanted(): Boolean = flags.proactive()

    /**
     * 功能开着时,进程起来、或用户从「完全退出」回来时调一次(MikaApp 只在开关开着时调)。
     * [paused] 为 true(完全退出期间)时只把组件状态对齐开关,不开始看守。
     */
    fun start(paused: Boolean) {
        val on = wanted()
        _enabled.value = on
        if (on) enableComponent()
        if (on && !paused) scope.launch { begin() }
    }

    /**
     * 「完全退出」时调:停下看守并请系统解绑,开关和白名单都不动。之后系统为别的服务把进程拉起来、
     * 又把监听绑上时,[onConnected] 看到没在看守会再解绑;用户重新打开 Mika 时由 [start] 恢复。
     */
    fun pause() {
        scope.launch {
            end()
            unbind()
        }
    }

    /**
     * 设置页的总开关:唯一改开关的入口。打开时 [target] 是确认框里给用户看过的那一家那个模型,同意就对着它记
     * (没配模型时为 null:先开着收,不发任何东西,配好模型后在页面上确认)。关掉时同意一并清掉:再打开要重新确认。
     * 关掉不删已经归纳出的东西(话题、联系人、各级归纳):那些由用户自己清;通知原文和内存里没处理完的清掉。
     */
    fun setEnabled(on: Boolean, target: TriageTarget? = null) {
        flags.setProactive(on)
        setConsent(TriageConsent.afterSwitch(on, target, _consent.value))
        _enabled.value = on
        scope.launch {
            if (on) {
                // 打开之前已在通知栏里的积压一条都不收
                prefs.enabledAtMs = System.currentTimeMillis()
                enableComponent()
                begin()
            } else {
                // 关掉只请系统解绑,不把组件停用:真机上(Android 16)组件一停用,系统就把通知使用权收回,
                // 再打开得让用户重新授权。解绑后系统不再送通知;进程重启被系统重新绑上时,[onConnected] 里再解绑。
                end()
                unbind()
                inbox.clear()
                triage.clear()
                learn.clear()
                dedup.clear()
                live.clear()
                prefs.clearDedup()
            }
        }
    }

    /**
     * 系统设置里有没有给 Mika 通知使用权。问通知服务本身,不读 `enabled_notification_listeners` 那个设置项:
     * 真机上使用权被收回后那个设置项里还留着旧值。
     */
    fun authorized(): Boolean = runCatching {
        app.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
    }.getOrDefault(false)

    private val granting = Mutex()

    /**
     * 用户在主动能力页点「由 Mika 助手授权」并确认后调:经内置助手(或已配对的连接)以 shell 身份给监听开通知使用权。
     * 只由用户操作触发,不在后台自动调;用户离开页面也跑完(授权生效了要重绑)。同一时间只跑一次(后来的排队,轮到时已授权就直接算成功)。
     * 生效后照解锁的路子请系统重绑,不用离开页面。日志里只记结果类别和截短的命令输出,没有通知内容。
     */
    suspend fun grantViaHelper(): GrantResult = execScope.async { granting.withLock { grantLocked() } }.await()

    private suspend fun grantLocked(): GrantResult {
        val command = ListenerGrant.command(component.packageName, component.className)
        val r = ListenerGrant.run(
            command,
            // 命令通道里的调用会阻塞、不响应取消:放到单独的协程里跑,超时只放弃等待,不必等它返回
            exec = { cmd -> execScope.async { shellRunner().exec(cmd) }.await() },
            authorized = ::authorized,
            ensureEnabled = ::enableComponent,
            onGranted = { scope.launch { if (watching != null && !connected.value) run(listOf(ProactiveLockPolicy.Action.Rebind)) } },
        )
        when (r) {
            is GrantResult.Refused -> Log.i(TAG, "helper grant refused: ${r.output}")
            else -> Log.i(TAG, "helper grant: ${r.javaClass.simpleName}")
        }
        return r
    }

    private val execScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- 判定 ----

    /** 判定实际会用的服务商和模型;没配模型为 null。只在主动能力开着时由本页取。 */
    fun triageTarget(): TriageTarget? = triageHooks.target()

    /**
     * 用户在确认框里同意了把通知发给 [target](总开关开着、但还没同意过,或服务商、模型变了之后):对着它记,
     * 判定和归纳接着跑。
     */
    fun confirmModel(target: TriageTarget) {
        setConsent(TriageConsent.of(target))
        scope.launch { triage.poke(); learn.poke() }
    }

    private fun setConsent(consent: TriageConsent?) {
        prefs.consent = consent
        _consent.value = consent
    }

    /**
     * 这个 App 的通知读不读、读的话是哪一档:[tier] 为 null 是不读(从名单里去掉,档位也复位);否则加进名单并设成这一档。
     * 不读、或调成「低」时,它已收的事件、存着的原文、各级归纳里依据过它的句子一并去掉——总开关关着时也照做。
     *
     * 返回名单存下来没有。没存下来(存储满了之类)时:要加进名单的不加,档位、存着的东西都不动;
     * 「不读」照样生效、该删的照删(这是用户要的,不看文件写没写成),并记下来重启后补做。页面据此提示再试一次。
     */
    fun setAppReading(packageName: String, tier: SourceTier?): Boolean {
        val saved = AllowEdits.set(allowed, prefs, packageName, tier != null)
        if (!saved && tier != null) return false
        storeTier(packageName, tier ?: SourceTier.Mid)
        scope.launch { if (tier == null || tier == SourceTier.Low) learn.forgetApps(setOf(packageName)) else learn.poke() }
        return saved
    }

    /** 「敏感类推断」开关;关掉时 [deleteExisting] 为 true 就删掉已存的敏感话题(否则保留,但不再增加)。 */
    fun setLearnSensitive(on: Boolean, deleteExisting: Boolean = false) {
        prefs.learnSensitive = on
        _learnSensitive.value = on
        scope.launch { if (!on && deleteExisting) learn.deleteSensitive() }
    }

    /**
     * 这次任务要带的「从通知里得来的」那一段(当日归纳、对用户的认识里够格的);主动能力或归纳关着、最保守的一档、
     * 什么都不够格时为 null。读的是攒下的东西,不调模型;在串行协程上读。
     */
    suspend fun observedForTasks(): String? {
        if (!FeatureFlags.get(app).proactive()) return null
        // 任务开工不能被主动能力那条串行协程拖住:它忙着的话(读写文件)等一小会儿,等不到这次就不带
        return kotlinx.coroutines.withTimeoutOrNull(OBSERVED_WAIT_MS) { scope.async { learn.observed(_learnLevel.value, ::learnTier) }.await() }
    }

    /**
     * 任务读这两天通知用的来源(notices_read 工具)。主动能力、归纳、「当日消息归纳」有一样没开,或激进程度是最保守的一档:null,
     * 工具就不出现。读的时候再按那一刻的开关和档位过滤一遍(任务跑着的时候用户可能改了设置)。
     */
    fun noticeSource(): NoticeSource? {
        if (!tasksMayReadNotices(_enabled.value && FeatureFlags.get(app).proactive(), _learnDigest.value, _learnLevel.value)) return null
        return object : NoticeSource {
            override val zone: java.time.ZoneId get() = java.time.ZoneId.systemDefault()
            override fun appLabel(packageName: String) = com.wangzan101.mika.task.ConfirmationNotifier.appLabel(app, packageName)
            // 等太久、存着的那份读不出、任务跑着的时候被关掉:null,工具报读不出来(不是「什么都没收到」)
            override suspend fun read(fromMs: Long, toMs: Long): NoticeView? =
                kotlinx.coroutines.withTimeoutOrNull(NOTICES_WAIT_MS) {
                    scope.async { learn.notices(fromMs, toMs, _learnLevel.value, ::learnTier) }.await()
                }
            override suspend fun digests(level: com.wangzan101.mika.core.proactive.DigestLevel): com.wangzan101.mika.core.proactive.DigestsView? =
                kotlinx.coroutines.withTimeoutOrNull(NOTICES_WAIT_MS) {
                    scope.async { learn.digests(level, _learnLevel.value, ::learnTier) }.await()
                }
        }
    }

    /** 激进程度:只是换一组门槛,已经攒下的东西不动。 */
    fun setLearnLevel(level: LearnLevel) {
        prefs.learnLevel = level.wire
        _learnLevel.value = level
    }

    /** 这个 App 的来源档位:「不从它归纳」里的是低,调高过的是高,其余是中。 */
    fun learnTier(packageName: String): SourceTier = when (packageName) {
        in _learnOptOut.value -> SourceTier.Low
        in _learnHigh.value -> SourceTier.High
        else -> SourceTier.Mid
    }

    /** 改一个 App 的来源档位。调成低是「不从它归纳」:它已收的事件、原文、句子一并去掉(在串行协程上做)。 */
    fun setLearnTier(packageName: String, tier: SourceTier) {
        storeTier(packageName, tier)
        scope.launch { if (tier == SourceTier.Low) learn.forgetApps(setOf(packageName)) else learn.poke() }
    }

    /** 记下档位(状态流先改,串行协程上的事后做:它们读的是改好的值)。 */
    private fun storeTier(packageName: String, tier: SourceTier) {
        val high = if (tier == SourceTier.High) _learnHigh.value + packageName else _learnHigh.value - packageName
        prefs.learnHigh = high
        _learnHigh.value = high
        val low = if (tier == SourceTier.Low) _learnOptOut.value + packageName else _learnOptOut.value - packageName
        prefs.learnOptOut = low
        _learnOptOut.value = low
    }

    /** 「当日消息归纳」开关;关掉时存着的通知原文一并删掉(已经归纳出的句子和各级归纳留着);在串行协程上做。 */
    fun setLearnDigest(on: Boolean) {
        prefs.learnDigest = on
        _learnDigest.value = on
        scope.launch { learn.poke() }
    }

    /** 归纳页上的操作(读话题池、删话题之类)丢到串行协程上做。 */
    internal fun onLearn(block: LearnRunner.() -> Unit) {
        scope.launch { learn.block() }
    }

    /** 页面用:一句分段归纳是照着哪些通知写的。等太久(串行协程正忙着读写文件)算读不出来。 */
    internal suspend fun learnRaw(note: com.wangzan101.mika.core.proactive.DigestNote): RawNotices =
        kotlinx.coroutines.withTimeoutOrNull(NOTICES_WAIT_MS) { scope.async { learn.rawFor(note) }.await() } ?: RawNotices.Unavailable

    fun retryTriage() {
        scope.launch { triage.retryFailed() }
    }

    fun setTriageBudget(limit: Long) {
        flags.setProactiveDailyTokenBudget(limit)
        _triageLimit.value = flags.proactiveDailyTokenBudget()
        scope.launch { triage.budgetChanged(); learn.budgetChanged() }
    }

    /** 主动能力今天已用的 Token 和请求数。 */
    fun triageUsage(): Pair<Long, Long> = triageHooks.tokensToday() to triageHooks.callsToday()

    fun usageVersion(): StateFlow<Long> = triageHooks.usageVersion()

    /** 理由用界面的语言写(和应用分类同一个说法)。 */
    private fun triageLanguage(): String {
        val tag = runCatching { AppLanguage.current(app).tag }.getOrNull()
        val locale = tag?.let(java.util.Locale::forLanguageTag) ?: app.resources.configuration.locales[0]
        return classifierLanguage(locale)
    }

    // ---- 服务转来的回调 ----

    internal fun onConnected(s: NoticeListenerService) = scope.launch {
        service = s
        connected.value = true
        if (watching == null) {
            // 功能关着,或在完全退出期间被系统拉起:不读,请系统解绑
            unbind()
            return@launch
        }
        run(policy.connected(readLocked(), System.currentTimeMillis()))
    }

    internal fun onDisconnected(s: NoticeListenerService) = scope.launch {
        if (service === s) {
            service = null
            connected.value = false
        }
    }

    internal fun onPosted(sbn: StatusBarNotification) = scope.launch {
        if (watching == null) return@launch
        if (readLocked()) {
            run(policy.ignoredWhileLocked(sbn.postTime, connected.value))
            return@launch
        }
        ingest(sbn)
    }

    internal fun onSeen(key: String, reason: Int) = scope.launch {
        hintBoard.dismissed(key)
        inbox.remove(key)
        learn.removed(key, reason)
        // 用户处理掉了这一条:同一个键的下一条从头数,不接着算成「同一条在重发」(见 [LiveUpdates.ended])
        live.ended(key)
    }

    // ---- 内部 ----

    private fun begin() {
        if (watching != null) return
        val r = object : BroadcastReceiver() {
            // 广播在主线程上来:丢到串行协程上读,和轮询、[readLocked] 不并发
            override fun onReceive(context: Context, intent: Intent) {
                scope.launch { lock.refresh() }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(r, filter)
        }
        receiver = r
        lock.start()
        var handled = lock.locked.value
        run(policy.start(handled, connected.value))
        watching = scope.launch {
            // 只处理和上次不同的值:开始看守之后、收集起来之前的变化也不会漏掉
            launch { lock.locked.collect { locked ->
                if (locked == handled) return@collect
                handled = locked
                run(if (locked) policy.locked(System.currentTimeMillis(), connected.value) else policy.unlocked(connected.value))
            } }
            // 取消勾选的 App,它已收的记录一并去掉
            launch {
                allowed.state.collect { set ->
                    inbox.removePackages { it in set }
                    // 取消勾选的 App 正在发的那一批判定也取消(和「应用访问」里禁用一样)
                    triage.dropApps { it in set }
                    learn.poke()
                }
            }
            // 在「应用访问」里禁用了的 App,它已收的记录、待判定、待归纳的一并去掉,在发的请求取消。
            // 那份设置读不出来时什么都不删(不知道禁用了哪些),只让判定和归纳重新看一遍:它们会停下在发的请求、停着等
            launch { accessChanges.collect { gate -> BlockedApps.accessChanged(gate, inbox, triage, learn) } }
            // 判定只在看守期间跑;锁屏状态和看守用的是同一份
            launch { triage.watch(lock.locked) { readLocked() } }
            launch { learn.watch(lock.locked) { readLocked() } }
        }
    }

    private fun end() {
        watching?.cancel()
        watching = null
        lock.stop()
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
    }

    private fun readLocked(): Boolean {
        lock.refresh()
        return lock.locked.value
    }

    private fun run(actions: List<ProactiveLockPolicy.Action>) {
        for (a in actions) when (a) {
            is ProactiveLockPolicy.Action.PersistLockedAt -> prefs.lastLockedAtMs = a.atMs
            ProactiveLockPolicy.Action.Unbind -> unbind()
            ProactiveLockPolicy.Action.Rebind -> runCatching { NotificationListenerService.requestRebind(component) }
                .onFailure { Log.i(TAG, "rebind refused: ${it.javaClass.simpleName}") }
            ProactiveLockPolicy.Action.CatchUp -> catchUp()
        }
    }

    /**
     * 解绑请求发出后系统就不再送回调了,断开的回调可能晚到:请求发出去就当已断开,
     * 免得这段时间里解锁被当成「还连着」而不去重绑。
     */
    private fun unbind() {
        val s = service ?: return
        if (s.unbindQuietly()) {
            service = null
            connected.value = false
        }
    }

    private fun catchUp() {
        // 监听没连着、通知栏读不出来:这次没补成,还欠着(下次解锁、连上、来新通知时再试)
        val s = service ?: return
        val active = s.activeOrNull() ?: return Unit.also { Log.i(TAG, "catch-up read failed, still owed") }
        // 通知栏读出来了:先和收件箱对一遍——锁屏那一阵监听是解绑的,被处理掉、撤回、超时的通知没有回调,
        // 不对的话它们一直留在收件箱里、提醒的数字上,点开还会写成一条早就没了的提醒。
        // 拿通知栏里的全部键来对(不只是我们读的那些 App 的);这样去掉的不算用户「点开」或「划掉」,不记给归纳
        try {
            val dropped = inbox.retainKeys(active.mapNotNullTo(HashSet()) { it.key })
            if (dropped > 0) Log.i(TAG, "catch-up dropped $dropped records no longer in the shade")
            val records = active.mapNotNull { sbn -> gatedRecord(sbn) }
            // 锁屏时间往前留几秒:解绑前已排队、被系统丢掉的回调也补得上,重复的由去重挡掉
            CatchUp.select(records, prefs.enabledAtMs, policy.lockedAtMs - CATCH_UP_SLACK_MS).forEach(::admit)
        } finally {
            // 欠的是「把通知栏读一遍」:读出来了就算还上。哪一条收的时候出了错不让它一直欠着(每来一条通知都重试、每次都卡在同一条上)
            policy.caughtUp()
        }
    }

    /** [sbn] 是没锁屏时到的(调用方已经看过锁屏状态)。 */
    private fun ingest(sbn: StatusBarNotification) {
        try {
            val r = gatedRecord(sbn) ?: return
            // 实时到的通知也守住「打开之前的不收」(系统偶尔把旧通知重发一遍)
            if (r.postedAtMs > prefs.enabledAtMs) admit(r)
        } finally {
            // 上一次补收没读成还欠着:趁现在有通知来、监听连着,再试一次(先收刚到的这一条;重复的由去重挡掉)
            if (policy.catchUpOwed) run(policy.retry(locked = false, connected = connected.value))
        }
    }

    /** 包名这一关在读通知内容之前:不在白名单里(或是 Mika 自己、或被应用访问禁用)的连内容都不读。 */
    private fun gatedRecord(sbn: StatusBarNotification): NoticeRecord? {
        val pkg = sbn.packageName ?: return null
        if (!BlockedApps.readable(pkg, allowed.current(), access())) return null
        return NoticeMapper.read(sbn)?.copy(importance = service?.importanceOf(sbn.key))?.toRecord()
    }

    private fun admit(raw: NoticeRecord) {
        inbox.noteReceived()
        // 清洗之后按 App 的写法把会话、说话的人、那句话拆开(QQ、微信;见 [NoticeShapes]),后面都用拆好的
        val r = NoticeShapes.apply(NoticeFilter.sanitize(raw))
        if (!BlockedApps.admits(r, allowed.current(), access())) return
        // 被 App 一遍遍重发的状态显示(导航播报之类)不收;刚认出来时,它前面收进来的那几条也去掉(见 [LiveUpdates])
        when (live.observe(r)) {
            LiveUpdates.Verdict.Normal -> {}
            LiveUpdates.Verdict.Live -> return
            LiveUpdates.Verdict.BecameLive -> {
                // 只记是哪个 App、出不出声,不记内容
                Log.i(TAG, "status display, not taken: ${r.packageName} silent=${r.silent}")
                hintBoard.withdraw(r.key)
                inbox.remove(r.key)
                learn.dropLive(r.key, r.packageName, r.postedAtMs - LiveUpdates.WINDOW_MS)
                return
            }
        }
        val admitted = dedup.admit(r) ?: return
        prefs.dedup = dedup.encode()
        inbox.add(r)
        // 归纳拿这回才见到的那几条消息(会话样式的通知),不只是通知的正文
        learn.capture(r, admitted.newMessages)
    }

    /**
     * 清单里监听组件默认是停用的(没用过这个功能的人,系统「通知使用权」列表里没有 Mika);第一次打开功能时启用,
     * 之后不再停用(停用会让系统收回使用权,见 [setEnabled])。
     */
    private fun enableComponent() {
        val pm = app.packageManager
        runCatching {
            if (pm.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            }
        }.onFailure { Log.w(TAG, "cannot enable the notification listener", it) }
    }

    // ---- 值得提醒(见 [NoticeHints]):只在内存里,胶囊上只给条数;点开时才写成对话里 Mika 的一条消息(见 [NoticeTalks]) ----

    private val hintBoard = NoticeHints()
    /** 判为值得提醒、还在收件箱里的几条,和还没看过的条数。 */
    val hints: StateFlow<NoticeHints.State> = hintBoard.state

    private val talks = NoticeTalks(com.wangzan101.mika.chat.ConversationStore.shared(app), prefs)

    /**
     * 用户点开了提醒(悬浮窗上的数字,或主动能力页那一行):把还没看的几条写成 Mika 的一条消息,放进今天的提醒对话,
     * 设为当前对话,这几条记为看过(见 [NoticeTalks.open])。返回那个对话的 id;没有可开的、写不进去为 null。不调模型。
     */
    suspend fun openHintTalk(): String? {
        val now = System.currentTimeMillis()
        return try {
            talks.open(hintBoard, now) { fresh ->
                NoticeTalk.compose(fresh.map { talkItem(it, now) }, talkCopy())?.let {
                    NoticeTalks.Draft(
                        it.text, tr(R.string.notice_talk_title, DateUtils.formatDateTime(app, now, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR)),
                        shown = it.shown,
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not open the notice talk: ${e.javaClass.simpleName}")
            null
        }
    }

    /** App 名按包名从系统读(不用通知里的字);时间是当天的只写几点几分。 */
    private fun talkItem(h: com.wangzan101.mika.core.proactive.NoticeHint, now: Long) = NoticeTalk.Item(
        app = com.wangzan101.mika.task.ConfirmationNotifier.appLabel(app, h.packageName),
        time = DateUtils.formatSameDayTime(h.postedAtMs, now, java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).toString(),
        title = h.title, text = h.text, reason = h.reason,
    )

    private fun talkCopy() = NoticeTalk.Copy(
        intro = { tr(R.string.notice_talk_intro, it) },
        reason = { tr(R.string.notice_hint_reason, it) },
        more = { tr(R.string.notice_talk_more, it) },
        outro = tr(R.string.notice_talk_outro),
    )

    init {
        // 收件箱、判定结果、总开关变了才对一遍;不计时
        scope.launch {
            combine(inbox.state, triage.state, _enabled) { box, t, on -> Triple(box.records, t.marks, on) }
                .collect { (records, marks, on) -> hintBoard.sync(records, marks, on) }
        }
    }

    companion object {
        /** 任务开工时等「从通知里得来的」那一段最多等多久。 */
        private const val OBSERVED_WAIT_MS = 2_000L
        /** 任务读通知时最多等多久(读文件、解密在主动能力那条协程上)。 */
        private const val NOTICES_WAIT_MS = 5_000L

        /** 任务能不能有读通知的工具:主动能力、「当日消息归纳」都开着,激进程度不是最保守的一档。 */
        internal fun tasksMayReadNotices(proactive: Boolean, digest: Boolean, level: LearnLevel): Boolean =
            proactive && digest && level.intoTasks.isNotEmpty()
        private const val TAG = "MikaProactive"
        private const val CATCH_UP_SLACK_MS = 5_000L

        fun create(
            context: Context, access: () -> AppGate, accessChanges: Flow<AppGate>, triage: TriageHooks,
            shellRunner: () -> ShellCommandRunner,
        ) = ProactiveRuntime(
            context, FeatureFlags.get(context),
            NoticeAllowStore(File(context.applicationContext.filesDir, "proactive-allow.json")), access, accessChanges, shellRunner, triage,
        )
    }
}
