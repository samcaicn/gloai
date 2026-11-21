package com.wangzan101.mika.wechat

import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.wechat.WechatAccount
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class PendingWechatMessage(
    val messageId: String,
    val peer: ChannelPeer,
    val text: String,
    val replyToAskId: String? = null,
    /** Rejected media is a durable notice, never a task with silently missing pictures. */
    val imageFailure: Boolean = false,
    /** Null identifies journal records written before image replies were marked explicitly. */
    val requireMatchingAsk: Boolean? = null,
)

@Serializable
data class SavedWechatImage(val path: String, val bytes: Long, val sha256: String) : WechatImageSource

@Serializable
private data class IndexedWechatMessage(val threadId: String, val messageId: String, val images: List<SavedWechatImage>)

@Serializable
private data class WechatState(
    val enabled: Boolean = false,
    val cursor: String = "",
    val threads: Map<String, String> = emptyMap(),
    val contextTokens: Map<String, String> = emptyMap(),
    val pending: List<PendingWechatMessage> = emptyList(),
    val recentSources: List<IndexedWechatMessage> = emptyList(),
    /** 按确认先后保留最近一段，游标回退或进程恢复时也不会再次登记同一消息。 */
    val acknowledgedMessages: List<String> = emptyList(),
)

@Serializable
private data class WechatUnlockGrant(
    val ownerId: String = "",
    val botId: String = "",
    val allowed: Boolean = false,
)

/** 凭证与运行状态分开保存：令牌加密，游标和未交任务的消息原子落盘。 */
class WechatStore(
    private val dir: File,
    private val cipher: KeyCipher,
    private val unlockFile: File = File(dir, "unlock.json"),
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val credentials = File(dir, "account.enc")
    private val stateFile = File(dir, "state.json")
    @Volatile private var account: WechatAccount? = readAccount()
    private var state = readState()
    private val _allowUnlock = MutableStateFlow(readUnlockAllowed())
    // The switch reports the durable grant. Block local use immediately while a failed revocation can be retried.
    private var revocationPending = false
    val allowUnlock: StateFlow<Boolean> = _allowUnlock.asStateFlow()

    @Synchronized fun account(): WechatAccount? = account

    @Synchronized fun saveAccount(value: WechatAccount) {
        // 撤销独立授权后才碰凭证与账本；后续写失败、进程重启也不能恢复旧授权。
        setUnlockAllowed(false)
        val sameAccount = account?.let { it.ownerId == value.ownerId && it.botId == value.botId } == true
        if (!sameAccount) {
            // 先清旧账号的游标与账本；同账号重绑则保留原来的消息状态。
            state = WechatState()
            saveState()
        }
        writeAtomic(credentials, cipher.encrypt(json.encodeToString(WechatAccount.serializer(), value).toByteArray()))
        account = value
    }

    @Synchronized fun clear() {
        setUnlockAllowed(false)
        account = null
        state = WechatState()
        credentials.delete()
        saveState()
    }

    @Synchronized fun enabled() = state.enabled
    @Synchronized fun setEnabled(value: Boolean) {
        val next = state.copy(enabled = value)
        if (!value) state = next
        try {
            saveState(next)
        } catch (failure: Exception) {
            // 关闭接收没能落盘时，旧 enabled=true 仍在盘上；撤销独立授权，重启也不能解锁。
            if (!value) try { setUnlockAllowed(false) } catch (revokeFailure: Exception) {
                failure.addSuppressed(revokeFailure)
            }
            throw failure
        }
        state = next
    }

    @Synchronized fun unlockAllowed() = _allowUnlock.value && !revocationPending

    /** 优先原子替换授权记录；无法写入时撤权可通过移除原记录完成。 */
    @Synchronized fun setUnlockAllowed(value: Boolean) {
        if (!value) {
            revocationPending = true
            try {
                writeAtomic(unlockFile, json.encodeToString(WechatUnlockGrant.serializer(), WechatUnlockGrant()).toByteArray())
            } catch (failure: Exception) {
                if (!unlockFile.delete() && unlockFile.exists()) throw failure
            }
            _allowUnlock.value = false
            revocationPending = false
            return
        }
        val current = checkNotNull(account) { "Bind WeChat before allowing unlock" }
        val grant = WechatUnlockGrant(current.ownerId, current.botId, allowed = true)
        writeAtomic(unlockFile, json.encodeToString(WechatUnlockGrant.serializer(), grant).toByteArray())
        revocationPending = false
        _allowUnlock.value = true
    }

    /** 每次实际解锁前重验来源，旧账号、旧线程或其他渠道不能沿用授权。 */
    @Synchronized fun canUnlock(peer: ChannelPeer?): Boolean {
        val current = account ?: return false
        return state.enabled && unlockAllowed() && peer != null &&
            peer.channelId == WechatChannel.ID && peer.peerId == current.ownerId &&
            state.threads[current.ownerId] == peer.threadId
    }

    @Synchronized fun cursor() = state.cursor
    @Synchronized fun saveCursor(value: String, expectedAccount: WechatAccount? = null) {
        if (expectedAccount != null && (account != expectedAccount || !state.enabled)) return
        val next = state.copy(cursor = value)
        saveState(next)
        state = next
    }
    @Synchronized fun contextToken(peerId: String) = state.contextTokens[peerId]

    @Synchronized fun accepts(peer: ChannelPeer, expectedAccount: WechatAccount? = null): Boolean {
        val current = account ?: return false
        return (expectedAccount == null || expectedAccount == current) && state.enabled &&
            peer.channelId == WechatChannel.ID && peer.peerId == current.ownerId &&
            state.threads[peer.peerId] == peer.threadId
    }

    @Synchronized fun hasMessage(messageId: String) =
        messageId in state.acknowledgedMessages || state.pending.any { it.messageId == messageId }

    @Synchronized fun rememberContext(peerId: String, token: String?, expectedAccount: WechatAccount? = null) {
        if (expectedAccount != null && (account != expectedAccount || !state.enabled || expectedAccount.ownerId != peerId)) return
        if (token.isNullOrBlank() || state.contextTokens[peerId] == token) return
        val next = state.copy(contextTokens = state.contextTokens + (peerId to token))
        saveState(next)
        state = next
    }

    @Synchronized fun peer(peerId: String): ChannelPeer {
        val thread = state.threads[peerId] ?: "wechat-${UUID.randomUUID()}".also {
            val next = state.copy(threads = state.threads + (peerId to it))
            saveState(next)
            state = next
        }
        return ChannelPeer(WechatChannel.ID, peerId, thread)
    }

    /** Resolve the private conversation and refresh its reply token under the same binding check. */
    @Synchronized fun messagePeer(expectedAccount: WechatAccount, token: String?): ChannelPeer? {
        if (account != expectedAccount || !state.enabled) return null
        val owner = expectedAccount.ownerId
        val thread = state.threads[owner] ?: "wechat-${UUID.randomUUID()}"
        val next = state.copy(threads = state.threads + (owner to thread),
            contextTokens = if (token.isNullOrBlank()) state.contextTokens else state.contextTokens + (owner to token))
        if (next != state) {
            saveState(next)
            state = next
        }
        return ChannelPeer(WechatChannel.ID, owner, thread)
    }

    /** 同一消息只入站一次；未确认的会在下次启动由 [pending] 补交。 */
    @Synchronized fun journal(messageId: String, peer: ChannelPeer, text: String, replyToAskId: String? = null,
        imageFailure: Boolean = false, expectedAccount: WechatAccount? = null, requireMatchingAsk: Boolean? = null,
        sourceImages: List<SavedWechatImage>? = null): Boolean {
        if (expectedAccount != null && !accepts(peer, expectedAccount)) return false
        if (hasMessage(messageId)) return false
        val sources = if (sourceImages == null) state.recentSources else
            (state.recentSources + IndexedWechatMessage(peer.threadId, messageId, sourceImages)).takeLast(MAX_ACKNOWLEDGED_MESSAGES)
        val next = state.copy(recentSources = sources, pending = state.pending +
            PendingWechatMessage(messageId, peer, text, replyToAskId, imageFailure, requireMatchingAsk))
        saveState(next)
        state = next
        return true
    }

    @Synchronized fun sourceImages(peer: ChannelPeer, account: WechatAccount, messageId: String): List<SavedWechatImage>? {
        if (!accepts(peer, account)) return null
        return state.recentSources.lastOrNull { it.threadId == peer.threadId && it.messageId == messageId }?.images
    }

    @Synchronized fun pending(): List<PendingWechatMessage> = state.pending

    @Synchronized fun acknowledge(messageId: String, expectedAccount: WechatAccount? = null) {
        if (expectedAccount != null && account != expectedAccount) return
        if (messageId in state.acknowledgedMessages && state.pending.none { it.messageId == messageId }) return
        val next = state.copy(
            pending = state.pending.filterNot { it.messageId == messageId },
            acknowledgedMessages = (state.acknowledgedMessages.filterNot { it == messageId } + messageId)
                .takeLast(MAX_ACKNOWLEDGED_MESSAGES),
        )
        saveState(next)
        state = next
    }

    /** 机主结果推送的投递目标:已启用、有账号、机主的回复令牌与已有线程都在才返回;缺一项就没有可投递的出口,也不为此新建线程。 */
    @Synchronized fun boundOwnerPeer(): ChannelPeer? {
        val current = account ?: return null
        if (!state.enabled) return null
        if (state.contextTokens[current.ownerId].isNullOrBlank()) return null
        val thread = state.threads[current.ownerId] ?: return null
        return ChannelPeer(WechatChannel.ID, current.ownerId, thread)
    }

    private fun readState(): WechatState = if (stateFile.isFile) runCatching {
        json.decodeFromString(WechatState.serializer(), stateFile.readText())
    }.getOrElse { WechatState() } else WechatState()

    private fun readAccount(): WechatAccount? = if (credentials.isFile) runCatching {
        json.decodeFromString(WechatAccount.serializer(), cipher.decrypt(credentials.readBytes()).decodeToString())
    }.getOrNull() else null

    private fun readUnlockAllowed(): Boolean {
        val current = account ?: return false
        if (!unlockFile.isFile) return false
        return runCatching {
            val grant = json.decodeFromString(WechatUnlockGrant.serializer(), unlockFile.readText())
            grant.allowed && grant.ownerId == current.ownerId && grant.botId == current.botId
        }.getOrDefault(false)
    }

    private fun saveState(value: WechatState = state) =
        writeAtomic(stateFile, json.encodeToString(WechatState.serializer(), value).toByteArray())

    private fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        internal const val MAX_ACKNOWLEDGED_MESSAGES = 512
    }
}
