package com.wangzan101.mika.chat.ui
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.BranchOrigin
import com.wangzan101.mika.chat.RetryAttachmentResult
import com.wangzan101.mika.chat.RetryAttachmentStatus
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.cap.sandbox.AttachmentFailureReason
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.agent.QuoteMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.screen.user.UserScreenMessage

/** 从某一轮分支、从某一句重新问。 */
internal class ChatBranchOps(private val vm: ChatViewModel, private val session: ChatSession) {
    private val _ui = session._ui
    private val deleted get() = vm.deleted
    private val deleting = session.deleting
    private val _branchCopy = session._branchCopy
    private val store = vm.store
    private val activeConversations = vm.activeConversations
    private val clock = vm.clock
    private val branchWorkspace = vm.branchWorkspace
    private val branchTitle = vm.branchTitle
    private val io = vm.io

    /** 屏上这个对话此刻分不了支:还没存过、有任务在跑或排队、已删或正在删、输入框正忙。 */
    private fun branchBlocked(): Boolean {
        val id = _ui.value.conversationId ?: return true
        return id in activeConversations.value || id in deleted.value || id in deleting || session.busy()
    }

    /** 长按的消息 [key] 能「从这里分支」时返回轮次;菜单据此显示,[branchFrom] 用的是同一个判断。 */
    fun branchTurn(key: String): Int? = branchableTurn(_ui.value.exchanges, key, branchBlocked())

    /** 长按的消息 [key] 能「从这里重新问」时返回轮次;菜单据此显示,[branchBefore] 用的是同一个判断。 */
    fun retryTurn(key: String): Int? = retryableTurn(_ui.value.exchanges, key, branchBlocked())

    /** 清掉上次分支复制工作区的提示。 */
    fun clearBranchCopy() {
        _branchCopy.value = null
    }

    /**
     * 从当前对话的第 [throughTurn] 轮(0 起,含这一轮)分支:复制轮次和标题/网页来源,再复制工作区 inbox/,成功后切到新对话。
     * 返回新对话编号;[canBranchAt] 不成立(有任务在跑或排队、正在删、输入框正忙、轮次越界、这一轮还没结束)或复制失败时为 null。
     * 工作区复制的结果(超过上限等)放在 [branchCopy] 里,不当作失败。
     */
    suspend fun branchFrom(throughTurn: Int): String? =
        branchInto(throughTurn, afterTurn = true, allowed = ::canBranchAt)

    /**
     * 从当前对话的第 [turn] 条用户消息「重新问」:复制这一轮之前的轮次,返回新对话编号和要预填的文字,不自动发送。
     * 原消息的附件/屏幕不会带进输入框,用 [BranchRetry.needsReattach] 告诉界面提示重新添加。
     * [canRetryAt] 不成立(同 [branchFrom],另加这一轮用户的话是空的)或复制失败时为 null。
     */
    suspend fun branchBefore(turn: Int): BranchRetry? {
        val exchanges = _ui.value.exchanges
        val raw = exchanges.getOrNull(turn)?.goal.orEmpty()
        fun attachmentPaths(text: String) = AttachmentLines.parse(QuoteMessage.words(AttachedPage.words(text))).files.map { it.first }
        val paths = attachmentPaths(raw).distinct()
        val historyPaths = exchanges.take(turn.coerceAtLeast(0)).flatMap { attachmentPaths(it.goal) }.filterNot { it in paths }.distinct()
        var copied = emptyList<RetryAttachmentResult>()
        val targetId = branchInto(turn, afterTurn = false, allowed = ::canRetryAt, retryPaths = paths + historyPaths) { copied = it } ?: return null
        val restored = copied.take(paths.size).filter { it.status == RetryAttachmentStatus.COPIED && it.sizeBytes != null }
        val attachments = restored.map {
            PendingAttachment("workspace:$targetId:${it.path}", it.path.substringAfterLast('/'), it.sizeBytes!!,
                workspaceConversationId = targetId, workspacePath = it.path)
        }
        val (accepted, _) = AttachTray.add(emptyList(), attachments)
        val failures = copied.filter { it.status != RetryAttachmentStatus.COPIED || it.sizeBytes == null } +
            restored.drop(accepted.size).map { it.copy(status = RetryAttachmentStatus.OVER_LIMIT) }
        val prefill = retryText(raw)
        session.updateDraft(targetId) {
            it.copy(input = prefill, attachments = accepted,
                rejected = if (_ui.value.conversationId == targetId) emptyList() else failures.map { failure ->
                    AttachmentFailure(failure.path.substringAfterLast('/'), AttachmentFailureReason.IO_ERROR)
                })
        }
        val screen = UserScreenMessage.parse(AttachmentMessage.parse(QuoteMessage.words(AttachedPage.words(raw))).words).hasScreen
        return BranchRetry(targetId, prefill, screen || failures.isNotEmpty(), failures, screenNeedsReattach = screen)
    }

    /**
     * [branchFrom] 和 [branchBefore] 共用:[allowed] 成立时新建对话,复制到第 [turn] 轮([afterTurn] 时含这一轮,否则只到它之前)、
     * 标题加后缀、写分支来源,再复制工作区 inbox/,然后切到新对话。返回新对话编号;不允许或复制失败为 null,不切走。
     */
    private suspend fun branchInto(
        turn: Int, afterTurn: Boolean, allowed: (List<Exchange>, Int, Boolean) -> Boolean,
        retryPaths: List<String>? = null, onRetryCopied: (List<RetryAttachmentResult>) -> Unit = {},
    ): String? {
        session.refreshParallel()
        _branchCopy.value = null
        val ui = _ui.value
        val sourceId = ui.conversationId ?: return null
        if (!allowed(ui.exchanges, turn, branchBlocked())) return null
        val targetId = store.newConversation()
        val base = withContext(io) { store.title(sourceId) }?.takeIf { it.isNotBlank() }
            ?: ChatCopy.title(ui.exchanges).ifBlank { tr(R.string.chat_new) }
        val origin = BranchOrigin(sourceId, turn, afterTurn, at = clock())
        val created = withContext(io) {
            if (afterTurn) store.branch(sourceId, targetId, turn, branchTitle(base), origin)
            else store.branchBefore(sourceId, targetId, turn, branchTitle(base), origin)
        }
        if (!created) return null
        if (!retryPaths.isNullOrEmpty()) {
            val failed = retryPaths.map { RetryAttachmentResult(it, RetryAttachmentStatus.FAILED) }
            val results = try {
                branchWorkspace?.copyInboxForRetry(sourceId, targetId, retryPaths) ?: failed
            } catch (e: CancellationException) { throw e } catch (e: Exception) { failed }
            onRetryCopied(if (results.map { it.path } == retryPaths) results else failed)
        } else {
            _branchCopy.value = try { branchWorkspace?.copyInbox(sourceId, targetId) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        }
        if (_ui.value.conversationId == sourceId && !branchBlocked()) vm.chats.openConversation(targetId)
        return targetId
    }
}
