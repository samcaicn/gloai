package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.proactive.DayResult
import com.wangzan101.mika.core.proactive.DigestPool
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.proactive.NoticeNotes
import com.wangzan101.mika.core.proactive.NotesResult
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

// 学习:汇总摘要与笔记摘要的模型调用
/**
 * 当日、周、月归纳共用的发请求:同意、建模型、失败后停一段都和别的批一样。[begin] 在请求发出前记下在做哪一份;
 * [request] 调模型;[apply] 把读懂了的结果记进 [pool],返回写进日志的一句;[tried] 给这一份的失败次数加一
 * (模型回了错、回复读不懂才算;根本没连上的不算)。
 */
internal fun LearnRunner.sendRollup(
    scope: CoroutineScope, what: String, call: ProactiveCall, pool: DigestPool, begin: () -> Unit,
    request: suspend (ChatModel, String, java.time.ZoneId) -> DayResult, apply: (DayResult.Done) -> String, tried: () -> Unit,
) {
    val consented = consent() ?: return publish(LearnStatus.NeedsConsent)
    val built = try {
        hooks.model(call)
    } catch (e: Exception) {
        log("learn model unavailable: ${e.javaClass.simpleName}")
        failed(clock())
        return publish(LearnStatus.Waiting)
    }
    if (built == null) return publish(LearnStatus.NoModel)
    if (!consented.covers(built.target)) {
        log("$what not sent: target is not the consented one")
        return publish(LearnStatus.NeedsConsent)
    }
    val model = built.model
    val lang = language()
    val z = zone()
    begin()
    publish(LearnStatus.Sending)
    val job = scope.launch(start = CoroutineStart.LAZY) {
        val result = try {
            request(model, lang, z)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$what request failed: ${e.javaClass.simpleName}")
            DayResult.Failed()
        }
        if (inFlight !== coroutineContext.job) return@launch
        inFlight = null
        slot.release(this@sendRollup)
        dayInFlight = null
        spanInFlight = null
        try {
            // 途中被清空过的(句子换成了新的一份),这次的结果不写回去
            val stillOk = digestPool === pool &&
                runCatching { on() && digest() && consent() == consented && consented.covers(hooks.target()) }.getOrDefault(false)
            if (!stillOk) {
                log("$what dropped: switch or consent changed")
            } else when (result) {
                is DayResult.Done -> {
                    val summary = apply(result)
                    saveDigest(pool)
                    failures = 0
                    holdUntilMs = 0L
                    val drops = result.dropped.entries.joinToString(",") { "${it.key}=${it.value}" }
                    log("$what: $summary" + if (drops.isEmpty()) "" else ", dropped $drops")
                }
                is DayResult.Failed -> {
                    failed(clock())
                    if (!result.unreachable) tried()
                    log("$what failed: unreachable=${result.unreachable}")
                }
                DayResult.BudgetExhausted -> budgetOutDay = dayOf(clock())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$what apply failed: ${e.javaClass.simpleName}")
            failed(clock())
            tried()
        }
        // 先叫等着的判定(判定先),再看自己还有没有要发的
        slot.wakeOthers(this@sendRollup)
        evaluate()
    }
    slot.take(this)
    inFlight = job
    job.start()
}

/**
 * 当日消息归纳的第 1 级:这个 App 还没归纳过的通知原文(最旧的先发,一批最多 [LearnTuning.NOTES_BATCH_MAX] 段)交给模型出几句话。
 * 同意、建模型、失败后停一段都和归纳话题的那一批一样。这一批回来之后:读懂了的记下句子;模型回了错、回复读不懂的也算做过
 * (不再发同一批);根本没连上的、被取消的(锁屏、开关变了)不算,留着下次。
 */
internal fun LearnRunner.sendNotes(scope: CoroutineScope, packageName: String) {
    val j = journal ?: return publish(LearnStatus.Waiting)
    val pool = loadedDigest() ?: return publish(LearnStatus.StoreUnavailable)
    val consented = consent() ?: return publish(LearnStatus.NeedsConsent)
    val batch = j.pending(packageName, LearnTuning.NOTES_BATCH_MAX)
    if (batch.isEmpty()) return publish(LearnStatus.Idle)
    val built = try {
        hooks.model(ProactiveCall.Notes)
    } catch (e: Exception) {
        log("learn model unavailable: ${e.javaClass.simpleName}")
        failed(clock())
        return publish(LearnStatus.Waiting)
    }
    if (built == null) return publish(LearnStatus.NoModel)
    if (!consented.covers(built.target)) {
        log("notes not sent: target is not the consented one")
        return publish(LearnStatus.NeedsConsent)
    }
    val model = built.model
    val lang = language()
    val z = zone()
    notesApp = packageName
    publish(LearnStatus.Sending)
    val job = scope.launch(start = CoroutineStart.LAZY) {
        val result = try {
            NoticeNotes(model, lang).note(packageName, batch, z)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("notes request failed: ${e.javaClass.simpleName}")
            NotesResult.Failed()
        }
        if (inFlight !== coroutineContext.job) return@launch
        inFlight = null
        slot.release(this@sendNotes)
        notesApp = null
        try {
            // 途中被清空过(日志、句子换成了新的一份)的,这次的结果不能写回去:那会把清掉的东西带回来
            val stillOk = journal === j && digestPool === pool &&
                runCatching { on() && digest() && eligible(packageName) && consent() == consented && consented.covers(hooks.target()) }
                    .getOrDefault(false)
            if (!stillOk) {
                log("notes result dropped: switch, app or consent changed")
            } else when (result) {
                is NotesResult.Noted -> {
                    val before = pool.data()
                    val added = pool.add(packageName, batch, result.notes, z, clock())
                    pool.expire(dayOf(clock()))
                    // 句子存上了,才把原文标成「做过了」并存:中途进程没了,最坏是同一批再归纳一次,不会丢
                    if (saveDigest(pool)) {
                        j.markNoted(batch)
                        saveJournal()
                        failures = 0
                        holdUntilMs = 0L
                        val drops = result.dropped.entries.joinToString(",") { "${it.key}=${it.value}" }
                        log("notes batch: ${batch.size} entries, $added notes" + if (drops.isEmpty()) "" else ", dropped $drops")
                    } else {
                        // 句子没存上:这一批不算做过。原文要是照样标成「做过了」并存下去,重启后句子没有、原文也不再排队,
                        // 这一批就永远丢了。内存里的句子也退回去——留着的话,下次再归纳这一批就重了
                        restoreDigest(before)
                        failed(clock())
                        log("notes batch not kept: ${batch.size} entries, digest save failed")
                    }
                }
                is NotesResult.Failed -> {
                    failed(clock())
                    if (!result.unreachable) {
                        j.markNoted(batch)
                        saveJournal()
                    }
                    log("notes batch failed: ${batch.size} entries, unreachable=${result.unreachable}")
                }
                NotesResult.BudgetExhausted -> budgetOutDay = dayOf(clock())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("notes apply failed: ${e.javaClass.simpleName}")
            failed(clock())
        }
        // 先叫等着的判定(判定先),再看自己还有没有要发的
        slot.wakeOthers(this@sendNotes)
        evaluate()
    }
    slot.take(this)
    inFlight = job
    job.start()
}
