package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.StopSummary
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.run.record.RunRecords
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.core.run.RunStore
import com.wangzan101.mika.core.run.StoredRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** 恢复时要告诉定时触发的几件事;产品里转给 [TriggerRuntime]。 */
interface TriggerOutcomes {
    fun interrupted(key: String, reason: String)
    fun delivered(key: String)
    fun actionFailed(key: String, reason: String)
}

/**
 * 对「中断了的运行」的善后:补送已有的回复、状态不明的不重发、否则告知「运行中断」并写进对话。
 * 两个入口:进程重启后的 [recover](App 启动和任务服务启动都会调,进程里只做一次,后到的什么都不做;
 * App 先于进程里任何服务起来,所以第一次调用时不会有本进程新开的运行被误判),
 * 以及任务服务被销毁、进程还在时的 [settleInterrupted]。**从不从中间续跑**:屏幕状态回不去。
 *
 * 「已告知」不另设字段,看送达账本:没有回复的运行记着 `interrupted:<运行>` 的提示,状态不明或送达失败的记着对应的提示;
 * 有记录就不再处理。回复送达了的运行直接转为 Ended。所以两个入口、多次调用都只告知一次。
 */
class RunRecovery(
    private val store: RunStore,
    private val outbox: Outbox,
    private val channels: (String) -> Channel?,
    private val triggers: TriggerOutcomes,
    private val conversations: ConversationStore,
    private val scope: CoroutineScope,
    private val records: RunRecords? = null,
    private val label: (String) -> String = { it },
    private val appLabel: (String) -> String = { it },
    private val languageTag: () -> String? = { null },
    private val scheduledLocalChannel: Channel? = null,
) {
    private val done = AtomicBoolean(false)
    private val lock = Mutex()

    /** 标记在这里同步做完(之后新开的运行不会被碰);善后在 [scope] 里慢慢做。返回这次标为中断的运行,不是第一次调用则为空。 */
    fun recover(live: Set<RunId> = emptySet()): List<StoredRun> {
        if (!done.compareAndSet(false, true)) return emptyList()
        val interrupted = store.interruptUnfinished(live)
        settleInterrupted()
        return interrupted
    }

    /**
     * 把所有中断了、还没告知过的运行善后一次。任务服务被销毁时当场调(进程还在,用户不必等到下次启动才知道),
     * 启动时的 [recover] 也会扫一遍,接住当场没做完就被杀的。
     */
    fun settleInterrupted() {
        scope.launch {
            lock.withLock {
                store.all().filter { it.phase == RunPhase.Interrupted && !told(it) }
                    .forEach { run -> runCatching { settle(run) } }
            }
        }
    }

    private fun resultId(run: StoredRun) = run.deliveryId ?: "result:${run.id.value}"
        .takeIf { run.channelPeer != null && outbox.get(it)?.delivery is Delivery.Result }

    private fun noticeIds(run: StoredRun) =
        if (resultId(run) != null) listOf("uncertain:${resultId(run)}", "failed:${resultId(run)}")
        else listOf("interrupted:${run.id.value}")

    private fun told(run: StoredRun) = noticeIds(run).any { outbox.get(it) != null }

    private suspend fun settle(run: StoredRun) {
        run.triggerWindowKey?.let { triggers.interrupted(it, "run interrupted before the result was confirmed") }
        val peer = run.channelPeer ?: ChannelPeer(LocalChannel.ID, "phone-owner", run.threadId)
        val scheduledChannel = scheduledLocalChannel.takeIf {
            peer.channelId == LocalChannel.ID && run.triggerWindowKey != null
        }
        val channel = scheduledChannel ?: channels(peer.channelId) ?: return
        val deliveryId = resultId(run)
        val prior = deliveryId?.let(outbox::get)
        val reply = run.reply ?: (prior?.delivery as? Delivery.Result)?.text
        if (reply != null && deliveryId != null) {
            val result = prior?.delivery as? Delivery.Result
                ?: Delivery.Result(deliveryId, run.threadId, reply, restored = true, title = tr(R.string.recovered_result))
            val state = ResultDelivery.send(outbox, channel, peer, result)
            when (state) {
                DeliveryState.Delivered -> finish(run, reply)
                else -> Unit // ResultDelivery has recorded the failure/uncertainty notice for the entire batch.
            }
        } else {
            val said = run.userText ?: run.goal
            val turnId = "interrupted:${run.id.value}"
            if (conversations.turns(run.threadId).none { it.runId == turnId }) {
                val reason = tr(R.string.recover_interrupted_turn)
                val summary = interruptedSummary(run, said)
                conversations.append(run.threadId, Turn(said, listOfNotNull(reason, summary).joinToString("\n"), "failed", runId = turnId))
            }
            outbox.deliver(channel, peer, Delivery.Notice(turnId, run.threadId,
                tr(R.string.recover_interrupted, said.take(40))))
        }
    }

    /** 只读上次落盘的事件补交代;记录已清理或读不出时仍保留通用中断原因。 */
    private suspend fun interruptedSummary(run: StoredRun, said: String): String? {
        val id = run.recordId ?: return null
        val events = try {
            records?.events(id).orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (!StopSummary.worthSaying(events, null)) return null
        val text = StopSummary.plain(said, events, null, label, appLabel, language = languageTag())
        return text.take(RECOVERY_SUMMARY_CHARS) + if (text.length > RECOVERY_SUMMARY_CHARS) "…" else ""
    }

    private fun finish(run: StoredRun, reply: String) {
        store.save(run.copy(phase = RunPhase.Ended, roundInProgress = false))
        run.triggerWindowKey?.let { key ->
            if (run.runSucceeded == false) triggers.actionFailed(key, reply) else triggers.delivered(key)
        }
    }

    private companion object {
        const val RECOVERY_SUMMARY_CHARS = 2_400
    }
}
