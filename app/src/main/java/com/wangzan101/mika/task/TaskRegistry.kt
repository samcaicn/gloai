package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.MemoryRunStore
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.core.run.RunStore
import com.wangzan101.mika.core.run.StoredRun
import com.wangzan101.mika.core.uistate.AppWait

sealed interface StartResult<out R> {
    data class Started<R>(val run: R) : StartResult<R>
    /** 超出并行上限,排在队里第 [position] 个。 */
    data class Queued<R>(val run: R, val position: Int) : StartResult<R>
    /** 已登记过的渠道消息不再次执行，包括进程重启后服务端重投的消息。 */
    data object DuplicateChannelMessage : StartResult<Nothing>
    /** 这个排队项已经登记过运行；无论运行后来是中断、取消还是结束，都不能再次执行。 */
    data object DuplicateFollowUp : StartResult<Nothing>
    /** 这个对话已经有任务(在跑或在排队):照 M1 规则不收,由对话自己排队。 */
    data object AlreadyRunningInConversation : StartResult<Nothing>
}

/**
 * 按对话索引的运行中任务、并行上限与跨任务的全局锁。上限为 1 时(开关关着)就是 M1 的一次一个。
 * [create] 在登记时建任务对象;R 在产品里是 [TaskRun],测试里换成普通对象。
 */
class TaskCenter<R : Any>(
    private val limit: () -> ParallelLimit,
    private val conversationOf: (R) -> String,
    /** 任务的唯一编号(通知按钮带着它);同一对话前后两个任务的编号不同。 */
    private val keyOf: (R) -> String,
    val store: RunStore = MemoryRunStore(),
    val resources: ResourceScheduler = ResourceScheduler(),
    /** 同对话下一件任务的持久队列;测试里换成内存实现。 */
    val followUps: FollowUpQueue = FollowUpQueue(),
    /** 同工作区串行化使用的键;默认按对话各自隔离,传入工作区解析器后同项目对话会排队串行。 */
    private val workspaceOf: (R) -> String = conversationOf,
    private val resolveWorkspace: (String) -> String = { it },
    private val create: (conversationId: String, goal: String) -> R,
) {
    private val running = mutableListOf<R>()
    private val queued = mutableListOf<R>()
    /** 迁移中的调用方继续从这里拿资源，实际所有权归 [resources]。 */
    val clipLock get() = resources.clipboard
    val takeoverQueue get() = resources.takeoverQueue

    @Synchronized
    fun tryStart(conversationId: String, goal: String, queueInConversation: Boolean = false,
        channelPeer: ChannelPeer? = null, channelMessageId: String? = null,
        followUp: QueuedFollowUp? = null): StartResult<R> {
        if (channelMessageId != null && store.all().any {
            it.channelMessageId == channelMessageId && it.channelPeer == channelPeer
        }) return StartResult.DuplicateChannelMessage
        if (followUp != null && store.all().any { it.followUpId == followUp.id }) {
            followUps.remove(followUp.id)
            return StartResult.DuplicateFollowUp
        }
        val workspace = resolveWorkspace(conversationId)
        val sameConversation = byConversation(conversationId) != null
        if (sameConversation && !queueInConversation) {
            // 点发送和任务收尾撞在一起:在同一个锁里放回队列,调用方据此知道下一件还排着
            followUp?.let { followUps.add(it) }
            return StartResult.AlreadyRunningInConversation
        }
        val sameWorkspaceActive = runs().any { workspaceOf(it) == workspace }
        val run = create(conversationId, goal)
        val record = StoredRun(RunId(keyOf(run)), conversationId, goal, RunPhase.Preparing,
            channelPeer = channelPeer, channelMessageId = channelMessageId, followUpId = followUp?.id)
        return if (!sameConversation && !sameWorkspaceActive && running.size < limit().max) {
            store.save(record)
            running += run
            StartResult.Started(run)
        } else {
            // 容量队列也保留持久排队项，真正 markRunning 时才移除；进程死亡不会留下无法恢复的空档。
            store.save(record.copy(phase = RunPhase.Queued))
            queued += run
            StartResult.Queued(run, queued.size)
        }
    }

    @Synchronized fun runs(): List<R> = running + queued
    @Synchronized fun running(): List<R> = running.toList()
    @Synchronized fun queued(): List<R> = queued.toList()
    @Synchronized fun isQueued(run: R): Boolean = queued.any { it === run }
    @Synchronized fun byConversation(id: String): R? = runs().firstOrNull { conversationOf(it) == id }
    @Synchronized fun byKey(key: String): R? = runs().firstOrNull { keyOf(it) == key }

    /**
     * 任务 [owner] 要在自己的屏上开 [pkg]:没人占、自己占着、或占着的任务已经不在用它([stillUsed] 为假,
     * 比如 App 自己关掉了)时占下并返回 true;否则另一个任务正在用,返回 false。查与占在同一把锁里,两个任务同时开同一个 App 只有一个能成。
     */
    @Synchronized
    fun reserve(pkg: String, owner: String, stillUsed: () -> Boolean): Boolean {
        return resources.reserveApp(pkg, owner, { byKey(it) != null }, stillUsed)
    }

    /** 任务 [owner] 此刻能不能占 [pkg](只查不占,规则同 [reserve])。 */
    @Synchronized
    fun appFree(pkg: String, owner: String, stillUsed: () -> Boolean): Boolean =
        resources.appFree(pkg, owner, { byKey(it) != null }, stillUsed)

    /**
     * 任务 [owner] 等别的任务放开 [pkg],最多 [timeoutMs](见 [ResourceScheduler.awaitApp]:互相等的不等)。
     * 挂起着等,不拿这里的锁;在不在跑按开始等那一刻的任务名单认。
     */
    suspend fun awaitApp(pkg: String, owner: String, timeoutMs: Long, free: suspend () -> Boolean): AppWait {
        val active = synchronized(this) { runs().map(keyOf).toSet() }
        return resources.awaitApp(pkg, owner, timeoutMs, { it in active }, free)
    }

    /** 还在排队就移出队伍并返回 true(调用方记为已取消);已经开跑返回 false,由任务自己取消。 */
    @Synchronized
    fun cancelIfQueued(run: R): Boolean {
        if (queued.none { it === run }) return false
        val record = store.get(RunId(keyOf(run)))
        record?.let { store.save(it.copy(phase = RunPhase.Ended)) }
        record?.followUpId?.let(followUps::remove)
        return queued.removeAll { it === run }
    }

    /** 任务结束(或排队时被取消);返回因此从队里放出来、该开始跑的任务。 */
    @Synchronized
    fun onEnded(run: R): List<R> {
        if (queued.removeAll { it === run }) return emptyList()
        running.removeAll { it === run }
        resources.releaseOwner(keyOf(run))
        val started = mutableListOf<R>()
        while (queued.isNotEmpty() && running.size < limit().max) {
            val index = queued.indexOfFirst { candidate ->
                val conv = conversationOf(candidate)
                val ws = workspaceOf(candidate)
                running.none { conversationOf(it) == conv || workspaceOf(it) == ws } &&
                    queued.takeWhile { it !== candidate }.none {
                        conversationOf(it) == conv || workspaceOf(it) == ws
                    }
            }
            if (index < 0) break
            val next = queued.removeAt(index)
            running += next
            store.get(RunId(keyOf(next)))?.let { store.save(it.copy(phase = RunPhase.Preparing)) }
            started += next
        }
        return started
    }

    @Synchronized fun markRunning(run: R) {
        val record = store.get(RunId(keyOf(run)))
        record?.let { store.save(it.copy(phase = RunPhase.Running, roundInProgress = true)) }
        // 到这一步才结束排队项的持久生命周期；此前崩溃由运行账本去重并报告中断。
        record?.followUpId?.let(followUps::remove)
    }

    /** 详细记录已经开出:账本只存它的编号,进程重启后可以读取事件生成交代。 */
    @Synchronized fun markRecord(run: R, recordId: String) {
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(recordId = recordId)) }
    }

    @Synchronized fun markResult(run: R, reply: String?, deliveryId: String? = null, successful: Boolean? = null) {
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(phase = RunPhase.Aftercare, reply = reply,
            deliveryId = deliveryId, runSucceeded = successful)) }
    }

    @Synchronized fun markDelivered(run: R) {
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(phase = RunPhase.Ended, roundInProgress = false)) }
    }

    /**
     * 收尾边界收到、已经不能注入原 Run 的用户原话:这个对话此刻没有任务就返回 true(调用方尝试开始);
     * 还有就先写持久队列,写成功返回 false,写失败也返回 true 让调用方走带并发保护的持久化重试。
     * 查与留在同一把锁里,和 [onEnded] 不会错开。
     */
    @Synchronized fun deferOrStart(threadId: String, text: String, id: String, source: QueuedSource = QueuedSource.Fallback): Boolean {
        if (byConversation(threadId) == null) return true
        // 写失败也交给 startFollowUp 再试一次;tryStart 会在同一把锁里阻止并发,绝不把未落盘项报告成已排队。
        return !followUps.add(QueuedFollowUp(id, threadId, text, System.currentTimeMillis(), source))
    }

    /** 开始之前先确认排队项已持久化；已经在账本里的 claimed 项不需要重写。 */
    @Synchronized fun persistFollowUp(item: QueuedFollowUp): Boolean {
        if (store.all().any { it.followUpId == item.id }) {
            followUps.remove(item.id)
            return true
        }
        return followUps.containsId(item.id) || followUps.add(item)
    }

    /** 这个对话的下一件;只取一件,所在对话没有任务时才取。 */
    @Synchronized fun takeReady(conversationId: String? = null): List<QueuedFollowUp> {
        val started = store.all().mapNotNull { it.followUpId }.toSet()
        started.forEach(followUps::remove)
        return followUps.claimNext({ byConversation(it) == null }, conversationId) { it.id in started }
    }

    @Synchronized fun cancelFollowUp(id: String): Boolean = followUps.cancel(id) != null

    @Synchronized fun dropFollowUps(conversationId: String) = followUps.dropConversation(conversationId)

    @Synchronized fun hasFollowUps(conversationId: String): Boolean = followUps.contains(conversationId)
}

/** 迁移期旧名称，现有调用方和测试仍可逐步转到 TaskCenter。 */
typealias TaskRegistry<R> = TaskCenter<R>
