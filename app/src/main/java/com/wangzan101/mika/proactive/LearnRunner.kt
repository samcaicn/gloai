package com.wangzan101.mika.proactive

import android.service.notification.NotificationListenerService
import com.wangzan101.mika.core.proactive.ContactPool
import com.wangzan101.mika.core.proactive.ContactPoolData
import com.wangzan101.mika.core.proactive.DayDigest
import com.wangzan101.mika.core.proactive.DigestData
import com.wangzan101.mika.core.proactive.DigestNote
import com.wangzan101.mika.core.proactive.DigestPool
import com.wangzan101.mika.core.proactive.LearnBuffer
import com.wangzan101.mika.core.proactive.LearnOutcome
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.NoticeJournal
import com.wangzan101.mika.core.proactive.NoticeJournalData
import com.wangzan101.mika.core.proactive.SpanDigest
import com.wangzan101.mika.core.proactive.SpanKind
import com.wangzan101.mika.core.proactive.NoticeMessage
import com.wangzan101.mika.core.proactive.NoticeRecord
import com.wangzan101.mika.core.proactive.ObservedTopic
import com.wangzan101.mika.core.proactive.TopicPool
import com.wangzan101.mika.core.proactive.TriageConsent
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「从通知里归纳」的调度,和 [TriageRunner] 一样在 [ProactiveRuntime] 那条串行协程上跑,只在看守期间([watch])活着。
 *
 * 省电:没有任何定时器、没有轮询。只在事情发生时看一遍([evaluate]):缓冲变了(收下一条)、锁屏状态变了、
 * [TriageHooks.busy] 变了、开关/同意/预算/不归纳的 App 变了([poke])。「最旧的一条过了 30 分钟」也只在这些时候才看。
 * 锁着时正在发的请求取消,那一批照旧待归纳。
 * 一次只发一批,一批只有一个 App、最多 12 条。没归纳成(记一次失败)之后停一段再发下一批,连续失败越多停得越久
 * ([LearnTuning.FAILURE_HOLD_MS]):不然模型一出问题,到期的各个 App 会一批接一批地发出去、失败。到点后也是等下一件事发生才看。
 * 那一批本身:模型回了错、超时、回复读不懂的,丢掉不重试(归纳本来就是攒统计);根本没连上模型的(模型只在家里的网络上、
 * 人出门了之类)留着,等连得上了再发。预算用完:安静停下,事件留着,当天不再发(改了预算才重新算)。
 * 日志([log])只记类别和条数,不记通知文字和话题名。
 *
 * 联系人与关系搭同一个请求(联系人读得出来时,请求里说可以给),不另发请求。用上结果时按这批事件(标题还在)
 * 算身份,再连上话题;这之后标题、正文照旧清掉。
 *
 * 开关和存着的东西:[on] 是总开关,[digest] 是「当日消息归纳」。关掉哪一个,都只是不再收、不再发,并把通知原文删掉;
 * 已经归纳出的(话题、联系人、分段归纳的句子、各级归纳)留着,由用户自己删、或到期删。所以清除、删除、到期这几条路
 * 在开关关着时也照做,不能假定「关着就什么都没存」。
 */
internal class LearnRunner(
    internal val hooks: TriageHooks,
    internal val on: () -> Boolean,
    internal val consent: () -> TriageConsent?,
    internal val budgetLimit: () -> Long,
    internal val language: () -> String,
    /**
     * 这个 App 现在能不能归纳:勾选了、不是「不从它归纳」。只用来决定收不收、发不发;
     * 不拿它去删存着的东西(名单读不出来时每个 App 都「不够格」,那不是用户取消的)——删由 [forgetApps] 点名来做。
     */
    internal val eligible: (String) -> Boolean,
    /**
     * 「应用访问」读不出来:不知道哪些 App 不让碰,整个停着——不收、不发、不往任务里带;攒下的一样都不删,
     * 读得出来了接着做。(不并进 [on] 和 [eligible]:那两个为假时会删原文、清待归纳的事件。)
     */
    internal val suspended: () -> Boolean = { false },
    /** 和判定共用的请求位(一次只发一个后台请求);不给就是自己一个。 */
    internal val slot: RequestSlot = RequestSlot(),
    /** 「敏感类推断」开着没有(默认关)。 */
    internal val sensitive: () -> Boolean,
    internal val store: TopicPoolStore,
    internal val contactStore: ContactPoolStore = NoContacts,
    internal val purges: LearnPurges = MemoryPurges(),
    /** 「当日消息归纳」开着没有(默认关):开着才把通知原文记进日志、存盘。 */
    internal val digest: () -> Boolean = { false },
    internal val journalStore: NoticeJournalStore = NoJournal,
    internal val digestStore: DigestStore = NoDigest,
    /** App 的名字(按包名从系统读):当日归纳的输入里用,好让模型说「在微信里」。 */
    internal val appLabel: (String) -> String = { it },
    internal val clock: () -> Long = System::currentTimeMillis,
    internal val zone: () -> ZoneId = ZoneId::systemDefault,
    internal val log: (String) -> Unit = {},
) {
    internal val buffer = LearnBuffer()
    internal var pool: TopicPool? = null
    internal var contactPool: ContactPool? = null
    internal val feed = HashMap<String, LearnFeed>()
    internal var lastDistilledAtMs: Long? = null
    internal var budgetOutDay: Long? = null
    /** 连续几批没归纳成,和因此停到什么时候(见 [held])。 */
    internal var failures = 0
    internal var holdUntilMs = 0L
    /** 已经在内存里做掉、还没存上的补删(见 [purge]):存上的那一刻从记着的里划掉。 */
    internal var topicPurgesApplied: Set<String> = emptySet()
    /** 文件里有的话题的 id(读出来时、每次存上时记下):内存里多出来的(刚归纳出来、还没存上的)不在里面。 */
    internal var savedTopics: Set<String> = emptySet()
    internal var contactPurgesApplied: Set<String> = emptySet()
    internal var digestPurgesApplied: Set<String> = emptySet()
    internal var journalPurgesApplied: Set<String> = emptySet()
    /**
     * 归纳那份上一次没存上:内存里的比文件里的新。再发分段、当日、周、月归纳的请求之前先把它存上,
     * 还存不上就不发——结果留不住,发了只是白花一次模型调用。
     */
    internal var digestUnsaved = false
    /** 通知日志:第一次用到时读出来([loadedJournal]);[journalUnsaved] 是还没存盘的改动数。 */
    internal var journal: NoticeJournal? = null
    internal var journalUnsaved = 0
    /** 分段归纳攒下的句子:第一次用到时读出来([loadedDigest])。 */
    internal var digestPool: DigestPool? = null
    /** 正在发的那一批分段归纳是哪个 App 的(没有在发为 null);和归纳话题的那一批共用 [inFlight],一次只发一个。 */
    internal var notesApp: String? = null
    /** 正在做的那一份当日归纳是哪一天的(没有在做为 null);同样占着 [inFlight]。 */
    internal var dayInFlight: Long? = null
    /** 用户点了「归纳今天到现在的」,还没做。 */
    internal var todayWanted = false
    /** 各天的当日归纳在这个进程里已经试过几次(回复总读不懂时不一直试)。 */
    internal val dayTries = HashMap<Long, Int>()
    /**
     * 「到现在为止」的那一份(自动做的、用户点的)在这个进程里失败过几次,另记:不能算进 [dayTries]——
     * 不然今天自动做的几次都读不懂,明天该做今天完整的那一份时就不试了,后面的日子也跟着等在它后面。
     */
    internal val partialTries = HashMap<Long, Int>()
    internal val daysFlow = MutableStateFlow<List<DayDigest>>(emptyList())
    /** 各天的当日归纳,新的在前。 */
    val digestDays: StateFlow<List<DayDigest>> = daysFlow.asStateFlow()
    /** 正在做的那一份周(月)归纳是哪一周(哪个月)的(没有在做为 null);同样占着 [inFlight]。 */
    internal var spanInFlight: Pair<SpanKind, Long>? = null
    /** 用户点了「归纳本周(本月)到现在的」,还没做。 */
    internal var weekWanted = false
    internal var monthWanted = false
    /** 各周、各月的归纳在这个进程里已经试过几次。 */
    internal val spanTries = HashMap<Pair<SpanKind, Long>, Int>()
    internal val weeksFlow = MutableStateFlow<List<SpanDigest>>(emptyList())
    internal val monthsFlow = MutableStateFlow<List<SpanDigest>>(emptyList())
    /** 各周的周归纳、各月的月归纳,新的在前。 */
    val digestWeeks: StateFlow<List<SpanDigest>> = weeksFlow.asStateFlow()
    val digestMonths: StateFlow<List<SpanDigest>> = monthsFlow.asStateFlow()
    internal val removedFlow = MutableStateFlow<Set<Pair<SpanKind, Long>>>(emptySet())
    /** 被用户删掉了、不再自动做的周(月)归纳。 */
    val digestRemoved: StateFlow<Set<Pair<SpanKind, Long>>> = removedFlow.asStateFlow()
    internal val removedDaysFlow = MutableStateFlow<Set<Long>>(emptySet())
    /** 被用户删掉了当日归纳的那几天(纪元日):不会再自动做,页面上要这么说。 */
    val digestRemovedDays: StateFlow<Set<Long>> = removedDaysFlow.asStateFlow()
    internal val topicsBlockedFlow = MutableStateFlow(0)
    /** 用户删掉后不再记录的话题有几个。 */
    val topicsBlocked: StateFlow<Int> = topicsBlockedFlow.asStateFlow()
    internal val notesFlow = MutableStateFlow<List<DigestNote>>(emptyList())
    /** 攒下的分段归纳(「当日消息归纳」关着、还没读出来时是空的)。 */
    val digestNotes: StateFlow<List<DigestNote>> = notesFlow.asStateFlow()
    internal var status = LearnStatus.Off

    internal val mutable = MutableStateFlow(LearnState())
    val state: StateFlow<LearnState> = mutable.asStateFlow()
    internal val topicsFlow = MutableStateFlow<List<ObservedTopic>>(emptyList())
    /** 话题池现在的样子;打开页面([open])之前、或读不出来时是空的。 */
    val topics: StateFlow<List<ObservedTopic>> = topicsFlow.asStateFlow()
    internal val contactsFlow = MutableStateFlow(ContactsView())
    /** 联系人现在的样子;打开页面之前、或读不出来时是空的。 */
    val contactsView: StateFlow<ContactsView> = contactsFlow.asStateFlow()

    internal var host: CoroutineScope? = null
    internal var readLocked: () -> Boolean = { true }
    internal var inFlight: Job? = null

    internal val requestActive: Boolean get() = inFlight?.isActive == true

    suspend fun watch(locked: StateFlow<Boolean>, readLocked: () -> Boolean) {
        var mine: CoroutineScope? = null
        try {
            coroutineScope {
                mine = this
                inFlight?.cancel()
                inFlight = null
                slot.release(this@LearnRunner)
                slot.listen(this@LearnRunner) { evaluate() }
                // 上一次看守留下的「在发的是谁的」也清掉:留着的话,这一次发的别的请求会被当成它的、被错取消
                notesApp = null
                dayInFlight = null
                spanInFlight = null
                buffer.abort()
                host = this
                this@LearnRunner.readLocked = readLocked
                launch { buffer.state.collect { evaluate() } }
                launch { locked.collect { evaluate() } }
                // 通知日志不定时:每次锁屏、解锁时把到期的删掉、把没存的存上(进程多半是在锁屏后被系统收走的)
                launch { locked.collect { tendJournal() } }
                launch { hooks.busy.collect { evaluate() } }
                awaitCancellation()
            }
        } finally {
            if (host === mine) {
                host = null
                inFlight = null
                notesApp = null
                dayInFlight = null
                spanInFlight = null
                // 不再看守(完全退出):日志里没存的存上
                if (on() && digest()) saveJournal()
                buffer.abort()
                publish(if (on()) LearnStatus.Paused else LearnStatus.Off)
                slot.unlisten(this)
                slot.release(this)
                slot.wakeOthers(this)
            }
        }
    }

    /**
     * 收件箱收下了一条(已清洗、筛过、去过重)。总开关关着、这个 App 不归纳时不收;用户还没同意过把通知发给模型时也不收——
     * 没同意就不为归纳攒任何东西,原文也不落盘(总开关开着但没配模型、等用户确认的时候)。
     * 同意过、只是服务商或模型变了在等重新确认的,照收:确认之后接着归纳。
     */
    fun capture(record: NoticeRecord, newMessages: List<NoticeMessage> = emptyList()) {
        if (!on() || suspended() || consent() == null || !eligible(record.packageName)) return
        // 通知日志只收归纳收的那些,而且「当日消息归纳」得开着
        if (digest()) record(record)
        if (buffer.capture(record, newMessages)) bump(record.packageName) { it.copy(captured = it.captured + 1) }
    }

    /**
     * 这个通知键其实是一条被反复重发的状态显示(导航播报之类,见 [LiveUpdates]):它从 [sinceMs] 起(这一阵重发)收进来、
     * 还没归纳的那几条去掉,通知原文里的也去掉。同一个键上更早的不动——很多 App 所有推送共用一个通知键。
     */
    fun dropLive(key: String, packageName: String, sinceMs: Long) {
        // 在途的请求里可能就有这几条:正在给这个 App 写分段归纳的那一批,或正在归纳话题的那一批里这个键这一阵的事件。
        // 取消(和清掉一个 App 时一样):不然回复回来,照着它们写的句子、归纳出的话题还是存下了。
        // 取消后归纳话题的那一批回到待归纳,下面一并去掉(不然锁屏之类再取消一次,它们又会被发出去);剩下的照常再发
        val stopped = notesApp == packageName || buffer.sending().any { it.key == key && it.postedAtMs >= sinceMs }
        if (stopped) stopRequest()
        buffer.forgetKey(key, sinceMs)
        if (on() && digest()) {
            // 通知原文里的:和按 App 删一样先记下来(见 [LearnPurges.journalPurges])。这会儿读不出来的、去掉了没存上的,
            // 读得出来时补做——不然它们还算没归纳过,下一次分段归纳又发给模型
            val keyId = com.wangzan101.mika.core.proactive.NoticeDedup.idOf(key)
            val mark = MARK_KEY + keyId + KEY_SINCE + sinceMs
            val j = loadedJournal()
            if (j == null) {
                purges.journalPurges = purges.journalPurges + mark
            } else if (j.removeKey(keyId, sinceMs, sinceMs + LIVE_WINDOW_MS)) {
                purges.journalPurges = purges.journalPurges + mark
                journalPurgesApplied = journalPurgesApplied + mark
                saveJournal()
            }
        }
        if (stopped && host != null) evaluate()
    }

    /** 记着的「这个通知键这一阵的去掉」:键的哈希、从什么时候起;不是这种记号、写得不对的为 null。 */
    internal fun keyIn(mark: String): Pair<String, Long>? {
        if (!mark.startsWith(MARK_KEY)) return null
        val parts = mark.removePrefix(MARK_KEY).split(KEY_SINCE)
        val since = parts.getOrNull(1)?.toLongOrNull()
        return if (parts.size == 2 && parts[0].isNotEmpty() && since != null) parts[0] to since else null
    }

    /** 记进通知日志;攒够 [JOURNAL_FLUSH_EVERY] 段没存的就存一次(进程在两次存盘之间没了,最多丢这么多段,或上次锁屏之后的)。 */
    internal fun record(record: NoticeRecord) {
        val j = loadedJournal() ?: return
        val added = j.add(record)
        if (added == 0) return
        journalUnsaved += added
        if (journalUnsaved >= JOURNAL_FLUSH_EVERY) saveJournal() else publish(status)
    }

    /** 系统说这条通知被移除了:点开、划掉记下来;「全部清除」不说明什么。已经归纳过的,补到它归到的话题上。 */
    fun removed(key: String, reason: Int) {
        val outcome = outcomeOf(reason)
        val late = buffer.outcome(key, outcome)
        if (late.isEmpty()) return
        val p = loadedPool() ?: return
        if (p.addOutcome(late, outcome, allowSensitive = sensitive())) save(p)
    }

    /**
     * 这几个 App 不再归纳了(用户取消勾选、调成「低」,或在「应用访问」里禁用):它们存着的通知原文、分段归纳的句子,
     * 和各级归纳里依据过它们的句子去掉;话题和联系人不动(要删用「清空来自它的」)。总开关关着时也照做。
     * 点名删,不按「现在不够格的都删」:读哪些 App 的名单读不出来时,没有一个 App 够格,那样会把攒下的全删光。
     * 归纳那份这时读不出来的,记下来,读得出来时补做。要在串行协程上调。
     */
    fun forgetApps(packages: Set<String>) {
        if (packages.isNotEmpty()) {
            if (on() && digest()) journalRemove(packages)
            // 正在做的当日(周、月)归纳里可能就有刚去掉的那个 App 的句子:取消,之后按剩下的重做
            digestRemove(packages, stopRequests = true)
        }
        poke()
    }

    /** 开关、同意、预算、不归纳的 App 变了:不再收的 App 的事件去掉,在发的请求该停的停,重新看一遍。要在串行协程上调。 */
    fun poke() {
        val sending = buffer.sending().firstOrNull()?.packageName
        if (sending != null && (!on() || !eligible(sending))) stopRequest()
        notesApp?.let { if (!on() || !digest() || !eligible(it)) stopRequest() }
        if ((dayInFlight != null || spanInFlight != null) && (!on() || !digest())) stopRequest()
        buffer.purge(eligible)
        // 通知原文:总开关或「当日消息归纳」关了就整份删掉。已经归纳出的句子和各级归纳不跟着开关删;
        // 某个 App 不再归纳时它存着的东西由 forgetApps 点名去掉,这里不按够不够格去删
        if (!on() || !digest()) dropJournal()
        if (host != null) evaluate() else publish(if (on()) LearnStatus.Paused else LearnStatus.Off)
    }

    fun budgetChanged() {
        budgetOutDay = null
        poke()
    }

    /**
     * 关掉主动能力:请求取消,没处理完的事件、通知原文和计数清掉。已经归纳出的(话题、联系人、句子、各级归纳)不动,
     * 由用户自己删。
     */
    fun clear() {
        stopRequest()
        buffer.clear()
        dropJournal()
        todayWanted = false
        weekWanted = false
        monthWanted = false
        feed.clear()
        lastDistilledAtMs = null
        failures = 0
        holdUntilMs = 0L
        publish(if (on()) LearnStatus.Paused else LearnStatus.Off)
    }

    // ---- 页面上的操作(在串行协程上调) ----

    /**
     * 打开页面时:读出话题池和联系人并整理一遍(丢掉淡忘的、去掉指着没了的话题的连接);有改动就存。
     * 失败后停着的也不再停(用户在看这一页,多半是刚改了设置想看结果):有到期的就再试一次,再失败照样接着拉长。
     */
    fun open() {
        holdUntilMs = 0L
        val p = loadedPool()
        if (p != null) {
            val dropped = p.maintain(clock())
            if (dropped.isNotEmpty()) {
                buffer.forgetTopics(dropped)
                save(p)
            } else {
                topicsFlow.value = p.all()
        topicsBlockedFlow.value = p.blockedCount
            }
        }
        loadedContacts()?.let { c -> if (c.maintain(clock(), p?.all()?.map { it.id }?.toSet())) saveContacts(c) }
        // 到期的归纳不管开关开没开都删(关着时东西还留着,期限照算)
        freshDigest()
        // 通知原文也读出来:页面上「清空来自它的」要列出只有原文、还没归纳出东西的 App(没在看守时它还没读过)
        if (on() && digest()) loadedJournal()
        if (p == null) publish(LearnStatus.StoreUnavailable) else if (host != null) evaluate()
    }

    companion object {
        /** 记着的删除里表示「敏感类」的那一项;不以冒号开头的都是包名(包名里不会有冒号)。 */
        const val PURGE_SENSITIVE = ":sensitive"
        /** 记着的单删:后面跟着编号(话题、联系人、一句分段归纳)或纪元日(某天、某周、某个月的归纳的起头那天)。 */
        /** 记着的「全清」:整份没删掉(存不上、文件也删不掉),读得出来时把里面的全清掉。 */
        internal const val MARK_ALL = ":all"
        internal const val MARK_TOPIC = ":topic:"
        internal const val MARK_CONTACT = ":contact:"
        /** 通知原文上:某个通知键某一阵的那几段。后面是键的哈希、[KEY_SINCE]、从什么时候起(毫秒)。 */
        internal const val MARK_KEY = ":key:"
        internal const val KEY_SINCE = "@"
        /** 那一阵有多长:认状态显示时往回看的窗口。 */
        internal const val LIVE_WINDOW_MS = com.wangzan101.mika.core.proactive.LiveUpdates.WINDOW_MS
        /** 一次合并:后面是并掉的话题 id、[MERGE_INTO]、并进去的话题 id。话题、联系人两份上都可能记着。 */
        internal const val MARK_MERGE = ":merge:"
        internal const val MERGE_INTO = ">"
        internal const val MARK_NOTE = ":note:"
        internal const val MARK_DAY = ":day:"
        internal const val MARK_WEEK = ":week:"
        internal const val MARK_MONTH = ":month:"

        /** 不记联系人时用的(测试、或没给存放处):读出来总是空的,存了也不留。 */
        internal object NoContacts : ContactPoolStore {
            override fun load() = ContactPoolData()
            override fun save(data: ContactPoolData) {}
            override fun dropBroken() {}
            override fun wipe() {}
        }

        /** 带进任务的那一段里最多放几个话题、几个联系人(按强弱取;整段另有 token 上限)。 */
        /** 一次最多连着直接记下几份「没有可归纳的」周(月)归纳(防着写错了转不出来)。 */
        internal const val AUTO_SPANS_MAX = 64
        const val OBSERVED_TOPICS = 40
        const val OBSERVED_PEOPLE = 20

        /** 通知日志攒够这么多段没存的就存一次。 */
        const val JOURNAL_FLUSH_EVERY = 20

        /** 不存当日消息归纳时用的(测试、或没给存放处)。 */
        internal object NoDigest : DigestStore {
            override fun load() = DigestData()
            override fun save(data: DigestData) {}
            override fun wipe() {}
        }

        /** 不存通知日志时用的(测试、或没给存放处)。 */
        internal object NoJournal : NoticeJournalStore {
            override fun load(): NoticeJournalData? = null
            override fun save(data: NoticeJournalData) {}
            override fun wipe() {}
        }

        /** 只在内存里记着的(测试用;正式的是 [ProactivePrefs])。 */
        internal class MemoryPurges : LearnPurges {
            override var topicPurges: Set<String> = emptySet()
            override var contactPurges: Set<String> = emptySet()
            override var digestPurges: Set<String> = emptySet()
            override var journalPurges: Set<String> = emptySet()
        }

        /** 系统的移除原因 → 用户做了什么。点开、单独划掉才算;「全部清除」和别的都不说明什么。 */
        fun outcomeOf(reason: Int): LearnOutcome = when (reason) {
            NotificationListenerService.REASON_CLICK -> LearnOutcome.Opened
            NotificationListenerService.REASON_CANCEL -> LearnOutcome.Dismissed
            else -> LearnOutcome.None
        }
    }
}
