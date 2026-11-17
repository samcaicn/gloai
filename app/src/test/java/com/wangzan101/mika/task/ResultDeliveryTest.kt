package com.wangzan101.mika.task

import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.ChannelInputSink
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.i18n.ChineseTexts
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ResultDeliveryTest {
    @get:Rule val texts = ChineseTexts()
    private val peer = ChannelPeer("wechat", "owner", "thread")
    private val result = Delivery.Result("result:run", "thread", "完成")
    private fun file(name: String, id: Int) = SharedFile(name, 12L, "Download/Mika/$name", "content://downloads/$id", "a".repeat(64))

    private class RecordingChannel(override val supportsImages: Boolean = true, val outcome: (Delivery) -> SendOutcome = { SendOutcome.Delivered }) : Channel {
        override val id = "wechat"
        override val confirms = false
        val sent = mutableListOf<Delivery>()
        override fun start(sink: ChannelInputSink) = AutoCloseable {}
        override suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome { sent += delivery; return outcome(delivery) }
    }

    @Test fun recordsAllPartsBeforeSendingAndOnlyIncludesExportedImages() = runTest {
        val outbox = Outbox()
        val channel = RecordingChannel { assertEquals(3, outbox.records().size); SendOutcome.Delivered }
        val image = file("screen.png", 1)
        ResultDelivery.prepare(outbox, channel, peer, result, listOf(image, file("table.csv", 3), file("photo.JPG", 2), image))
        assertTrue(channel.sent.isEmpty())
        assertEquals(DeliveryState.Delivered, ResultDelivery.send(outbox, channel, peer, result))
        assertEquals(listOf("result:run", "result:run:image:0", "result:run:image:1"), channel.sent.map { it.id })
        assertEquals(image.uri, (channel.sent[1] as Delivery.Image).uri)
        assertEquals(image.sha256, (channel.sent[1] as Delivery.Image).sha256)
    }

    @Test fun scheduledFailureStreakIsSavedBeforeSending() = runTest {
        val outbox = Outbox()
        val channel = RecordingChannel()
        val failure = TaskState.Failed("没有打开网页", FailKind.Environment)
        val result = TaskService.resultDelivery("result:plan", peer.threadId, failure, failureStreak = 3)

        ResultDelivery.prepare(outbox, channel, peer, result, emptyList())
        val saved = outbox.pendingRecovery().single().delivery as Delivery.Result
        assertEquals("定时任务已连续失败 3 次", saved.title)
        assertEquals("没有打开网页\n可以在定时任务页暂停或修改这个计划。", saved.text)
        assertEquals(DeliveryState.Delivered, ResultDelivery.send(outbox, channel, peer, result))
        assertEquals(listOf(saved), channel.sent)
    }

    @Test fun uncertainImageDoesNotResendSuccessfulTextOrImages() = runTest {
        val outbox = Outbox()
        val channel = RecordingChannel { if (it.id.endsWith(":image:1")) SendOutcome.Uncertain else SendOutcome.Delivered }
        ResultDelivery.prepare(outbox, channel, peer, result, listOf(file("one.png", 1), file("two.jpg", 2)))
        assertEquals(DeliveryState.Uncertain, ResultDelivery.send(outbox, channel, peer, result))
        assertEquals(DeliveryState.Delivered, outbox.get(result.id)?.state)
        assertEquals(DeliveryState.Uncertain, outbox.get("result:run:image:1")?.state)
        assertEquals("uncertain:result:run", channel.sent.last().id)
        val sent = channel.sent.toList()
        assertEquals(DeliveryState.Uncertain, ResultDelivery.send(outbox, channel, peer, result))
        assertEquals(sent, channel.sent)
    }

    @Test fun definiteImageFailureGetsOneRecordedNotice() = runTest {
        val outbox = Outbox()
        val channel = RecordingChannel { if (it is Delivery.Image) SendOutcome.Failed else SendOutcome.Delivered }
        ResultDelivery.prepare(outbox, channel, peer, result, listOf(file("one.png", 1)))
        assertEquals(DeliveryState.Failed, ResultDelivery.send(outbox, channel, peer, result))
        ResultDelivery.send(outbox, channel, peer, result)
        assertEquals(listOf(result.id, "result:run:image:0", "failed:result:run"), channel.sent.map { it.id })
    }

    @Test fun textOnlyChannelsDoNotReceiveImageDeliveries() = runTest {
        val outbox = Outbox()
        val channel = RecordingChannel(supportsImages = false)
        ResultDelivery.prepare(outbox, channel, peer, result, listOf(file("one.png", 1)))
        assertEquals(DeliveryState.Delivered, ResultDelivery.send(outbox, channel, peer, result))
        assertEquals(listOf(result), channel.sent)
    }
}
