package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.RunPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRegistryTest {
    private data class Run(val conversationId: String, val goal: String) {
        val key = "$conversationId/$goal"
    }

    private var limit = ParallelLimit(2, null)
    private val registry = TaskRegistry({ limit }, { it.conversationId }, { it.key }) { c, g -> Run(c, g) }

    @Test fun secondConversationStartsWhenLimitTwo() {
        val a = registry.tryStart("a", "设置版本号")
        val b = registry.tryStart("b", "新建闹钟")
        assertTrue(a is StartResult.Started)
        assertTrue(b is StartResult.Started)
        assertEquals(listOf("a", "b"), registry.running().map { it.conversationId })
        assertEquals(Run("b", "新建闹钟"), registry.byConversation("b"))
    }

    @Test fun sameConversationQueues() {
        registry.tryStart("a", "一")
        // 同一对话里已有任务:照 M1 规则交给对话自己排队,登记处不收
        assertSame(StartResult.AlreadyRunningInConversation, registry.tryStart("a", "二"))
        assertEquals(1, registry.runs().size)
    }

    @Test fun scheduledRunsQueueBehindSameConversationEvenWithFreeParallelSlot() {
        val first = (registry.tryStart("a", "现在的任务") as StartResult.Started).run
        val scheduled = registry.tryStart("a", "到点的任务", queueInConversation = true) as StartResult.Queued
        assertEquals(listOf(first), registry.running())
        assertEquals(listOf(scheduled.run), registry.queued())
        assertEquals(listOf(scheduled.run), registry.onEnded(first))
    }

    @Test fun overLimitQueues() {
        limit = ParallelLimit(1, null)
        registry.tryStart("a", "一")
        val b = registry.tryStart("b", "二")
        val c = registry.tryStart("c", "三")
        assertEquals(1, (b as StartResult.Queued).position)
        assertEquals(2, (c as StartResult.Queued).position)
        assertEquals(listOf("a"), registry.running().map { it.conversationId })
        assertEquals(listOf("b", "c"), registry.queued().map { it.conversationId })
        assertTrue(registry.isQueued(b.run))
        // 排着队的对话也不能再收第二个
        assertSame(StartResult.AlreadyRunningInConversation, registry.tryStart("c", "四"))
    }

    @Test fun endedRunStartsQueued() {
        limit = ParallelLimit(1, null)
        val a = (registry.tryStart("a", "一") as StartResult.Started).run
        val b = (registry.tryStart("b", "二") as StartResult.Queued).run
        registry.tryStart("c", "三")
        assertEquals(listOf(b), registry.onEnded(a))
        assertEquals(listOf("b"), registry.running().map { it.conversationId })
        assertEquals(listOf("c"), registry.queued().map { it.conversationId })
        assertNull(registry.byConversation("a"))
        // 排队中的被取消:不放出新的,队伍往前挪
        val c = registry.byConversation("c")!!
        assertEquals(emptyList<Run>(), registry.onEnded(c))
        assertEquals(emptyList<Run>(), registry.queued())
    }

    // 两个任务同时要开同一个 App:先占到的那个才能开,另一个拿到「别的任务在用」
    @Test fun sameAppReservedByFirstRun() {
        val a = (registry.tryStart("a", "一") as StartResult.Started).run
        val b = (registry.tryStart("b", "二") as StartResult.Started).run
        assertTrue(registry.reserve("settings", a.key) { true })
        assertTrue(registry.reserve("settings", a.key) { true })
        assertFalse(registry.reserve("settings", b.key) { true })
        // 占着的任务已不在用它(App 自己关了):放给后来的
        assertTrue(registry.reserve("settings", b.key) { false })
        assertFalse(registry.reserve("settings", a.key) { true })
        // 任务结束,占的包一起放掉
        registry.onEnded(b)
        assertTrue(registry.reserve("settings", a.key) { true })
    }

    @Test fun centerUsesItsResourceSchedulerForMainScreenAndClipboard() {
        assertSame(registry.resources.takeoverQueue, registry.takeoverQueue)
        assertSame(registry.resources.clipboard, registry.clipLock)
    }

    @Test fun cancelIfQueuedOnlyTakesQueuedRuns() {
        limit = ParallelLimit(1, null)
        val a = (registry.tryStart("a", "一") as StartResult.Started).run
        val b = (registry.tryStart("b", "二") as StartResult.Queued).run
        assertFalse(registry.cancelIfQueued(a))
        assertTrue(registry.cancelIfQueued(b))
        assertFalse(registry.cancelIfQueued(b))
        assertEquals(emptyList<Run>(), registry.onEnded(a))
        assertEquals(RunPhase.Ended, registry.store.get(RunId(b.key))?.phase)
    }

    @Test fun centerStoresTransitionsBeforeAndAfterDelivery() {
        limit = ParallelLimit(1, null)
        val a = (registry.tryStart("a", "一") as StartResult.Started).run
        val b = (registry.tryStart("b", "二") as StartResult.Queued).run
        assertEquals(RunPhase.Preparing, registry.store.get(RunId(a.key))?.phase)
        assertEquals(RunPhase.Queued, registry.store.get(RunId(b.key))?.phase)
        registry.markRunning(a)
        assertEquals(true, registry.store.get(RunId(a.key))?.roundInProgress)
        registry.markResult(a, "好了")
        assertEquals("好了", registry.store.get(RunId(a.key))?.reply)
        registry.markDelivered(a)
        assertEquals(RunPhase.Ended, registry.store.get(RunId(a.key))?.phase)
        registry.onEnded(a)
        assertEquals(RunPhase.Preparing, registry.store.get(RunId(b.key))?.phase)
    }

    @Test fun channelMessageIsExecutedOnlyOnceAndKeepsItsReplyAddress() {
        val peer = ChannelPeer("wechat", "owner", "thread")
        val first = registry.tryStart("thread", "任务", queueInConversation = true,
            channelPeer = peer, channelMessageId = "message-1")
        val duplicate = registry.tryStart("thread", "任务", queueInConversation = true,
            channelPeer = peer, channelMessageId = "message-1")
        assertTrue(first is StartResult.Started)
        assertSame(StartResult.DuplicateChannelMessage, duplicate)
        val key = (first as StartResult.Started).run.key
        assertEquals(peer, registry.store.get(RunId(key))?.channelPeer)
        assertEquals("message-1", registry.store.get(RunId(key))?.channelMessageId)
        assertEquals(1, registry.runs().size)
    }

    @Test fun consecutiveChannelMessagesQueueInOrderWithoutMergingOrRepeatingRedeliveries() {
        val peer = ChannelPeer("wechat", "owner", "thread")
        val first = registry.tryStart("thread", "相同的话", queueInConversation = true,
            channelPeer = peer, channelMessageId = "message-1")
        val second = registry.tryStart("thread", "相同的话", queueInConversation = true,
            channelPeer = peer, channelMessageId = "message-2")
        val redelivered = registry.tryStart("thread", "相同的话", queueInConversation = true,
            channelPeer = peer, channelMessageId = "message-2")

        assertTrue(first is StartResult.Started)
        assertTrue(second is StartResult.Queued)
        assertSame(StartResult.DuplicateChannelMessage, redelivered)
        assertEquals(listOf("相同的话", "相同的话"), registry.runs().map { it.goal })
    }

    @Test fun messagesAtFinishingBoundaryAreKeptInOrder() {
        val run = (registry.tryStart("thread", "原来的") as StartResult.Started).run
        assertFalse(registry.deferOrStart("thread", "first", "id-1"))
        assertFalse(registry.deferOrStart("thread", "second", "id-2"))
        // 对话还有任务:不交出
        assertEquals(emptyList<QueuedFollowUp>(), registry.takeReady())
        registry.onEnded(run)
        // 同一对话一次只取一件;第一件跑完之前第二件继续排着
        assertEquals(listOf("first"), registry.takeReady().map { it.text })
        assertEquals(listOf("second"), registry.takeReady().map { it.text })
        assertEquals(emptyList<QueuedFollowUp>(), registry.takeReady())
    }

    // 收尾和留话同时发生:要么当场开始,要么留下后在收尾时交出,不会两头都落空
    @Test fun deferRacingWithEndNeverStrandsTheMessage() {
        repeat(200) { i ->
            val run = (registry.tryStart("t$i", "原来的") as StartResult.Started).run
            var startedNow = false
            val t = Thread { startedNow = registry.deferOrStart("t$i", "插话", "id-$i") }
            t.start()
            registry.onEnded(run)
            t.join()
            val handedOut = registry.takeReady().any { it.conversationId == "t$i" }
            assertTrue("message $i lost or duplicated", startedNow != handedOut)
        }
    }

    // 留下的话只等自己的对话空出来,不等别的对话的任务
    @Test fun deferredMessageStartsWhenItsConversationIsFree() {
        val a = (registry.tryStart("a", "一") as StartResult.Started).run
        registry.tryStart("b", "别的对话")
        assertFalse(registry.deferOrStart("a", "下一件", "id-a"))
        registry.onEnded(a)
        assertEquals(listOf("下一件"), registry.takeReady().map { it.text })
        assertTrue(registry.deferOrStart("c", "空着的对话", "id-c"))
    }

    @Test fun sameWorkspaceConversationsSerializeWhileOtherWorkspacesRunInParallel() {
        val workspaces = mapOf("p1-a" to "p:proj-1", "p1-b" to "p:proj-1", "p1-c" to "p:proj-1", "other" to "other")
        val shared = TaskRegistry<Run>(
            { ParallelLimit(2, null) },
            { it.conversationId },
            { it.key },
            workspaceOf = { workspaces[it.conversationId] ?: it.conversationId },
            resolveWorkspace = { workspaces[it] ?: it },
        ) { c, g -> Run(c, g) }

        val first = (shared.tryStart("p1-a", "步1") as StartResult.Started).run
        val second = (shared.tryStart("p1-b", "步2") as StartResult.Queued)
        val third = (shared.tryStart("p1-c", "步3") as StartResult.Queued)
        val other = (shared.tryStart("other", "独立任务") as StartResult.Started).run

        assertEquals(1, second.position)
        assertEquals(2, third.position)
        assertEquals(listOf(first, other), shared.running())

        // 结束 other 时，p1-b/p1-c 仍需等 p1-a 完成，不能并发跑进 p:proj-1
        assertEquals(emptyList<Run>(), shared.onEnded(other))
        assertEquals(listOf(first), shared.running())

        // 结束 p1-a 时仅按 FIFO 放出 p1-b，p1-c 继续排队
        assertEquals(listOf(second.run), shared.onEnded(first))
        assertEquals(listOf(second.run), shared.running())
        assertEquals(listOf(third.run), shared.queued())

        assertEquals(listOf(third.run), shared.onEnded(second.run))
        assertEquals(listOf(third.run), shared.running())
    }
}
