package com.wangzan101.mika.wechat

import android.util.Log
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.ChannelInput
import com.wangzan101.mika.core.run.ChannelInputSink
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.core.cap.wechat.WechatApi
import com.wangzan101.mika.core.cap.wechat.WechatApiException
import com.wangzan101.mika.core.cap.wechat.WechatAccount
import com.wangzan101.mika.core.cap.wechat.WechatUserContent
import com.wangzan101.mika.core.cap.wechat.WechatUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** 微信只适配收发；主人校验、提问路由和任务执行都在通用渠道层。 */
class WechatChannel(
    private val store: WechatStore,
    private val api: WechatApi,
    private val activeAsk: (String) -> Delivery.Ask? = { null },
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val imageFiles: WechatImageFiles? = null,
    private val imageFailureText: () -> String = { "The images could not be received. No task was started. Please resend fewer or smaller images." },
    private val reportImageFailure: (suspend (ChannelPeer, Delivery.Notice) -> Unit)? = null,
) : Channel {
    override val id = ID
    override val confirms = false
    override val supportsImages = true
    @Volatile private var sink: ChannelInputSink? = null
    private val pollLock = Mutex()

    override fun isAlive(peer: ChannelPeer): Boolean {
        return store.accepts(peer)
    }

    override fun start(sink: ChannelInputSink): AutoCloseable {
        this.sink = sink
        return AutoCloseable { if (this.sink === sink) this.sink = null }
    }

    override fun acknowledge(messageId: String) = store.acknowledge(messageId)

    override suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome {
        if (peer.channelId != id || !isAlive(peer)) return SendOutcome.Failed
        val account = store.account() ?: return SendOutcome.Failed
        if (delivery is Delivery.Image) return sendImage(account, peer, delivery)
        val text = when (delivery) {
            is Delivery.Ask -> delivery.text
            is Delivery.Result -> delivery.text
            is Delivery.Notice -> delivery.text
            is Delivery.Progress -> delivery.text
            is Delivery.Image -> error("handled above")
        }
        return withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            val outcome = try {
                // 与图片路径同款复查:排队到真正调 API 之间暂停或换绑,旧投递不能发出。
                if (!store.accepts(peer, account)) return@withContext SendOutcome.Failed
                api.sendText(account, peer.peerId, delivery.id, text, store.contextToken(peer.peerId))
                SendOutcome.Delivered
            } catch (e: CancellationException) {
                throw e
            } catch (_: WechatApiException) {
                SendOutcome.Failed
            } catch (_: IOException) {
                SendOutcome.Uncertain
            }
            log("${delivery::class.simpleName} send $outcome in ${(System.nanoTime() - started) / 1_000_000} ms")
            outcome
        }
    }

    private suspend fun sendImage(account: WechatAccount, peer: ChannelPeer, delivery: Delivery.Image): SendOutcome =
        withContext(Dispatchers.IO) {
            val files = imageFiles ?: return@withContext SendOutcome.Failed
            // Nothing is visible in the chat until sendImage. An upload failure is a definite failure.
            val uploaded = try {
                val bytes = files.read(delivery)
                if (!store.accepts(peer, account)) return@withContext SendOutcome.Failed
                api.uploadImage(account, peer.peerId, bytes)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return@withContext SendOutcome.Failed }
            // Uploads may take time. Never send a completed old upload after pause/unbind/rebinding.
            if (!store.accepts(peer, account)) return@withContext SendOutcome.Failed
            try {
                api.sendImage(account, peer.peerId, delivery.id, uploaded, store.contextToken(peer.peerId))
                SendOutcome.Delivered
            } catch (e: CancellationException) { throw e }
            catch (_: WechatApiException) { SendOutcome.Failed }
            catch (_: IOException) { SendOutcome.Uncertain }
        }

    /** 先补交上次未登记的消息，再取新消息；任务登记处做最终幂等。 */
    suspend fun pollOnce(): WechatUpdates? = pollLock.withLock {
        val input = sink ?: return@withLock null
        val account = store.account()?.takeIf { store.enabled() } ?: return@withLock null
        store.pending().forEach { pending ->
            if (!current(account, input) || !store.accepts(pending.peer, account)) return@withLock null
            dispatchPending(pending, account, input)
        }
        val updates = api.updatesCancellable(account, store.cursor())
        // 换绑或停止后，旧请求的结果不能再写入新会话，也不能推进新账号的游标。
        if (store.account() != account || sink !== input || !store.enabled()) return@withLock null
        updates.msgs.forEach { message ->
            val owner = account.ownerId
            if (message.fromUserId != owner) return@forEach
            val content = message.userContent() ?: return@forEach
            val messageId = message.serverMessageId() ?: return@forEach
            val peer = store.messagePeer(account, message.contextToken) ?: return@withLock null
            // Check before downloading/importing: replay must reuse the journal's workspace paths.
            if (store.hasMessage(messageId)) return@forEach
            if (store.account() != account || sink !== input || !store.enabled()) return@withLock null
            val askId = activeInputAskId(peer.threadId)
            val images = resolveImages(content, peer, account)
            if (images != null && images.isEmpty()) {
                if (content.text.isBlank()) return@forEach
                store.journal(messageId, peer, content.text, askId, expectedAccount = account,
                    requireMatchingAsk = false, sourceImages = emptyList())
            } else {
                try {
                    if (images == null) throw IOException("The original quoted message is unavailable")
                    val files = imageFiles ?: throw IOException("Image storage unavailable")
                    files.receive(peer, images) { stored ->
                        if (!current(account, input)) return@receive false
                        if (askId != null && activeInputAskId(peer.threadId) != askId) {
                            store.acknowledge(messageId, account)
                            return@receive false
                        }
                        val text = AttachmentLines.composeBytes(content.text, stored.map { it.path to it.bytes })
                        store.journal(messageId, peer, text, askId, expectedAccount = account,
                            requireMatchingAsk = askId != null,
                            sourceImages = if (content.images.isEmpty()) emptyList() else stored)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (!current(account, input)) return@withLock null
                    store.journal(messageId, peer, imageFailureText(), imageFailure = true, expectedAccount = account)
                }
            }
            if (!current(account, input)) return@withLock null
            store.pending().firstOrNull { it.messageId == messageId }?.let { dispatchPending(it, account, input) }
        }
        if (updates.cursor.isNotEmpty() && current(account, input)) store.saveCursor(updates.cursor, account)
        updates
    }

    /** An unresolved quote must never launch its caption with a guessed attachment. */
    private fun resolveImages(content: WechatUserContent, peer: ChannelPeer, account: WechatAccount): List<WechatImageSource>? {
        if (content.references.isEmpty()) return content.images.map(::DownloadWechatImage)
        return content.references.flatMap { ref ->
            val item = ref.item
            val inline = item?.imageItem
            val media = inline?.media
            when {
                item != null && item.type != 0 && item.type != 2 -> emptyList()
                inline != null && (!media?.fullUrl.isNullOrBlank() || !media?.encryptQueryParam.isNullOrBlank()) ->
                    listOf(DownloadWechatImage(inline))
                else -> ref.targetId()?.let { store.sourceImages(peer, account, it) }
                    ?: return null
            }
        }
    }

    private fun current(account: WechatAccount, input: ChannelInputSink) =
        store.account() == account && sink === input && store.enabled()

    private fun activeInputAskId(threadId: String): String? =
        activeAsk(threadId)?.takeIf { it.kind == Delivery.Ask.Kind.Input }?.askId

    private suspend fun dispatchPending(pending: PendingWechatMessage, account: WechatAccount, input: ChannelInputSink) {
        if (!current(account, input) || !store.accepts(pending.peer, account)) return
        if (pending.imageFailure) {
            val notice = Delivery.Notice("wechat:image-failed:${pending.peer.threadId}:${pending.messageId}",
                pending.peer.threadId, pending.text)
            if (reportImageFailure != null) reportImageFailure.invoke(pending.peer, notice) else send(pending.peer, notice)
            if (current(account, input)) store.acknowledge(pending.messageId, account)
        } else {
            val requireMatchingAsk = pending.requireMatchingAsk ?: (pending.replyToAskId != null &&
                AttachmentLines.parse(pending.text).files.any { (path, _) -> LEGACY_IMAGE_PATH.matches(path) })
            input.receive(ChannelInput.Text(pending.peer, pending.messageId, pending.text,
                pending.replyToAskId, requireMatchingAsk))
        }
    }

    companion object {
        const val ID = "wechat"
        private const val TAG = "MikaWechat"
        private val LEGACY_IMAGE_PATH = Regex("""/workspace/inbox/wechat-\d+(?: \(\d+\))?\.(?:png|jpe?g|gif|webp)""")
    }
}
