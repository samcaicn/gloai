package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.ContactPool
import com.wangzan101.mika.core.proactive.ContactType
import com.wangzan101.mika.core.proactive.DigestNote
import com.wangzan101.mika.core.proactive.DigestPool
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.NoticeJournal
import com.wangzan101.mika.core.proactive.LearnLevel
import com.wangzan101.mika.core.proactive.ObservedContext
import com.wangzan101.mika.core.proactive.SourceTier
import com.wangzan101.mika.core.proactive.NoticeView
import com.wangzan101.mika.core.proactive.SpanKind
import java.time.Instant
import kotlinx.coroutines.CancellationException
import com.wangzan101.mika.proactive.LearnRunner.Companion.MARK_ALL
import com.wangzan101.mika.proactive.LearnRunner.Companion.LIVE_WINDOW_MS
import com.wangzan101.mika.proactive.LearnRunner.Companion.OBSERVED_TOPICS
import com.wangzan101.mika.proactive.LearnRunner.Companion.OBSERVED_PEOPLE

// 学习:通知日志的读写与观察到的原始数据
/** 没读出来时读一次(没有文件就从现在开始记);读不出来(密钥库一时不可用、IO)为 null,下次再试,这期间不记也不存。 */
internal fun LearnRunner.loadedJournal(): NoticeJournal? {
    journal?.let { return it }
    return try {
        NoticeJournal(journalStore.load(), clock = clock).also {
            journal = it
            // 记着的还没落盘的按 App 删除(当时读不出来的、删了没存上的):现在补做,存上了才划掉(见 saveJournal)
            val pending = purges.journalPurges
            if (pending.isNotEmpty()) {
                it.removePackages { p -> MARK_ALL !in pending && p !in pending }
                // 记着的状态显示(见 dropLive):只去掉那一阵的——同一个键上之后的正常通知不动
                for (w in pending) keyIn(w)?.let { (keyId, since) -> it.removeKey(keyId, since, since + LIVE_WINDOW_MS) }
                journalPurgesApplied = journalPurgesApplied + pending
            }
            // 读进来时就有过期的:内存里已经去掉了,文件里还在——马上存一次(存里面会刷新页面上的数)
            if (it.expiredOnLoad || pending.isNotEmpty()) saveJournal() else publish(status)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("learn journal unavailable: ${e.javaClass.simpleName}")
        null
    }
}

/** 到期的删掉,有改动或有没存的就存上。「当日消息归纳」关着、或日志还没用过时什么都不做。 */
internal fun LearnRunner.tendJournal() {
    if (!on() || !digest()) return
    val j = loadedJournal() ?: return
    if (j.expire() || journalUnsaved > 0) saveJournal()
}

internal fun LearnRunner.saveJournal() {
    val j = journal ?: return
    j.expire()
    try {
        journalStore.save(j.data())
        journalUnsaved = 0
        // 做过的按 App 删除存上了:不再记着,免得这个 App 以后又被允许时,把那之后新记的也删掉
        if (journalPurgesApplied.isNotEmpty()) {
            purges.journalPurges = purges.journalPurges - journalPurgesApplied
            journalPurgesApplied = emptySet()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("learn journal save failed: ${e.javaClass.simpleName}")
        // 记着还有没存的:下次整理时再存(不然到期删掉的那些,之后再没有别的改动来带它们落盘)
        if (journalUnsaved == 0) journalUnsaved = 1
    }
    publish(status)
}

/** 整份删掉:内存里的和文件(读不出来时也删)。正在发的那一批分段归纳取消。 */
internal fun LearnRunner.dropJournal() {
    if (notesApp != null) stopRequest()
    val had = journal?.size ?: 0
    journal = null
    journalUnsaved = 0
    // 整份删掉了,没有还要补做的。删不掉的(文件里还是旧的,内存里那份已经丢了,下次一读又回来了):
    // 记一个「全清」,读得出来时照它清空
    journalPurgesApplied = emptySet()
    if (wipe("journal") { journalStore.wipe() }) {
        if (purges.journalPurges.isNotEmpty()) purges.journalPurges = emptySet()
    } else if (purges.journalPurges != setOf(MARK_ALL)) {
        purges.journalPurges = setOf(MARK_ALL)
    }
    if (had > 0) publish(status)
}

/**
 * 从通知原文里去掉这几个 App 的。和 [digestRemove] 一个做法:删掉了就先记进 [purges] 再存,存上了才划掉([saveJournal]);
 * 这时读不出来的记下来,读得出来时补做([loadedJournal])。不记的话,读不出来那次等于没删,删了没存上、进程又没了的
 * 文件里还是旧的——之后这个 App 又被允许,两天内的旧原文就又能被读到、被拿去归纳。
 * 不因为读不出来就整份删:[forgetApps] 每次名单变动都带着整个不够格的名单来,密钥库一时不可用就会把攒下的原文全删光。
 */
internal fun LearnRunner.journalRemove(packages: Set<String>) {
    if (packages.isEmpty()) return
    val j = loadedJournal()
    if (j == null) {
        if (!purges.journalPurges.containsAll(packages)) purges.journalPurges = purges.journalPurges + packages
        return
    }
    if (j.removePackages { it !in packages }) {
        if (!purges.journalPurges.containsAll(packages)) purges.journalPurges = purges.journalPurges + packages
        journalPurgesApplied = journalPurgesApplied + packages
        saveJournal()
    } else if (packages.any { it in purges.journalPurges }) {
        saveJournal()
    }
}

/**
 * 这次任务要带的「从通知里得来的」那一段(见 [ObservedContext]):按激进程度 [level] 和各 App 的来源档位 [tier] 挑出够格的,
 * 排成一段、限好长。归纳关着、最保守的一档、什么都不够格时为 null。不调模型;在串行协程上调。
 *
 * 够格的:来源档位在 [LearnLevel.intoTasks] 里、现在还在归纳的 App 的——
 * - 当日归纳里今天(到现在的)和昨天的句子:一句依据的几个 App 都够格才带;
 * - 话题:只数够格的 App 印证过的日子,满 [LearnLevel.topicDays] 天;
 * - 联系人:已确立的。
 * 敏感的话题和联系人只有「敏感类推断」开着时才带。
 */
internal fun LearnRunner.observed(level: LearnLevel, tier: (String) -> SourceTier): String? {
    if (!on() || suspended() || level.intoTasks.isEmpty()) return null
    val ok: (String) -> Boolean = { eligible(it) && tier(it) in level.intoTasks }
    val now = clock()
    val today = dayOf(now)
    val digests = if (digest()) freshDigest() else null
    fun linesOf(day: Long): List<String> =
        digests?.day(day)?.lines.orEmpty().filter { it.apps.isNotEmpty() && it.apps.all(ok) }.map { it.text }
    val allowSensitive = sensitive()
    // 该淡忘的(留存低于 FORGET_BELOW)不带:它们要等下一次整理才真的从池里去掉,而整理只在收下新归纳、
    // 打开内容页时做——很久没有新归纳、也没开过页面的话,早该忘掉的话题和联系人还在池里。
    // 这里只是不带,不在任务这条路上改池、存盘。用户自己定过关系的联系人不淡忘(同 ContactPool.maintain)
    val topics = loadedPool()?.all().orEmpty()
        .filter { (allowSensitive || !it.sensitive) && it.retention(now) >= LearnTuning.FORGET_BELOW && ObservedContext.qualifiedTopic(it, level, ok) }
        .sortedByDescending { it.score(now) }.take(OBSERVED_TOPICS)
    val people = loadedContacts()?.all().orEmpty()
        .filter { ok(it.packageName) && it.established && (allowSensitive || !it.sensitive) }
        .filter { it.userSet || it.retention(now) >= LearnTuning.FORGET_BELOW }
        .sortedByDescending { it.score(now) }.take(OBSERVED_PEOPLE)
        .map { ObservedContext.Person(it.name, it.type == ContactType.Group, it.relation, it.userSet) }
    val asOf = digests?.day(today)?.let { java.time.Instant.ofEpochMilli(it.madeAtMs).atZone(zone()).toLocalTime() }
        ?.let { "%02d:%02d".format(java.util.Locale.ENGLISH, it.hour, it.minute) }
    // 刚过完的那一周、那个月:只带完整的那份(「到现在为止」的不当成上周、上月)
    fun earlier(kind: SpanKind, key: Long, days: LongRange): ObservedContext.Earlier? =
        digests?.span(kind, key)?.takeIf { !it.partial }?.lines.orEmpty()
            .filter { it.apps.isNotEmpty() && it.apps.all(ok) }.map { it.text }
            .takeIf { it.isNotEmpty() }?.let { ObservedContext.Earlier(days.first, days.last, it, digests?.span(kind, key)?.leftOut ?: 0) }
    val lastWeek = DigestPool.weekOf(today) - 7
    val lastMonth = DigestPool.monthOf(DigestPool.monthOf(today) - 1)
    return ObservedContext.render(
        linesOf(today), linesOf(today - 1), topics, people, asOf,
        lastWeek = earlier(SpanKind.Week, lastWeek, lastWeek..lastWeek + 6),
        // 月归纳管的是结束在那个月的几周:标题写它实际管到的日子
        lastMonth = earlier(SpanKind.Month, lastMonth, DigestPool.monthCover(lastMonth)),
        // 那两天的当日归纳做的时候有没有放不下的句子:有就在标题里说
        todayLeftOut = digests?.day(today)?.leftOut ?: 0,
        yesterdayLeftOut = digests?.day(today - 1)?.leftOut ?: 0,
    )
}

/**
 * 任务读通知用([NoticeSource]):[fromMs] 到 [toMs] 之间、来源档位够格的 App 的通知原文和分段归纳。
 * 归纳或「当日消息归纳」关着、最保守的一档、日志读不出来时为 null。在串行协程上调。
 */
internal fun LearnRunner.notices(fromMs: Long, toMs: Long, level: LearnLevel, tier: (String) -> SourceTier): NoticeView? {
    if (!on() || suspended() || !digest() || level.intoTasks.isEmpty()) return null
    val ok: (String) -> Boolean = { eligible(it) && tier(it) in level.intoTasks }
    val j = loadedJournal() ?: return null
    // 到期的先删:读的人不该看到过了保留期的
    if (j.expire()) saveJournal()
    // 归纳那份读不出来时不能当成「没有句子」给出去:原文照给,另说明句子这会儿读不出来
    val pool = freshDigest()
    val notes = pool?.all().orEmpty()
        .filter { ok(it.packageName) && it.toMs in fromMs until toMs }.sortedBy { it.fromMs }
    val (given, rest) = j.between(fromMs, toMs).partition { ok(it.packageName) }
    // 档位不够格所以没给的 App 有几个(只给个数):读的人据此说「还有几个 App 的没看」,而不是「今天没别的了」
    val withheld = rest.filter { eligible(it.packageName) }.mapTo(HashSet()) { it.packageName }.size
    return NoticeView(j.completeSinceMs, given, notes, withheld, notesUnavailable = pool == null)
}

/**
 * 页面用:[note] 这一句分段归纳是照着哪些通知写的(见 [NoticeJournal.around];给用户自己看,不看来源档位)。
 * 到期的先删再给。在串行协程上调。
 */
internal fun LearnRunner.rawFor(note: DigestNote): RawNotices {
    if (!on() || !digest()) return RawNotices.Off
    if (suspended()) return RawNotices.Unavailable
    val j = loadedJournal() ?: return RawNotices.Unavailable
    if (j.expire()) saveJournal()
    return RawNotices.Found(j.around(note.packageName, note.title, note.fromMs, note.toMs))
}
