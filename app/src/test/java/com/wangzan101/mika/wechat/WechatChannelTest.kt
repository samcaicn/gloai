package com.wangzan101.mika.wechat

import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.run.ChannelGateway
import com.wangzan101.mika.core.run.ChannelInput
import com.wangzan101.mika.core.run.ChannelInputSink
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.MemoryOwnerIdentity
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.core.wechat.WechatAccount
import com.wangzan101.mika.core.wechat.WechatApi
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class WechatChannelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }

    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun imageReplyWhoseQuestionChangesDuringImportIsDiscarded() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val peer = store.peer("owner")
            val original = Delivery.Ask("first", peer.threadId, "first", Delivery.Ask.Kind.Input, "Which photo?")
            var active = original
            var imported: Boolean? = null
            val images = object : WechatImageFiles {
                override suspend fun receive(peer: ChannelPeer, images: List<WechatImageSource>,
                    accept: (List<SavedWechatImage>) -> Boolean): Boolean {
                    active = original.copy(id = "replacement", askId = "replacement")
                    return accept(emptyList()).also { imported = it }
                }

                override suspend fun read(image: Delivery.Image): ByteArray = error("Unexpected image export")
            }
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "test"), { active }, {}, images)
            rejectUnexpectedRouting(peer) { active }.start(channel).use {
                server.enqueue(response("""{"get_updates_buf":"next","msgs":[{
                    "message_id":"image-reply","from_user_id":"owner","message_type":1,"context_token":"ctx",
                    "item_list":[{"type":1,"text_item":{"text":"this one"}},{"type":2,"image_item":{}}]}]}"""))
                channel.pollOnce()
            }

            assertEquals(false, imported)
            assertTrue(store.hasMessage("image-reply"))
            assertTrue(store.pending().isEmpty())
            assertEquals("next", store.cursor())
        }
    }

    @Test fun journaledImageReplyFromBeforeUpgradeDoesNotStartAnotherTaskOnReplay() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val dir = tmp.newFolder()
            val original = WechatStore(dir, cipher)
            original.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            original.setEnabled(true)
            val peer = original.peer("owner")
            assertTrue(original.journal("image-reply", peer, "附件:/workspace/inbox/wechat-1 (2).jpg(25 B)", "old"))
            val store = WechatStore(dir, cipher)
            val active = Delivery.Ask("current", peer.threadId, "current", Delivery.Ask.Kind.Input, "Which photo?")
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "test"), { active }, {})

            rejectUnexpectedRouting(peer) { active }.start(channel).use {
                server.enqueue(response("""{"get_updates_buf":"next"}"""))
                channel.pollOnce()
            }

            assertTrue(store.hasMessage("image-reply"))
            assertTrue(store.pending().isEmpty())
        }
    }

    private fun rejectUnexpectedRouting(peer: ChannelPeer, activeAsk: () -> Delivery.Ask) =
        ChannelGateway(MemoryOwnerIdentity().also { it.bind(peer) }, { activeAsk() }, object : ChannelGateway.Route {
            override suspend fun newTask(peer: ChannelPeer, messageId: String, text: String): Unit = error("Unexpected task")
            override suspend fun answer(threadId: String, askId: String, text: String): Boolean = error("Unexpected answer")
            override suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice) = false
            override suspend fun stop(runId: RunId) = Unit
            override suspend fun cancelAsk(threadId: String): Unit = error("Unexpected question cancellation")
        })

    @Test fun pollingJournalsOnlyOwnerTextAndAcknowledgesAfterTaskRegistration() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.35"), log = {})
            val received = mutableListOf<ChannelInput>()
            channel.start(ChannelInputSink { received += it })
            server.enqueue(response("""{"get_updates_buf":"next","msgs":[
                {"message_id":1,"from_user_id":"owner","message_type":1,"context_token":"ctx","item_list":[{"type":1,"text_item":{"text":"任务"}}]},
                {"message_id":2,"from_user_id":"stranger","message_type":1,"item_list":[{"type":1,"text_item":{"text":"不收"}}]}
            ]}"""))

            channel.pollOnce()

            val input = received.single() as ChannelInput.Text
            assertEquals("1", input.messageId)
            assertEquals("任务", input.text)
            assertEquals("wechat", input.peer.channelId)
            assertEquals("next", store.cursor())
            assertEquals("ctx", store.contextToken("owner"))
            assertEquals(1, store.pending().size)
            channel.acknowledge("1")
            assertEquals(emptyList<PendingWechatMessage>(), store.pending())
        }
    }

    @Test fun pendingFollowUpKeepsAskIdAcrossReopenAndRedeliversUntilAcknowledged() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val dir = tmp.newFolder()
            val first = WechatStore(dir, cipher)
            first.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            first.setEnabled(true)
            val peer = first.peer("owner")
            assertTrue(first.journal("reply-1", peer, "答案", replyToAskId = "ask-1"))
            val store = WechatStore(dir, cipher)
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.35"), log = {})
            val received = mutableListOf<ChannelInput>()
            channel.start(ChannelInputSink { received += it })
            server.enqueue(response("""{"get_updates_buf":"next","msgs":[]}"""))
            server.enqueue(response("""{"get_updates_buf":"next","msgs":[]}"""))

            channel.pollOnce()
            channel.pollOnce()

            assertEquals(listOf("reply-1", "reply-1"), received.map { (it as ChannelInput.Text).messageId })
            assertEquals(listOf("ask-1", "ask-1"), received.map { (it as ChannelInput.Text).replyToAskId })
            channel.acknowledge("reply-1")
            server.enqueue(response("""{"get_updates_buf":"next","msgs":[]}"""))
            channel.pollOnce()
            assertEquals(2, received.size)
        }
    }

    @Test fun acceptedLiveFollowUpIsAcknowledgedThroughGenericGateway() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val peer = store.peer("owner")
            val ask = Delivery.Ask("ask-delivery", peer.threadId, "ask-1", Delivery.Ask.Kind.Input, "问题")
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.35"), activeAsk = { ask }, log = {})
            val events = mutableListOf<String>()
            val gateway = ChannelGateway(MemoryOwnerIdentity().also { it.bind(peer) }, { ask }, object : ChannelGateway.Route {
                override suspend fun newTask(peer: ChannelPeer, messageId: String, text: String) { events += "new:$text" }
                override suspend fun answer(threadId: String, askId: String, text: String): Boolean { events += "answer:$text"; return true }
                override suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice) = false
                override suspend fun stop(runId: RunId) = Unit
                override suspend fun cancelAsk(threadId: String) = Unit
            })
            gateway.start(channel)
            server.enqueue(response("""{"get_updates_buf":"next","msgs":[
                {"message_id":"reply-1","from_user_id":"owner","message_type":1,"item_list":[{"type":1,"text_item":{"text":"答案"}}]}
            ]}"""))

            channel.pollOnce()

            assertEquals(listOf("answer:答案"), events)
            assertEquals(emptyList<PendingWechatMessage>(), store.pending())
            assertFalse(store.journal("reply-1", peer, "答案", replyToAskId = "ask-1"))
        }
    }

    @Test fun acknowledgedMessageIsNotJournaledAgainWhenServerRepeatsItAfterReopen() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val dir = tmp.newFolder()
            val first = WechatStore(dir, cipher)
            first.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            first.setEnabled(true)
            val channel = WechatChannel(first, WechatApi(OkHttpClient(), "0.1.35"), log = {})
            val received = mutableListOf<ChannelInput>()
            val sink = ChannelInputSink { received += it }
            channel.start(sink)
            val update = """{"get_updates_buf":"next","msgs":[{"message_id":1,"from_user_id":"owner","message_type":1,"item_list":[{"type":1,"text_item":{"text":"任务"}}]}]}"""
            server.enqueue(response(update))
            channel.pollOnce()
            channel.acknowledge("1")

            val recoveredStore = WechatStore(dir, cipher)
            val recovered = WechatChannel(recoveredStore, WechatApi(OkHttpClient(), "0.1.35"), log = {})
            recovered.start(sink)
            server.enqueue(response(update))
            recovered.pollOnce()

            assertEquals(1, received.size)
            assertEquals("next", recoveredStore.cursor())
        }
    }

    @Test fun emptyPollDoesNotStopLaterMessageDelivery() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.35"), log = {})
            val received = mutableListOf<ChannelInput>()
            channel.start(ChannelInputSink { received += it })
            server.enqueue(response("""{"get_updates_buf":"empty","msgs":[]}"""))
            server.enqueue(response("""{"get_updates_buf":"message","msgs":[{"message_id":2,"from_user_id":"owner","message_type":1,"item_list":[{"type":1,"text_item":{"text":"下一条"}}]}]}"""))

            channel.pollOnce()
            channel.pollOnce()

            assertEquals(listOf("2"), received.map { (it as ChannelInput.Text).messageId })
            assertEquals("message", store.cursor())
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun stoppingAndRestartingPollingDoesNotBlockResultDelivery() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.35"), log = {})
            val peer = store.peer("owner")
            val sink = ChannelInputSink { }
            val registration = channel.start(sink)
            registration.close()
            server.enqueue(response("""{"ret":0}"""))

            assertEquals(SendOutcome.Delivered, channel.send(peer, Delivery.Result("result:paused", peer.threadId, "暂停时完成")))

            channel.start(sink)
            server.enqueue(response("""{"ret":0}"""))
            assertEquals(SendOutcome.Delivered, channel.send(peer, Delivery.Result("result:resumed", peer.threadId, "恢复后完成")))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun cancellingLongPollCancelsTheHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.35"), log = {})
            channel.start(ChannelInputSink { })
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.Default) { channel.pollOnce() }
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))

            withTimeout(1_000) { job.cancelAndJoin() }

            assertTrue(job.isCancelled)
            assertEquals("", store.cursor())
        }
    }

    @Test fun responseFromPreviousAccountCannotAdvanceNewAccountState() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("old-token", server.url("/").toString(), "bot", "old-owner"))
            store.setEnabled(true)
            store.saveCursor("old-cursor")
            val oldPeer = store.peer("old-owner")
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.35"), log = {})
            val received = mutableListOf<ChannelInput>()
            channel.start(ChannelInputSink { received += it })
            server.enqueue(response("""{"get_updates_buf":"stale","msgs":[{"message_id":1,"from_user_id":"old-owner","message_type":1,"item_list":[{"type":1,"text_item":{"text":"旧账号"}}]}]}""").setHeadersDelay(300, TimeUnit.MILLISECONDS))
            val oldPoll = launch(Dispatchers.Default) { channel.pollOnce() }
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            store.saveAccount(WechatAccount("new-token", server.url("/").toString(), "bot", "new-owner"))
            store.setEnabled(true)
            oldPoll.join()

            assertEquals("", store.cursor())
            assertEquals(emptyList<ChannelInput>(), received)
            assertFalse(channel.isAlive(oldPeer))
            server.enqueue(response("""{"get_updates_buf":"new","msgs":[{"message_id":2,"from_user_id":"new-owner","message_type":1,"item_list":[{"type":1,"text_item":{"text":"新账号"}}]}]}"""))
            channel.pollOnce()
            assertEquals(listOf("2"), received.map { (it as ChannelInput.Text).messageId })
            assertEquals("new", store.cursor())
        }
    }

    @Test fun acceptanceNoticeIsSentAsARealMessage() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.35"), log = {})
            val peer = store.peer("owner")
            server.enqueue(response("""{"ret":0}"""))

            assertEquals(SendOutcome.Delivered,
                channel.send(peer, Delivery.Notice("accepted:1", peer.threadId, "收到，任务已登记。")))
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("accepted:1"))
            assertTrue(body.contains("收到，任务已登记。"))
        }
    }

    @Test fun sendDistinguishesRejectionFromUncertainNetworkResult() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.35"), log = {})
            val peer = store.peer("owner")
            val delivery = Delivery.Result("result:1", peer.threadId, "完成")
            server.enqueue(response("""{"ret":-1,"errmsg":"rejected"}"""))
            assertEquals(SendOutcome.Failed, channel.send(peer, delivery))
            server.enqueue(response("""{"ret":0}""").setResponseCode(500))
            assertEquals(SendOutcome.Uncertain, channel.send(peer, delivery))
        }
    }
}
