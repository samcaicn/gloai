package com.wangzan101.mika.core.proactive

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/**
 * 一条通知(按键)在某个发出时间的判定结果。键的发出时间变了(通知更新了)就不再算数;判过的键更新后先冷却一段
 * (见 [TriagePlanner.cooldownMs]),到点再待判定,冷却期间界面照旧显示这个旧结果。
 */
sealed interface TriageMark {
    val postedAtMs: Long

    data class Done(override val postedAtMs: Long, val verdict: TriageVerdict) : TriageMark
    /** 那一批的请求失败了;不自动重试,等用户点「再试一次」。 */
    data class Failed(override val postedAtMs: Long) : TriageMark
}

/** 现在该不该发一批。 */
sealed interface TriagePlan {
    data class Send(val packageName: String) : TriagePlan
    /** 有待判定的在等这个 App 凑一凑,或有判过的通知在冷却、已有新内容;到 [atMs](最早的那个)再看。 */
    data class WaitUntil(val atMs: Long) : TriagePlan
    data class Idle(val why: Why) : TriagePlan

    enum class Why { Locked, Nothing, BudgetUsedUp, Busy, InFlight }
}

/** 某个 App 的待判定:最早看到它有待判定的时间、条数。 */
data class PendingApp(val firstAtMs: Long, val count: Int)

/** 界面上的几个数:待判定(不含正在发的)、已判定、失败、正在发的、冷却中(判过后又有新内容、还没到再判的时候)。 */
data class TriageCounts(val pending: Int = 0, val judged: Int = 0, val failed: Int = 0, val sending: Int = 0, val cooling: Int = 0)

/**
 * 判定的状态:哪些通知待判定、按 App 分批、每个 App 每天「值得提醒」的会话数上限、各条的结果、「失败的再试一次」。纯逻辑,
 * 不碰时间以外的任何东西;调用方(App 里的串行协程)每次有变化先 [sync] 收件箱,再问 [plan]。
 * 结果只在内存里,跟着收件箱:收件箱里没了的键,结果也丢掉。通知文字只经 [begin] 交出去,不在这里另存。
 *
 * 一次只有一批在发;锁屏或关掉时调用方取消请求并 [abort],这一批照旧待判定,不算失败。
 *
 * 冷却:聊天类 App 每来一句就把同一条通知(同一个键)重发一遍。一个键判过之后,它的更新不马上待判定,先等到冷却结束
 * (从这次判定算起;时长只看我们自己的判定和次数,见 [cooldownMs];判成值得提醒、请求期间又更新了的按忽略、记下算,
 * 见 [finish]),到点有新内容才待判定、照常凑批,一次请求就带上这期间攒下的文字。冷却不计时:到点由 [plan] 给出
 * [TriagePlan.WaitUntil],调用方只在解锁时计时。
 * 用户处理了这条(收件箱里没了这个键:点开、划掉、不再勾选这个 App、清空)就忘掉它的冷却和次数,以后同一个键从头算。
 *
 * 失败也要停一段:失败的请求一样算进每天的请求次数,坏掉的模型加上一直在说话的聊天,不停就会每 20 秒发一次。
 * 一批失败后,其中每个键按连续失败的次数停 2、5、15、30 分钟([BACKOFF_MS]),停着期间的更新不待判定(界面仍是「判定失败」,
 * 算失败的数);到点有新内容才照常凑批再判,没有新内容就一直是失败、不自动重试。判成功一次就把失败次数清零。
 * 「再试一次」不管停没停完,马上发一次;再失败就从加了一的次数接着停。
 * 全新的键(这个 App 别的键在停着)照常凑批就发:模型坏着时每个新会话仍会发一次请求,这可以接受,有每天的请求上限兜底。
 */
class TriagePlanner(
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val windowMs: Long = WINDOW_MS,
    private val batchMax: Int = NoticeTriage.MAX_BATCH,
    surfaceCap: Int = SURFACE_CAP_PER_APP,
) {
    /** 每个 App 每天最多提醒几个不同的会话;跟着激进程度变([LearnLevel.surfaceCap]),由调度在用上结果之前设好。 */
    var surfaceCap: Int = surfaceCap

    private val marks = HashMap<String, TriageMark>()
    /** 判过或判失败的键的冷却(失败的停一段),和 [marks] 分开记;收件箱里没了的键一并丢掉。 */
    private val cools = HashMap<String, Cool>()
    /** 最近一次 [sync] / [plan] 的时间:冷却到没到点按它算。 */
    private var nowMs = Long.MIN_VALUE
    private val firstAt = HashMap<String, Long>()
    private var records: List<NoticeRecord> = emptyList()
    private var history: Map<String, List<NoticeLine>> = emptyMap()
    /** 正在发的那一批:(键, 发出时间),和它的 App。 */
    private var inFlight: List<Pair<String, Long>>? = null
    private var inFlightPackage: String? = null
    /**
     * 今天各 App 判成过「值得提醒」的通知键(只存键的指纹,只在内存里);按会话算,同一个键再判成值得提醒不再占名额。
     * 和 [marks] 分开记,结果被清掉、键从收件箱里没了又回来,都不重置上限。
     */
    private val surfaced = HashMap<String, HashSet<Long>>()
    private var surfacedDay = Long.MIN_VALUE
    /** 请求时发现主动能力今天的预算用完了的那天;那天里不再发。 */
    private var budgetOutDay: Long? = null

    /** 收件箱现在的样子;去掉已不在收件箱里的结果和冷却,记下冷却中的键第一次有新内容的时间、各 App 最早有待判定的时间。 */
    fun sync(snapshot: NoticeInbox.Snapshot, nowMs: Long) {
        records = snapshot.records
        history = snapshot.history
        val keys = records.mapTo(HashSet()) { it.key }
        marks.keys.retainAll(keys)
        cools.keys.retainAll(keys)
        // 请求期间被用户处理掉的键不再记结果(之后同一个键再来是新的一条,不该带上冷却)
        inFlight = inFlight?.filter { it.first in keys }
        for (r in records) {
            val c = cools[r.key] ?: continue
            if (c.newerAtMs == null && mark(r) == null && !sending(r)) cools[r.key] = c.copy(newerAtMs = nowMs)
        }
        refresh(nowMs)
    }

    /** 这条记录(这个发出时间)的结果;没有、或是更早那次的,为 null。 */
    fun mark(record: NoticeRecord): TriageMark? = marks[record.key]?.takeIf { it.postedAtMs == record.postedAtMs }

    /** 现有结果的副本,给界面用:[TriageMark.postedAtMs] 对上记录的是这次的结果,比记录旧的是有新内容前那次的结果。 */
    fun marks(): Map<String, TriageMark> = HashMap(marks)

    /** 待判定:这个发出时间还没有结果、没在发、也不在冷却。 */
    fun pending(): List<NoticeRecord> = records.filter { unjudged(it) && !cooling(it) }

    /** 冷却中:判过、之后又有新内容,还没到再判的时候。 */
    fun cooling(): List<NoticeRecord> = records.filter { unjudged(it) && cooling(it) }

    fun pendingApps(): Map<String, PendingApp> = pending().groupBy { it.packageName }
        .mapValues { (pkg, list) -> PendingApp(firstAt[pkg] ?: Long.MAX_VALUE, list.size) }

    fun counts(): TriageCounts {
        var judged = 0
        var failed = 0
        for (r in records) when (mark(r)) {
            is TriageMark.Done -> judged++
            is TriageMark.Failed -> failed++
            null -> {}
        }
        // 判失败后停着、又有新内容的,界面上还是失败,算失败的数
        val (heldFailed, cooling) = cooling().partition { marks[it.key] is TriageMark.Failed }
        return TriageCounts(pending().size, judged, failed + heldFailed.size, inFlight?.size ?: 0, cooling.size)
    }

    val inFlightNow: Boolean get() = inFlight != null

    /** 正在发的那一批是哪个 App 的;没在发为 null。 */
    val inFlightApp: String? get() = inFlightPackage

    /** 现在该做什么。[budgetLeft] 是账本那边还够不够;请求时发现用完了的那天([budgetExhausted])也当不够。 */
    fun plan(unlocked: Boolean, busy: Boolean, budgetLeft: Boolean, nowMs: Long): TriagePlan {
        refresh(nowMs)
        if (unlocked && inFlight != null) return TriagePlan.Idle(TriagePlan.Why.InFlight)
        val left = budgetLeft && budgetOutDay != dayOf(nowMs)
        val wake = cooling().mapNotNull { cools[it.key]?.untilMs }.minOrNull()
        return decide(unlocked, busy, left, pendingApps(), nowMs, windowMs, batchMax, wake)
    }

    /** 开始发 [packageName] 的一批:它最早的至多 [batchMax] 条待判定,连同各自先后的文字。 */
    fun begin(packageName: String): List<TriageItem> {
        check(inFlight == null) { "one batch at a time" }
        val batch = pending().filter { it.packageName == packageName }.sortedBy { it.postedAtMs }.take(batchMax)
        if (batch.isEmpty()) return emptyList()
        inFlight = batch.map { it.key to it.postedAtMs }
        inFlightPackage = packageName
        // 这次的新内容交出去了;之后再来的新内容另算起点
        batch.forEach { r -> cools[r.key]?.let { cools[r.key] = it.copy(newerAtMs = null) } }
        return batch.map { TriageItem(it, history[it.key].orEmpty()) }
    }

    /**
     * 这一批判完了:只记还在收件箱里的;这个 App 当天判成值得提醒的会话(不同的键)到了上限后,新的键再判成值得提醒的
     * 按忽略记(内置说明);今天已经算过的键照样提醒。结果记在发出去的那个
     * 发出时间上;请求期间又更新了的,照样从现在开始冷却(否则一直在说话的聊天会每批都重来一遍),新内容等冷却到点再判。
     * 这时判成值得提醒的,结果对不上新的发出时间,提醒出不来([NoticeHints] 只认对得上的),冷却按忽略、记下的逐级时长算,
     * 不按「已经标出来了」的 [SURFACE_COOLDOWN_MS]。
     * 先 [sync] 一次,收件箱在请求期间的变化才算得上。
     */
    fun finish(verdicts: Map<String, TriageVerdict>, nowMs: Long) {
        val batch = takeBatch() ?: return
        val pkg = inFlightPackage.also { inFlightPackage = null } ?: return
        rollDay(nowMs)
        for ((key, posted) in batch) {
            val record = records.firstOrNull { it.key == key } ?: continue
            var v = verdicts[key] ?: TriageVerdict(TriageDecision.Ignore, note = TriageNote.NoVerdict)
            if (v.decision == TriageDecision.Surface) {
                val seen = surfaced.getOrPut(pkg) { HashSet() }
                val h = fingerprint(key)
                if (h !in seen) {
                    if (seen.size >= surfaceCap) v = TriageVerdict(TriageDecision.Ignore, note = TriageNote.SurfaceCapped)
                    else seen += h
                }
            }
            marks[key] = TriageMark.Done(posted, v)
            val n = (cools[key]?.verdicts ?: 0) + 1
            val updated = record.postedAtMs != posted
            val wait = if (updated && v.decision == TriageDecision.Surface) cooldownMs(TriageDecision.Ignore, n)
                else cooldownMs(v.decision, n)
            // 判成功了:失败次数清零
            cools[key] = Cool(nowMs + wait, n, newerAtMs = nowMs.takeIf { updated })
        }
    }

    /**
     * 这一批的请求失败了([nowMs] 是失败的时间):还在收件箱里的记成失败(记在发出去的那个发出时间上),不再自动发;
     * 每个键按连续失败的次数停一段([failureHoldMs]),停着期间的更新不待判定。判定次数不变。
     */
    fun fail(nowMs: Long) {
        val batch = takeBatch() ?: return
        inFlightPackage = null
        for ((key, posted) in batch) {
            val record = records.firstOrNull { it.key == key } ?: continue
            marks[key] = TriageMark.Failed(posted)
            val c = cools[key]
            val f = (c?.failures ?: 0) + 1
            cools[key] = Cool(nowMs + failureHoldMs(f), c?.verdicts ?: 0, nowMs.takeIf { record.postedAtMs != posted }, f)
        }
    }

    /** 这一批没发成(锁屏、关掉而取消,或预算用完):照旧待判定,不算失败。 */
    fun abort() {
        inFlight = null
        inFlightPackage = null
    }

    /** 请求时发现预算用完了:这批照旧待判定,当天不再发。 */
    fun budgetExhausted(nowMs: Long) {
        abort()
        budgetOutDay = dayOf(nowMs)
    }

    /** 用户改了预算:不再认「今天已用完」,按账本重新算。 */
    fun clearBudgetLatch() {
        budgetOutDay = null
    }

    fun budgetLatched(nowMs: Long): Boolean = budgetOutDay == dayOf(nowMs)

    /**
     * 「判定失败的再试一次」:失败的(包括停着、又有新内容的)重新待判定,不用再等凑批,也不管失败后停没停完;
     * 点一次只多试一次(再失败就又是失败,从加了一的失败次数接着停)。返回几条。
     */
    fun retryFailed(nowMs: Long): Int {
        val failed = records.filter { marks[it.key] is TriageMark.Failed }
        failed.forEach { r ->
            marks.remove(r.key)
            cools[r.key]?.let { cools[r.key] = it.copy(untilMs = minOf(it.untilMs, nowMs)) }
        }
        failed.map { it.packageName }.toSet().forEach { pkg ->
            firstAt[pkg] = minOf(firstAt[pkg] ?: Long.MAX_VALUE, nowMs - windowMs)
        }
        return failed.size
    }

    /** 关掉功能或判定时:全部结果、冷却、计时起点都不留;每天的上限计数留着(同一天里关了再开不重置)。 */
    fun clear() {
        marks.clear()
        cools.clear()
        firstAt.clear()
        records = emptyList()
        history = emptyMap()
        abort()
    }

    /**
     * 按 [now] 更新各 App 凑批的起点(冷却到点的键也在这里变成待判定,[plan] 时也要算)。冷却中有了新内容的键,
     * 起点是冷却结束和第一次看到新内容里晚的那个:锁着时到点的,解锁后不用再等;冷却过后才来的更新照常等凑批。
     */
    private fun refresh(now: Long) {
        nowMs = now
        val pending = pending()
        // 正在发的那个 App 的起点也留着:这批被取消(锁屏)后照旧待判定,解锁后不用重新等
        val apps = pending.mapTo(HashSet()) { it.packageName } + listOfNotNull(inFlightPackage)
        firstAt.keys.retainAll(apps)
        for (r in pending) {
            val start = cools[r.key]?.let { c -> maxOf(c.untilMs, c.newerAtMs ?: now) } ?: now
            firstAt[r.packageName] = minOf(firstAt[r.packageName] ?: Long.MAX_VALUE, start)
        }
        inFlightPackage?.let { firstAt.putIfAbsent(it, now) }
    }

    /** 这个发出时间还没有结果,也没在发。 */
    private fun unjudged(r: NoticeRecord) = mark(r) == null && !sending(r)

    private fun cooling(r: NoticeRecord) = (cools[r.key]?.untilMs ?: Long.MIN_VALUE) > nowMs

    private fun takeBatch(): List<Pair<String, Long>>? = inFlight.also { inFlight = null }

    private fun sending(r: NoticeRecord) = inFlight?.any { it.first == r.key && it.second == r.postedAtMs } == true

    private fun dayOf(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate().toEpochDay()

    /** 通知键的指纹(SHA-256 的前 8 个字节):计数里不留键本身。 */
    private fun fingerprint(key: String): Long {
        val d = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        var h = 0L
        for (i in 0 until 8) h = (h shl 8) or (d[i].toLong() and 0xFF)
        return h
    }

    private fun rollDay(nowMs: Long) {
        val d = dayOf(nowMs)
        if (d != surfacedDay) { surfacedDay = d; surfaced.clear() }
    }

    /**
     * 一个键的冷却:[untilMs] 到点(按最近一次判定或失败算);[verdicts] 是用户没处理它期间判成功过几次;
     * [newerAtMs] 是冷却中第一次看到新内容的时间(没有新内容为 null);[failures] 是上次判成功以来连续失败了几次。
     */
    private data class Cool(val untilMs: Long, val verdicts: Int, val newerAtMs: Long? = null, val failures: Int = 0)

    companion object {
        /** 一个 App 有了第一条待判定后最多等多久凑一批。 */
        const val WINDOW_MS = 20_000L
        /** 每个 App 每天最多几个会话(不同的通知键)判成「值得提醒」。 */
        const val SURFACE_CAP_PER_APP = 10

        private const val MINUTE_MS = 60_000L
        /** 判成「值得提醒」后的冷却:已经标出来了,最新的文字用户自己就看得到。 */
        const val SURFACE_COOLDOWN_MS = 30 * MINUTE_MS
        /** 判成忽略、记下后的冷却,按用户没处理它期间判过的次数逐级拉长,之后都是最后一级。 */
        val BACKOFF_MS: List<Long> = listOf(2 * MINUTE_MS, 5 * MINUTE_MS, 15 * MINUTE_MS, 30 * MINUTE_MS)

        /** 一个键第 [verdicts] 次(从 1 起)判成 [decision] 之后,要冷却多久才再判。只看我们自己的判定和次数,不看通知内容。 */
        fun cooldownMs(decision: TriageDecision, verdicts: Int): Long =
            if (decision == TriageDecision.Surface) SURFACE_COOLDOWN_MS
            else BACKOFF_MS[(verdicts - 1).coerceIn(0, BACKOFF_MS.lastIndex)]

        /** 一个键连续第 [failures] 次(从 1 起)判定失败之后停多久:和忽略、记下用同一组时长。 */
        fun failureHoldMs(failures: Int): Long = BACKOFF_MS[(failures - 1).coerceIn(0, BACKOFF_MS.lastIndex)]

        /**
         * 该不该发一批:纯函数。锁着什么都不做(也不计时);没有待判定的、预算用完、有任务在跑都不发;
         * 否则凑够 [batchMax] 条、或等满 [windowMs] 的 App 里最早的那个发;都没到就等到最早的那个到点。
         * [coolingUntilMs] 是有新内容的冷却里最早到点的时间(没有为 null,须晚于 [nowMs]):只有它时也要等到那时再看,
         * 和凑批一起时等先到的那个。
         */
        fun decide(
            unlocked: Boolean, busy: Boolean, budgetLeft: Boolean, pending: Map<String, PendingApp>, nowMs: Long,
            windowMs: Long = WINDOW_MS, batchMax: Int = NoticeTriage.MAX_BATCH, coolingUntilMs: Long? = null,
        ): TriagePlan {
            if (!unlocked) return TriagePlan.Idle(TriagePlan.Why.Locked)
            if (pending.isEmpty() && coolingUntilMs == null) return TriagePlan.Idle(TriagePlan.Why.Nothing)
            if (!budgetLeft) return TriagePlan.Idle(TriagePlan.Why.BudgetUsedUp)
            if (busy) return TriagePlan.Idle(TriagePlan.Why.Busy)
            val ready = pending.filter { (_, p) -> p.count >= batchMax || nowMs - p.firstAtMs >= windowMs }
            ready.minByOrNull { it.value.firstAtMs }?.let { return TriagePlan.Send(it.key) }
            val windowEnd = pending.values.minOfOrNull { it.firstAtMs + windowMs }
            return TriagePlan.WaitUntil(listOfNotNull(windowEnd, coolingUntilMs).min())
        }
    }
}
