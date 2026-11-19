package com.wangzan101.mika.wechat

import com.wangzan101.mika.cap.sandbox.JVM_GATE
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.run.ChannelInput
import com.wangzan101.mika.core.run.ChannelInputSink
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.core.wechat.WechatAccount
import com.wangzan101.mika.core.wechat.WechatApi
import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.TimeUnit

class WechatImageChannelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYPj/HwADAgH/5ncLrgAAAABJRU5ErkJggg==")
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }
    private fun json(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)
    private fun store(server: MockWebServer, dir: File = tmp.newFolder()) = WechatStore(dir, cipher).apply {
        saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
        setEnabled(true)
    }
    private fun api(server: MockWebServer) = WechatApi(OkHttpClient(), "test", cdnBaseUrl = server.url("/cdn").toString())
    private fun update(server: MockWebServer, owner: String = "owner", id: String = "1", caption: String = "") = """{
        "get_updates_buf":"next","msgs":[{"message_id":"$id","from_user_id":"$owner","message_type":1,"context_token":"ctx",
        "item_list":[{"type":1,"text_item":{"text":"$caption"}},{"type":2,"image_item":{"media":{"full_url":"${server.url("/picture")}"}}}]}]}
    """

    @Test fun pictureOnlyMessageDownloadsIntoWorkspaceAndReplaysJournalWithoutDownloadingAgain() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val dir = tmp.newFolder()
            val store = store(server, dir)
            val api = api(server)
            val workspace = tmp.newFolder()
            val files = StoredWechatImages(tmp.newFolder(), api::downloadImage, { PreparedWechatImage(it.bytes, it.extension) },
                { workspace }, JVM_GATE, SandboxTree(), { true }, { null })
            val inputs = mutableListOf<ChannelInput.Text>()
            val channel = WechatChannel(store, api, log = {}, imageFiles = files)
            channel.start(ChannelInputSink { inputs += it as ChannelInput.Text })
            server.enqueue(json(update(server)))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(png)))
            channel.pollOnce()
            assertEquals("next", store.cursor())
            val text = inputs.single().text
            val path = AttachmentLines.parse(text).files.single().first
            assertArrayEquals(png, File(workspace, path.removePrefix("/workspace/")).readBytes())
            assertEquals(text, WechatStore(dir, cipher).pending().single().text)

            val reopened = WechatStore(dir, cipher)
            val recovered = WechatChannel(reopened, api, log = {}, imageFiles = files)
            recovered.start(ChannelInputSink { inputs += it as ChannelInput.Text; recovered.acknowledge(it.messageId) })
            server.enqueue(json(update(server)))
            recovered.pollOnce()
            assertEquals(listOf(text, text), inputs.map { it.text })
            assertEquals(3, server.requestCount) // poll, image, poll; the repeated image is already acknowledged.
            assertEquals(1, File(workspace, "inbox").listFiles()!!.size)
            assertTrue(reopened.pending().isEmpty())
        }
    }

    @Test fun quotedPictureAndCaptionReachTaskTogether() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val api = api(server)
            val workspace = tmp.newFolder()
            val files = StoredWechatImages(tmp.newFolder(), api::downloadImage, { PreparedWechatImage(it.bytes, it.extension) },
                { workspace }, JVM_GATE, SandboxTree(), { true }, { null })
            val inputs = mutableListOf<ChannelInput.Text>()
            val channel = WechatChannel(store(server), api, log = {}, imageFiles = files)
            channel.start(ChannelInputSink { inputs += it as ChannelInput.Text })
            server.enqueue(json("""{"msgs":[{"message_id":1,"from_user_id":"owner","message_type":1,"item_list":[
                {"type":1,"text_item":{"text":"看看这张图"},"ref_msg":{"message_item":{
                    "type":2,"image_item":{"media":{"full_url":"${server.url("/picture")}"}}}}}]}]}"""))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(png)))
            channel.pollOnce()
            val message = AttachmentLines.parse(inputs.single().text)
            assertEquals("看看这张图", message.words)
            assertEquals(1, message.files.size)
            val file = File(workspace, message.files.single().first.removePrefix("/workspace/"))
            assertArrayEquals(png, file.readBytes())
        }
    }

    @Test fun idOnlyQuoteResolvesEarlierImageAfterRestartAndRejectsReplacement() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val api = api(server)
            val dir = tmp.newFolder()
            val workspace = tmp.newFolder()
            val files = StoredWechatImages(tmp.newFolder(), api::downloadImage, { PreparedWechatImage(it.bytes, it.extension) },
                { workspace }, JVM_GATE, SandboxTree(), { true }, { null })
            val inputs = mutableListOf<ChannelInput.Text>()
            val failures = mutableListOf<Delivery.Notice>()
            fun start(state: WechatStore): WechatChannel {
                val channel = WechatChannel(state, api, log = {}, imageFiles = files,
                    reportImageFailure = { _, notice -> failures += notice })
                channel.start(ChannelInputSink { inputs += it as ChannelInput.Text; channel.acknowledge(it.messageId) })
                return channel
            }

            // Neither a client id nor an adjacent image is a substitute for the server's original id.
            val idA = "18446744073709551615"
            val first = start(store(server, dir))
            server.enqueue(json(update(server).replace("\"message_id\":\"1\",", "")
                .replace("\"type\":2", "\"type\":2,\"msg_id\":$idA")))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(png)))
            first.pollOnce()
            val other = png + 42.toByte()
            server.enqueue(json(update(server, id = "B")))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(other)))
            first.pollOnce()

            val afterRestart = start(WechatStore(dir, cipher))
            fun quote(id: String, reference: String) = json("""{"msgs":[{"message_id":"$id","from_user_id":"owner",
                "message_type":1,"item_list":[{"type":1,"text_item":{"text":"read the card"},"ref_msg":$reference}]}]}""")
            server.enqueue(quote("quote-a", """{"svr_id":$idA,"message_item":{"msg_id":"B"},"title":"image"}"""))
            afterRestart.pollOnce()
            val firstPath = AttachmentLines.parse(inputs.first().text).files.single().first
            val quoted = AttachmentLines.parse(inputs.last().text)
            assertEquals("read the card", quoted.words)
            assertEquals(firstPath, quoted.files.single().first)
            val original = File(workspace, firstPath.removePrefix("/workspace/"))
            assertArrayEquals(png, original.readBytes())
            assertEquals(2, File(workspace, "inbox").listFiles()!!.size)

            // Android WeChat sent item type 0 and the original top-level message id inside the quoted item.
            server.enqueue(quote("android", """{"message_item":{"type":0,"msg_id":$idA}}"""))
            afterRestart.pollOnce()
            assertEquals(firstPath, AttachmentLines.parse(inputs.last().text).files.single().first)

            // Even a same-size replacement must not be delivered as the original.
            original.writeBytes(png.clone().also { it[it.lastIndex] = 0 })
            server.enqueue(quote("changed", """{"message_item":{"type":2,"msg_id":"$idA"}}"""))
            afterRestart.pollOnce()
            server.enqueue(quote("unknown", """{"svr_id":"absent"}"""))
            afterRestart.pollOnce()
            assertEquals(4, inputs.size)
            assertEquals(2, failures.size)
            assertEquals(8, server.requestCount)
        }
    }

    @Test fun missingPictureProducesOneDurableFailureNoticeAndNeverStartsCaptionAsTask() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = store(server)
            val failures = mutableListOf<Delivery.Notice>()
            val files = fakeFiles(receive = { _, _ -> throw IOException("unreadable") })
            val channel = WechatChannel(store, api(server), log = {}, imageFiles = files,
                imageFailureText = { "请重新发送图片" }, reportImageFailure = { _, notice ->
                    assertTrue(store.pending().single().imageFailure)
                    failures += notice
                })
            channel.start(ChannelInputSink { fail("The caption must not execute without its picture") })
            repeat(2) {
                server.enqueue(json(update(server, caption = "按图操作")))
                channel.pollOnce()
            }
            assertEquals(listOf("请重新发送图片"), failures.map { it.text })
            assertTrue(store.pending().isEmpty())
            assertEquals("next", store.cursor())
        }
    }

    @Test fun unboundSenderDoesNotTriggerMediaDownloadAndPausedImportCannotJournal() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = store(server)
            var imports = 0
            val files = fakeFiles(receive = { _, accept -> imports++; store.setEnabled(false); accept(emptyList()) })
            val channel = WechatChannel(store, api(server), log = {}, imageFiles = files)
            channel.start(ChannelInputSink { fail("Paused input must not be routed") })
            server.enqueue(json(update(server, owner = "stranger")))
            channel.pollOnce()
            assertEquals(0, imports)
            store.saveCursor("before")
            server.enqueue(json(update(server)))
            channel.pollOnce()
            assertEquals(1, imports)
            assertTrue(store.pending().isEmpty())
            assertEquals("before", store.cursor())
        }
    }

    @Test fun imageReplyUploadsThenSendsANativeImageWithTheSameContext() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = store(server)
            val peer = store.peer("owner")
            store.rememberContext("owner", "ctx")
            val channel = WechatChannel(store, api(server), log = {}, imageFiles = fakeFiles())
            server.enqueue(json("""{"upload_full_url":"${server.url("/cdn/upload")}"}"""))
            server.enqueue(MockResponse().setHeader("x-encrypted-param", "download-reference"))
            server.enqueue(json("""{"ret":0}"""))
            assertEquals(SendOutcome.Delivered, channel.send(peer,
                Delivery.Image("result:1:image:0", peer.threadId, "content://media/external/downloads/1", "shot.png", png.size.toLong())))
            assertEquals("/ilink/bot/getuploadurl", server.takeRequest().path)
            assertNull(server.takeRequest().getHeader("Authorization"))
            val sent = server.takeRequest().body.readUtf8()
            assertTrue(sent.contains("\"image_item\""))
            assertTrue(sent.contains("\"context_token\":\"ctx\""))
            assertTrue(sent.contains("\"client_id\":\"result:1:image:0\""))
            assertFalse(sent.contains("\"text_item\""))
        }
    }

    @Test fun rejectedGroupMessageCannotOverwriteThePrivateReplyToken() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = store(server)
            store.peer("owner")
            store.rememberContext("owner", "private-context")
            val channel = WechatChannel(store, api(server), log = {}, imageFiles = fakeFiles())
            channel.start(ChannelInputSink { fail("Group message must not enter a private task") })
            server.enqueue(json(update(server).replace("\"context_token\":\"ctx\"", "\"group_id\":\"group\",\"context_token\":\"group-context\"")))
            channel.pollOnce()
            assertEquals("private-context", store.contextToken("owner"))
            val oldAccount = store.account()!!
            store.saveAccount(oldAccount.copy(botId = "new-bot"))
            store.setEnabled(true)
            assertNull(store.messagePeer(oldAccount, "stale"))
            assertNull(store.contextToken("owner"))
        }
    }

    @Test fun uploadFailureIsDefiniteButNetworkFailureDuringSendIsUncertain() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = store(server)
            val peer = store.peer("owner")
            val channel = WechatChannel(store, api(server), log = {}, imageFiles = fakeFiles())
            val image = Delivery.Image("result:1:image:0", peer.threadId, "content://media/external/downloads/1", "shot.png", png.size.toLong())
            server.enqueue(json("""{"ret":-1,"errmsg":"no upload"}"""))
            assertEquals(SendOutcome.Failed, channel.send(peer, image))
            server.enqueue(json("""{"upload_full_url":"${server.url("/cdn/upload")}"}"""))
            server.enqueue(MockResponse().setHeader("x-encrypted-param", "reference"))
            server.enqueue(MockResponse().setResponseCode(500))
            assertEquals(SendOutcome.Uncertain, channel.send(peer, image))
        }
    }

    @Test fun rebindingDuringUploadPreventsImageSendToTheOldSession() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = store(server)
            val peer = store.peer("owner")
            val channel = WechatChannel(store, api(server), log = {}, imageFiles = fakeFiles())
            server.enqueue(json("""{"upload_full_url":"${server.url("/cdn/upload")}"}"""))
            server.enqueue(MockResponse().setHeader("x-encrypted-param", "reference").setHeadersDelay(300, TimeUnit.MILLISECONDS))
            val outcome = CompletableDeferred<SendOutcome>()
            val job = launch(Dispatchers.Default) { outcome.complete(channel.send(peer,
                Delivery.Image("result:1:image:0", peer.threadId, "content://media/external/downloads/1", "shot.png", png.size.toLong()))) }
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            store.saveAccount(WechatAccount("new-token", server.url("/").toString(), "other-bot", "owner"))
            store.setEnabled(true)
            job.join()
            assertEquals(SendOutcome.Failed, outcome.await())
            assertEquals(2, server.requestCount)
            assertFalse(channel.isAlive(peer))
        }
    }

    private fun fakeFiles(receive: suspend (List<WechatImageSource>, (List<SavedWechatImage>) -> Boolean) -> Boolean = { _, accept -> accept(emptyList()) }) =
        object : WechatImageFiles {
            override suspend fun receive(peer: ChannelPeer, images: List<WechatImageSource>, accept: (List<SavedWechatImage>) -> Boolean) = receive(images, accept)
            override suspend fun read(image: Delivery.Image) = png
        }
}
