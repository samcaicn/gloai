package com.wangzan101.mika.chat.ui
import com.wangzan101.mika.chat.SharedDraft
import com.wangzan101.mika.chat.SharedDraftAttachment
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.cap.sandbox.AttachmentFailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.wangzan101.mika.files.ui.LibraryAddPlan
import com.wangzan101.mika.files.ui.LibraryAttachmentResult
import com.wangzan101.mika.files.ui.LibraryFileHandle
import com.wangzan101.mika.files.ui.LibraryMergeResult
import com.wangzan101.mika.files.ui.LibraryStaged

/** 输入框上方的附件:相机相册文件、设备文件、文件库。 */
internal class ChatAttachmentOps(private val vm: ChatViewModel, private val session: ChatSession) {
    private val _ui = session._ui
    private val drafts = session.drafts
    private var draftStateChanged by session::draftStateChanged
    private val libraryCopies = session.libraryCopies
    private val librarySources = session.librarySources
    private val libraryImports = session.libraryImports
    private val deviceImports = session.deviceImports
    private var sharedDraftSerial by session::sharedDraftSerial
    private val _deleted = session._deleted
    private val deleting = session.deleting
    private val scope = vm.scope
    private val activeConversations = vm.activeConversations
    private val sandboxReady = vm.sandboxReady
    private val describeAttachments = vm.describeAttachments
    private val sharedDrafts = vm.sharedDrafts

    /** 从相机、相册、文件选择器拿到的文件:能加附件时(见 [ChatSession.canAttach])、沙箱就绪时才收;读出名字和大小后挂在输入框上方,随下一句话发出去。 */
    fun attach(uris: List<String>, conversationId: String? = _ui.value.conversationId) {
        if (uris.isEmpty() || conversationId != _ui.value.conversationId || !session.canAttach(_ui.value)) return
        val id = session.draftConversation()
        val sharedBefore = _ui.value.sharedDraft
        val serialBefore = sharedDraftSerial
        // 不走前台时(没接前台、子任务的对话)附件随新任务直接开:读文件期间这条会话开始了任务就不收了;走前台时有没有任务在跑都收
        val wasIdle = vm.deskFor(id) == null && _ui.value.composer == ComposerMode.Idle
        scope.launch {
            val items = try { describeAttachments(uris) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            if (wasIdle && _ui.value.composer != ComposerMode.Idle) return@launch
            if (_ui.value.conversationId == id && (!session.canAttach(_ui.value) || sharedDraftSerial != serialBefore)) return@launch
            if (_ui.value.conversationId != id && drafts.value[id]?.sharedDraft != sharedBefore) return@launch
            session.updateDraft(id) { draft ->
                val (all, dropped) = AttachTray.add(draft.attachments, items)
                draft.copy(attachments = all, overLimit = dropped, rejected = emptyList())
            }
        }
    }

    /** Copy selected device files into the conversation that opened the browser. */
    internal fun importDeviceAttachments(conversationId: String, paths: List<String>,
        copy: suspend (String, String) -> com.wangzan101.mika.core.workspace.DeviceFileImport) {
        if (_ui.value.conversationId != conversationId || !session.canAttach(_ui.value) || paths.isEmpty()) return
        val slots = (AttachTray.LIMIT - _ui.value.attachments.size).coerceAtLeast(0)
        val selected = paths.distinct().take(slots)
        val dropped = (paths.distinct().size - selected.size).coerceAtLeast(0)
        if (selected.isEmpty()) {
            session.updateDraft(conversationId) { it.copy(overLimit = dropped) }
            return
        }
        session.updateDraft(conversationId) { it.copy(importing = true, rejected = emptyList(), overLimit = dropped) }
        val job = scope.launch {
            val added = mutableListOf<PendingAttachment>()
            val failures = mutableListOf<AttachmentFailure>()
            try {
                for (path in selected) {
                    if (conversationId in _deleted.value || conversationId in deleting) break
                    try {
                        val result = copy(path, conversationId)
                        added += PendingAttachment("workspace:$conversationId:${result.path}", result.path.substringAfterLast('/'), result.size,
                            workspaceConversationId = conversationId, workspacePath = result.path)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        val reason = when ((e as? com.wangzan101.mika.core.workspace.SandboxError)?.code) {
                            "too_large" -> AttachmentFailureReason.TOO_LARGE
                            "low_device_space" -> AttachmentFailureReason.LOW_DEVICE_SPACE
                            "workspace_limit" -> AttachmentFailureReason.WORKSPACE_LIMIT
                            else -> AttachmentFailureReason.IO_ERROR
                        }
                        failures += AttachmentFailure(path.substringAfterLast('/'), reason)
                    }
                }
            } finally {
                session.updateDraft(conversationId) { draft ->
                    val (merged, overflow) = AttachTray.add(draft.attachments, added)
                    draft.copy(importing = false, attachments = merged, rejected = failures, overLimit = draft.overLimit + overflow)
                }
            }
        }
        deviceImports[conversationId] = job
        job.invokeOnCompletion { deviceImports.remove(conversationId, job) }
    }

    /** 为当前新会话分配草稿编号；调用方在主线程调用。 */
    fun libraryTargetConversation(): String = session.draftConversation()

    /** 目标草稿此刻能不能收文件库的文件；不能时返回 null。调用方在主线程调用。 */
    private fun libraryDraft(conversationId: String): ComposerDraft? {
        if (conversationId.isBlank() || conversationId in _deleted.value || conversationId in deleting || !sandboxReady.value)
            return null
        val current = _ui.value.takeIf { it.conversationId == conversationId }
        if (current != null && !session.canAttach(current)) return null
        val draft = current?.draft() ?: drafts.value[conversationId] ?: ComposerDraft()
        if (draft.importing || (current == null && conversationId in activeConversations.value)) return null
        return draft
    }

    /**
     * 批量加入前的规划:按传入顺序去掉目标草稿里已有的来源,再截到剩余槽位。
     * 不复制文件；合并时会按当时的目标状态再校验一次。调用方在主线程调用。
     */
    fun planLibraryAttachments(conversationId: String, sources: List<LibraryFileHandle>): LibraryAddPlan? {
        val draft = libraryDraft(conversationId) ?: return null
        var slots = (AttachTray.LIMIT - draft.attachments.size).coerceAtLeast(0)
        var duplicates = 0
        var overLimit = 0
        val accepted = mutableListOf<LibraryFileHandle>()
        val seen = mutableSetOf<LibraryFileHandle>()
        for (source in sources) {
            val duplicate = !seen.add(source) || draft.attachments.any { librarySources[it.uri] == source }
            when {
                duplicate -> duplicates++
                slots <= 0 -> overLimit++
                else -> {
                    accepted += source
                    slots--
                }
            }
        }
        return LibraryAddPlan(accepted, duplicates, overLimit)
    }

    /**
     * 把已经复制好的一批文件合并到目标草稿；只更新一次草稿。
     * 收下的副本登记在 [libraryCopies] 里，没收下的当场释放。调用方在主线程调用。
     */
    fun addLibraryAttachments(conversationId: String, items: List<LibraryStaged>, overLimit: Int = 0): LibraryMergeResult {
        val draft = libraryDraft(conversationId)
        if (draft == null) {
            items.forEach { runCatching { it.release() } }
            return LibraryMergeResult(0, 0, 0, unavailable = true)
        }
        var attachments = draft.attachments
        var added = 0
        var duplicates = 0
        var full = 0
        val accepted = mutableListOf<LibraryStaged>()
        for (item in items) {
            if (attachments.any { librarySources[it.uri] == item.source }) {
                duplicates++
                runCatching { item.release() }
                continue
            }
            val (merged, dropped) = AttachTray.add(attachments, listOf(item.attachment))
            if (dropped != 0) {
                full++
                runCatching { item.release() }
                continue
            }
            attachments = merged
            added++
            accepted += item
        }
        if (added > 0) {
            draftStateChanged = true
            accepted.forEach { item ->
                libraryCopies.putIfAbsent(item.attachment.uri, item.release)
                librarySources.putIfAbsent(item.attachment.uri, item.source)
            }
            session.updateDraft(conversationId) {
                it.copy(attachments = attachments, rejected = emptyList(), overLimit = overLimit + full)
            }
        }
        return LibraryMergeResult(added, duplicates, full, unavailable = false)
    }

    /** 合并文件库副本到指定会话的草稿，不切换会话或覆盖已有内容。调用方在主线程调用。 */
    fun addLibraryAttachment(conversationId: String, item: PendingAttachment, source: LibraryFileHandle? = null,
                             release: () -> Unit = {}): LibraryAttachmentResult {
        val draft = libraryDraft(conversationId) ?: return LibraryAttachmentResult.Unavailable
        val duplicate = source != null && draft.attachments.any { librarySources[it.uri] == source }
        if (duplicate) {
            val referenced = item.uri in libraryImports || item.uri in libraryCopies ||
                _ui.value.attachments.any { it.uri == item.uri } ||
                drafts.value.values.any { saved -> saved.attachments.any { it.uri == item.uri } }
            if (!referenced) runCatching { release() }
            return LibraryAttachmentResult.Added
        }
        val (items, dropped) = AttachTray.add(draft.attachments, listOf(item))
        if (dropped != 0) return LibraryAttachmentResult.Full
        draftStateChanged = true
        libraryCopies.putIfAbsent(item.uri, release)
        if (source != null) librarySources.putIfAbsent(item.uri, source)
        session.updateDraft(conversationId) { it.copy(attachments = items, rejected = emptyList(), overLimit = 0) }
        return LibraryAttachmentResult.Added
    }

    /** 面板里最近的照片点一下:选上或取消;已经满了返回 false。 */
    fun toggleAttachment(item: PendingAttachment): Boolean {
        val ui = _ui.value
        if (!session.canAttach(ui)) return true
        session.draftConversation()
        val t = AttachTray.toggle(ui.attachments, item)
        if (ui.attachments.any { it.uri == item.uri } && t.items.none { it.uri == item.uri }) removeAttachment(item.uri)
        else _ui.update { it.copy(attachments = t.items) }
        return !t.full
    }

    fun clearSharedAttachments(draft: SharedDraft) {
        val currentText = _ui.value.input
        if (currentText.isBlank()) {
            sharedDrafts?.clear(draft.conversationId)
        } else {
            val updated = draft.copy(text = currentText, attachments = emptyList())
            if (sharedDrafts?.save(updated) != true) sharedDrafts?.removeAttachment(draft.conversationId)
        }
        _ui.update { current ->
            if (current.sharedDraft == draft) {
                current.copy(sharedDraft = draft.copy(text = current.input, attachments = emptyList()).takeIf { it.text.isNotBlank() })
            } else current
        }
    }

    private fun removeSharedAttachment(draft: SharedDraft, target: SharedDraftAttachment) {
        val currentText = _ui.value.input
        val remaining = draft.attachments.filterNot { it.fileName == target.fileName }
        val nextShared = draft.copy(text = currentText, attachments = remaining)
            .takeIf { it.text.isNotBlank() || it.attachments.isNotEmpty() }
        if (nextShared == null) {
            sharedDrafts?.clear(draft.conversationId)
        } else if (sharedDrafts?.save(nextShared) != true) {
            sharedDrafts?.removeAttachment(draft.conversationId, target.fileName)
        }
        _ui.update { current ->
            if (current.sharedDraft == draft) {
                current.copy(sharedDraft = draft.copy(text = current.input, attachments = remaining)
                    .takeIf { it.text.isNotBlank() || it.attachments.isNotEmpty() })
            } else current
        }
    }

    fun removeAttachment(uri: String) {
        val draft = _ui.value.sharedDraft
        val matched = draft?.attachments?.firstOrNull { session.attachmentUri(it) == uri }
        if (draft != null && matched != null) {
            removeSharedAttachment(draft, matched)
        }
        _ui.update { ui -> ui.copy(attachments = ui.attachments.filterNot { it.uri == uri }) }
        session.releaseUnusedLibraryCopies()
    }

    fun dismissRejected() = _ui.update { it.copy(rejectedAttachments = emptyList(), overLimit = 0) }
}
