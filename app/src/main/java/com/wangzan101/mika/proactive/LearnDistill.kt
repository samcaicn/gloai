package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.ContactDrop
import com.wangzan101.mika.core.proactive.ExistingTopic
import com.wangzan101.mika.core.proactive.LearnDrop
import com.wangzan101.mika.core.proactive.LearnResult
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.proactive.NoticeLearn
import com.wangzan101.mika.core.proactive.TopicPool
import com.wangzan101.mika.core.proactive.TriageGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

// 学习:一批通知的提炼调用与结果落地
internal fun LearnRunner.evaluate() {
    try {
        evaluateOrThrow()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 外面的东西(模型配置、用量账本)出错不能带垮看守(和锁屏、判定在同一个作业里)
        log("learn evaluate failed: ${e.javaClass.simpleName}")
        // 已开了一批、请求却没发出去:出错的是外面的东西,不是这批,留着,停一段再发
        if (inFlight == null && buffer.sending().isNotEmpty()) keepBatch(clock())
        // 已经在发的请求不动:读一下配置出错不说明它该停(停了它已经算过钱,回头还得再发一遍)。只是这一回不发新的。
        // 但只在确实没锁屏时才留着:锁屏状态读不出来、或锁着,照旧停——锁屏不发是硬规矩
        if (inFlight != null && unlockedForSure()) publish(LearnStatus.Sending) else stopAndPublish(LearnStatus.Idle)
    }
}

/** 锁屏状态读得出来、而且没锁。读不出来的不算。 */
internal fun LearnRunner.unlockedForSure(): Boolean =
    try { !readLocked() } catch (e: CancellationException) { throw e } catch (e: Exception) { false }

internal fun LearnRunner.evaluateOrThrow() {
    val scope = host ?: return
    if (!on()) return stopAndPublish(LearnStatus.Off)
    if (suspended()) return stopAndPublish(LearnStatus.Suspended)
    if (readLocked()) return stopAndPublish(LearnStatus.Locked)
    when (TriageGate.of(true, consent(), hooks.target())) {
        TriageGate.NoModel -> return stopAndPublish(LearnStatus.NoModel)
        TriageGate.NeedsConsent -> return stopAndPublish(LearnStatus.NeedsConsent)
        else -> {}
    }
    if (inFlight != null) return publish(LearnStatus.Sending)
    // 归纳那份上回没存上的:每次有事先补存一次(不等到又有归纳要做)
    if (digestUnsaved) digestPool?.let(::saveDigest)
    // 「当日消息归纳」开着时,通知日志里还没做过分段归纳的、该做当日归纳的那一天也是待办
    val notesPending = digest() && (loadedJournal()?.pendingCount ?: 0) > 0
    val now = clock()
    val dayDue = dayToDigest(now)
    // 周、月归纳排在当日归纳后面:有哪一天该做,先做那一天
    val spanDue = if (dayDue == null) spanToDigest(now) else null
    if (buffer.pendingCount == 0 && !notesPending && dayDue == null && spanDue == null) return publish(LearnStatus.Idle)
    if (hooks.busy.value) return publish(LearnStatus.Busy)
    if (!budgetLeft(now)) return publish(LearnStatus.BudgetUsedUp)
    if (held(now)) return publish(LearnStatus.Waiting)
    // 判定那边正在发:等它发完(它发完会叫这边再看一遍)。主动能力的后台请求一次只发一个
    if (slot.heldByOther(this)) return publish(LearnStatus.Waiting)
    // 先发归纳话题的那一批;没有到期的,再看分段归纳有没有到期的。一次只发一个请求
    buffer.due(now, eligible)?.let { return send(scope, it) }
    // 归纳那份还是没存上(上面刚补存过一次):不发分段、当日、周、月的请求(见 digestUnsaved)
    if (digestUnsaved && (notesPending || dayDue != null || spanDue != null)) return publish(LearnStatus.StoreUnavailable)
    if (notesPending) {
        journal?.due(now, eligible, LearnTuning.NOTES_DUE_COUNT, LearnTuning.NOTES_DUE_AGE_MS)?.let { return sendNotes(scope, it) }
    }
    dayDue?.let { (day, partial) -> return sendDay(scope, day, partial) }
    spanDue?.let { (kind, start, partial) -> return sendSpan(scope, kind, start, partial) }
    publish(LearnStatus.Waiting)
}

internal fun LearnRunner.send(scope: CoroutineScope, packageName: String) {
    val p = loadedPool() ?: return publish(LearnStatus.StoreUnavailable)
    val consented = consent() ?: return publish(LearnStatus.NeedsConsent)
    val events = buffer.begin(packageName)
    if (events.isEmpty()) return publish(LearnStatus.Idle)
    val built = try {
        hooks.model(ProactiveCall.Learn)
    } catch (e: Exception) {
        // 建模型(要解密 Key)出错:不是这批的问题,留着;停一段再试,免得每来一条都再出一次错
        log("learn model unavailable: ${e.javaClass.simpleName}")
        keepBatch(clock())
        return publish(LearnStatus.Waiting)
    }
    if (built == null) {
        buffer.abort()
        return publish(LearnStatus.NoModel)
    }
    // 门口查过之后配置可能变了:建模型用的不是同意过的那个,就不发(事件留着)
    if (!consented.covers(built.target)) {
        buffer.abort()
        log("learn not sent: target is not the consented one")
        return publish(LearnStatus.NeedsConsent)
    }
    val model = built.model
    val now = clock()
    // 给模型看的话题,和它回复里的 t1… 一一对应;请求途中被删了的,用的时候丢掉
    val allowSensitive = sensitive()
    // 这个 App 自己印证过的排在前面:它多半还在推那些题材,模型看得到才引用得上
    val shown = p.strongest(now, includeSensitive = allowSensitive, packageName = packageName).map { ExistingTopic(it.id, it.kind, it.label) }
    // 联系人读不出来时不请模型给(给了也存不下)
    val contactsOn = loadedContacts() != null
    val lang = language()
    bump(packageName) { it.copy(batches = it.batches + 1) }
    publish(LearnStatus.Sending)
    // 先记下作业再开始跑:请求体里要拿它和 inFlight 比
    val job = scope.launch(start = CoroutineStart.LAZY) {
        // 这里抛出去会带垮整个看守(锁屏、判定都在里面):读回复时出了意外也只算这批失败
        val result = try {
            NoticeLearn(model, lang, allowSensitive, contactsOn).learn(packageName, events, shown)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("learn request failed: ${e.javaClass.simpleName}")
            LearnResult.Failed()
        }
        // 已经不是在发的那一个(被取消了,模型却还是回来了;新的一批可能已经在发):什么都不动
        if (inFlight !== coroutineContext.job) return@launch
        inFlight = null
        slot.release(this@send)
        // 用上结果时也一样:出了意外就当这批失败
        try {
            val stillOk = runCatching { on() && eligible(packageName) && consent() == consented && consented.covers(hooks.target()) }
                .getOrDefault(false)
            if (!stillOk) {
                buffer.abort()
                log("learn result dropped: switch, app or consent changed")
            } else when (result) {
                is LearnResult.Learned -> {
                    applyResult(p, packageName, result)
                    failures = 0
                    holdUntilMs = 0L
                }
                is LearnResult.Failed -> if (result.unreachable) keepBatch(clock()) else dropBatch(clock())
                LearnResult.BudgetExhausted -> {
                    buffer.abort()
                    budgetOutDay = dayOf(clock())
                }
            }
            val drops = (result as? LearnResult.Learned)?.dropped.orEmpty().entries.joinToString(",") { "${it.key}=${it.value}" }
            val why = (result as? LearnResult.Failed)?.let { if (it.unreachable) " unreachable" else if (it.unreadable) " unreadable" else "" }.orEmpty()
            log("learn batch: ${result.javaClass.simpleName}$why, ${events.size} events" + if (drops.isEmpty()) "" else ", dropped $drops")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("learn apply failed: ${e.javaClass.simpleName}")
            if (buffer.sending().isNotEmpty()) dropBatch(clock())
        }
        // 先叫等着的判定(判定先),再看自己还有没有要发的
        slot.wakeOthers(this@send)
        evaluate()
    }
    slot.take(this)
    inFlight = job
    job.start()
}

/** 用上一批的结果:用的是这批现在的样子(点开、划掉可能是发出之后才到的)。中间没有挂起点。 */
internal fun LearnRunner.applyResult(p: TopicPool, packageName: String, result: LearnResult.Learned) {
    val events = buffer.sending()
    val now = clock()
    // 敏感类按用上这一刻的开关算:请求途中关掉了的,标了敏感的这次也不收
    val applied = p.apply(packageName, events, result.signals, now, zone(), allowSensitive = sensitive())
    // 先存话题,再存联系人:联系人上连着的话题落盘时,话题池里得已经有了(中途进程没了也不会留下指着空处的连接)
    save(p)
    // 联系人:身份要用标题,得在 finish 清掉标题之前算
    val contactDropped = HashMap(result.contactDropped)
    var contactsAccepted = 0
    val c = loadedContacts()
    if (c == null) {
        if (result.contacts.isNotEmpty()) contactDropped[ContactDrop.Off] = (contactDropped[ContactDrop.Off] ?: 0) + result.contacts.size
    } else {
        val r = c.apply(events, result.contacts, applied.byEvent, now, zone(), allowSensitive = sensitive())
        r.dropped.forEach { (k, v) -> contactDropped[k] = (contactDropped[k] ?: 0) + v }
        contactsAccepted = r.accepted
        if (c.maintain(now, p.all().map { it.id }.toSet()) || r.changed) saveContacts(c)
    }
    buffer.finish(applied.byEvent.mapKeys { (i, _) -> events[i].seq })
    lastDistilledAtMs = now
    bump(packageName) { f ->
        val dropped = HashMap(f.dropped)
        result.dropped.forEach { (k, v) -> dropped[k] = (dropped[k] ?: 0) + v }
        // 校验过了、池子却没收的(请求途中话题被删、敏感类在途中关掉)也算丢弃
        if (applied.missing > 0) dropped[LearnDrop.NotApplied] = (dropped[LearnDrop.NotApplied] ?: 0) + applied.missing
        if (applied.blocked > 0) dropped[LearnDrop.Blocked] = (dropped[LearnDrop.Blocked] ?: 0) + applied.blocked
        val cd = HashMap(f.contactDropped)
        contactDropped.forEach { (k, v) -> cd[k] = (cd[k] ?: 0) + v }
        f.copy(
            distilled = f.distilled + events.size, accepted = f.accepted + applied.applied, dropped = dropped,
            contactsAccepted = f.contactsAccepted + contactsAccepted, contactDropped = cd,
        )
    }
}

/** 这批没归纳成,丢掉;记一次失败,停一段再发下一批。 */
internal fun LearnRunner.dropBatch(now: Long) {
    val pkg = buffer.sending().firstOrNull()?.packageName
    // 先停住再动缓冲:缓冲一变就会重新看一遍,那时已经得是停着的
    failed(now)
    buffer.fail()
    if (pkg != null) bump(pkg) { it.copy(failed = it.failed + 1) }
}

/** 这批没发成、但不是它的问题(没连上模型、建模型出错):留着待归纳;同样记一次失败,停一段再发。 */
internal fun LearnRunner.keepBatch(now: Long) {
    val pkg = buffer.sending().firstOrNull()?.packageName
    failed(now)
    buffer.abort()
    if (pkg != null) bump(pkg) { it.copy(failed = it.failed + 1) }
}

internal fun LearnRunner.failed(now: Long) {
    failures++
    holdUntilMs = now + LearnTuning.failureHoldMs(failures)
}

/** 上一批没归纳成,还在停着。钟被往回拨了(剩下的比最长的一级还长)的不算。 */
internal fun LearnRunner.held(now: Long): Boolean =
    failures > 0 && now < holdUntilMs && holdUntilMs - now <= LearnTuning.FAILURE_HOLD_MS.last()
