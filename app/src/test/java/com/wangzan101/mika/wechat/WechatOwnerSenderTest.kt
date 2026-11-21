package com.wangzan101.mika.wechat

import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.ChannelInputSink
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.core.wechat.WechatAccount
import com.wangzan101.mika.core.wechat.WechatOwnerResult
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 机主结果推送发送器:绑定快照比对(TOCTOU)与经 Outbox 的同文幂等。 */
class WechatOwnerSenderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain
        override fun decrypt(data: ByteArray): ByteArray = data
    }

    /** 只数调用次数的假渠道;断言换绑/停用后一次都没被调到。 */
    private class CountingChannel(var outcome: SendOutcome = SendOutcome.Delivered) : Channel {
        override val id = WechatChannel.ID
        override val confirms = false
        var sends = 0
            private set

        override fun start(sink: ChannelInputSink) = AutoCloseable {}
        override suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome {
            sends++
            return outcome
        }
    }

    private fun boundStore(dir: File) = WechatStore(dir, cipher).apply {
        saveAccount(WechatAccount("secret", "https://example.test", "bot", "owner"))
        setEnabled(true)
        rememberContext("owner", "context")
        peer("owner")
    }

    @Test fun deliversToTheBoundOwnerWhileTheBindingStaysTheSame() = runTest {
        val store = boundStore(tmp.newFolder())
        val channel = CountingChannel()
        val outbox = Outbox()
        val port = AndroidWechatOwnerPort(store, channel, outbox, "run-1")

        val result = port.send("任务结果")

        assertEquals(WechatOwnerResult.Status.Delivered, result.status)
        assertEquals(1, channel.sends)
        val record = outbox.records().single()
        assertTrue(record.delivery.id.startsWith("owner-send:run-1:"))
        assertEquals(store.boundOwnerPeer(), record.peer)
    }

    @Test fun rebindingAfterTheSnapshotMakesSendUnavailableWithoutSending() = runTest {
        val store = boundStore(tmp.newFolder())
        val channel = CountingChannel()
        val port = AndroidWechatOwnerPort(store, channel, Outbox(), "run-1")
        store.saveAccount(WechatAccount("new-token", "https://example.test", "bot", "owner"))

        val result = port.send("任务结果")

        assertEquals(WechatOwnerResult.Status.Unavailable, result.status)
        assertEquals(0, channel.sends)
    }

    @Test fun disablingTheChannelAfterTheSnapshotMakesSendUnavailableWithoutSending() = runTest {
        val store = boundStore(tmp.newFolder())
        val channel = CountingChannel()
        val port = AndroidWechatOwnerPort(store, channel, Outbox(), "run-1")
        store.setEnabled(false)

        val result = port.send("任务结果")

        assertEquals(WechatOwnerResult.Status.Unavailable, result.status)
        assertEquals(0, channel.sends)
    }

    @Test fun sendingTheSameTextTwiceDeliversOnceAndReturnsTheFirstStatus() = runTest {
        val store = boundStore(tmp.newFolder())
        val channel = CountingChannel()
        val port = AndroidWechatOwnerPort(store, channel, Outbox(), "run-1")

        val first = port.send("任务结果")
        val second = port.send("任务结果")

        assertEquals(WechatOwnerResult.Status.Delivered, first.status)
        assertEquals(first.status, second.status)
        assertEquals(1, channel.sends)
    }

    @Test fun anUncertainDeliveryIsNotResent() = runTest {
        val store = boundStore(tmp.newFolder())
        val channel = CountingChannel(SendOutcome.Uncertain)
        val port = AndroidWechatOwnerPort(store, channel, Outbox(), "run-1")

        val first = port.send("任务结果")
        val second = port.send("任务结果")

        assertEquals(WechatOwnerResult.Status.Uncertain, first.status)
        assertEquals(WechatOwnerResult.Status.Uncertain, second.status)
        assertEquals(1, channel.sends)
    }

    @Test fun aRunWithoutABoundOwnerChatIsUnavailableWithoutSending() = runTest {
        val store = WechatStore(tmp.newFolder(), cipher)
        val channel = CountingChannel()
        val port = AndroidWechatOwnerPort(store, channel, Outbox(), "run-1")

        val result = port.send("任务结果")

        assertEquals(WechatOwnerResult.Status.Unavailable, result.status)
        assertEquals(0, channel.sends)
    }
}
