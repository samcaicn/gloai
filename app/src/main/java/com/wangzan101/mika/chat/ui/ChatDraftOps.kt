package com.wangzan101.mika.chat.ui
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.chat.SharedDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import com.wangzan101.mika.chat.ui.ChatReduce.state

/** 输入框草稿与系统分享进来的草稿。 */
internal class ChatDraftOps(private val vm: ChatViewModel, private val session: ChatSession) {
    private val _ui = session._ui
    private val drafts = session.drafts
    private var draftStateChanged by session::draftStateChanged
    private var sharedDraftSerial by session::sharedDraftSerial
    private val draftConversations get() = vm.draftConversations
    private var liveIndex by session::liveIndex
    private var lingerJob by session::lingerJob
    private var pendingStart by session::pendingStart
    private var screenSeen by session::screenSeen
    private var pipClosed by session::pipClosed
    private val store = vm.store
    private val sharedDrafts = vm.sharedDrafts

    fun withDrafts(history: List<ConversationSummary>): List<ConversationSummary> =
        draftConversations.value.filter { draft -> history.none { it.id == draft.id } }.map {
            it.copy(title = store.title(it.id) ?: it.title, pinnedAt = store.pinnedAt(it.id),
                page = store.pageOrigin(it.id), branchOf = store.branchOrigin(it.id), archivedAt = store.archivedAt(it.id),
                restoredAt = store.restoredAt(it.id), projectId = store.projectId(it.id) ?: it.projectId)
        } + history

    fun input(text: String) {
        if (text == _ui.value.input) return
        val id = session.draftConversation()
        val currentShared = _ui.value.sharedDraft
        val updatedShared = currentShared?.copy(text = text)?.takeIf { it.text.isNotBlank() || it.attachments.isNotEmpty() }
        if (currentShared != null) {
            if (updatedShared == null) sharedDrafts?.clear(id)
            else sharedDrafts?.save(updatedShared)
        }
        _ui.update { it.copy(input = text, sharedDraft = updatedShared) }
    }

    /** 和原输入框一样通过 Android saved state 恢复文字;附件授权仍只在当前进程保留。 */
    internal fun saveDraftTexts(): List<String> {
        val ui = _ui.value
        val texts = drafts.value.mapValues { it.value.input } + listOfNotNull(ui.conversationId?.let { it to ui.input }).toMap()
        return listOf(ui.conversationId.orEmpty()) + texts.flatMap { (id, text) -> listOf(id, text) }
    }

    internal fun restoreDraftTexts(saved: List<String>) {
        // Activity 重建时进程里的状态更新得更近,不拿旧快照盖回去。
        if (draftStateChanged || saved.isEmpty()) return
        draftStateChanged = true
        saved.drop(1).chunked(2).filter { it.size == 2 }.forEach { (id, text) ->
            session.updateDraft(id) { it.copy(input = text) }
        }
        val id = saved.first().takeIf { it.isNotEmpty() } ?: return
        if (_ui.value.conversationId == null) {
            _ui.value = session.restoreDraft(_ui.value.copy(conversationId = id, exchanges = session.saved(id)))
            session.markCurrent(CurrentChat.Open(id))
        }
    }

    /**
     * 系统分享进入对话草稿:只预填输入框/附件,分享内容本身绝不启动任务。
     * - [inCurrentConversation] 为 false(界面未在后台存活时的冷启动分享):进入全新的对话草稿,旧对话正在跑的任务原地保留。
     * - [inCurrentConversation] 为 true(界面已在后台存活):放入当前已打开的会话;若当前会话已有草稿则挂入 [ChatUi.pendingSharedDraft],
     *   由界面弹窗让用户选择「替换」或「取消」。
     */
    fun openSharedDraft(
        prepared: SharedDraft,
        inCurrentConversation: Boolean = false,
        rejected: List<AttachmentFailure> = emptyList(),
        overLimit: Int = 0,
    ): Boolean {
        val drafts = sharedDrafts ?: return false
        session.refreshParallel()
        if (inCurrentConversation) {
            val hasUnsentDraft = !_ui.value.importing && _ui.value.draft().hasContent
            if (!hasUnsentDraft) return applySharedDraftToCurrent(prepared, rejected, overLimit)
            val id = session.draftConversation()
            val pending = prepared.copy(conversationId = id)
            // Replacing the pending draft and cleaning up its old attachments happen after the new draft is saved.
            if ((pending.attachments.isNotEmpty() && session.sharedAttachments(pending).size != pending.attachments.size) || !drafts.savePending(pending)) {
                drafts.discard(pending.attachments)
                return false
            }
            session.pendingSharedFailures = rejected
            session.pendingSharedOverLimit = overLimit
            _ui.update { it.copy(pendingSharedDraft = pending) }
            return true
        }
        if (pendingStart?.isActive == true &&
            (_ui.value.conversationId == null || _ui.value.exchanges.getOrNull(liveIndex) == null)
        ) {
            drafts.discard(prepared.attachments)
            return false
        }
        val id = store.newConversation()
        val (draft, attachments) = persistSharedDraft(id, prepared) ?: return false
        cancelPendingSharedDraft()
        session.keepDraft()
        val previous = _ui.value
        if (previous.conversationId != id) vm.live.parkLive()
        // 原任务还在等服务收尾时,直接按服务既有的排队能力交过去;它的开始和插话不能跟着新草稿走。
        vm.sending.handOffPendingStart()
        lingerJob?.cancel()
        liveIndex = -1
        screenSeen = false
        pipClosed = false
        _ui.update {
            it.rebuildForConversation(
                conversationId = id,
                sharedDraft = draft,
                input = draft.text,
                attachments = attachments,
            ).copy(rejectedAttachments = rejected, overLimit = overLimit)
        }
        session.markCurrent(CurrentChat.Open(id))
        return true
    }

    /** 用户在替换草稿弹窗里点了「替换」:用待确认的分享内容覆盖当前会话草稿。 */
    fun confirmPendingSharedDraft(): Boolean {
        val pending = _ui.value.pendingSharedDraft ?: return false
        val failures = session.pendingSharedFailures
        val overLimit = session.pendingSharedOverLimit
        return applySharedDraftToCurrent(pending, failures, overLimit)
    }

    /** 用户在替换草稿弹窗里点了「取消」或关闭弹窗:保留原草稿,清掉这次分享的临时附件副本。 */
    fun cancelPendingSharedDraft() {
        val pending = _ui.value.pendingSharedDraft
        session.pendingSharedFailures = emptyList()
        session.pendingSharedOverLimit = 0
        if (pending != null) {
            _ui.update { if (it.pendingSharedDraft == pending) it.copy(pendingSharedDraft = null) else it }
        }
        sharedDrafts?.clearPending()
        if (pending != null) sharedDrafts?.discard(pending.attachments)
    }

    private fun applySharedDraftToCurrent(
        prepared: SharedDraft,
        rejected: List<AttachmentFailure>,
        overLimit: Int,
    ): Boolean {
        val id = _ui.value.conversationId ?: store.newConversation()
        val (draft, attachments) = persistSharedDraft(id, prepared) ?: return false
        cancelPendingSharedDraft()
        draftStateChanged = true
        sharedDraftSerial++
        _ui.update {
            it.copy(
                conversationId = id,
                input = draft.text,
                attachments = attachments,
                sharedDraft = draft,
                quote = null,
                rejectedAttachments = rejected,
                overLimit = overLimit,
                pendingSharedDraft = null,
            )
        }
        session.releaseUnusedLibraryCopies()
        session.markCurrent(CurrentChat.Open(id))
        return true
    }

    private fun persistSharedDraft(id: String, prepared: SharedDraft): Pair<SharedDraft, List<PendingAttachment>>? {
        val drafts = sharedDrafts ?: return null
        val draft = prepared.copy(conversationId = id)
        val attachments = session.sharedAttachments(draft)
        if ((draft.attachments.isNotEmpty() && attachments.size != draft.attachments.size) || !drafts.save(draft)) {
            drafts.discard(draft.attachments)
            return null
        }
        return draft to attachments
    }

    fun discardSharedDraft(target: SharedDraft? = _ui.value.sharedDraft) {
        val draft = target ?: return
        val isCurrent = _ui.value.sharedDraft == draft || drafts.value[draft.conversationId]?.sharedDraft == draft
        if (!isCurrent) return
        val sharedUris = draft.attachments.mapNotNull(session::attachmentUri).toSet()
        sharedDrafts?.clear(draft.conversationId)
        _ui.update { current ->
            if (current.sharedDraft == draft) current.copy(
                sharedDraft = null,
                // 私有分享附件随草稿一起删掉;普通待发附件仍属于全局输入框。
                attachments = current.attachments.filterNot { it.uri in sharedUris },
            ) else current
        }
        drafts.update { saved ->
            val existing = saved[draft.conversationId]
            if (existing?.sharedDraft != draft) saved
            else {
                val next = existing.copy(sharedDraft = null, attachments = existing.attachments.filterNot { it.uri in sharedUris })
                if (next.hasContent) saved + (draft.conversationId to next) else saved - draft.conversationId
            }
        }
    }
}
