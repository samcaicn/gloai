package com.wangzan101.mika.ui

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.task.TaskGate

import com.wangzan101.mika.cap.sandbox.ImportResult
import com.wangzan101.mika.chat.BranchCopyResult
import com.wangzan101.mika.chat.BranchOrigin
import com.wangzan101.mika.chat.BranchWorkspace
import com.wangzan101.mika.chat.RetryAttachmentResult
import com.wangzan101.mika.chat.RetryAttachmentStatus
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.chat.DeleteBatch
import com.wangzan101.mika.chat.SharedDraft
import com.wangzan101.mika.chat.SharedDraftStore
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.QueuedFollowUp
import com.wangzan101.mika.task.QueuedSource
import com.wangzan101.mika.ui.PipPhysics.Corner
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.userscreen.UserScreenMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    @Test fun readAloudReportsFailureAndAllowsRetry() = runTest {
        val errors = mutableListOf<String>()
        var attempts = 0
        val app = com.wangzan101.mika.MikaApp()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            speakText = { text -> app.speak(text) { if (++attempts == 1) throw java.io.IOException("offline") } },
            voiceFailed = errors::add)
        vm.speak("你好")
        runCurrent()
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("offline"))
        vm.speak("再试一次")
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, errors.size)
    }

    @Test fun readAloudCancellationDoesNotBecomeAPlaybackError() = runTest {
        val errors = mutableListOf<String>()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            speakText = { throw kotlinx.coroutines.CancellationException("stopped") },
            voiceFailed = errors::add)
        vm.speak("你好")
        runCurrent()
        assertTrue(errors.isEmpty())
    }

    private val state = MutableStateFlow<TaskState>(TaskState.Idle)
    private val stage = MutableStateFlow(Takeover.Stage.None)
    private val calls = mutableListOf<String>()
    private val acts = MutableStateFlow<List<RunEvent.Activity>>(emptyList())
    private val steerFlow = MutableStateFlow<List<SteerNote>>(emptyList())
    private val followUpFlow = MutableStateFlow<List<QueuedFollowUp>>(emptyList())
    private val steerIds = mutableListOf<String>()
    private var canWithdraw = true
    private var queueSaveSucceeds = true
    private val queueStartIfIdle = mutableListOf<Boolean>()
    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) { calls += "start:$goal" }
        override fun steer(conversationId: String, messageId: String, text: String) { calls += "steer:$text"; steerIds += messageId }
        override fun enqueueFollowUp(conversationId: String, messageId: String, text: String, startIfIdle: Boolean): Boolean {
            calls += "queue:$text"; steerIds += messageId; queueStartIfIdle += startIfIdle
            if (!queueSaveSucceeds) return false
            followUpFlow.value = followUpFlow.value + QueuedFollowUp(messageId, conversationId, text)
            return true
        }
        override fun cancelFollowUp(conversationId: String, messageId: String): Boolean {
            calls += "cancelQueued:$messageId"
            followUpFlow.value = followUpFlow.value.filterNot { it.id == messageId }
            return true
        }
        override fun drainFollowUps(conversationId: String) { calls += "drain:$conversationId" }
        override fun withdrawSteer(conversationId: String, messageId: String): Boolean {
            calls += "withdraw:$messageId"
            return canWithdraw
        }
        override fun cancel() { calls += "cancel" }
        override fun resume() { calls += "resume" }
        override fun takeOver() { calls += "takeOver" }
        override fun handBack() { calls += "handBack" }
        override fun finishedMyself() { calls += "finishedMyself" }
        override fun skip() { calls += "skip" }
        override fun answer(text: String) { calls += "answer:$text" }
        override fun approve(key: Int) { calls += "approve:$key" }
        override fun decline(key: Int) { calls += "decline:$key" }
        override fun show(conversationId: String) { calls += "show:$conversationId" }
        override fun cancelIn(conversationId: String) { calls += "cancel@$conversationId" }
        override fun resumeIn(conversationId: String) { calls += "resume@$conversationId" }
        override fun answerIn(conversationId: String, text: String) { calls += "answer@$conversationId:$text" }
        override fun approveIn(conversationId: String, key: Int) { calls += "approve@$conversationId:$key" }
        override fun togglePipSoundIn(conversationId: String) { calls += "toggle-sound@$conversationId" }
    }

    private val ready = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)
    private val ok = Prerequisites.check(ready, hasMainModel = true)

    private fun TestScope.vm(store: ConversationStore = ConversationStore(tmp.newFolder())) =
        ChatViewModel(backgroundScope, state, stage, actions, store, appLabel = { if (it == "com.chat") "聊天" else it },
            activities = acts, steers = steerFlow, followUps = followUpFlow).also {
            it.setFollowUpMode(FollowUpMode.Steer)
            runCurrent()
        }

    /** 和 [vm] 一样,但能指定这块屏开没开。 */
    private fun TestScope.vmWithScreen(open: MutableStateFlow<Boolean>) =
        ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            appLabel = { if (it == "com.chat") "聊天" else it }, activities = acts, steers = steerFlow,
            screenOpen = open, followUps = followUpFlow).also { runCurrent() }

    private fun TestScope.emit(s: TaskState) {
        state.value = s
        runCurrent()
    }

    /** 运行事件里又多了一件实际做过的事。 */
    private fun TestScope.did(step: Int, kind: String, tool: String, target: String?, effect: String = "completed", title: String? = null, failed: Boolean = false) {
        acts.value = acts.value + RunEvent.Activity(0, step, kind, tool, target, effect, title = title, failed = failed)
        runCurrent()
    }

    // 通知提醒那一轮(Mika 直接写进对话的,没跑过任务):没有用户气泡,也没有运行记录可看;同一天点开两次就有两轮,各是各的
    @Test fun noticeTurnsHaveNoUserBubbleAndNoRunToOpen() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("talk", Turn("", "我从通知里挑出 1 条", "succeeded", runId = "notice:n1"))
        store.append("talk", Turn("帮我回一下", "已回复", "succeeded", runId = "run-1"))
        val vm = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), stage, actions, store, parallelFlag = { false }, sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("talk")
        runCurrent()
        val (notice, reply) = vm.ui.value.exchanges
        assertEquals("", notice.goal)
        assertEquals("notice:n1", notice.runId)
        assertNull(notice.recordedRun)
        assertEquals("我从通知里挑出 1 条", (notice.status as ExchangeStatus.Done).answer)
        assertEquals("run-1", reply.recordedRun)
        // 页面上的行数和画出来的对得上:提醒那一轮只有回答那一行,普通的一轮是原话、回答两行(查看过程收进回答卡的操作区了)
        assertEquals(1 + 2, chatItemCount(vm.ui.value.exchanges, kept = null, phoneSetupRow = null, hasBlocked = false))
        // 对话开着的时候又写进一轮提醒:接在后面,前面的不动、不合并
        store.append("talk", Turn("", "我从通知里挑出 2 条", "succeeded", runId = "notice:n2"))
        runCurrent()
        assertEquals(listOf("notice:n1", "run-1", "notice:n2"), vm.ui.value.exchanges.map { it.runId })
        assertEquals(listOf("我从通知里挑出 1 条", "已回复", "我从通知里挑出 2 条"), vm.ui.value.exchanges.map { (it.status as ExchangeStatus.Done).answer })
    }

    @Test fun workflowConversationCanOpenWhileRunningAndPreservesDraft() = runTest {
        val active = MutableStateFlow<List<String>>(emptyList())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            activeConversations = active, parallelFlag = { false }, runGoal = { "workflow step" })
        runCurrent()
        vm.openConversation("original")
        vm.input("draft to keep")
        vm.quote(QuoteMessage.From.Mika, "quoted result")
        active.value = listOf("step")
        emit(TaskState.Running(1, "working"))
        vm.openWorkflowConversation("step")
        assertEquals("step", vm.ui.value.conversationId)
        assertTrue(calls.contains("show:step"))
        assertFalse(calls.any { it.startsWith("start:") })
        active.value = emptyList()
        emit(TaskState.Idle)
        vm.openWorkflowConversation("original")
        assertEquals("draft to keep", vm.ui.value.input)
        assertEquals("quoted result", vm.ui.value.quote?.text)
    }

    @Test fun draftsStayWithTheirConversationInSerialAndParallelModes() = runTest {
        for (parallel in listOf(false, true)) {
            val vm = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), stage, actions,
                ConversationStore(tmp.newFolder()), parallelFlag = { parallel }, sandboxReady = MutableStateFlow(true))
            runCurrent()
            vm.openConversation("a")
            vm.input("draft A")
            vm.quote(QuoteMessage.From.Mika, "answer A")
            vm.attach(listOf("content://files/a.pdf"))
            runCurrent()
            vm.openConversation("b")
            assertEquals("", vm.ui.value.input)
            assertTrue(vm.ui.value.attachments.isEmpty())
            assertNull(vm.ui.value.quote)
            assertTrue(vm.ui.value.attachEnabled)
            vm.input("draft B")
            vm.openConversation("a")
            assertEquals("draft A", vm.ui.value.input)
            assertEquals("answer A", vm.ui.value.quote?.text)
            assertEquals(listOf("content://files/a.pdf"), vm.ui.value.attachments.map { it.uri })
            vm.openConversation("a")
            assertEquals("draft A", vm.ui.value.input)
            vm.openConversation("b")
            assertEquals("draft B", vm.ui.value.input)
        }
    }

    @Test fun unsentNewConversationCanBeReopenedAndDeleted() = runTest {
        val vm = vm()
        vm.input("\nnot sent yet")
        val first = vm.ui.value.conversationId!!
        vm.newConversation()
        vm.input("another draft")
        val second = vm.ui.value.conversationId!!
        runCurrent()
        assertEquals(setOf(first, second), vm.draftConversations.value.map { it.id }.toSet())
        assertEquals("not sent yet", vm.draftConversations.value.first { it.id == first }.title)
        vm.openConversation(first)
        assertEquals("\nnot sent yet", vm.ui.value.input)
        vm.deleteConversation(first)
        runCurrent()
        assertEquals(listOf(second), vm.draftConversations.value.map { it.id })
        vm.openConversation(second)
        assertEquals("another draft", vm.ui.value.input)
    }

    @Test fun openingAtAMessageKeepsTheTargetForTheChatPage() = runTest {
        val vm = vm()
        vm.openConversation("a")
        vm.openConversationAt("b", ChatTarget.Message(1, null))
        assertEquals("b", vm.ui.value.conversationId)
        assertEquals(ChatTarget.Message(1, null), vm.jump.value?.target)

        val first = vm.jump.value!!
        vm.consumeJump(first)
        assertNull(vm.jump.value)

        // 已经在这个对话里也要能重新跳;serial 让聊天页把同一处当成新请求
        vm.openConversationAt("b", ChatTarget.Status(2))
        assertEquals("b", vm.ui.value.conversationId)
        assertEquals(ChatTarget.Status(2), vm.jump.value?.target)
        assertTrue(vm.jump.value!!.serial > first.serial)
    }

    @Test fun parallelRunningTasksKeepTheirDraftsAndFollowUpModes() = runTest {
        val running = MutableStateFlow<TaskState>(TaskState.Running(1, "working"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            parallelFlag = { true }, activeConversations = MutableStateFlow(listOf("a", "b")),
            runGoal = { "task $it" }, runState = { running }, sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.switchTo("a")
        vm.setFollowUpMode(FollowUpMode.Queue)
        vm.input("A next task")
        vm.attach(listOf("content://a.pdf"))
        runCurrent()
        vm.switchTo("b")
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)
        assertEquals("", vm.ui.value.input)
        vm.input("B correction")
        vm.switchTo("a")
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)
        assertEquals("A next task", vm.ui.value.input)
        assertEquals("a.pdf", vm.ui.value.attachments.single().name)
        vm.switchTo("b")
        assertEquals("B correction", vm.ui.value.input)
        assertTrue(calls.none { it.startsWith("start:") || it.startsWith("steer:") || it.startsWith("queue:") })
    }

    @Test fun failedSendKeepsDraftAndSuccessfulSendOnlyClearsItsConversation() = runTest {
        val vm = vm()
        vm.openConversation("a")
        vm.input("draft A")
        vm.openConversation("b")
        vm.input("draft B")
        assertFalse(vm.send(vm.ui.value.input, Prerequisites.check(ready, hasMainModel = false)))
        assertEquals("draft B", vm.ui.value.input)
        assertTrue(vm.send(vm.ui.value.input, ok))
        assertEquals("", vm.ui.value.input)
        emit(TaskState.Succeeded("done"))
        vm.openConversation("a")
        assertEquals("draft A", vm.ui.value.input)
    }

    @Test fun unsentDraftsKeepTheirSidebarActionsAndStayAheadOfOldHistory() = runTest {
        val store = ConversationStore(tmp.newFolder())
        repeat(55) { store.append("old-$it", Turn("old $it", "done", "succeeded")) }
        val vm = vm(store)
        vm.input("unsent")
        val id = vm.ui.value.conversationId!!
        vm.renameConversation(id, "renamed draft")
        vm.setPinned(id, true)
        runCurrent()
        val all = vm.withDrafts(store.list())
        assertEquals(id, all.first().id)
        assertEquals("renamed draft", all.first().title)
        assertTrue(all.first().pinnedAt != null)
        val rows = drawerSections(all, CurrentConversation(id, false, "", ""), emptyList(), { null }, true, 1L)
        assertEquals(id, rows.pinned.single().id)
        assertEquals("", rows.pinned.single().time)
        vm.setPinned(id, false)
        assertEquals(id, vm.withDrafts(store.list()).first().id)
    }

    @Test fun delayedAttachmentDescriptionReturnsToItsOriginalDraftAndIgnoresDeletion() = runTest {
        var described = CompletableDeferred<List<PendingAttachment>>()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true), describeAttachments = { described.await() })
        runCurrent()
        vm.openConversation("a")
        vm.attach(listOf("content://a.pdf"))
        vm.openConversation("b")
        vm.input("B")
        described.complete(listOf(PendingAttachment("content://a.pdf", "a.pdf", 1)))
        runCurrent()
        assertTrue(vm.ui.value.attachments.isEmpty())
        assertEquals("B", vm.ui.value.input)
        vm.openConversation("a")
        assertEquals("a.pdf", vm.ui.value.attachments.single().name)
        vm.attach(listOf("content://stale.pdf"), "b")
        runCurrent()
        assertEquals(1, vm.ui.value.attachments.size)
        described = CompletableDeferred()
        vm.attach(listOf("content://late.pdf"))
        runCurrent()
        vm.deleteConversation("a")
        described.complete(listOf(PendingAttachment("content://late.pdf", "late.pdf", 1)))
        runCurrent()
        assertTrue(vm.draftConversations.value.none { it.id == "a" })
        assertTrue(vm.ui.value.attachments.isEmpty())
        described = CompletableDeferred()
        vm.attach(listOf("content://late.pdf"))
        runCurrent()
        vm.setFollowUpMode(FollowUpMode.Steer)
        assertTrue(vm.send("already started", ok))
        described.complete(listOf(PendingAttachment("content://late.pdf", "late.pdf", 1)))
        runCurrent()
        assertTrue(vm.ui.value.attachments.isEmpty())
    }

    @Test fun deviceImportKeepsOriginalDraftAndReportsPartialFailure() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        vm.importDeviceAttachments("a", listOf("/good.pdf", "/bad.pdf")) { path, id ->
            assertEquals("a", id)
            gate.await()
            if (path.contains("bad")) throw IllegalStateException()
            com.wangzan101.mika.core.cap.devicefiles.DeviceFileImport("/workspace/inbox/good.pdf", 12)
        }
        runCurrent()
        assertTrue(vm.ui.value.importing)
        assertFalse(vm.send("hello", ok))
        vm.openConversation("b")
        gate.complete(Unit)
        runCurrent()
        assertTrue(vm.ui.value.attachments.isEmpty())
        vm.openConversation("a")
        assertFalse(vm.ui.value.importing)
        assertEquals("a", vm.ui.value.attachments.single().workspaceConversationId)
        assertEquals("bad.pdf", vm.ui.value.rejectedAttachments.single().name)
    }

    @Test fun deviceImportOnlyCopiesAvailableAttachmentSlots() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        repeat(8) { vm.addLibraryAttachment("a", PendingAttachment("content://$it", "$it.pdf", 1)) }
        val copied = mutableListOf<String>()
        vm.importDeviceAttachments("a", listOf("/one", "/two")) { path, _ ->
            copied += path
            com.wangzan101.mika.core.cap.devicefiles.DeviceFileImport("/workspace/inbox/one", 1)
        }
        runCurrent()
        assertEquals(listOf("/one"), copied)
        assertEquals(9, vm.ui.value.attachments.size)
        assertEquals(1, vm.ui.value.overLimit)
    }

    @Test fun deviceImportFinishesBeforeConversationDeletion() = runTest {
        val gate = CompletableDeferred<Unit>()
        var deleted = false
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true), removeConversation = { deleted = true })
        runCurrent()
        vm.openConversation("a")
        vm.importDeviceAttachments("a", listOf("/file")) { _, _ ->
            gate.await()
            com.wangzan101.mika.core.cap.devicefiles.DeviceFileImport("/workspace/inbox/file", 1)
        }
        runCurrent()
        vm.deleteConversation("a")
        runCurrent()
        assertFalse(deleted)
        gate.complete(Unit)
        runCurrent()
        assertTrue(deleted)
        assertTrue(vm.draftConversations.value.none { it.id == "a" })
    }

    @Test fun libraryAttachmentMergesIntoTargetDraftWithoutSwitchingOrOverwriting() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        vm.input("draft A")
        vm.openConversation("b")
        vm.input("draft B")
        val attachment = PendingAttachment("content://library/a", "a.pdf", 1)
        assertEquals(LibraryAttachmentResult.Added, vm.addLibraryAttachment("a", attachment))
        assertEquals("draft B", vm.ui.value.input)
        assertTrue(vm.ui.value.attachments.isEmpty())
        vm.openConversation("a")
        assertEquals("draft A", vm.ui.value.input)
        assertEquals(listOf(attachment), vm.ui.value.attachments)
        repeat(8) { assertEquals(LibraryAttachmentResult.Added, vm.addLibraryAttachment("a", PendingAttachment("content://library/$it", "$it.pdf", 1))) }
        assertEquals(LibraryAttachmentResult.Full, vm.addLibraryAttachment("a", PendingAttachment("content://library/overflow", "overflow.pdf", 1)))
        assertEquals(9, vm.ui.value.attachments.size)
        vm.deleteConversation("a")
        assertEquals(LibraryAttachmentResult.Unavailable, vm.addLibraryAttachment("a", attachment))
    }

    @Test fun librarySourceIsAddedOncePerDraftAndDuplicateCopyIsReleased() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        vm.input("existing draft")
        val source = LibraryFileHandle.Workspace("origin", "/workspace/inbox/a.pdf")
        val first = PendingAttachment("content://library/first", "a.pdf", 1)
        val duplicate = PendingAttachment("content://library/second", "a.pdf", 1)
        var firstReleased = 0
        var duplicateReleased = 0
        val existing = PendingAttachment("content://other/file", "other.txt", 1)
        assertEquals(LibraryAttachmentResult.Added, vm.addLibraryAttachment("a", existing))
        assertEquals(LibraryAttachmentResult.Added, vm.addLibraryAttachment("a", first, source) { firstReleased++ })
        vm.openConversation("b")
        assertEquals(LibraryAttachmentResult.Added, vm.addLibraryAttachment("a", duplicate, source) { duplicateReleased++ })
        assertEquals(1, duplicateReleased)
        assertEquals(0, firstReleased)
        vm.openConversation("a")
        assertEquals("existing draft", vm.ui.value.input)
        assertEquals(listOf(existing, first), vm.ui.value.attachments)
        vm.removeAttachment(first.uri)
        assertEquals(1, firstReleased)
        assertEquals(LibraryAttachmentResult.Added, vm.addLibraryAttachment("a", duplicate, source))
        assertEquals(listOf(existing, duplicate), vm.ui.value.attachments)
    }

    @Test fun libraryCopiesRemainWhileReferencedOrImportingAndReleaseAfterward() = runTest {
        val imported = CompletableDeferred<ImportResult>()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true), importAttachments = { _, _ -> imported.await() })
        runCurrent()
        vm.openConversation("a")
        var released = 0
        val item = PendingAttachment("content://library/copy", "copy.pdf", 1)
        assertEquals(LibraryAttachmentResult.Added, vm.addLibraryAttachment("a", item) { released++ })
        assertEquals(LibraryAttachmentResult.Added, vm.addLibraryAttachment("b", item))
        vm.removeAttachment(item.uri)
        assertEquals(0, released)
        vm.openConversation("b")
        assertTrue(vm.send("send", ok))
        runCurrent()
        vm.deleteConversation("b")
        assertEquals(0, released)
        imported.complete(ImportResult(listOf("/workspace/inbox/copy.pdf" to 1L), emptyList()))
        runCurrent()
        assertEquals(1, released)
    }

    @Test fun replacingSharedDraftAndChangingFollowUpModeReleaseLibraryCopies() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true), sharedDrafts = SharedDraftStore(tmp.newFolder()))
        runCurrent()
        vm.openConversation("a")
        var replacedReleased = 0
        val first = PendingAttachment("content://library/first", "first.pdf", 1)
        vm.addLibraryAttachment("a", first) { replacedReleased++ }
        assertTrue(vm.openSharedDraft(SharedDraft("", "replacement"), inCurrentConversation = true))
        assertEquals(0, replacedReleased)
        assertTrue(vm.confirmPendingSharedDraft())
        assertEquals(1, replacedReleased)
        assertEquals("replacement", vm.ui.value.input)

        vm.setFollowUpMode(FollowUpMode.Queue)
        var modeReleased = 0
        val second = PendingAttachment("content://library/second", "second.pdf", 1)
        vm.addLibraryAttachment("a", second) { modeReleased++ }
        vm.setFollowUpMode(FollowUpMode.Steer)
        assertEquals(1, modeReleased)
        assertTrue(vm.ui.value.attachments.isEmpty())
        assertEquals("replacement", vm.ui.value.input)
    }

    @Test fun libraryCopyIsReleasedAfterSuccessfulSend() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            importAttachments = { _, _ -> ImportResult(listOf("/workspace/inbox/a.pdf" to 1L), emptyList()) })
        runCurrent()
        vm.openConversation("a")
        var released = 0
        vm.addLibraryAttachment("a", PendingAttachment("content://library/a", "a.pdf", 1)) { released++ }
        assertTrue(vm.send("send", ok))
        runCurrent()
        assertEquals(1, released)
    }

    @Test fun libraryBatchAddsFilesInOrderWithoutSwitchingDrafts() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        vm.input("draft A")
        vm.openConversation("b")
        vm.input("draft B")
        val handles = (1..3).map { LibraryFileHandle.Workspace("origin", "/workspace/inbox/$it.pdf") }
        val plan = vm.planLibraryAttachments("a", handles)
        assertNotNull(plan)
        assertEquals(handles, plan!!.accepted)
        var released = 0
        val staged = handles.mapIndexed { index, source ->
            LibraryStaged(PendingAttachment("content://library/$index", "${index + 1}.pdf", 1), source) { released++ }
        }
        val result = vm.addLibraryAttachments("a", staged, plan.overLimit)
        assertEquals(3, result.added)
        assertEquals(0, result.duplicates)
        assertEquals(0, result.full)
        assertFalse(result.unavailable)
        assertEquals(0, released)
        assertEquals("draft B", vm.ui.value.input)
        assertTrue(vm.ui.value.attachments.isEmpty())
        vm.openConversation("a")
        assertEquals("draft A", vm.ui.value.input)
        assertEquals(listOf("1.pdf", "2.pdf", "3.pdf"), vm.ui.value.attachments.map { it.name })
    }

    @Test fun libraryBatchPlanSkipsDuplicateSourcesAndStopsAtCapacity() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        val existing = LibraryFileHandle.Workspace("origin", "/workspace/inbox/existing.pdf")
        assertEquals(LibraryAttachmentResult.Added,
            vm.addLibraryAttachment("a", PendingAttachment("content://library/existing", "existing.pdf", 1), existing))
        repeat(7) { index ->
            assertEquals(LibraryAttachmentResult.Added,
                vm.addLibraryAttachment("a", PendingAttachment("content://library/fill-$index", "fill-$index.pdf", 1)))
        }
        val more = listOf(existing) + (1..2).map { LibraryFileHandle.Workspace("origin", "/workspace/inbox/new-$it.pdf") }
        val plan = vm.planLibraryAttachments("a", more)!!
        assertEquals(1, plan.duplicates)
        assertEquals(listOf(more[1]), plan.accepted)
        assertEquals(1, plan.overLimit)
    }

    @Test fun libraryBatchCommitRechecksCapacityAndReleasesRejectedCopies() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        repeat(8) { index ->
            vm.addLibraryAttachment("a", PendingAttachment("content://library/fill-$index", "fill-$index.pdf", 1))
        }
        val first = LibraryFileHandle.Workspace("origin", "/workspace/inbox/first.pdf")
        val second = LibraryFileHandle.Workspace("origin", "/workspace/inbox/second.pdf")
        val plan = vm.planLibraryAttachments("a", listOf(first, second))!!
        assertEquals(1, plan.accepted.size)
        // 复制期间最后一个槽位被占掉:合并时重新检查。
        vm.addLibraryAttachment("a", PendingAttachment("content://other/last", "last.pdf", 1))
        val released = mutableListOf<String>()
        val staged = listOf(first, second).mapIndexed { index, source ->
            LibraryStaged(PendingAttachment("content://library/$index", "$index.pdf", 1), source) { released += "$index" }
        }
        val result = vm.addLibraryAttachments("a", staged, plan.overLimit)
        assertEquals(0, result.added)
        assertEquals(2, result.full)
        assertEquals(setOf("0", "1"), released.toSet())
        assertEquals(9, vm.ui.value.attachments.size)
    }

    @Test fun libraryBatchCommitSkipsDuplicatesAndReleasesTheirCopies() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        val source = LibraryFileHandle.Workspace("origin", "/workspace/inbox/a.pdf")
        vm.addLibraryAttachment("a", PendingAttachment("content://library/existing", "a.pdf", 1), source)
        var released = 0
        val result = vm.addLibraryAttachments("a",
            listOf(LibraryStaged(PendingAttachment("content://library/copy", "a.pdf", 1), source) { released++ }))
        assertEquals(0, result.added)
        assertEquals(1, result.duplicates)
        assertEquals(1, released)
    }

    @Test fun libraryBatchReleasesEverythingWhenTargetIsGone() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        val source = LibraryFileHandle.Workspace("origin", "/workspace/inbox/a.pdf")
        assertNotNull(vm.planLibraryAttachments("a", listOf(source)))
        vm.deleteConversation("a")
        var released = 0
        val result = vm.addLibraryAttachments("a",
            listOf(LibraryStaged(PendingAttachment("content://library/a", "a.pdf", 1), source) { released++ }), 0)
        assertTrue(result.unavailable)
        assertEquals(1, released)
    }

    @Test fun libraryBatchCopiesAreReleasedWhenRemoved() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        val source = LibraryFileHandle.Workspace("origin", "/workspace/inbox/a.pdf")
        var released = 0
        val item = PendingAttachment("content://library/a", "a.pdf", 1)
        val result = vm.addLibraryAttachments("a", listOf(LibraryStaged(item, source) { released++ }))
        assertEquals(1, result.added)
        assertEquals(0, released)
        vm.removeAttachment(item.uri)
        assertEquals(1, released)
    }

    @Test fun failedAttachmentImportAndQueueWriteKeepTheComposerDraft() = runTest {
        var result = ImportResult(emptyList(), listOf("a.pdf"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true), importAttachments = { _, _ -> result }, followUps = followUpFlow)
        runCurrent()
        vm.attach(listOf("content://a.pdf"))
        runCurrent()
        assertTrue(vm.send("", ok))
        runCurrent()
        assertEquals(1, vm.ui.value.attachments.size)
        assertTrue(calls.isEmpty())
        vm.removeAttachment("content://a.pdf")
        assertTrue(vm.send("first task", ok))
        emit(TaskState.Running(1, ""))
        vm.setFollowUpMode(FollowUpMode.Queue)
        vm.input("next task")
        vm.attach(listOf("content://a.pdf"))
        runCurrent()
        result = ImportResult(listOf("/workspace/inbox/a.pdf" to 1L), emptyList())
        queueSaveSucceeds = false
        assertTrue(vm.send(vm.ui.value.input, ok))
        runCurrent()
        assertEquals("next task", vm.ui.value.input)
        assertEquals(1, vm.ui.value.attachments.size)
        assertTrue(vm.ui.value.queued.isEmpty())
    }

    @Test fun attachmentSendInterruptedBySwitchRetainsTheOriginalDraftWithoutSendingInAnotherChat() = runTest {
        val imported = CompletableDeferred<ImportResult>()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            parallelFlag = { true }, sandboxReady = MutableStateFlow(true), importAttachments = { _, _ -> imported.await() })
        runCurrent()
        vm.openConversation("a")
        vm.input("A with file")
        vm.attach(listOf("content://a.pdf"))
        runCurrent()
        assertTrue(vm.send(vm.ui.value.input, ok))
        vm.openConversation("b")
        vm.input("B untouched")
        imported.complete(ImportResult(emptyList(), listOf("a.pdf")))
        runCurrent()
        assertEquals("B untouched", vm.ui.value.input)
        assertTrue(calls.none { it.startsWith("start:") })
        vm.openConversation("a")
        assertEquals("A with file", vm.ui.value.input)
        assertFalse(vm.ui.value.importing)
        assertEquals(1, vm.ui.value.attachments.size)
    }

    @Test fun androidSavedStateRestoresTextsButDoesNotOverwriteLiveDrafts() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = vm(store)
        vm.openConversation("a")
        vm.input("A")
        vm.openConversation("b")
        vm.input("B")
        val saved = vm.saveDraftTexts()
        vm.input("newer B")
        vm.restoreDraftTexts(saved)
        assertEquals("newer B", vm.ui.value.input)
        val restored = vm(store)
        restored.restoreDraftTexts(saved)
        assertEquals("b", restored.ui.value.conversationId)
        assertEquals("B", restored.ui.value.input)
        restored.openConversation("a")
        assertEquals("A", restored.ui.value.input)
    }

    @Test fun consecutiveSharesRetainEditedTextAndPrivateImagesInTheirOwnConversations() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val shared = SharedDraftStore(tmp.newFolder())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, sharedDrafts = shared)
        runCurrent()
        shared.imageFile("one.jpg")!!.writeBytes(byteArrayOf(1))
        val image = com.wangzan101.mika.chat.SharedDraftImage("one.jpg", "one.jpg", 1)
        vm.openSharedDraft(SharedDraft("", "first share", image))
        val first = vm.ui.value.conversationId!!
        vm.input("edited share")
        vm.openSharedDraft(SharedDraft("", "second share"))
        val second = vm.ui.value.conversationId!!
        vm.openConversation(first)
        assertEquals("edited share", vm.ui.value.input)
        assertEquals("one.jpg", vm.ui.value.attachments.single().name)
        assertTrue(shared.imageFile("one.jpg")!!.exists())
        vm.openConversation(second)
        assertEquals("second share", vm.ui.value.input)
        vm.deleteConversation(first)
        assertFalse(shared.imageFile("one.jpg")!!.exists())
        assertEquals("second share", vm.ui.value.input)
    }

    @Test fun clearingSharedTextBeforeSwitchDoesNotRestoreTheOldShareOnRestart() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val shared = SharedDraftStore(tmp.newFolder())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, sharedDrafts = shared)
        runCurrent()
        vm.openSharedDraft(SharedDraft("", "clear me"))
        val id = vm.ui.value.conversationId!!
        vm.input("")
        vm.newConversation()
        assertTrue(shared.readAll().isEmpty())
        val restored = ChatViewModel(backgroundScope, state, stage, actions, store, sharedDrafts = shared)
        runCurrent()
        restored.openConversation(id)
        assertEquals("", restored.ui.value.input)
        assertNull(restored.ui.value.sharedDraft)
    }

    @Test fun blocksWhenPrerequisiteMissing() = runTest {
        val vm = vm()
        val check = Prerequisites.check(ready.copy(accessibility = AccessibilityState.Off), hasMainModel = false)
        assertFalse(vm.send("查天气", check))
        assertTrue(calls.isEmpty())
        // 无障碍只在操作手机 App 时需要,不挡任务;挡住的只有没模型
        assertEquals(listOf(Problem.NoModel), vm.ui.value.blocked)
        assertTrue(vm.ui.value.exchanges.isEmpty())

        // 通知没开不算挡路
        assertTrue(vm.send("查天气", Prerequisites.check(ready.copy(notificationsAllowed = false), hasMainModel = true)))
        assertNull(vm.ui.value.blocked)
        assertEquals(listOf("start:查天气"), calls)
    }

    @Test fun showsStepsFromState() = runTest {
        val vm = vm()
        vm.send("打开设置看电量", ok)
        emit(TaskState.Running(0, "正在准备"))
        assertTrue(vm.ui.value.exchanges.single().steps.isEmpty())

        emit(TaskState.Running(1, ""))
        var steps = vm.ui.value.exchanges.single().steps
        assertTrue(steps.isEmpty())
        assertEquals("正在处理", liveLine(vm.ui.value.exchanges.single()))

        // 选了工具还没做:不按工具名列一行,等实际做了再列
        emit(TaskState.Running(1, "我先打开设置", "phone_launch_app"))
        assertTrue(vm.ui.value.exchanges.single().steps.isEmpty())
        did(1, "act", "phone_launch_app", "com.chat", "place_changed")
        steps = vm.ui.value.exchanges.single().steps
        assertEquals(StepRow(1, "打开 App · 聊天", "", StepState.Run), steps.single())
        assertEquals("打开 App · 聊天", liveLine(vm.ui.value.exchanges.single()))

        // 读取:不出现「看屏幕」;连着读同一处合成一行;不知道读的是哪里的不列
        emit(TaskState.Running(2, "我先打开设置"))
        emit(TaskState.Running(2, "phone_observe", "phone_observe"))
        did(2, "observe", "phone_observe", "com.chat", title = "会话列表")
        did(2, "observe", "phone_diagnose", "com.chat", title = "会话列表")
        did(2, "observe", "phone_observe", null)
        steps = vm.ui.value.exchanges.single().steps
        assertEquals(listOf("打开 App · 聊天", "看了聊天的会话列表"), steps.map { it.text })
        assertEquals(listOf(StepState.Ok, StepState.Run), steps.map { it.state })

        emit(TaskState.WaitingTakeover("captcha", "出现滑块验证码"))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(StepState.Wait, ex.steps.last().state)
        assertEquals("需要你处理", liveLine(ex))
        assertEquals(ExchangeStatus.NeedsTakeover("captcha", "出现滑块验证码"), ex.status)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        vm.takeOver()
        assertEquals("takeOver", calls.last())
    }

    /** 经 MCP 调外部连接器:在跑时说在调哪个;做完单独一行标连接器配置的名字,失败也看得出是哪个连接器。 */
    @Test fun mcpConnectorNamedOnLiveLineAndSteps() = runTest {
        val vm = vm()
        vm.send("查一下团队笔记", ok)
        emit(TaskState.Running(1, "我去笔记里找找", mcpConnector = "Team notes"))
        assertEquals("正在调用 Team notes MCP…", liveLine(vm.ui.value.exchanges.single()))

        acts.value = acts.value + RunEvent.Activity(0, 1, "act", "mcp_demo__search", null, "completed", mcpConnector = "Team notes")
        runCurrent()
        acts.value = acts.value + RunEvent.Activity(0, 2, "act", "mcp_demo__search", null, "some_error", failed = true, mcpConnector = "Team notes")
        runCurrent()
        emit(TaskState.Succeeded("找到了"))
        val steps = vm.ui.value.exchanges.single().steps
        assertEquals(StepRow(1, "已调用 Team notes MCP", "", StepState.Ok), steps[0])
        assertEquals(StepRow(2, "已调用 Team notes MCP", tr(R.string.step_failed), StepState.Ok), steps[1])
        // 错误码不给人看
        assertFalse(steps.joinToString().contains("some_error") || steps.joinToString().contains("mcp_demo"))
    }

    @Test fun paymentTakeoverCarriesDetailsAndCanBeSkipped() = runTest {
        val vm = vm()
        vm.send("买一杯咖啡", ok)
        emit(TaskState.WaitingTakeover("payment", "请付款", item = "拿铁 大杯", amount = "¥18", address = "公司前台"))
        assertEquals(
            ExchangeStatus.NeedsTakeover("payment", "请付款", "拿铁 大杯", "¥18", "公司前台"),
            vm.ui.value.exchanges.single().status,
        )
        vm.skip()
        assertEquals("skip", calls.last())
    }

    // 回答原样显示;来源不从回答里拆,由运行记录里代码记下的那份给出
    @Test fun finalAnswerShownAsIs() = runTest {
        val vm = vm()
        vm.send("电量多少", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        did(1, "observe", "phone_observe", "com.chat")
        emit(TaskState.Running(2, "phone_observe")) // 下一步还在等模型就结束了:这一行不留
        emit(TaskState.Succeeded("电量 86%,正在充电。来源:设置 › 电池"))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Done("电量 86%,正在充电。来源:设置 › 电池", null), ex.status)
        assertEquals(listOf(StepRow(1, "看了聊天", "", StepState.Ok)), ex.steps)
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)

        assertEquals("没有依据段落" to null, splitEvidence("没有依据段落"))
        // 旧记录(对话文件)里的写法:最后单独一行以「依据:」开头,照旧拆出来
        assertEquals("已把亮度调到 50%。\n自动亮度已关闭。" to "设置 › 显示 页面的亮度滑块显示 50%,自动亮度开关为关",
            splitEvidence("已把亮度调到 50%。\n自动亮度已关闭。\n依据: 设置 › 显示 页面的亮度滑块显示 50%,自动亮度开关为关"))
        assertEquals("结论。" to "来自搜索结果第一条", splitEvidence("结论。证据：来自搜索结果第一条"))
    }

    /** 每一步都带着它实际做的动作:点开这一步才看得到用了哪个工具、对什么、结果如何。 */
    @Test fun eachStepCarriesWhatItActuallyDid() = runTest {
        val vm = vm()
        vm.send("打开设置看电量", ok)
        emit(TaskState.Running(0, "正在准备"))
        did(1, "act", "phone_tap", "com.chat", "changed")
        did(1, "act", "phone_type_text", "com.other", "unknown_code", failed = true)
        val ex = vm.ui.value.exchanges.single()
        assertEquals(
            listOf(
                // 用工具的界面说法,不带工具名
                StepAction("点击", "聊天", "changed", false),
                StepAction("输入文字", "com.other", "unknown_code", true),
            ),
            ex.stepActions.getValue(1),
        )
        // 只挂在有动作的那一步上
        assertEquals(setOf(1), ex.stepActions.keys)
        // 点开后每件事一行:做了什么 · 对什么 · 结果;错误码不给人看,说「没成功」
        val lines = ex.stepActions.getValue(1).map(ChatCopy::actionLine)
        assertEquals("点击 · 聊天 · ${tr(R.string.result_effective)}", lines[0])
        assertEquals("输入文字 · com.other · ${tr(R.string.step_failed)}", lines[1])
        lines.forEach { assertFalse(it, it.contains("phone_") || it.contains("unknown_code")) }
    }

    @Test fun unverifiedIsMarkedOnTheResult() = runTest {
        val vm = vm()
        vm.send("查余额", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        emit(TaskState.Succeeded("余额 10 元", verified = false, note = "结果页受系统防截屏保护"))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Done("余额 10 元", null, verified = false, note = "结果页受系统防截屏保护"), ex.status)
        assertEquals("办完了,但没能核实:结果页受系统防截屏保护", (ex.status as ExchangeStatus.Done).unverifiedLine)
        assertEquals(PipMode.Done, vm.ui.value.pip.mode)

        vm.send("再查", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        emit(TaskState.Succeeded("余额 10 元"))
        assertNull((vm.ui.value.exchanges.last().status as ExchangeStatus.Done).unverifiedLine)
    }

    @Test fun liveLineUsesTheModelsReason() = runTest {
        var now = 1_000L
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen, clock = { now },
            activities = acts).also { runCurrent() }
        vm.send("查上海到杭州的车次", ok)
        emit(TaskState.Running(1, "", "phone_launch_app", reason = "打开 12306"))
        did(1, "act", "phone_launch_app", "cn.railway")
        screen.value = true
        runCurrent()
        var ex = vm.ui.value.exchanges.single()
        assertEquals(StepRow(1, "打开 App · cn.railway", "", StepState.Run, "打开 12306"), ex.steps.single())
        assertEquals("打开 12306", liveLine(ex))
        assertTrue(vm.ui.value.operatingPhone)

        // 模型请求在途时显示中性状态，不虚构新步骤。
        emit(TaskState.Running(2, ""))
        ex = vm.ui.value.exchanges.single()
        assertEquals(1, ex.steps.size)
        assertEquals("正在处理", liveLine(ex))

        emit(TaskState.Running(2, "", "phone_tap", reason = "正在 12306 搜索上海到杭州的车次"))
        assertEquals("正在 12306 搜索上海到杭州的车次", liveLine(vm.ui.value.exchanges.single()))
        did(2, "act", "phone_tap", "cn.railway", "changed")

        // 等解锁时不算在后台操作
        emit(TaskState.Held(TaskGate.LOCKED))
        assertFalse(vm.ui.value.operatingPhone)

        emit(TaskState.Running(3, "正在核实结果", reason = "正在核实结果"))
        did(3, "observe", "phone_observe", "cn.railway", title = "查询结果")
        now = 81_000L
        emit(TaskState.Succeeded("有 3 趟\n依据:12306 查询结果页"))
        ex = vm.ui.value.exchanges.single()
        assertEquals(80_000L, ex.elapsedMs)
        assertFalse(vm.ui.value.operatingPhone)
    }

    /** 顶栏副标题「正在操作 …」:最近一件有对象的事所在的 App(包名换成 App 名)。 */
    @Test fun livePlaceIsTheLatestTargetedApp() = runTest {
        val vm = vm()
        vm.send("看聊天", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertNull(vm.livePlace(vm.ui.value.exchanges.single()))
        did(1, "act", "phone_launch_app", "com.chat")
        did(2, "act", "phone_wait", null)
        val ex = vm.ui.value.exchanges.single()
        assertEquals("聊天", vm.livePlace(ex))
        assertEquals("正在操作聊天" to ChatCopy.Tone.Accent, ChatCopy.subtitle(ex, vm.livePlace(ex)))
    }

    // 随调用写的进度说明优先于调用里的用意
    @Test fun liveLinePrefersNarration() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder())).also { runCurrent() }
        vm.send("查车次", ok)
        emit(TaskState.Running(1, "先打开 12306 看看", "phone_launch_app", reason = "打开 12306", narration = "先打开 12306 看看"))
        assertEquals("先打开 12306 看看", liveLine(vm.ui.value.exchanges.single()))
        emit(TaskState.Running(2, "", "phone_tap", reason = "点搜索"))
        assertEquals("点搜索", liveLine(vm.ui.value.exchanges.single()))
    }

    @Test fun streamedTextIsVisibleDuringRequestAndClearedAtCompletion() = runTest {
        val vm = vm()
        vm.send("解释这个概念", ok)
        emit(TaskState.Running(1, "", preview = "第一段"))
        var ex = vm.ui.value.exchanges.single()
        assertEquals("第一段", ex.draft)
        assertTrue(ex.steps.isEmpty())
        assertEquals("正在处理", liveLine(ex))

        emit(TaskState.Running(1, "", preview = "第一段和第二段"))
        assertEquals("第一段和第二段", vm.ui.value.exchanges.single().draft)

        emit(TaskState.Succeeded("完整答复"))
        ex = vm.ui.value.exchanges.single()
        assertNull(ex.draft)
        assertEquals(ExchangeStatus.Done("完整答复", null), ex.status)
    }

    // 退回一次后仍没对上的几处:结果卡片上逐条列出,不打绿勾
    @Test fun unconfirmedItemsAreListedOnTheCard() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder())).also { runCurrent() }
        vm.send("看价格", ok)
        emit(TaskState.Running(1, "", "phone_observe"))
        emit(TaskState.Succeeded("咖啡豆 ¥79", verified = false, note = "未确认:79", unconfirmed = listOf("79")))
        val done = vm.ui.value.exchanges.single().status as ExchangeStatus.Done
        assertEquals("咖啡豆 ¥79", done.answer)
        assertNull(done.evidence)
        assertEquals("这几处没能确认:79", done.unverifiedLine)
    }

    @Test fun phoneHintOnlyWhileTheScreenIsOpen() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        vm.send("算一下 1+1", ok)
        emit(TaskState.Running(1, "", "run_command"))
        // 没开过屏(文件、网页、纯对话的任务):不说在后台操作手机
        assertFalse(vm.ui.value.operatingPhone)
        screen.value = true
        runCurrent()
        assertTrue(vm.ui.value.operatingPhone)
        emit(TaskState.Succeeded("2"))
        assertFalse(vm.ui.value.operatingPhone)
    }

    @Test fun answerPartsPrefersAStructuredEvidence() {
        assertEquals("电量 86%" to "设置 › 电池", answerParts("电量 86%\n依据:设置 › 电池"))
        assertEquals("电量 86%" to "电池页", answerParts("电量 86% ", evidence = " 电池页 "))
        assertEquals("电量 86%\n依据:设置 › 电池" to "电池页", answerParts("电量 86%\n依据:设置 › 电池", evidence = "电池页"))
        // 给了空的就照旧从回答里拆
        assertEquals("电量 86%" to "设置 › 电池", answerParts("电量 86%\n依据:设置 › 电池", evidence = " "))
        assertEquals("没有依据" to null, answerParts("没有依据"))
    }

    // 新记录:来源由代码从证据账本里找(sources 事件),逐条列出,画面是这些读取那一步的
    @Test fun evidenceSourceUsesRecordedSources() {
        val events = listOf(
            RunEvent.Media(1, 1, 1, "com.chat"),
            RunEvent.Media(2, 2, 3, "网站 www.12306.cn"),
            RunEvent.Sources(3, listOf(
                com.wangzan101.mika.core.verify.AnswerSource(1, "screen", "com.chat", null, 1, listOf(1)),
                com.wangzan101.mika.core.verify.AnswerSource(4, "web", "https://www.12306.cn/index", "车次查询", 3, emptyList()),
            )),
        )
        val source = evidenceSource(events) { if (it == "com.chat") "聊天" else it }
        assertEquals(EvidenceSource("www.12306.cn", listOf(1), listOf("第 1 步 · 聊天", "第 3 步 · www.12306.cn · 车次查询")), source)
        assertEquals("来源 · www.12306.cn · 1 张截图", sourceLabel(source))
    }

    @Test fun evidenceSourceIsTheLastSavedScreen() {
        val events = listOf(
            RunEvent.Media(1, 1, 1, "com.chat"),
            RunEvent.Media(2, 2, 3, "网站 www.12306.cn"),
            RunEvent.Media(3, 3, 3, null),
        )
        val source = evidenceSource(events) { if (it == "com.chat") "聊天" else it }
        assertEquals(EvidenceSource("www.12306.cn", listOf(2, 3)), source)
        assertEquals("来源 · www.12306.cn · 2 张截图", sourceLabel(source))
        assertEquals(EvidenceSource("聊天", listOf(1)), evidenceSource(events.take(1)) { if (it == "com.chat") "聊天" else it })
        // 没有截图(文件、纯对话的任务)、还没取到时只写「来源」
        assertEquals(EvidenceSource(null, emptyList()), evidenceSource(emptyList()))
        assertEquals("来源", sourceLabel(EvidenceSource(null, emptyList())))
        assertEquals("来源", sourceLabel(null))
    }

    @Test fun livePreviewVisibleOnlyWhileRunning() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        assertFalse(vm.ui.value.pip.visible)
        vm.send("任务", ok)
        emit(TaskState.Running(1, "", null))
        screen.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Running, vm.ui.value.pip.mode)

        emit(TaskState.Succeeded("好了"))
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Done, vm.ui.value.pip.mode)
        advanceTimeBy(ChatViewModel.DONE_LINGER_MS + 1)
        runCurrent()
        assertFalse(vm.ui.value.pip.visible)

        screen.value = false
        runCurrent()
        vm.send("再来", ok)
        emit(TaskState.Running(1, "", null))
        screen.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        emit(TaskState.Failed("出错了", FailKind.GaveUp))
        assertFalse(vm.ui.value.pip.visible)
    }

    @Test fun pipOnlyOnceTheTaskHasAScreen() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        vm.send("打开设置看安卓版本", ok)
        assertFalse(vm.ui.value.pip.visible)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        assertFalse(vm.ui.value.pip.visible)
        vm.expandPip()
        assertFalse(vm.ui.value.pip.expanded)
        // 开 App 前的提问只在聊天卡片里
        emit(TaskState.WaitingInput("哪个?", null))
        assertFalse(vm.ui.value.pip.visible)
        emit(TaskState.Running(2, "phone_launch_app", "phone_launch_app"))

        screen.value = true // 第一次 phone_launch_app 建了屏
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Running, vm.ui.value.pip.mode)
        // 开过屏之后的接管照常要你处理
        emit(TaskState.WaitingTakeover("login", "需要登录"))
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)

        // 收尾先撤屏再报结束:完成后停留的那几秒照样显示
        screen.value = false
        runCurrent()
        emit(TaskState.Succeeded("安卓 14\n依据:关于手机页"))
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Done, vm.ui.value.pip.mode)
    }

    @Test fun chatOnlyTaskNeverShowsPip() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = MutableStateFlow(false)).also { runCurrent() }
        vm.send("你好", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        emit(TaskState.Running(2, ""))
        emit(TaskState.Succeeded("你好!", verified = false, reviewed = false))
        assertFalse(vm.ui.value.pip.visible)
        val done = vm.ui.value.exchanges.single().status as ExchangeStatus.Done
        assertEquals(ExchangeStatus.Done("你好!", null, verified = false, reviewed = false), done)
        // 什么也没核对的聊天:不打标
        assertNull(done.unverifiedLine)
    }

    @Test fun pipSnapsToNearestCorner() {
        val b = PipPhysics.Bounds(1080f, 2400f, 200f, 420f, margin = 30f, top = 300f, bottom = 250f)
        assertEquals(850f to 330f, b.position(Corner.TopEnd))
        assertEquals(30f to 1700f, b.position(Corner.BottomStart))
        // 停在右上附近、没有速度:回右上
        assertEquals(Corner.TopEnd, PipPhysics.snapCorner(700f, 500f, 0f, 0f, b))
        // 同一位置向下甩:投射落点在下半屏
        assertEquals(Corner.BottomEnd, PipPhysics.snapCorner(700f, 500f, 0f, 2500f, b))
        // 向左下甩
        assertEquals(Corner.BottomStart, PipPhysics.snapCorner(700f, 500f, -2000f, 2500f, b))
        assertEquals(499f, PipPhysics.project(1000f), 0.01f)
        // 窗口太矮(比如键盘弹出):不钻到顶栏下面,下方的角退到和上方一样
        val short = b.copy(height = 900f)
        assertEquals(30f to 330f, short.position(Corner.BottomStart))

        // 临界阻尼:收敛到目标,不越过
        val spring = PipPhysics.Spring()
        var m = PipPhysics.Motion(0f, 0f)
        var max = 0f
        repeat(120) {
            m = spring.step(m, 100f, 1f / 60)
            max = maxOf(max, m.position)
        }
        assertTrue(spring.settled(m, 100f))
        assertTrue("overshoot $max", max <= 100.5f)
    }

    @Test fun pipAutoExpandsWhenNeedsUser() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        vm.send("任务", ok)
        emit(TaskState.Running(1, "", null))
        screen.value = true
        runCurrent()
        assertFalse(vm.ui.value.pip.expanded)

        emit(TaskState.WaitingTakeover("login", "需要登录"))
        assertTrue(vm.ui.value.pip.expanded)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)

        vm.collapsePip()
        assertFalse(vm.ui.value.pip.expanded)
        stage.value = Takeover.Stage.HandedOver
        runCurrent()
        assertFalse(vm.ui.value.pip.expanded) // 同一次等待里不反复弹开
        assertEquals(Takeover.Stage.HandedOver, vm.ui.value.takeoverStage)

        emit(TaskState.Running(2, "", null))
        assertFalse(vm.ui.value.pip.expanded)
        // 只有接管自动展开:提问时要让输入框和选项留在眼前
        emit(TaskState.WaitingInput("选哪个?", listOf("A", "B")))
        assertFalse(vm.ui.value.pip.expanded)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)
        assertEquals(ComposerMode.Answer, vm.ui.value.composer)
        assertTrue(vm.send("A", ok))
        assertEquals("answer:A", calls.last())
    }

    @Test fun sharedDraftIsFreshPersistentAndNeverRunsBeforeExplicitSend() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, conversations, sharedDrafts = drafts)
        runCurrent()

        assertTrue(vm.openSharedDraft(SharedDraft("", "https://example.com")))
        val first = vm.ui.value.conversationId!!
        assertEquals("https://example.com", vm.ui.value.sharedDraft?.text)
        assertEquals(CurrentChat.Open(first), conversations.currentChat())
        assertTrue(conversations.list().isEmpty())
        assertTrue(calls.isEmpty())

        // 同一段文字再分享一次也是另一个新对话,不留下两个空对话记录。
        assertTrue(vm.openSharedDraft(SharedDraft("", "https://example.com")))
        val second = vm.ui.value.conversationId!!
        assertTrue(first != second)
        assertEquals(second, drafts.read()?.conversationId)
        assertTrue(conversations.list().isEmpty())
        assertTrue(calls.isEmpty())

        // 进程重建只恢复与当前对话匹配的那一份,仍不执行。
        val restored = ChatViewModel(backgroundScope, state, stage, actions, conversations, sharedDrafts = drafts)
        runCurrent()
        assertEquals(second, restored.ui.value.conversationId)
        assertEquals("https://example.com", restored.ui.value.sharedDraft?.text)
        assertTrue(calls.isEmpty())

        assertTrue(restored.send("https://example.com", ok))
        assertEquals(listOf("start:https://example.com"), calls)
        assertEquals(listOf(first), drafts.readAll().map { it.conversationId })
    }

    @Test fun restoringSharedDraftKeepsAlreadyReadAttachmentCapability() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        val initial = ChatViewModel(backgroundScope, state, stage, actions, conversations, sharedDrafts = drafts).also { runCurrent() }
        assertTrue(initial.openSharedDraft(SharedDraft("", "分享草稿")))

        // 真机主线程会立即收下 sandboxReady 的初值,随后恢复草稿;恢复不能把已经读到的能力覆盖掉。
        val immediate = kotlinx.coroutines.CoroutineScope(
            backgroundScope.coroutineContext + kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
        )
        val restored = ChatViewModel(
            immediate, state, stage, actions, conversations,
            sandboxReady = MutableStateFlow(true), sharedDrafts = drafts,
        )
        assertEquals("分享草稿", restored.ui.value.sharedDraft?.text)
        assertTrue(restored.ui.value.attachEnabled)
    }

    @Test fun sharedDraftImageUsesTheExistingAttachmentImportAndSendPath() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        drafts.imageFile("shared.jpg")!!.writeBytes(byteArrayOf(1, 2, 3))
        val imported = mutableListOf<List<String>>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            sharedDrafts = drafts,
            sharedDraftUri = { "content://mika.shared/$it" },
            importAttachments = { _, uris ->
                imported += uris
                com.wangzan101.mika.cap.sandbox.ImportResult(listOf("/workspace/inbox/shared.jpg" to 3L), emptyList())
            },
        )
        runCurrent()
        val image = com.wangzan101.mika.chat.SharedDraftImage("shared.jpg", "photo.jpg", 3)
        assertTrue(vm.openSharedDraft(SharedDraft("", "说明这张图", image)))
        assertEquals(listOf("content://mika.shared/shared.jpg"), vm.ui.value.attachments.map { it.uri })
        assertTrue(imported.isEmpty())
        assertTrue(calls.isEmpty())

        assertTrue(vm.send("说明这张图", ok))
        runCurrent()
        assertEquals(listOf(listOf("content://mika.shared/shared.jpg")), imported)
        assertEquals(listOf("start:说明这张图\n附件:/workspace/inbox/shared.jpg(3 B)"), calls)
        assertNull(drafts.read())
        assertFalse(drafts.imageFile("shared.jpg")!!.exists())
    }

    @Test fun olderAttachmentImportCannotClearANewerSharedDraft() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        drafts.imageFile("first.jpg")!!.writeBytes(byteArrayOf(1))
        drafts.imageFile("second.jpg")!!.writeBytes(byteArrayOf(2))
        val releaseImport = CompletableDeferred<Unit>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            sharedDrafts = drafts,
            sharedDraftUri = { "content://mika.shared/$it" },
            importAttachments = { _, _ ->
                releaseImport.await()
                com.wangzan101.mika.cap.sandbox.ImportResult(listOf("/workspace/inbox/first.jpg" to 1L), emptyList())
            },
        )
        runCurrent()
        val first = com.wangzan101.mika.chat.SharedDraftImage("first.jpg", "first.jpg", 1)
        assertTrue(vm.openSharedDraft(SharedDraft("", "第一份", first)))
        val firstId = vm.ui.value.conversationId
        assertTrue(vm.send("第一份", ok))
        assertTrue(vm.ui.value.importing)

        val second = com.wangzan101.mika.chat.SharedDraftImage("second.jpg", "second.jpg", 1)
        assertTrue(vm.openSharedDraft(SharedDraft("", "", second)))
        val secondId = vm.ui.value.conversationId
        releaseImport.complete(Unit)
        runCurrent()
        assertEquals(secondId, vm.ui.value.conversationId)
        assertEquals(second, vm.ui.value.sharedDraft?.image)
        assertEquals(listOf("content://mika.shared/second.jpg"), vm.ui.value.attachments.map { it.uri })
        assertTrue(calls.isEmpty())

        // 纯图片草稿移除图片后彻底消失,不留下空白分享元数据。
        vm.removeAttachment("content://mika.shared/second.jpg")
        assertNull(vm.ui.value.sharedDraft)
        assertTrue(vm.ui.value.attachments.isEmpty())
        assertEquals(listOf(firstId), drafts.readAll().map { it.conversationId })
        assertTrue(drafts.imageFile("first.jpg")!!.exists())
        assertFalse(drafts.imageFile("second.jpg")!!.exists())
    }

    @Test fun inactiveSharedDraftDoesNotReplaceAnotherCurrentConversation() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, conversations, sharedDrafts = drafts)
        runCurrent()
        assertTrue(vm.openSharedDraft(SharedDraft("", "draft")))
        assertFalse(vm.openSharedDraft(SharedDraft("", " ")))
        assertEquals("draft", vm.ui.value.sharedDraft?.text)

        conversations.setCurrent(CurrentChat.Fresh)
        val recreated = ChatViewModel(backgroundScope, state, stage, actions, conversations, sharedDrafts = drafts)
        runCurrent()
        assertNull(recreated.ui.value.conversationId)
        assertNull(recreated.ui.value.sharedDraft)
        assertEquals("draft", drafts.read()?.text)
        assertTrue(calls.isEmpty())
    }

    @Test fun shareDuringPendingStartHandsOffOnlyTheOldTaskAndItsSteers() = runTest {
        val busy = MutableStateFlow(true)
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, conversations, busy, sharedDrafts = drafts)
        runCurrent()
        assertTrue(vm.send("原来的任务", ok))
        vm.setFollowUpMode(FollowUpMode.Steer)
        assertTrue(vm.send("原来的插话", ok))
        runCurrent()
        assertTrue(calls.isEmpty())
        val old = vm.ui.value.conversationId!!

        assertTrue(vm.openSharedDraft(SharedDraft("", "新草稿")))
        assertEquals(listOf("start:原来的任务", "steer:原来的插话"), calls)
        assertFalse(calls.any { it.startsWith("cancel") })
        assertTrue(old != vm.ui.value.conversationId)
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        assertTrue(vm.ui.value.exchanges.isEmpty())

        // 等待协程后来即使观察到服务空闲,也不能重复开始原任务或把新对话的话送过去。
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:原来的任务", "steer:原来的插话"), calls)
        assertTrue(calls.isNotEmpty())
    }

    @Test fun shareInCurrentConversationPrefillsDirectlyAndPreservesHistoryOnRestart() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val existing = conversations.newConversation()
        conversations.append(existing, Turn("上一轮问题", "上一轮回答", "succeeded"))
        val drafts = SharedDraftStore(tmp.newFolder())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, conversations, sharedDrafts = drafts)
        runCurrent()

        // 当前在已打开的会话且无草稿时,后台分享直接放入当前会话,不新建对话,也不丢历史轮次。
        vm.openConversation(existing)
        assertEquals(existing, vm.ui.value.conversationId)
        assertEquals(1, vm.ui.value.exchanges.size)
        assertTrue(vm.openSharedDraft(SharedDraft("", "后台分享文字"), inCurrentConversation = true))
        assertEquals(existing, vm.ui.value.conversationId)
        assertNull(vm.ui.value.pendingSharedDraft)
        assertEquals("后台分享文字", vm.ui.value.input)
        assertEquals("后台分享文字", vm.ui.value.sharedDraft?.text)
        assertEquals(1, vm.ui.value.exchanges.size)
        assertEquals("上一轮问题", vm.ui.value.exchanges.single().goal)

        // 当前会话中直接修改分享文字而不切换会话时,修改实时同步落盘;重启恢复保留修改后的文字与原有历史轮次。
        vm.input("修改后的分享文字")
        val restored = ChatViewModel(backgroundScope, state, stage, actions, conversations, sharedDrafts = drafts)
        runCurrent()
        assertEquals(existing, restored.ui.value.conversationId)
        assertEquals("修改后的分享文字", restored.ui.value.input)
        assertEquals(listOf("上一轮问题"), restored.ui.value.exchanges.map { it.goal })
    }

    @Test fun shareInCurrentConversationPromptsWhenDraftExistsAndSupportsReplaceOrCancel() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        drafts.imageFile("old.jpg")!!.writeBytes(byteArrayOf(1))
        drafts.imageFile("cancelled.jpg")!!.writeBytes(byteArrayOf(2))
        drafts.imageFile("replaced.jpg")!!.writeBytes(byteArrayOf(3))
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            sharedDrafts = drafts,
            sharedDraftUri = { "content://mika.shared/$it" },
        )
        runCurrent()

        val oldImage = com.wangzan101.mika.chat.SharedDraftImage("old.jpg", "old.jpg", 1)
        assertTrue(vm.openSharedDraft(SharedDraft("", "原有草稿", oldImage), inCurrentConversation = true))
        val currentId = vm.ui.value.conversationId!!
        vm.quote(QuoteMessage.From.Mika, "被引用的回复")

        // 已有草稿时收到后台分享:先挂入 pendingSharedDraft 等用户选择,原草稿不动。
        val cancelledImage = com.wangzan101.mika.chat.SharedDraftImage("cancelled.jpg", "cancelled.jpg", 1)
        assertTrue(vm.openSharedDraft(SharedDraft("", "将被取消的分享", cancelledImage), inCurrentConversation = true))
        assertEquals("将被取消的分享", vm.ui.value.pendingSharedDraft?.text)
        assertEquals("原有草稿", vm.ui.value.input)
        assertNotNull(vm.ui.value.quote)

        val missingImage = cancelledImage.copy(fileName = "missing.jpg")
        assertFalse(vm.openSharedDraft(SharedDraft("", "无效的替换", missingImage), inCurrentConversation = true))
        assertEquals("将被取消的分享", vm.ui.value.pendingSharedDraft?.text)
        assertEquals(vm.ui.value.pendingSharedDraft, drafts.readPending())
        assertTrue(drafts.imageFile("cancelled.jpg")!!.isFile)

        // 点「取消」:保留原草稿与原图,删除新分享的临时图片副本。
        vm.cancelPendingSharedDraft()
        assertNull(vm.ui.value.pendingSharedDraft)
        assertEquals("原有草稿", vm.ui.value.input)
        assertNotNull(vm.ui.value.quote)
        assertTrue(drafts.imageFile("old.jpg")!!.exists())
        assertFalse(drafts.imageFile("cancelled.jpg")!!.exists())

        // 再次后台分享:若弹窗期间进程被回收重建,cleanup 不会误删待确认图片,重建后仍可点「替换」。
        val replacedImage = com.wangzan101.mika.chat.SharedDraftImage("replaced.jpg", "replaced.jpg", 1)
        assertTrue(vm.openSharedDraft(SharedDraft("", "确认替换的分享", replacedImage), inCurrentConversation = true))
        drafts.cleanup()
        assertTrue(drafts.imageFile("replaced.jpg")!!.exists())
        val restored = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            sharedDrafts = drafts,
            sharedDraftUri = { "content://mika.shared/$it" },
        )
        runCurrent()
        assertEquals("确认替换的分享", restored.ui.value.pendingSharedDraft?.text)
        assertTrue(restored.confirmPendingSharedDraft())
        assertNull(restored.ui.value.pendingSharedDraft)
        assertEquals(currentId, restored.ui.value.conversationId)
        assertEquals("确认替换的分享", restored.ui.value.input)
        assertNull(restored.ui.value.quote)
        assertEquals(listOf("content://mika.shared/replaced.jpg"), restored.ui.value.attachments.map { it.uri })
        assertFalse(drafts.imageFile("old.jpg")!!.exists())
        assertTrue(drafts.imageFile("replaced.jpg")!!.exists())
    }

    @Test fun shareImageInBusyConversationAutomaticallySwitchesToQueueMode() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        drafts.imageFile("busy.jpg")!!.writeBytes(byteArrayOf(1, 2))
        drafts.imageFile("answer.jpg")!!.writeBytes(byteArrayOf(3, 4))
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            sharedDrafts = drafts,
            sharedDraftUri = { "content://mika.shared/$it" },
            importAttachments = { _, _ -> ImportResult(listOf("/workspace/inbox/answer.jpg" to 2L), emptyList()) },
        )
        runCurrent()

        assertTrue(vm.send("正在运行的任务", ok))
        state.value = TaskState.Running(1, "step")
        runCurrent()
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)

        // 任务运行中收到后台图片分享:自动切换到排队模式。
        val image = com.wangzan101.mika.chat.SharedDraftImage("busy.jpg", "busy.jpg", 2)
        assertTrue(vm.openSharedDraft(SharedDraft("", "排队看这张图", image), inCurrentConversation = true))
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)
        assertEquals(listOf("content://mika.shared/busy.jpg"), vm.ui.value.attachments.map { it.uri })

        // 用户改了文字后手动切回插话模式:图片副本被清理,改过的文字仍保留在草稿存储中。
        vm.input("改成纯文字插话")
        vm.setFollowUpMode(FollowUpMode.Steer)
        assertTrue(vm.ui.value.attachments.isEmpty())
        assertFalse(drafts.imageFile("busy.jpg")!!.exists())
        assertEquals("改成纯文字插话", drafts.read()?.text)

        // 发送插话后,分享草稿从存储中一并清除。
        assertTrue(vm.send("改成纯文字插话", ok))
        assertNull(vm.ui.value.sharedDraft)
        assertNull(drafts.read())

        // Answer 模式(NeedsInput)下收到后台图片分享(即使无文字):同样切到排队模式并正常排队发送附件。
        state.value = TaskState.WaitingInput("选哪个?", listOf("A", "B"))
        runCurrent()
        assertEquals(ComposerMode.Answer, vm.ui.value.composer)
        val answerImg = com.wangzan101.mika.chat.SharedDraftImage("answer.jpg", "answer.jpg", 2)
        assertTrue(vm.openSharedDraft(SharedDraft("", "", answerImg), inCurrentConversation = true))
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)
        assertTrue(vm.send("", ok))
        runCurrent()
        assertTrue(calls.contains("queue:附件:/workspace/inbox/answer.jpg(2 B)"))
        assertFalse(drafts.imageFile("answer.jpg")!!.exists())
    }

    @Test fun shareIntoCurrentConversationDuringAttachmentImportDoesNotPromptOrDeleteImportingFile() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        drafts.imageFile("first.jpg")!!.writeBytes(byteArrayOf(1))
        drafts.imageFile("second.jpg")!!.writeBytes(byteArrayOf(2))
        val releaseImport = CompletableDeferred<Unit>()
        var firstFileExistedDuringImport = false
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            sharedDrafts = drafts,
            sharedDraftUri = { "content://mika.shared/$it" },
            importAttachments = { _, _ ->
                releaseImport.await()
                firstFileExistedDuringImport = drafts.imageFile("first.jpg")!!.exists()
                ImportResult(listOf("/workspace/inbox/first.jpg" to 1L), emptyList())
            },
        )
        runCurrent()

        val first = com.wangzan101.mika.chat.SharedDraftImage("first.jpg", "first.jpg", 1)
        assertTrue(vm.openSharedDraft(SharedDraft("", "看图", first), inCurrentConversation = true))
        assertTrue(vm.send("看图", ok))
        assertTrue(vm.ui.value.importing)

        // 正在异步导入上一条分享图片时收到同会话新分享:不弹过期替换确认框,也不提前删掉正在导入的 first.jpg。
        val second = com.wangzan101.mika.chat.SharedDraftImage("second.jpg", "second.jpg", 1)
        assertTrue(vm.openSharedDraft(SharedDraft("", "看图", second), inCurrentConversation = true))
        assertNull(vm.ui.value.pendingSharedDraft)
        assertTrue(drafts.imageFile("first.jpg")!!.exists())

        releaseImport.complete(Unit)
        runCurrent()
        assertTrue(firstFileExistedDuringImport)
        assertFalse(drafts.imageFile("first.jpg")!!.exists())
        assertTrue(drafts.imageFile("second.jpg")!!.exists())
        assertEquals("看图", vm.ui.value.input)
        assertEquals(listOf("content://mika.shared/second.jpg"), vm.ui.value.attachments.map { it.uri })
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)
    }

    @Test fun editingSharedTextDuringImportStillClearsSentAttachmentsAndSharedDraft() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        drafts.imageFile("shot.jpg")!!.writeBytes(byteArrayOf(1))
        val releaseImport = CompletableDeferred<Unit>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            sharedDrafts = drafts,
            sharedDraftUri = { "content://mika.shared/$it" },
            importAttachments = { _, _ ->
                releaseImport.await()
                ImportResult(listOf("/workspace/inbox/shot.jpg" to 1L), emptyList())
            },
        )
        runCurrent()

        val shot = com.wangzan101.mika.chat.SharedDraftImage("shot.jpg", "shot.jpg", 1)
        assertTrue(vm.openSharedDraft(SharedDraft("", "看图", shot), inCurrentConversation = true))
        assertTrue(vm.send("看图", ok))
        assertTrue(vm.ui.value.importing)

        // 导入期间只改了文字,不算被新分享替换:已发出的图片不能留在输入框上方,分享草稿和图片副本照常清掉。
        vm.input("看图改")
        releaseImport.complete(Unit)
        runCurrent()
        assertTrue(vm.ui.value.attachments.isEmpty())
        assertNull(vm.ui.value.sharedDraft)
        assertNull(drafts.read())
        assertFalse(drafts.imageFile("shot.jpg")!!.exists())
    }

    @Test fun startWaitsUntilServiceFinishedPreviousTask() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        assertTrue(vm.send("下一个", ok))
        runCurrent()
        assertTrue(calls.isEmpty())
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:下一个"), calls)
    }

    @Test fun stopWhileQueuedBehindServiceCancelsTheStart() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        assertTrue(vm.send("下一个", ok))
        runCurrent()
        vm.cancel()
        busy.value = false
        runCurrent()
        assertTrue(calls.toString(), calls.isEmpty()) // 没开始,也不去取消服务里收尾中的上一个任务
        assertEquals(ExchangeStatus.Cancelled(), vm.ui.value.exchanges.single().status)
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        assertFalse(vm.ui.value.pip.visible)
    }

    // 等着服务空下来时,那条消息下面说清在等前一个任务,不像卡住了;交给服务后换成任务自己的进展
    @Test fun waitingMessageSaysItWaitsForThePreviousTask() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        assertTrue(vm.send("下一个", ok))
        runCurrent()
        val waiting = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Working, waiting.status)
        assertEquals("等前一个任务做完再开始", ChatCopy.progressLine(waiting))
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:下一个"), calls)
        emit(TaskState.Running(1, "打开设置", reason = "打开设置"))
        assertEquals("打开设置", ChatCopy.progressLine(vm.ui.value.exchanges.single()))
    }

    // 对话页看到服务空着就交过去,交到之前别处(定时、电脑)先开始了一个:服务把这条排在它后面(不丢),
    // 对话里看得到在排队;那个任务收尾后这条照常开始、做完
    private fun TestScope.raceVm(): ChatViewModel {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), MutableStateFlow(false),
            shownTask = shown, activeConversations = active).also { runCurrent() }
        assertTrue(vm.send("订会议室", ok))
        assertEquals(listOf("start:订会议室"), calls)
        val mine = vm.ui.value.conversationId!!
        // 别处的任务抢先开始、在服务里显示着:不接到这个对话里
        active.value = listOf("remote")
        shown.value = "remote" to TaskState.Running(3, "查快递")
        emit(TaskState.Running(3, "查快递"))
        assertEquals("订会议室", vm.ui.value.exchanges.single().goal)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)
        // 这条排在它后面,服务显示它的排队状态
        val queued = TaskState.Held(GateReason.queued("排队中:前面还有 1 个任务"))
        active.value = listOf("remote", mine)
        shown.value = mine to queued
        emit(queued)
        return vm
    }

    @Test fun messageQueuedBehindATaskStartedElsewhereStartsAfterIt() = runTest {
        val vm = raceVm()
        val mine = vm.ui.value.conversationId!!
        assertEquals(ExchangeStatus.Queued("排队中:前面还有 1 个任务"), vm.ui.value.exchanges.single().status)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        // 那个任务收尾,这条放出来开始跑,直到做完
        active.value = listOf(mine)
        shown.value = mine to TaskState.Running(1, "打开日历", reason = "打开日历")
        emit(TaskState.Running(1, "打开日历", reason = "打开日历"))
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)
        shown.value = mine to TaskState.Succeeded("订好了")
        emit(TaskState.Succeeded("订好了"))
        assertEquals("订好了", (vm.ui.value.exchanges.single().status as ExchangeStatus.Done).answer)
    }

    // 排着队时点停止:只停这个对话的那条,不发不带编号的停止(那会停掉服务显示着的、别处开始的任务)
    @Test fun stopWhileQueuedInServiceCancelsOnlyThisMessage() = runTest {
        val vm = raceVm()
        val mine = vm.ui.value.conversationId!!
        vm.cancel()
        assertEquals("cancel@$mine", calls.last())
        assertFalse("cancel" in calls)
    }

    @Test fun historyLoadsWhenOpeningConversation() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴\n依据:天气 App", "succeeded"))
        store.append(id, Turn("订票", "验证码过不去", "failed"))
        store.append(id, Turn("查余额", "余额 10 元\n依据:钱包页", "unverified", note = "防截屏"))
        val vm = vm(store)
        vm.openConversation(id)
        val ex = vm.ui.value.exchanges
        assertEquals(listOf("查天气", "订票", "查余额"), ex.map { it.goal })
        assertEquals(ExchangeStatus.Done("余额 10 元", "钱包页", verified = false, note = "防截屏"), ex[2].status)
        assertEquals(ExchangeStatus.Done("晴", "天气 App"), ex[0].status)
        assertEquals(ExchangeStatus.Failed("验证码过不去"), ex[1].status)

        vm.send("再查一次", ok)
        assertEquals("start:再查一次", calls.last())
        assertEquals(id, vm.ui.value.conversationId)
    }

    @Test fun confirmationCardShowsButtons() = runTest {
        val vm = vm()
        assertTrue(vm.send("给张三发明天见", ok))
        emit(TaskState.Running(3, "打字", "phone_type_text"))
        did(3, "act", "phone_type_text", "com.chat", "changed")
        val waiting = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "张三", "明天见", null, "com.chat"))
        emit(waiting)
        val ex = vm.ui.value.exchanges.last()
        assertEquals(ExchangeStatus.NeedsConfirmation("要你同意:发送", "在 聊天\n对象:张三\n内容:明天见", ConfirmationNotifier.requestKey(waiting)), ex.status)
        assertEquals(StepState.Wait, ex.steps.last().state)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)
        assertEquals("等你同意", liveLine(ex))
        assertFalse(vm.ui.value.operatingPhone)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        // 回答后回到运行
        emit(TaskState.Running(4, "", null))
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
    }

    @Test fun approveCallsAction() = runTest {
        val vm = vm()
        // 卡片按钮带着它所属的申报
        vm.approve(7)
        vm.decline(8)
        assertEquals(listOf("approve:7", "decline:8"), calls)
    }

    @Test fun endedTaskCarriesItsRunId() = runTest {
        val endedRunId = MutableStateFlow<String?>("r-before")
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded", runId = "r-old"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, endedRunId = endedRunId)
        runCurrent()
        vm.openConversation(id)
        assertEquals("r-old", vm.ui.value.exchanges.single().runId)

        vm.send("查电量", ok)
        emit(TaskState.Running(1, "看看", "phone_observe"))
        assertNull(vm.ui.value.exchanges.last().runId) // 还在跑时不给入口
        // 服务先记下这次的编号再报结束
        endedRunId.value = "r-new"
        emit(TaskState.Succeeded("85%"))
        assertEquals("r-new", vm.ui.value.exchanges.last().runId)

        // 下一个任务的编号后来才到:已结束的那条不跟着变
        vm.send("再查", ok)
        emit(TaskState.Running(1, "看看", "phone_observe"))
        endedRunId.value = "r-next"
        runCurrent()
        assertEquals("r-new", vm.ui.value.exchanges[1].runId)
        emit(TaskState.Failed("没找到", FailKind.GaveUp))
        assertEquals("r-next", vm.ui.value.exchanges[2].runId)
        assertEquals("r-new", vm.ui.value.exchanges[1].runId)
    }

    // 停止:当场已停止、输入框空出来;交代还在写时标「正在整理」,写好记进对话后成为这一轮的回答
    @Test fun stoppedTurnShowsSummaryWhenItArrives() = runTest {
        val endedRunId = MutableStateFlow<String?>(null)
        val summarizing = MutableStateFlow<Set<String>>(emptySet())
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, endedRunId = endedRunId, summarizing = summarizing)
        runCurrent()
        vm.openConversation(id)
        vm.send("看四个设置页", ok)
        emit(TaskState.Running(1, "看看", "phone_launch_app"))
        vm.cancel()
        assertEquals("cancel", calls.last())
        // 服务先登记「正在整理」、记下编号,再报结束,接着记下「已取消」那一轮
        summarizing.value = setOf("r1")
        endedRunId.value = "r1"
        emit(TaskState.Cancelled)
        store.append(id, Turn("看四个设置页", "已取消", "cancelled", runId = "r1"))
        runCurrent()
        assertEquals(ExchangeStatus.Cancelled(summarizing = true), vm.ui.value.exchanges.single().status)
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)

        store.updateTurn(id, "r1") { it.copy(outcome = "停止前看完了网络、蓝牙;显示、声音还没看。") }
        runCurrent()
        summarizing.value = emptySet()
        runCurrent()
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Cancelled("停止前看完了网络、蓝牙;显示、声音还没看。"), ex.status)
        assertEquals("r1", ex.runId)
        // 重新打开对话:从存下的那份读出交代
        vm.newConversation()
        vm.openConversation(id)
        assertEquals(ExchangeStatus.Cancelled("停止前看完了网络、蓝牙;显示、声音还没看。"), vm.ui.value.exchanges.single().status)
    }

    // 交代写不成(或没什么可交代):「正在整理」去掉,只留已停止
    @Test fun stoppedTurnWithoutSummaryDropsTheHint() = runTest {
        val endedRunId = MutableStateFlow<String?>(null)
        val summarizing = MutableStateFlow<Set<String>>(emptySet())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), endedRunId = endedRunId, summarizing = summarizing)
        runCurrent()
        vm.send("看设置", ok)
        emit(TaskState.Running(1, "看看", "phone_launch_app"))
        summarizing.value = setOf("r2")
        endedRunId.value = "r2"
        emit(TaskState.Cancelled)
        assertEquals(ExchangeStatus.Cancelled(summarizing = true), vm.ui.value.exchanges.single().status)
        summarizing.value = emptySet()
        runCurrent()
        assertEquals(ExchangeStatus.Cancelled(), vm.ui.value.exchanges.single().status)
    }

    @Test fun conversationSwitchesKeepOwnAttachmentsAndCapability() = runTest {
        for (parallel in listOf(false, true)) {
            val store = ConversationStore(tmp.newFolder())
            store.append("a", Turn("第一件事", "好了", "succeeded"))
            store.append("b", Turn("第二件事", "好了", "succeeded"))
            val vm = ChatViewModel(
                backgroundScope, state, stage, actions, store,
                sandboxReady = MutableStateFlow(true),
                parallelFlag = { parallel }, shownTask = shown, activeConversations = active,
            ).also { runCurrent() }
            vm.openConversation("a")
            vm.attach(listOf("content://draft/$parallel"))
            runCurrent()
            val pending = vm.ui.value.attachments
            assertEquals("parallel=$parallel", listOf("content://draft/$parallel"), pending.map { it.uri })

            vm.openConversation("b")
            assertTrue("parallel=$parallel", vm.ui.value.attachEnabled)
            assertTrue("parallel=$parallel", vm.ui.value.attachments.isEmpty())
            vm.openConversation("a")
            assertTrue("parallel=$parallel", vm.ui.value.attachEnabled)
            assertEquals("parallel=$parallel", pending, vm.ui.value.attachments)

            // 新对话有自己的空输入框;设备能力和原对话的待发附件都不能丢。
            vm.newConversation()
            assertTrue("parallel=$parallel", vm.ui.value.attachEnabled)
            assertTrue("parallel=$parallel", vm.ui.value.attachments.isEmpty())
            vm.openConversation("a")
            assertTrue("parallel=$parallel", vm.ui.value.attachEnabled)
            assertEquals("parallel=$parallel", pending, vm.ui.value.attachments)
        }
    }

    @Test fun partialAttachmentFailureKeepsDraftUntilFailedFileIsRemoved() = runTest {
        val sandbox = MutableStateFlow(false)
        val imported = mutableListOf<Pair<String, List<String>>>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = sandbox,
            importAttachments = { id, uris ->
                imported += id to uris
                ImportResult(
                    listOf("/workspace/inbox/报表 (1).csv" to 2048L),
                    if (uris.size > 1) listOf("big.iso") else emptyList(),
                    if (uris.size > 1) listOf(com.wangzan101.mika.cap.sandbox.AttachmentFailure("big.iso", com.wangzan101.mika.cap.sandbox.AttachmentFailureReason.TOO_LARGE)) else emptyList(),
                )
            },
            describeAttachments = { uris -> uris.map { PendingAttachment(it, if (it.endsWith("1")) "报表.csv" else "big.iso", 10L) } },
        ).also { runCurrent() }
        // 沙箱没就绪:没有附件按钮,也不收
        assertFalse(vm.ui.value.attachEnabled)
        vm.attach(listOf("content://x/1"))
        runCurrent()
        assertTrue(vm.ui.value.attachments.isEmpty())

        sandbox.value = true
        runCurrent()
        assertTrue(vm.ui.value.attachEnabled)
        // 选好时只挂在输入框上方,不复制
        vm.attach(listOf("content://x/1", "content://x/2"))
        runCurrent()
        assertEquals(listOf("content://x/1", "content://x/2"), vm.ui.value.attachments.map { it.uri })
        assertTrue(imported.isEmpty())

        // 部分失败时不启动,文字和整批附件保留,供用户选择如何继续。
        vm.input("汇总一下")
        assertTrue(vm.send(vm.ui.value.input, ok))
        runCurrent()
        val id = vm.ui.value.conversationId!!
        assertEquals(listOf(id to listOf("content://x/1", "content://x/2")), imported)
        assertTrue(calls.isEmpty())
        assertEquals("汇总一下", vm.ui.value.input)
        assertEquals(listOf("content://x/1", "content://x/2"), vm.ui.value.attachments.map { it.uri })
        assertEquals(listOf("big.iso"), vm.ui.value.rejectedAttachments.map { it.name })
        assertEquals(com.wangzan101.mika.cap.sandbox.AttachmentFailureReason.TOO_LARGE, vm.ui.value.rejectedAttachments.single().reason)
        assertFalse(vm.ui.value.importing)

        // 用户明确移除失败项后,发送剩余附件。
        vm.removeAttachment("content://x/2")
        assertTrue(vm.send(vm.ui.value.input, ok))
        runCurrent()
        assertEquals(id to listOf("content://x/1"), imported.last())
        assertEquals(listOf("start:汇总一下\n附件:/workspace/inbox/报表 (1).csv(2 KB)"), calls)
        assertTrue(vm.ui.value.attachments.isEmpty())
        assertTrue(vm.ui.value.input.isEmpty())
        // 显式插话模式下不收附件
        vm.setFollowUpMode(FollowUpMode.Steer)
        vm.attach(listOf("content://x/3"))
        runCurrent()
        assertTrue(vm.ui.value.attachments.isEmpty())
    }

    @Test fun attachmentsAloneCanBeSent() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            importAttachments = { _, _ -> com.wangzan101.mika.cap.sandbox.ImportResult(listOf("/workspace/inbox/a.jpg" to 3L * 1024 * 1024), emptyList()) },
        ).also { runCurrent() }
        assertTrue(vm.toggleAttachment(PendingAttachment("content://m/1", "a.jpg", 1L)))
        assertTrue(vm.send("", ok))
        runCurrent()
        assertEquals(listOf("start:附件:/workspace/inbox/a.jpg(3.0 MB)"), calls)
    }

    @Test fun attachmentsAreImportedBeforeTheirQueuedTaskIsPersisted() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true), followUps = followUpFlow,
            importAttachments = { _, _ -> com.wangzan101.mika.cap.sandbox.ImportResult(listOf("/workspace/inbox/a.jpg" to 1024), emptyList()) },
        ).also { runCurrent() }
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Queue)
        assertTrue(vm.toggleAttachment(PendingAttachment("content://m/1", "a.jpg", 1L)))
        assertTrue(vm.send("看下一张", ok))
        runCurrent()

        assertEquals(listOf("queue:看下一张\n附件:/workspace/inbox/a.jpg(1 KB)"), calls.filter { it.startsWith("queue:") })
        assertTrue(vm.ui.value.exchanges.single().steers.isEmpty())
        assertEquals(listOf("看下一张\n附件:/workspace/inbox/a.jpg(1 KB)"), vm.ui.value.queued.map { it.text })
    }

    @Test fun failedImportWithTextKeepsDraftAndCanBeRetried() = runTest {
        var fails = true
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            importAttachments = { _, _ ->
                if (fails) throw java.io.IOException("read failed")
                ImportResult(listOf("/workspace/inbox/a.pdf" to 1L), emptyList())
            },
        ).also { runCurrent() }
        vm.input("分析这个文件")
        vm.toggleAttachment(PendingAttachment("content://a", "a.pdf", 1L))
        assertTrue(vm.send(vm.ui.value.input, ok))
        runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals("分析这个文件", vm.ui.value.input)
        assertEquals(listOf("a.pdf"), vm.ui.value.attachments.map { it.name })
        assertEquals(listOf("a.pdf"), vm.ui.value.rejectedAttachments.map { it.name })
        fails = false
        assertTrue(vm.send(vm.ui.value.input, ok))
        runCurrent()
        assertEquals(listOf("start:分析这个文件\n附件:/workspace/inbox/a.pdf(1 B)"), calls)
        assertTrue(vm.ui.value.attachments.isEmpty())
    }

    @Test fun failedQueuedAttachmentImportDoesNotEnqueueAndKeepsDraft() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true), followUps = followUpFlow,
            importAttachments = { _, _ -> ImportResult(listOf("/workspace/inbox/a.pdf" to 1L), listOf("b.pdf")) },
        ).also { runCurrent() }
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Queue)
        vm.input("分析文件")
        vm.toggleAttachment(PendingAttachment("content://a", "a.pdf", 1L))
        vm.toggleAttachment(PendingAttachment("content://b", "b.pdf", 1L))
        assertTrue(vm.send(vm.ui.value.input, ok))
        runCurrent()
        assertTrue(calls.none { it.startsWith("queue:") })
        assertTrue(vm.ui.value.queued.isEmpty())
        assertEquals("分析文件", vm.ui.value.input)
        assertEquals(listOf("a.pdf", "b.pdf"), vm.ui.value.attachments.map { it.name })
        assertEquals(listOf("b.pdf"), vm.ui.value.rejectedAttachments.map { it.name })
    }

    @Test fun nothingIsSentWhenEveryAttachmentFailsAndThereAreNoWords() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            importAttachments = { _, _ -> com.wangzan101.mika.cap.sandbox.ImportResult(emptyList(), listOf("huge.mov")) },
        ).also { runCurrent() }
        vm.toggleAttachment(PendingAttachment("content://m/1", "huge.mov", 1L))
        vm.send("", ok)
        runCurrent()
        assertTrue(calls.isEmpty())
        assertTrue(vm.ui.value.exchanges.isEmpty())
        assertEquals(listOf("huge.mov"), vm.ui.value.rejectedAttachments.map { it.name })
    }

    @Test fun pickingPhotosStopsAtTheLimit() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            describeAttachments = { uris -> uris.map { PendingAttachment(it, "p.jpg", 1L) } },
        ).also { runCurrent() }
        repeat(AttachTray.LIMIT) { assertTrue(vm.toggleAttachment(PendingAttachment("content://m/$it", "p.jpg", 1L))) }
        assertFalse(vm.toggleAttachment(PendingAttachment("content://m/x", "p.jpg", 1L)))
        // 再点已选的一张就取消
        assertTrue(vm.toggleAttachment(PendingAttachment("content://m/0", "p.jpg", 1L)))
        assertEquals(AttachTray.LIMIT - 1, vm.ui.value.attachments.size)
        vm.attach(listOf("content://f/1", "content://f/2"))
        runCurrent()
        assertEquals(AttachTray.LIMIT, vm.ui.value.attachments.size)
        assertEquals(1, vm.ui.value.overLimit)
        vm.dismissRejected()
        assertEquals(0, vm.ui.value.overLimit)
    }

    @Test fun sharedFilesShowOnTheLiveExchangeAndAreRestored() = runTest {
        val files = MutableStateFlow<List<com.wangzan101.mika.chat.SharedFile>>(emptyList())
        val store = ConversationStore(tmp.newFolder())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, sharedFiles = files).also { runCurrent() }
        vm.send("导出表格", ok)
        emit(TaskState.Running(1, "整理"))
        val f = com.wangzan101.mika.chat.SharedFile("表.xlsx", 5000, "Download/Mika/表.xlsx", "content://media/1")
        files.value = listOf(f)
        runCurrent()
        assertEquals(listOf(f), vm.ui.value.exchanges.last().files)
        emit(TaskState.Succeeded("好了\n依据: 已导出"))
        assertEquals(listOf(f), vm.ui.value.exchanges.last().files)

        val id = vm.ui.value.conversationId!!
        store.append(id, Turn("导出表格", "好了", "succeeded"), listOf(f))
        vm.openConversation(id)
        assertEquals(listOf(f), vm.ui.value.exchanges.single().files)
    }

    @Test fun sizeLabels() {
        assertEquals("12 B", ChatViewModel.sizeLabel(12))
        assertEquals("2 KB", ChatViewModel.sizeLabel(2048))
        assertEquals("1.5 MB", ChatViewModel.sizeLabel(1_572_864))
    }

    // ---- 并行任务(开关 parallelTasks 打开)----
    private val shown = MutableStateFlow<Pair<String, TaskState>?>(null)
    private val active = MutableStateFlow<List<String>>(emptyList())

    private fun TestScope.parallelVm(
        store: ConversationStore,
        files: MutableStateFlow<List<com.wangzan101.mika.chat.SharedFile>> = MutableStateFlow(emptyList()),
        sandbox: MutableStateFlow<Boolean> = MutableStateFlow(false),
    ) = ChatViewModel(
        backgroundScope, state, stage, actions, store, serviceBusy = MutableStateFlow(true),
        parallelFlag = { true }, shownTask = shown, activeConversations = active, runGoal = { if (it == "b") "查快递" else null },
        sharedFiles = files, sandboxReady = sandbox,
    ).also { runCurrent() }

    private fun TestScope.show(conversationId: String, s: TaskState) {
        shown.value = conversationId to s
        runCurrent()
    }

    /** 删掉屏上这个对话:当场换成新的空对话,侧栏不再列它,删的事交给后台。 */
    @Test fun deletingTheOpenConversationShowsAFreshOne() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.append("b", Turn("订票", "好了", "succeeded"))
        val removed = mutableListOf<String>()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, removeConversation = { removed += it; store.delete(it) }).also { runCurrent() }
        vm.openConversation("a")
        assertEquals(1, vm.ui.value.exchanges.size)

        vm.deleteConversation("a")
        runCurrent()
        assertEquals(listOf("a"), removed)
        assertEquals(setOf("a"), vm.deleted.value)
        assertNull(vm.ui.value.conversationId)
        assertTrue(vm.ui.value.exchanges.isEmpty())
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)

        // 删别的对话:屏上的不动
        vm.openConversation("b")
        vm.deleteConversation("x")
        runCurrent()
        assertEquals("b", vm.ui.value.conversationId)
        assertEquals(listOf("订票"), vm.ui.value.exchanges.map { it.goal })
    }

    /** 批量删除:几条一起删,进度一条条往前走;一条删不掉不挡后面的,最后照实说几条失败。 */
    @Test fun deletingABatchReportsProgressAndFailures() = runTest {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        listOf("a", "b", "c").forEach { store.append(it, Turn("问题 $it", "好", "succeeded")) }
        val blocker = java.io.File(dir, "b.json.tmp")
        assertTrue(blocker.mkdir())
        val removed = mutableListOf<String>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, store,
            removeConversation = { id ->
                store.delete(id)
                removed += id
            },
        ).also { runCurrent() }

        vm.deleteConversations(listOf("a", "b", "c"))
        // 提交后当场从侧栏拿掉,不等后台删完
        assertEquals(setOf("a", "b", "c"), vm.deleted.value)
        assertEquals(DeleteBatch(3, 0, 0), vm.deleteBatch.value)

        runCurrent()
        assertEquals(listOf("a", "c"), removed)
        assertEquals(setOf("a", "c"), vm.deleted.value)
        assertEquals(listOf("b"), store.list().map { it.id })
        assertEquals(DeleteBatch(3, 3, 1), vm.deleteBatch.value)
        assertTrue(vm.deleteBatch.value!!.finished)

        assertTrue(blocker.delete())
        vm.deleteConversations(listOf("b"))
        runCurrent()
        assertEquals(listOf("a", "c", "b"), removed)
        assertTrue(store.list().isEmpty())
        assertEquals(DeleteBatch(1, 1, 0), vm.deleteBatch.value)
        vm.clearDeleteBatch()
        assertNull(vm.deleteBatch.value)
    }

    /** 侧栏里改名、置顶:存下来,侧栏按存下的重排(置顶的在「置顶」一段,最近置顶的在前);删掉的对话不再改。 */
    @Test fun renameAndPinReorderTheDrawer() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.append("b", Turn("订票", "好了", "succeeded"))
        store.append("c", Turn("写周报", "好了", "succeeded"))
        var now = 1_000L
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, clock = { now }, removeConversation = { store.delete(it) }).also { runCurrent() }
        fun sections() = drawerSections(store.list(), CurrentConversation(null, false, "", ""), emptyList(), vm::runningGoal, vm.canSwitch(), 0L)

        vm.renameConversation("b", " 回家的票 ")
        vm.setPinned("c", true)
        now = 2_000L
        vm.setPinned("b", true)
        val s = sections()
        assertEquals(listOf("回家的票", "写周报"), s.pinned.map { it.title })
        assertEquals(listOf("a"), s.recent.map { it.id })

        vm.setPinned("b", false)
        assertEquals(listOf("c"), sections().pinned.map { it.id })
        assertEquals(setOf("a", "b"), sections().recent.mapNotNull { it.id }.toSet())

        vm.deleteConversation("c")
        runCurrent()
        vm.renameConversation("c", "不该写进去")
        vm.setPinned("c", true)
        assertNull(store.title("c"))
        assertNull(store.pinnedAt("c"))
    }

    /** 删掉的对话又有人往里存了(悬浮胶囊接着在那个编号里说):侧栏重新列出它。 */
    @Test fun aDeletedConversationThatIsWrittenAgainIsListedAgain() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, removeConversation = { store.delete(it) }).also { runCurrent() }
        vm.deleteConversation("a")
        runCurrent()
        assertEquals(setOf("a"), vm.deleted.value)
        store.append("a", Turn("再来", "好", "succeeded"))
        runCurrent()
        assertTrue(vm.deleted.value.isEmpty())
    }

    /** 并行时删掉正在跑任务的屏上对话:不再跟那个任务,它之后的状态也不接到新对话里。 */
    @Test fun deletingARunningOpenConversationStopsFollowingIt() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = parallelVm(store)
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        active.value = listOf("a")
        show("a", TaskState.Running(1, "打开设置"))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        vm.deleteConversation("a")
        runCurrent()
        val fresh = vm.ui.value.conversationId
        assertTrue(fresh != null && fresh != "a")
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        assertFalse(vm.ui.value.pip.visible)
        show("a", TaskState.Running(2, "看屏幕"))
        show("a", TaskState.Cancelled)
        assertTrue(vm.ui.value.exchanges.isEmpty())
        assertEquals(fresh, vm.ui.value.conversationId)
    }

    @Test fun otherRunningExcludesCurrent() = runTest {
        val vm = parallelVm(ConversationStore(tmp.newFolder()))
        vm.switchTo("a")
        active.value = listOf("a", "b", "c")
        runCurrent()
        assertEquals(listOf("b", "c"), vm.otherRunning.value)
        vm.switchTo("b")
        runCurrent()
        assertEquals(listOf("a", "c"), vm.otherRunning.value)
    }

    @Test fun switchToLoadsThatConversation() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("b", Turn("查天气", "晴\n依据:天气 App", "succeeded"))
        val vm = parallelVm(store)
        // 对话 a 的任务在跑
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        show("a", TaskState.Running(1, "打开设置"))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        // 对话 b 也有任务在跑:切过去先让服务显示它,载入历史并接上它此刻的状态
        active.value = listOf("a", "b")
        show("b", TaskState.Running(2, "看屏幕"))
        vm.switchTo("b")
        assertEquals("b", vm.ui.value.conversationId)
        assertTrue("show:b" in calls)
        assertEquals(listOf("查天气", "查快递"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
        // a 的状态不进 b 的对话
        show("a", TaskState.Succeeded("done"))
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)

        // 屏上是 b:停止只发给 b 的任务,不先改显示
        calls.clear()
        vm.cancel()
        assertEquals(listOf("cancel@b"), calls)

        // 没有任务的新对话:别的任务还在跑也能开新任务,不等服务空下来
        vm.newConversation()
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        assertTrue(vm.send("新建闹钟", ok))
        assertEquals("start:新建闹钟", calls.last())
    }

    /** 并行时换对话整块重建 ChatUi:设备支持附件是能力、不随对话变,切一次对话加号不能就没了。 */
    @Test fun switchToKeepsAttachmentsEnabled() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("b", Turn("查天气", "晴", "succeeded"))
        val vm = parallelVm(store, sandbox = MutableStateFlow(true))
        assertTrue(vm.ui.value.attachEnabled)

        // 空闲对话之间来回切
        vm.switchTo("a")
        assertTrue(vm.ui.value.attachEnabled)
        vm.switchTo("b")
        assertTrue(vm.ui.value.attachEnabled)

        // 一条对话的任务跑着时切到另一条
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        show("a", TaskState.Running(1, "打开设置"))
        vm.switchTo("b")
        assertTrue(vm.ui.value.attachEnabled)
    }

    private val learnCalls = mutableListOf<String>()
    private val savedNotes = mutableListOf<com.wangzan101.mika.memory.RunNotes>()
    private val learning = object : LearningActions {
        override suspend fun undo(token: String): Boolean { learnCalls += "undo:$token"; return token != "expired" }
        override suspend fun approve(pendingId: String): Boolean { learnCalls += "approve:$pendingId"; return true }
        override suspend fun forget(memoryId: String): Boolean { learnCalls += "forget:$memoryId"; return true }
        override fun viewSkill(draftId: String) { learnCalls += "view:$draftId" }
        override suspend fun restoreSkill(name: String) { learnCalls += "restore:$name" }
        override suspend fun deleteSkill(name: String) { learnCalls += "delete:$name" }
        override suspend fun save(notes: com.wangzan101.mika.memory.RunNotes) { savedNotes += notes }
    }
    private val notesFlow = MutableStateFlow<Map<String, com.wangzan101.mika.memory.RunNotes>>(emptyMap())
    private fun item(id: String, content: String) = com.wangzan101.mika.core.memory.MemoryItem(
        id, com.wangzan101.mika.core.memory.MemoryKind.preference, content, emptyList(),
        com.wangzan101.mika.core.memory.MemorySource("manual", atMs = 0), com.wangzan101.mika.core.memory.MemoryStatus.active, createdAt = 0, updatedAt = 0,
    )
    private val items = MutableStateFlow(listOf(item("a", "用户点外卖不要香菜"), item("b", "用户喝咖啡不加糖"), item("p1", "点外卖先比价")))

    private fun TestScope.vmWithHistory(): ChatViewModel {
        val store = ConversationStore(tmp.newFolder())
        store.append("c1", Turn("点外卖", "已下单\n依据: 订单页", "succeeded", runId = "r1"))
        return ChatViewModel(backgroundScope, state, stage, actions, store, runNotes = notesFlow, learning = learning, memoryItems = items).also {
            runCurrent(); it.openConversation("c1"); runCurrent()
        }
    }

    private val remembered = com.wangzan101.mika.core.memory.MemoryNote(com.wangzan101.mika.core.memory.NoteKind.added, "a", "t1", null)
    private val procedure = com.wangzan101.mika.core.memory.MemoryNote(com.wangzan101.mika.core.memory.NoteKind.pending, "p1", "t2", "p1")

    @Test fun memoryNotesShownUnderResult() = runTest {
        val vm = vmWithHistory()
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", listOf(remembered)))
        runCurrent()
        val note = vm.ui.value.exchanges.single().memoryNotes.single()
        assertEquals("记住了:用户点外卖不要香菜", vm.noteText(note))
        // 内容现取:那条删掉后写「一条已删除的记忆」
        items.value = items.value.filter { it.id != "a" }
        assertEquals("记住了:一条已删除的记忆", vm.noteText(note))
        // 重新打开对话时从存下的那一轮读回(只存了种类和编号)
        val store = ConversationStore(tmp.newFolder())
        store.append("c2", Turn("点外卖", "ok", "succeeded", runId = "r2", memoryNotes = listOf(remembered), usedMemory = listOf("a")))
        val vm2 = ChatViewModel(backgroundScope, state, stage, actions, store).also { runCurrent(); it.openConversation("c2"); runCurrent() }
        assertEquals(listOf(remembered), vm2.ui.value.exchanges.single().memoryNotes)
    }

    @Test fun undoRemovesNote() = runTest {
        val vm = vmWithHistory()
        val expired = remembered.copy(undoToken = "expired")
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", listOf(remembered, expired)))
        runCurrent()
        vm.undoNote("r1", remembered); runCurrent()
        assertEquals(listOf("undo:t1"), learnCalls)
        assertEquals(listOf(expired), vm.ui.value.exchanges.single().memoryNotes)
        // 令牌失效:不删提示,只去掉「撤销」
        vm.undoNote("r1", expired); runCurrent()
        assertEquals(listOf(expired.copy(undoToken = null)), vm.ui.value.exchanges.single().memoryNotes)
        assertEquals(listOf(expired.copy(undoToken = null)), savedNotes.last().memoryNotes)
    }

    @Test fun pendingProcedureShowsYesNo() = runTest {
        val vm = vmWithHistory()
        val second = procedure.copy(undoToken = "expired", pendingId = "p2", memoryId = "p2")
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", listOf(procedure, second)))
        runCurrent()
        assertEquals("要我以后都这样做吗?点外卖先比价", vm.noteText(procedure))
        vm.approveNote("r1", procedure); runCurrent()
        assertEquals(listOf("approve:p1"), learnCalls)
        // 「不要」时令牌已失效:直接删掉那条做法
        vm.rejectNote("r1", vm.ui.value.exchanges.single().memoryNotes.single()); runCurrent()
        assertEquals(listOf("approve:p1", "undo:expired", "forget:p2"), learnCalls)
        assertTrue(vm.ui.value.exchanges.single().memoryNotes.isEmpty())
    }

    @Test fun usedMemoryCountShown() = runTest {
        val vm = vmWithHistory()
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", usedMemory = listOf("a", "b")))
        runCurrent()
        val ex = vm.ui.value.exchanges.single()
        assertEquals(listOf("用户点外卖不要香菜", "用户喝咖啡不加糖"), vm.usedMemoryTexts(ex.usedMemory))
        // 全部清除后不再列出(界面据此不显示「用到 n 条记忆」)
        items.value = emptyList()
        assertTrue(vm.usedMemoryTexts(ex.usedMemory).isEmpty())
    }

    @Test fun suspendedSkillRestoreAndView() = runTest {
        val vm = vmWithHistory()
        val learned = com.wangzan101.mika.core.skills.SkillNote("Mika 学会了一项技能:发微信", draftId = "d1")
        val suspended = com.wangzan101.mika.core.skills.SkillNote("这项技能最近不灵,先停用了", suspended = "wechat-msg")
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", skillNotes = listOf(learned, suspended)))
        runCurrent()
        vm.viewSkill(learned)
        vm.restoreSkill("r1", suspended); runCurrent()
        assertEquals(listOf("view:d1", "restore:wechat-msg"), learnCalls)
        assertEquals(listOf(learned), vm.ui.value.exchanges.single().skillNotes)
    }

    // 在没有任务的对话里按按钮,绝不落到别的对话的任务上;按下时带的是那一刻屏上的对话
    @Test fun buttonsInConversationWithoutRunGoNowhere() = runTest {
        val vm = parallelVm(ConversationStore(tmp.newFolder()))
        active.value = listOf("a")
        show("a", TaskState.WaitingInput("哪个?", null))
        vm.switchTo("b")
        calls.clear()
        vm.cancel(); vm.resume(); vm.answer("第一个"); vm.approve(7)
        assertTrue(calls.isEmpty())

        vm.switchTo("a")
        calls.clear()
        vm.resume()
        vm.switchTo("b")
        assertEquals(listOf("resume@a"), calls.filter { "@" in it })
    }

    // 接管进度只跟屏上这个对话的任务;切到没有任务的对话时清掉
    @Test fun takeoverStageFollowsScreenConversation() = runTest {
        val vm = parallelVm(ConversationStore(tmp.newFolder()))
        active.value = listOf("a")
        vm.switchTo("a")
        show("a", TaskState.WaitingTakeover("login", "请登录"))
        stage.value = Takeover.Stage.HandedOver
        runCurrent()
        assertEquals(Takeover.Stage.HandedOver, vm.ui.value.takeoverStage)
        vm.switchTo("b")
        assertEquals(Takeover.Stage.None, vm.ui.value.takeoverStage)
        stage.value = Takeover.Stage.Waiting
        runCurrent()
        assertEquals(Takeover.Stage.None, vm.ui.value.takeoverStage)
        vm.newConversation()
        assertEquals(Takeover.Stage.None, vm.ui.value.takeoverStage)
    }

    // 导出的文件和成功标准一样是每个任务各自的:服务显示的是别的对话的任务时,不挂到屏上这个对话的卡片上
    @Test fun sharedFilesFollowScreenConversation() = runTest {
        val files = MutableStateFlow<List<com.wangzan101.mika.chat.SharedFile>>(emptyList())
        val vm = parallelVm(ConversationStore(tmp.newFolder()), files)
        active.value = listOf("a", "b")
        vm.switchTo("a")
        show("a", TaskState.Running(1, "整理"))
        val mine = com.wangzan101.mika.chat.SharedFile("a.csv", 1, "Download/Mika/a.csv", "content://media/1")
        files.value = listOf(mine)
        runCurrent()
        assertEquals(listOf(mine), vm.ui.value.exchanges.last().files)
        show("b", TaskState.Running(1, "查快递"))
        files.value = listOf(com.wangzan101.mika.chat.SharedFile("b.csv", 1, "Download/Mika/b.csv", "content://media/2"))
        runCurrent()
        assertEquals(listOf(mine), vm.ui.value.exchanges.last().files)
    }

    // 每个对话的卡片用它自己那次的运行编号,不管服务当时显示的是哪个任务
    @Test fun endedCardUsesItsConversationsRunId() = runTest {
        val ended = MutableStateFlow<Map<String, String?>>(emptyMap())
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), serviceBusy = MutableStateFlow(true),
            endedRunId = MutableStateFlow("r-shown-other"), endedRunIds = ended,
            parallelFlag = { true }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        show("a", TaskState.Running(1, "打开设置"))
        // 别的对话的任务结束了,只记在它自己名下
        ended.value = mapOf("b" to "r-b")
        runCurrent()
        assertNull(vm.ui.value.exchanges.last().runId)
        ended.value = mapOf("b" to "r-b", "a" to "r-a")
        show("a", TaskState.Succeeded("12"))
        assertEquals("r-a", vm.ui.value.exchanges.last().runId)
    }

    // 跑的时候就挂上运行编号(「运行过程」当场能进),按卡片所在的对话取,不用服务显示中的那个
    @Test fun runningCardUsesItsOwnConversationsLiveRunId() = runTest {
        val live = mutableMapOf<String, String>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), serviceBusy = MutableStateFlow(true),
            endedRunIds = MutableStateFlow(mapOf("a" to "r-a")), liveRunId = { live[it] },
            parallelFlag = { true }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        live["b"] = "r-b"
        show("a", TaskState.Running(0, "正在准备"))
        assertNull("这个对话的任务还没分到编号:不拿别的对话的", vm.ui.value.exchanges.last().runId)
        live["a"] = "r-a"
        show("a", TaskState.Running(1, "打开设置"))
        assertEquals("r-a", vm.ui.value.exchanges.last().runId)
        show("a", TaskState.Succeeded("12"))
        assertEquals("r-a", vm.ui.value.exchanges.last().runId)
    }

    // 开关在界面建好之后才打开:开始任务时重读,不用重建界面
    @Test fun parallelFlagReadWhenStarting() = runTest {
        var flag = false
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            parallelFlag = { flag }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        flag = true
        assertTrue(vm.send("设置版本号", ok))
        assertEquals("start:设置版本号", calls.last())
        val first = vm.ui.value.conversationId
        active.value = listOf(first!!)
        runCurrent()
        // 新对话里能接着开第二个
        vm.newConversation()
        assertTrue(vm.ui.value.conversationId != first)
        assertTrue(vm.send("新建闹钟", ok))
        assertEquals("start:新建闹钟", calls.last())
    }

    // 换对话时也重读开关
    @Test fun parallelFlagReadWhenSwitching() = runTest {
        var flag = false
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            parallelFlag = { flag }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        active.value = listOf("b")
        runCurrent()
        flag = true
        vm.openConversation("b")
        // 并行时切到有任务在跑的对话会让服务显示它
        assertTrue("show:b" in calls)
    }

    // 有没有屏也是每个任务各自的:显示中的是别的对话的任务时不进这张卡片
    @Test fun otherConversationsScreenStaysOut() = runTest {
        val screen = MutableStateFlow(true)
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen,
            parallelFlag = { true }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        vm.switchTo("a")
        // 别的对话的任务开着屏:新任务还没有屏,不出画中画
        assertTrue(vm.send("设置版本号", ok))
        assertFalse(vm.ui.value.pip.visible)
        show("b", TaskState.Running(1, "看屏幕"))
        runCurrent()
        show("a", TaskState.Running(1, "打开设置"))
        runCurrent()
    }

    // 真机上界面线程是立即调度的:服务一换显示,状态当场就送进来。切走再切回一个还在跑的对话,
    // 那一问一答的原话、步骤(包括切走期间的)、文件都要在,不能另起一条空原话的
    @Test fun switchingBackRestoresTheRunningExchange() = runTest {
        val runStates = mapOf("a" to MutableStateFlow<TaskState>(TaskState.Running(0, "")), "b" to MutableStateFlow<TaskState>(TaskState.Running(0, "")))
        val fileA = com.wangzan101.mika.chat.SharedFile("a.csv", 1, "Download/Mika/a.csv", "content://media/1")
        val runFiles = mapOf("a" to listOf(fileA), "b" to emptyList())
        val goals = mapOf("a" to "第一个任务", "b" to "第二个任务")
        val files = MutableStateFlow<List<com.wangzan101.mika.chat.SharedFile>>(emptyList())
        // 和服务一样:换显示时先抄文件,再抄带对话编号的状态
        val immediate = object : TaskActions by actions {
            override fun show(conversationId: String) {
                files.value = runFiles.getValue(conversationId)
                shown.value = conversationId to runStates.getValue(conversationId).value
            }
        }
        fun publish(c: String, s: TaskState) {
            runStates.getValue(c).value = s
            if (shown.value?.first == c) shown.value = c to s
        }
        val ui = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        val vm = ChatViewModel(
            ui, state, stage, immediate, ConversationStore(tmp.newFolder()), serviceBusy = MutableStateFlow(true),
            parallelFlag = { true }, shownTask = shown, activeConversations = active, runGoal = goals::get, runState = runStates::get,
            sharedFiles = files,
        )
        vm.switchTo("a")
        assertTrue(vm.send("第一个任务", ok))
        active.value = listOf("a")
        immediate.show("a")
        publish("a", TaskState.Running(1, "打开设置", "phone_launch_app"))

        // 切到没有任务的 b 开第二个任务:a 的那一问一答停到一边,a 接着跑
        vm.switchTo("b")
        assertTrue(vm.send("第二个任务", ok))
        active.value = listOf("a", "b")
        immediate.show("b")
        publish("b", TaskState.Running(1, "看屏幕"))
        publish("a", TaskState.Running(2, "点版本号", "phone_tap", reason = "点版本号"))
        assertEquals("第二个任务", vm.ui.value.exchanges.last().goal)

        vm.switchTo("a")
        val ex = vm.ui.value.exchanges.last()
        assertEquals("第一个任务", ex.goal)
        assertEquals(listOf(fileA), ex.files)
        // 切走期间 a 的进展照样记着
        assertEquals("点版本号", ex.line)
        assertEquals(ExchangeStatus.Working, ex.status)
        assertEquals(1, vm.ui.value.exchanges.size)
    }

    @Test fun withdrawRemovesOnlyTheUnprocessedMessage() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Steer)
        vm.send("撤回这句", ok)
        vm.send("留下这句", ok)
        val id = steerIds.first()
        assertTrue(vm.withdrawSteer(id))
        assertEquals(listOf(SteerState.Withdrawn, SteerState.Pending), vm.ui.value.exchanges.single().steers.map { it.state })
        assertFalse(vm.withdrawSteer(id))
        assertEquals(1, calls.count { it == "withdraw:$id" })
        assertEquals("留下这句", vm.ui.value.exchanges.single().steers.last().text)
    }

    @Test fun messagesWhileRunningQueueByDefault() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)
        vm.send("下一件", ok)
        runCurrent()
        assertEquals(listOf("queue:下一件"), calls.filter { it.startsWith("queue:") })
        assertEquals(listOf("下一件"), vm.ui.value.queued.map { it.text })
        assertTrue(vm.ui.value.exchanges.single().steers.isEmpty())
    }

    @Test fun guideQueuedMovesTheMessageIntoTheCurrentTask() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.send("下一件", ok)
        runCurrent()
        val item = vm.ui.value.queued.single()
        assertTrue(vm.guideQueued(item.id, ok))
        runCurrent()
        assertTrue(vm.ui.value.queued.isEmpty())
        assertEquals(listOf("下一件"), vm.ui.value.exchanges.single().steers.map { it.text })
        assertTrue(calls.contains("cancelQueued:${item.id}"))
        assertTrue(calls.contains("steer:下一件"))
    }

    @Test fun guideQueuedDoesNotMoveAMessageAfterTheTaskEnds() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.send("下一件", ok)
        runCurrent()
        emit(TaskState.Succeeded("完成了"))
        assertFalse(vm.guideQueued(vm.ui.value.queued.single().id, ok))
        assertEquals(listOf("下一件"), vm.ui.value.queued.map { it.text })
        assertTrue(vm.ui.value.exchanges.single().steers.isEmpty())
    }

    @Test fun queueModeEnqueuesInsteadOfSteering() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Queue)
        vm.send("下一件", ok)
        runCurrent()
        assertEquals(listOf("queue:下一件"), calls.filter { it.startsWith("queue:") })
        assertTrue(vm.ui.value.exchanges.single().steers.isEmpty())
        assertEquals(listOf("下一件"), vm.ui.value.queued.map { it.text })
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)
    }

    @Test fun failedQueueSaveDoesNotReportDurableItem() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Queue)
        queueSaveSucceeds = false
        assertFalse(vm.send("不能丢", ok))
        runCurrent()
        assertTrue(vm.ui.value.queued.isEmpty())
        assertTrue(vm.ui.value.exchanges.single().steers.isEmpty())
    }

    @Test fun newTaskKeepsQueueAsTheDefaultFollowUpMode() = runTest {
        val vm = vm()
        vm.send("新任务", ok)
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)
    }

    @Test fun queuedTrayFollowsStoreAndHidesClaimedAndFallback() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Queue)
        vm.send("下一件", ok)
        runCurrent()
        val item = vm.ui.value.queued.single()
        // 服务取走:排队条隐藏
        followUpFlow.value = listOf(item.copy(claimed = true))
        runCurrent()
        assertTrue(vm.ui.value.queued.isEmpty())
        // fallback 不由排队条显示(交给插话那行的「下一件」)
        followUpFlow.value = listOf(item.copy(claimed = false, source = QueuedSource.Fallback))
        runCurrent()
        assertTrue(vm.ui.value.queued.isEmpty())
        // 别的对话的排队不混进来
        followUpFlow.value = listOf(item.copy(conversationId = "other"))
        runCurrent()
        assertTrue(vm.ui.value.queued.isEmpty())
    }

    @Test fun cancelAndEditQueuedUseTheServiceAndRestoreText() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Queue)
        vm.send("取回这句", ok)
        runCurrent()
        val id = vm.ui.value.queued.single().id
        assertEquals("取回这句", vm.editQueued(id))
        assertTrue(vm.ui.value.queued.isEmpty())
        assertTrue(calls.contains("cancelQueued:$id"))

        vm.setFollowUpMode(FollowUpMode.Queue)
        vm.send("取消这句", ok)
        runCurrent()
        val cancelId = vm.ui.value.queued.single().id
        assertTrue(vm.cancelQueued(cancelId))
        assertTrue(vm.ui.value.queued.isEmpty())
    }

    @Test fun stopKeepsExplicitQueue() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Queue)
        vm.send("下一件", ok)
        runCurrent()
        vm.cancel()
        assertEquals(listOf("下一件"), vm.ui.value.queued.map { it.text })
    }


    @Test fun editingRestoresQuoteAndTextWithoutOverwritingAnotherQuote() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Steer)
        val quote = QuoteMessage.Quote(QuoteMessage.From.Mika, "明天有雨")
        vm.quote(quote.from, quote.text)
        vm.send("改这句话", ok)
        val id = steerIds.single()
        vm.quote(QuoteMessage.From.You, "正在写的引用")
        assertNull(vm.editSteer(id))
        vm.clearQuote()
        assertEquals("改这句话", vm.editSteer(id))
        assertEquals(quote, vm.ui.value.quote)
        assertEquals(SteerState.Withdrawn, vm.ui.value.exchanges.single().steers.single().state)
        vm.send("改好的话", ok)
        assertEquals("steer:${QuoteMessage.compose(quote, "改好的话")}", calls.last())
    }

    @Test fun failedWithdrawalDoesNotPretendTheModelMissedTheMessage() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Steer)
        vm.send("模型刚取走", ok)
        val id = steerIds.single()
        canWithdraw = false
        assertNull(vm.editSteer(id))
        assertEquals(SteerState.Pending, vm.ui.value.exchanges.single().steers.single().state)
        assertNull(vm.ui.value.quote)
        steerFlow.value = listOf(SteerNote(id, "模型刚取走", SteerState.Joined))
        runCurrent()
        assertFalse(vm.withdrawSteer(id))
        assertEquals(SteerState.Joined, vm.ui.value.exchanges.single().steers.single().state)
    }

    // 电脑追加的话也显示在对话里,但长按不给撤回、编辑,直接调也撤不回,不去问服务
    @Test fun remoteSteersOfferNoWithdrawOrEdit() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.send("手机插话", ok)
        val local = steerIds.single()
        steerFlow.value = listOf(SteerNote(local, "手机插话", SteerState.Received), SteerNote("r", "电脑追加", SteerState.Received, remote = true))
        runCurrent()
        val exchanges = vm.ui.value.exchanges
        assertEquals(listOf(false, true), exchanges.single().steers.map { it.remote })
        assertNull(withdrawableSteer(exchanges, "m0-r"))
        assertEquals(local, withdrawableSteer(exchanges, "m0-$local")?.id)
        assertFalse(vm.withdrawSteer("r"))
        assertNull(vm.editSteer("r"))
        assertFalse(calls.any { it == "withdraw:r" })
        assertEquals(SteerState.Received, vm.ui.value.exchanges.single().steers.last().state)
    }

    // 悬浮胶囊带着屏幕、浏览器页带着页面说明插的话也显示在对话里:取回编辑会把标记原文放进输入框,只给撤回,不给编辑
    @Test fun steersWithAScreenOrAPageCanOnlyBeWithdrawn() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        val screen = UserScreenMessage.compose("s1", "这个按钮是干什么的")
        val page = AttachedPage.compose(AttachedPage.Page("https://example.com/a", "标题", "选中的一段"), "这段什么意思")
        steerFlow.value = listOf(SteerNote("s", screen, SteerState.Received), SteerNote("p", page, SteerState.Received))
        runCurrent()
        assertEquals("s", withdrawableSteer(vm.ui.value.exchanges, "m0-s")?.id)
        assertFalse(editableSteer(screen))
        assertFalse(editableSteer(page))
        assertNull(vm.editSteer("s"))
        assertNull(vm.editSteer("p"))
        assertFalse(calls.any { it.startsWith("withdraw:") })
        assertTrue(vm.withdrawSteer("p"))
        assertEquals(listOf(SteerState.Received, SteerState.Withdrawn), vm.ui.value.exchanges.single().steers.map { it.state })
        // 只有字和引用的照旧能编辑
        assertTrue(editableSteer("改这句话"))
        assertTrue(editableSteer(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, "明天有雨"), "改这句话")))
    }

    @Test fun withdrawingBeforeTheTaskStartsPreventsLaterDelivery() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), serviceBusy = busy)
        runCurrent()
        vm.send("当前任务", ok)
        vm.setFollowUpMode(FollowUpMode.Steer)
        vm.send("别发这句", ok)
        val id = vm.ui.value.exchanges.single().steers.single().id
        assertTrue(vm.withdrawSteer(id))
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:当前任务"), calls)
        assertEquals(SteerState.Withdrawn, vm.ui.value.exchanges.single().steers.single().state)
    }

    @Test fun queueWhileWaitingForBusyServicePersistsWithoutStartingEarly() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            serviceBusy = busy, followUps = followUpFlow)
        runCurrent()
        vm.send("当前任务", ok)
        vm.setFollowUpMode(FollowUpMode.Queue)
        assertTrue(vm.send("下一件", ok))
        runCurrent()
        // 原话还没交给服务:下一件已经持久化,但明确不许抢跑;原话开始也不再重写。
        assertEquals(listOf("下一件"), vm.ui.value.queued.map { it.text })
        assertEquals(listOf(false), queueStartIfIdle)
        busy.value = false
        runCurrent()
        assertEquals(1, calls.count { it == "start:当前任务" })
        assertEquals(1, calls.count { it == "queue:下一件" })
        assertEquals(listOf("下一件"), vm.ui.value.queued.map { it.text })
    }

    // 任务在跑时输入框照样能发:发出去的话挂在这一问一答下面,交给当前任务;去向由服务报回来
    @Test fun messageWhileRunningSteersTheTask() = runTest {
        val vm = vm()
        vm.send("订明天的会议室", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        vm.setFollowUpMode(FollowUpMode.Steer)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        assertTrue(vm.send("改成后天", ok))
        assertEquals("steer:改成后天", calls.last())
        var ex = vm.ui.value.exchanges.single()
        assertEquals(listOf("改成后天"), ex.steers.map { it.text })
        assertNull(steerLabel(ex.steers.single().state))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        // 模型下一步之前拿到了这句话
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成后天", SteerState.Joined))
        runCurrent()
        assertEquals("已加入当前任务", steerLabel(vm.ui.value.exchanges.single().steers.single().state))

        // 又说一句,任务就收尾了:这句没交到任务手里,作为下一件事
        assertTrue(vm.send("顺便订午饭", ok))
        emit(TaskState.Succeeded("订好了"))
        ex = vm.ui.value.exchanges.single()
        assertEquals(listOf(SteerState.Joined, SteerState.Next), ex.steers.map { it.state })
        assertEquals("当前任务已结束，已排到下一件", steerLabel(ex.steers.last().state))
        assertEquals(1, vm.ui.value.exchanges.size)
    }

    // 插话打断了正在写的回复:那段字随插话的去向一起报回来,挂在插话上;之后状态再变也不丢
    @Test fun interruptedReplyStaysWithItsSteer() = runTest {
        val vm = vm()
        vm.send("查今天的天气", ok)
        emit(TaskState.Running(1, "", preview = "今天晴,最高"))
        assertTrue(vm.send("改成查明天", ok))

        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成查明天", SteerState.Joined, interrupted = "今天晴,最高"))
        emit(TaskState.Running(2, ""))
        val note = vm.ui.value.exchanges.single().steers.single()
        assertEquals("今天晴,最高", note.interrupted)
        assertEquals(SteerState.Joined, note.state)

        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成查明天", SteerState.Joined))
        runCurrent()
        assertEquals("今天晴,最高", vm.ui.value.exchanges.single().steers.single().interrupted)
    }

    // 任务的收件箱一收下就有回音「已收到，下一步调整当前任务」;任务就此收尾时它和还没发到的一样作为下一件事
    @Test fun receivedSteerIsShownAtOnceAndBecomesNextIfTheRunEnds() = runTest {
        val vm = vm()
        vm.send("订明天的会议室", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertTrue(vm.send("改成后天", ok))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成后天", SteerState.Received))
        runCurrent()
        val note = vm.ui.value.exchanges.single().steers.single()
        assertEquals(SteerState.Received, note.state)
        assertEquals("已收到，下一步调整当前任务", steerLabel(note.state))
        emit(TaskState.Succeeded("订好了"))
        assertEquals(SteerState.Next, vm.ui.value.exchanges.single().steers.single().state)
    }

    @Test fun stopCancelsReceivedSteersToo() = runTest {
        val vm = vm()
        vm.send("订明天的会议室", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertTrue(vm.send("改成后天", ok))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成后天", SteerState.Received))
        runCurrent()
        vm.cancel()
        assertEquals(SteerState.Cancelled, vm.ui.value.exchanges.single().steers.single().state)
    }

    // 收尾之后服务才报回去向:照样改到那一问一答上
    @Test fun lateSteerOutcomeStillLandsOnItsExchange() = runTest {
        val vm = vm()
        vm.send("查快递", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        vm.setFollowUpMode(FollowUpMode.Steer)
        vm.send("只看顺丰的", ok)
        emit(TaskState.Failed("网络断了", FailKind.Environment))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "只看顺丰的", SteerState.Next))
        runCurrent()
        assertEquals(SteerState.Next, vm.ui.value.exchanges.single().steers.single().state)
    }

    // 没成功的动作只说没成功,不把错误码给人看
    @Test fun failedActionHidesErrorCode() = runTest {
        val vm = vm()
        vm.send("发消息", ok)
        emit(TaskState.Running(1, "", "phone_tap"))
        did(1, "act", "phone_tap", "com.chat", "ref_expired", failed = true)
        val row = vm.ui.value.exchanges.single().steps.single()
        assertEquals("点击 · 聊天", row.title)
        assertEquals("没成功", row.subtitle)
        assertFalse(row.toString().contains("ref_expired"))
    }

    // 让它记、它按规矩没收(不是你亲口说的、敏感、没明确要改):不是出了故障,不写「没成功」,说没收和原因;工具用界面上的名字
    @Test fun declinedMemoryWriteSaysWhyInsteadOfFailed() = runTest {
        val vm = vm()
        vm.send("总结几条写进记忆", ok)
        emit(TaskState.Running(1, "", "memory_save"))
        did(1, "observe", "memory_save", null, "content_not_from_user", failed = true)
        did(2, "observe", "memory_save", null, "sensitive", failed = true)
        did(3, "observe", "memory_edit", null, "edit_not_requested", failed = true)
        did(4, "observe", "memory_edit", null, "not_found", failed = true)
        val rows = vm.ui.value.exchanges.single().steps
        assertEquals(listOf("写入记忆", "写入记忆", "修改记忆", "修改记忆"), rows.map { it.title })
        assertEquals(listOf("不是你亲口说的,没有写入", "含敏感信息,没有写入", "你没明确要改或删,没有改动", "没成功"), rows.map { it.subtitle })
        assertFalse(rows.toString().contains("content_not_from_user") || rows.toString().contains("memory_save"))
        // 点开一步、运行过程页用的是同一份说法
        assertEquals("不是你亲口说的,没有写入", ChatCopy.effectLabel("quote_not_from_user"))
        assertEquals("没成功", ChatCopy.effectLabel("memory_full"))
        // 开着「让 Mika 自己归纳着记」、但这句话没让它记
        assertEquals("这句话没让它记,没有写入", ChatCopy.effectLabel("inference_not_requested"))
    }

    // 原话的任务还在等服务收尾上一个时插的话:先开始原话的任务,再把插话交给它
    @Test fun steerDuringPendingStartIsSentAfterTheStart() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy, steers = steerFlow)
        runCurrent()
        assertTrue(vm.send("订会议室", ok))
        vm.setFollowUpMode(FollowUpMode.Steer)
        assertTrue(vm.send("要大一点的", ok))
        runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals(listOf("要大一点的"), vm.ui.value.exchanges.single().steers.map { it.text })
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:订会议室", "steer:要大一点的"), calls)
    }

    // 停止时还没交到任务手里的插话一起作废,标成已取消;还在等服务时停止,插话也不发
    @Test fun stopCancelsPendingSteers() = runTest {
        val vm = vm()
        vm.send("查快递", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        vm.setFollowUpMode(FollowUpMode.Steer)
        vm.send("只看顺丰的", ok)
        vm.cancel()
        assertEquals("cancel", calls.last())
        assertEquals(SteerState.Cancelled, vm.ui.value.exchanges.single().steers.single().state)
        assertEquals("已取消", steerLabel(SteerState.Cancelled))
        emit(TaskState.Cancelled)
        assertEquals(SteerState.Cancelled, vm.ui.value.exchanges.single().steers.single().state)

        val busy = MutableStateFlow(true)
        calls.clear()
        val vm2 = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        vm2.send("订会议室", ok)
        vm2.setFollowUpMode(FollowUpMode.Steer)
        vm2.send("要大一点的", ok)
        vm2.cancel()
        busy.value = false
        runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals(SteerState.Cancelled, vm2.ui.value.exchanges.single().steers.single().state)
    }

    // 插话可能成为下一件事:和开新任务过同一道检查
    @Test fun steerIsCheckedLikeANewTask() = runTest {
        val vm = vm()
        vm.send("查快递", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertFalse(vm.send("只看顺丰的", Prerequisites.check(ready, hasMainModel = false)))
        assertEquals(listOf(Problem.NoModel), vm.ui.value.blocked)
        assertTrue(vm.ui.value.exchanges.single().steers.isEmpty())
    }

    // 引用随下一句话发出去:新任务和插话都带上,发完就清掉;只有引用没写话时不发
    @Test fun quoteGoesWithTheNextMessage() = runTest {
        val vm = vm()
        vm.quote(QuoteMessage.From.Mika, "  明天有小雨 ")
        assertEquals(QuoteMessage.Quote(QuoteMessage.From.Mika, "明天有小雨"), vm.ui.value.quote)
        assertFalse(vm.send("  ", ok))
        assertTrue(calls.isEmpty())

        assertTrue(vm.send("那要带伞吗", ok))
        val goal = "> [Quoting your earlier reply]\n> 明天有小雨\n\n那要带伞吗"
        assertEquals(listOf("start:$goal"), calls)
        assertEquals(goal, vm.ui.value.exchanges.single().goal)
        assertNull(vm.ui.value.quote)

        emit(TaskState.Running(1, "", "phone_launch_app"))
        vm.setFollowUpMode(FollowUpMode.Steer)
        vm.quote(QuoteMessage.From.You, "查天气")
        assertTrue(vm.send("改成后天", ok))
        val steer = "> [Quoting my earlier message]\n> 查天气\n\n改成后天"
        assertEquals("steer:$steer", calls.last())
        assertEquals(steer, vm.ui.value.exchanges.single().steers.single().text)
        assertNull(vm.ui.value.quote)

        // 去掉引用:照常只发话
        vm.quote(QuoteMessage.From.Mika, "x")
        vm.clearQuote()
        vm.send("再快一点", ok)
        assertEquals("steer:再快一点", calls.last())
    }

    // 引用只属于屏上这个对话:换对话、开新对话时清掉
    @Test fun quoteIsDroppedWhenTheConversationChanges() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded"))
        val vm = vm(store)
        vm.quote(QuoteMessage.From.Mika, "晴")
        vm.openConversation(id)
        assertNull(vm.ui.value.quote)
        vm.quote(QuoteMessage.From.Mika, "晴")
        vm.newConversation()
        assertNull(vm.ui.value.quote)
    }

    // 存下的带引用的话重开时原样回来(界面据此画出引的那条);旧的话没有引用
    @Test fun quotedTurnsSurviveReopening() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        val quoted = QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, "晴"), "那后天呢")
        store.append(id, Turn("查天气", "晴", "succeeded"))
        store.append(id, Turn(quoted, "多云", "succeeded", steers = listOf(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.You, "查天气"), "顺便看风"))))
        val vm = vm(store)
        vm.openConversation(id)
        val (old, new) = vm.ui.value.exchanges
        assertNull(QuoteMessage.parse(old.goal).quote)
        assertEquals("查天气", QuoteMessage.parse(old.goal).words)
        assertEquals(QuoteMessage.Quote(QuoteMessage.From.Mika, "晴"), QuoteMessage.parse(new.goal).quote)
        assertEquals("那后天呢", QuoteMessage.parse(new.goal).words)
        assertEquals("顺便看风", QuoteMessage.parse(new.steers.single().text).words)
        assertEquals("查天气", ChatCopy.title(vm.ui.value.exchanges))
    }

    // 交给任务的插话随那一轮存下:重开对话、重启后照样显示在那一轮下面
    @Test fun joinedSteersSurviveReopening() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("订会议室", "订好了", "succeeded", steers = listOf("要大一点的")))
        val vm = vm(store)
        vm.openConversation(id)
        val ex = vm.ui.value.exchanges.single()
        assertEquals(listOf("要大一点的"), ex.steers.map { it.text })
        assertEquals("已加入当前任务", steerLabel(ex.steers.single().state))
    }

    // 插话打断的那段回复也随那一轮存下,重开时还挂在那句插话上;没打断的那句没有
    @Test fun interruptedRepliesSurviveReopening() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天", "顺便看看后天"), interruptedReplies = listOf("今天晴", "")))
        val vm = vm(store)
        vm.openConversation(id)
        assertEquals(listOf("今天晴", null), vm.ui.value.exchanges.single().steers.map { it.interrupted })
    }

    // 插话之前已经说完的回复:随插话的去向报回来,之后状态再变也不丢;存下后重开照样挂在那句插话上
    @Test fun finishedReplyStaysWithItsSteer() = runTest {
        val vm = vm()
        vm.send("查今天的天气", ok)
        emit(TaskState.Running(1, "", preview = "今天晴,最高 25 度。"))
        assertTrue(vm.send("改成查明天", ok))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成查明天", SteerState.Joined, finished = "今天晴,最高 25 度。"))
        emit(TaskState.Running(2, ""))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成查明天", SteerState.Joined))
        runCurrent()
        assertEquals("今天晴,最高 25 度。", vm.ui.value.exchanges.single().steers.single().finished)
    }

    @Test fun finishedRepliesSurviveReopening() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天", "顺便看看后天"), finishedReplies = listOf("今天晴", "")))
        val vm = vm(store)
        vm.openConversation(id)
        assertEquals(listOf("今天晴", null), vm.ui.value.exchanges.single().steers.map { it.finished })
    }

    // ---- 别处(悬浮胶囊、定时任务)开始的任务和存下的轮次 ----

    /** 开关关着,服务的状态带着对话编号([shown])。 */
    private fun TestScope.taggedVm(store: ConversationStore, endedRunId: MutableStateFlow<String?> = MutableStateFlow(null)) = ChatViewModel(
        backgroundScope, state, stage, actions, store, endedRunId = endedRunId,
        shownTask = shown, activeConversations = active, runGoal = { if (it == "a") "胶囊里说的" else null }, steers = steerFlow, activities = acts,
    ).also { runCurrent() }

    /** 服务显示的任务换了状态:带编号的那份先写,和服务里一样。 */
    private fun TestScope.serve(conversationId: String, s: TaskState) {
        shown.value = conversationId to s
        state.value = s
        runCurrent()
    }

    // 别处跑完存进屏上这个对话的一轮:当场补上,只一条
    @Test fun turnSavedElsewhereAppearsOnce() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = vm(store)
        vm.openConversation("a")
        store.append("a", Turn("订会议室", "订好了", "succeeded", runId = "r2", steers = listOf("要大一点的")))
        runCurrent()
        assertEquals(listOf("查天气", "订会议室"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(listOf("要大一点的"), vm.ui.value.exchanges.last().steers.map { it.text })
        // 后面改了那一轮(补上记忆提示):重读,不多出一条
        store.updateTurn("a", "r2") { it.copy(outcome = "订好了 3 楼") }
        runCurrent()
        assertEquals(2, vm.ui.value.exchanges.size)
        assertEquals("订好了 3 楼", (vm.ui.value.exchanges.last().status as ExchangeStatus.Done).answer)
    }

    // 存进别的对话的轮次不动屏上这个对话
    @Test fun turnSavedInAnotherConversationLeavesScreenAlone() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = vm(store)
        vm.openConversation("a")
        val before = vm.ui.value
        store.append("b", Turn("订会议室", "订好了", "succeeded", runId = "r2"))
        runCurrent()
        assertEquals(before, vm.ui.value)
    }

    // 这里跟的任务结束、那一轮存进对话:不论先存还是先报结束,都只一条,保留这里的步骤
    @Test fun ownRunSavedIsNotDuplicated() = runTest {
        val ended = MutableStateFlow<String?>(null)
        for (savedFirst in listOf(false, true)) {
            val store = ConversationStore(tmp.newFolder())
            val vm = taggedVm(store, ended)
            assertTrue(vm.send("查快递", ok))
            val id = vm.ui.value.conversationId!!
            serve(id, TaskState.Running(1, "", "phone_launch_app"))
            did(1, "act", "phone_launch_app", "com.chat")
            val turn = Turn("查快递", "到了", "succeeded", runId = "r-$savedFirst")
            ended.value = "r-$savedFirst"
            if (savedFirst) { store.append(id, turn); runCurrent() }
            serve(id, TaskState.Succeeded("到了"))
            if (!savedFirst) { store.append(id, turn); runCurrent() }
            val ex = vm.ui.value.exchanges.single()
            assertEquals("r-$savedFirst", ex.runId)
            assertTrue(ex.live)
            assertEquals(1, ex.steps.size)
            assertEquals(ComposerMode.Idle, vm.ui.value.composer)
            acts.value = emptyList()
        }
    }

    // 屏上这个对话里别处开始的任务:接着显示,原话用任务自己的,别处插的话也挂上;结束存下后只一条
    @Test fun runStartedElsewhereInThisConversationIsFollowed() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val ended = MutableStateFlow<String?>(null)
        val vm = taggedVm(store, ended)
        vm.openConversation("a")
        active.value = listOf("a")
        serve("a", TaskState.Running(1, "打开设置"))
        assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        steerFlow.value = listOf(SteerNote("m1", "只看顺丰的", SteerState.Joined))
        runCurrent()
        assertEquals(listOf("只看顺丰的"), vm.ui.value.exchanges.last().steers.map { it.text })
        ended.value = "r2"
        serve("a", TaskState.Succeeded("好了"))
        store.append("a", Turn("胶囊里说的", "好了", "succeeded", runId = "r2", steers = listOf("只看顺丰的")))
        runCurrent()
        assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(listOf("只看顺丰的"), vm.ui.value.exchanges.last().steers.map { it.text })
    }

    // 打开(或回到)一个有任务在跑、这里还没在跟的对话:当场接上,不等下一次状态变化
    @Test fun openingOrRefreshingAttachesActiveRun() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = taggedVm(store)
        active.value = listOf("a")
        shown.value = "a" to TaskState.Running(2, "看屏幕")
        vm.openConversation("a")
        assertTrue("show:a" in calls)
        assertEquals("胶囊里说的", vm.ui.value.exchanges.single().goal)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)

        // 回到前台时再接一次:已经在跟,不另起一条
        vm.refresh()
        assertEquals(1, vm.ui.value.exchanges.size)
    }

    // 开关关着时别的对话的任务不接到屏上这个对话里;这里正在跟的任务被换下显示时让服务显示回来
    @Test fun serialRunInAnotherConversationIsNotAttached() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = taggedVm(store)
        vm.openConversation("a")
        active.value = listOf("b")
        serve("b", TaskState.Running(1, "打开设置"))
        assertEquals(listOf("查天气"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        serve("b", TaskState.Succeeded("好了"))
        assertEquals(1, vm.ui.value.exchanges.size)

        assertTrue(vm.send("查快递", ok))
        active.value = listOf("a")
        serve("a", TaskState.Running(1, "", "phone_launch_app"))
        calls.clear()
        // 定时任务在对话 b 里排上,服务显示了它
        active.value = listOf("a", "b")
        serve("b", TaskState.Held(GateReason.queued("排队中:前面还有 1 个任务")))
        assertEquals(listOf("show:a"), calls)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
    }

    // 还没有对话时接上的任务:连同那个对话存下的轮次一起显示,之后存进来的也对得上
    @Test fun adoptingARunLoadsItsConversation() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = taggedVm(store)
        active.value = listOf("a")
        serve("a", TaskState.Running(1, "打开设置"))
        assertEquals("a", vm.ui.value.conversationId)
        assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
    }

    // ---- 别处开始的任务,按服务里真实的先后:先显示(状态送出),再登记进任务列表 ----

    private val goals = mutableMapOf<String, String>()
    private val endedRun = MutableStateFlow<String?>(null)

    private fun TestScope.registryVm(store: ConversationStore, parallel: Boolean) = ChatViewModel(
        backgroundScope, state, stage, actions, store, appLabel = { if (it == "com.chat") "聊天" else it }, endedRunId = endedRun, parallelFlag = { parallel },
        shownTask = shown, activeConversations = active, runGoal = goals::get, steers = steerFlow, activities = acts,
    ).also { runCurrent() }

    /** 服务开始一个任务:先显示它(带编号的状态、状态),之后才登记进任务列表(对话、原话)。 */
    private fun TestScope.startedElsewhere(conversationId: String, goal: String) {
        val s = TaskState.Running(0, "正在准备")
        shown.value = conversationId to s
        state.value = s
        runCurrent()
        goals[conversationId] = goal
        active.value = active.value + conversationId
        runCurrent()
    }

    // 屏上这个对话里别处(悬浮胶囊)发的话:在跑时就显示那句话和步骤,和在这里发的一样
    @Test fun runStartedElsewhereShowsMessageWhileRunning() = runTest {
        for (parallel in listOf(false, true)) {
            val store = ConversationStore(tmp.newFolder())
            store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
            val vm = registryVm(store, parallel)
            vm.openConversation("a")
            startedElsewhere("a", "胶囊里说的")
            assertEquals("parallel=$parallel", listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
            assertEquals(ComposerMode.Busy, vm.ui.value.composer)
            shown.value = "a" to TaskState.Running(1, "打开设置", "phone_launch_app")
            state.value = shown.value!!.second
            runCurrent()
            did(1, "act", "phone_launch_app", "com.chat")
            assertEquals(listOf("打开 App · 聊天"), vm.ui.value.exchanges.last().steps.map { it.text })
            // 回到前台再接一次:不另起一条
            vm.refresh()
            assertEquals(2, vm.ui.value.exchanges.size)
            assertEquals("胶囊里说的", vm.ui.value.exchanges.last().goal)
            endedRun.value = "r2-$parallel"
            serve("a", TaskState.Succeeded("好了"))
            store.append("a", Turn("胶囊里说的", "好了", "succeeded", runId = "r2-$parallel"))
            runCurrent()
            assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
            active.value = emptyList(); acts.value = emptyList(); goals.clear(); calls.clear()
        }
    }

    // 屏上还没有对话时接上别处开始的任务:那句话也在
    @Test fun adoptedRunShowsItsMessage() = runTest {
        val vm = registryVm(ConversationStore(tmp.newFolder()), parallel = false)
        startedElsewhere("a", "胶囊里说的")
        assertEquals("a", vm.ui.value.conversationId)
        assertEquals(listOf("胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
    }

    // 别处在新对话里开始的任务:侧栏列在进行中;打开它(回到 Mika)就看到那句话和在跑的状态
    @Test fun runInNewConversationElsewhereOpensWithMessage() = runTest {
        for (parallel in listOf(false, true)) {
            val store = ConversationStore(tmp.newFolder())
            store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
            val vm = registryVm(store, parallel)
            vm.openConversation("a")
            startedElsewhere("n", "新对话里说的")
            assertEquals(listOf("查天气"), vm.ui.value.exchanges.map { it.goal })
            assertEquals(listOf("n"), vm.otherRunning.value)
            val sections = drawerSections(store.list(), CurrentConversation("a", false, "查天气", ""), vm.otherRunning.value, vm::runningGoal, vm.canSwitch(), 0L)
            assertEquals(listOf("新对话里说的"), sections.running.map { it.title })
            vm.openConversation("n")
            assertEquals("n", vm.ui.value.conversationId)
            assertEquals(listOf("新对话里说的"), vm.ui.value.exchanges.map { it.goal })
            assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)
            serve("n", TaskState.Succeeded("好了"))
            active.value = emptyList(); goals.clear(); calls.clear()
        }
    }

    // 正在长出来的回答排在已交给模型的插话后面、还没交给模型的插话前面
    @Test fun draftSitsBetweenJoinedAndWaitingSteers() {
        val joined = SteerNote("a", "改成明天", SteerState.Joined)
        val received = SteerNote("b", "简短点", SteerState.Received)
        val pending = SteerNote("c", "再简单点", SteerState.Pending)
        assertEquals(listOf(joined) to listOf(received, pending), steersAroundDraft(listOf(joined, received, pending)))
        assertEquals(listOf(joined) to emptyList<SteerNote>(), steersAroundDraft(listOf(joined)))
        assertEquals(emptyList<SteerNote>() to listOf(received), steersAroundDraft(listOf(received)))
    }
    /** 等另一个任务放开 App 时仍在干活:进展那一行写在等谁,不算暂停、也不算要你处理。 */
    @Test fun waitingForAnotherTasksAppIsStillWorking() = runTest {
        val vm = vm()
        vm.send("给张三发消息", ok)
        emit(TaskState.Running(2, "打开聊天", tool = "phone_launch_app", progress = com.wangzan101.mika.core.agent.RunProgress.WaitingForApp("聊天")))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Working, ex.status)
        assertEquals("等另一个任务用完聊天", ex.line)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
    }

    /** 屏一开小画面就浮出来;用户关掉后只剩小按钮,任务照常在跑。 */
    @Test fun smallWindowCanBeClosedForThisTask() = runTest {
        val open = MutableStateFlow(false)
        val vm = vmWithScreen(open)
        vm.send("打开设置看电量", ok)
        emit(TaskState.Running(0, "正在准备"))
        assertFalse("还没开屏时不浮出来", vm.ui.value.pip.visible)
        open.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertFalse(vm.ui.value.pip.closed)

        vm.closePip()
        assertTrue("关掉后还有小按钮", vm.ui.value.pip.visible)
        assertTrue(vm.ui.value.pip.closed)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)
        // 之后的进展不让它自己再浮出来
        emit(TaskState.Running(1, "点了设置"))
        assertTrue(vm.ui.value.pip.closed)

        vm.reopenPip()
        assertFalse("点小按钮再打开", vm.ui.value.pip.closed)
    }

    @Test fun pipSoundButtonAlwaysDispatchesRealActionAndDoesNotInventState() = runTest {
        val vm = vm()
        vm.send("打开视频", ok)
        val id = vm.ui.value.conversationId!!
        calls.clear()
        val before = vm.ui.value.pip
        // 方向由任务里的真实音频记录定(恢复/再静上),界面每次只发真实动作,不自己记开关态
        vm.togglePipSound()
        vm.togglePipSound()
        assertEquals(listOf("toggle-sound@$id", "toggle-sound@$id"), calls)
        assertEquals(before, vm.ui.value.pip)
    }

    /** 关着小画面时要你接管:不自己弹开,但小按钮进入「要你处理」的样子,要你做的事照样在对话卡片里。 */
    @Test fun closedSmallWindowStillSignalsWhenItNeedsYou() = runTest {
        val open = MutableStateFlow(false)
        val vm = vmWithScreen(open)
        vm.send("付款", ok)
        emit(TaskState.Running(0, "正在准备"))
        open.value = true
        runCurrent()
        vm.closePip()
        emit(TaskState.WaitingTakeover("payment", "要你付款"))
        val pip = vm.ui.value.pip
        assertTrue(pip.visible)
        assertTrue(pip.closed)
        assertFalse("关着时接管也不弹开", pip.expanded)
        assertEquals(PipMode.NeedsYou, pip.mode)
        assertTrue(vm.ui.value.exchanges.single().status is ExchangeStatus.NeedsTakeover)
    }

    /** 关掉只管那一个任务:下一个任务照常浮出小画面。 */
    @Test fun closingTheSmallWindowOnlyLastsForThatTask() = runTest {
        val open = MutableStateFlow(false)
        val vm = vmWithScreen(open)
        vm.send("打开设置看电量", ok)
        emit(TaskState.Running(0, "正在准备"))
        open.value = true
        runCurrent()
        vm.closePip()
        emit(TaskState.Succeeded("电量 80%"))
        open.value = false
        advanceTimeBy(ChatViewModel.DONE_LINGER_MS + 1)
        runCurrent()

        vm.send("再看看存储", ok)
        emit(TaskState.Running(0, "正在准备"))
        open.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertFalse(vm.ui.value.pip.closed)
    }

    // ---------- 胶囊接着哪个对话 ----------
    // 胶囊不看对话文件最后被谁写过,而是看界面此刻在哪个对话:这三处改动各告诉它一次。

    @Test fun newChatTellsTheCapsuleItIsAFreshOne() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = vm(store)
        assertEquals(null, store.currentChat())
        vm.newConversation()
        assertEquals(CurrentChat.Fresh, store.currentChat())
    }

    @Test fun openingAConversationTellsTheCapsuleWhichOne() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("c-old", Turn("查天气", "晴", "succeeded"))
        val vm = vm(store)
        vm.openConversation("c-old")
        assertEquals(CurrentChat.Open("c-old"), store.currentChat())
    }

    /** 新对话里说的第一句话定下编号:胶囊之后接着这个对话,不是上一个。 */
    @Test fun theFirstMessageInANewChatTellsTheCapsuleItsId() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = vm(store)
        vm.newConversation()
        assertTrue(vm.send("查天气", ok))
        val id = vm.ui.value.conversationId
        assertTrue(id != null)
        assertEquals(CurrentChat.Open(id!!), store.currentChat())
        emit(TaskState.Succeeded("晴"))
        // 结束后还是它:胶囊不会跳回上一个对话
        assertEquals(CurrentChat.Open(id), store.currentChat())
    }

    /** 只记下复制了哪两个工作区,返回事先给的结果。 */
    private class FakeBranchWorkspace(var result: BranchCopyResult? = BranchCopyResult(copied = 1)) : BranchWorkspace {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long): BranchCopyResult? {
            calls += sourceId to targetId
            return result
        }
    }

    /** 从回答分支:复制到这一轮(含),标题加后缀,切到新对话;原对话不变,工作区的 inbox 也复制过去。 */
    @Test fun branchFromCopiesThroughTheTurnAndOpensTheBranch() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        store.append(source, Turn("三", "丙", "succeeded"))
        store.setTitle(source, "原对话")
        val workspace = FakeBranchWorkspace()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, store,
            branchWorkspace = workspace, branchTitle = { it + " · 分支" }, clock = { 123L },
        ).also { runCurrent() }
        vm.openConversation(source)

        val target = vm.branchFrom(1)

        assertNotNull(target)
        assertTrue(target != source)
        assertEquals(target, vm.ui.value.conversationId)
        assertEquals(listOf("一", "二"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(listOf("一", "二", "三"), store.turns(source).map { it.userText })
        assertEquals("原对话 · 分支", store.title(target!!))
        assertEquals(BranchOrigin(source, 1, afterTurn = true, at = 123L), store.branchOrigin(target))
        assertEquals(listOf(source to target), workspace.calls)
    }

    /** 源对话没有标题时用第一句话当底,再加后缀。 */
    @Test fun branchTitleFallsBackToTheFirstWords() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("帮我整理发票", "好了", "succeeded"))
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, store, branchTitle = { it + " · 分支" },
        ).also { runCurrent() }
        vm.openConversation(source)
        val target = vm.branchFrom(0)
        assertNotNull(target)
        assertEquals("帮我整理发票 · 分支", store.title(target!!))
    }

    /** 任务在跑、输入框正忙、轮次越界、这一轮还没结束、没有对话:都不分支。 */
    @Test fun branchFromRefusesRunningBusyAndBadTurns() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val activeNow = MutableStateFlow<List<String>>(emptyList())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, activeConversations = activeNow).also { runCurrent() }
        vm.openConversation(source)

        activeNow.value = listOf(source)
        runCurrent()
        assertTrue(vm.currentRunning())
        assertNull(vm.branchFrom(0))

        activeNow.value = emptyList()
        runCurrent()
        assertFalse(vm.currentRunning())
        assertNull(vm.branchFrom(1))
        assertNull(vm.branchFrom(-1))

        // 发出新一句后输入框正忙,不能再分支
        assertTrue(vm.send("新一句", ok))
        assertNull(vm.branchFrom(0))
    }

    /** 工作区超过上限、没复制成:轮次照常分支,界面能拿到「附件没有全部复制」的提示。 */
    @Test fun branchFromReportsAttachmentsThatDidNotCopy() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val workspace = FakeBranchWorkspace(BranchCopyResult(copied = 0, truncated = true))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, branchWorkspace = workspace).also { runCurrent() }
        vm.openConversation(source)

        assertNotNull(vm.branchFrom(0))
        assertEquals(BranchCopyResult(copied = 0, truncated = true), vm.branchCopy.value)
        vm.clearBranchCopy()
        assertNull(vm.branchCopy.value)
    }

    /** 源对话文件坏了:返回 null,不切走。 */
    @Test fun branchFromFailsWithoutSwitchingWhenTheSourceIsUnreadable() = runTest {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val vm = vm(store)
        vm.openConversation(source)
        java.io.File(dir, "$source.json").writeText("{not json")

        assertNull(vm.branchFrom(0))
        assertEquals(source, vm.ui.value.conversationId)
    }

    /** 从用户消息重新问:复制这一轮之前,返回要预填的文字;原对话不变,切到新对话。 */
    @Test fun branchBeforeCopiesEarlierTurnsAndReturnsPrefill() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        store.append(source, Turn("三", "丙", "succeeded"))
        store.setTitle(source, "原对话")
        val workspace = FakeBranchWorkspace()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, store,
            branchWorkspace = workspace, branchTitle = { it + " · 分支" }, clock = { 7L },
        ).also { runCurrent() }
        vm.openConversation(source)

        val result = vm.branchBefore(1)

        assertNotNull(result)
        val branch = result!!.conversationId
        assertEquals("二", result.prefill)
        assertFalse(result.needsReattach)
        assertEquals(listOf("一"), store.turns(branch).map { it.userText })
        assertEquals("原对话 · 分支", store.title(branch))
        assertEquals(BranchOrigin(source, 1, afterTurn = false, at = 7L), store.branchOrigin(branch))
        assertEquals(branch, vm.ui.value.conversationId)
        assertEquals(listOf("一"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(listOf(source to branch), workspace.calls)
    }

    @Test fun retryRestoresAttachmentsInOrderAndSendsEditedTextWithoutImportingThem() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        val paths = listOf("/workspace/inbox/a.pdf", "/workspace/inbox/b.jpg", "/workspace/inbox/c.jpg")
        store.append(source, Turn(paths.joinToString("\n") { "附件:$it(2 B)" }, "好", "succeeded"))
        val workspace = object : BranchWorkspace {
            override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long) = BranchCopyResult(0)
            override suspend fun copyInboxForRetry(sourceId: String, targetId: String, paths: List<String>, maxBytes: Long) =
                paths.map { RetryAttachmentResult(it, RetryAttachmentStatus.COPIED, 2) }
            override suspend fun checkInboxAttachments(targetId: String, paths: List<String>) =
                paths.map { RetryAttachmentResult(it, RetryAttachmentStatus.COPIED, 2) }
        }
        var imports = 0
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, branchWorkspace = workspace,
            importAttachments = { _, _ -> imports++; ImportResult(emptyList(), emptyList()) }).also { runCurrent() }
        vm.openConversation(source)
        val result = vm.branchBefore(0)!!
        assertEquals("", vm.ui.value.input)
        assertEquals(paths, vm.ui.value.attachments.map { it.workspacePath })
        assertTrue(calls.none { it.startsWith("start:") })
        vm.removeAttachment(vm.ui.value.attachments[1].uri)
        vm.input("修改后的问题")
        assertTrue(vm.send("修改后的问题", ok))
        runCurrent()
        assertEquals(0, imports)
        assertEquals("start:修改后的问题\n附件:${paths[0]}(2 B)\n附件:${paths[2]}(2 B)", calls.last { it.startsWith("start:") })
        assertFalse(result.needsReattach)
    }

    @Test fun retryReportsMissingItemsAndKeepsDraftsSeparate() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        val first = "/workspace/inbox/a.pdf"
        val missing = "/workspace/inbox/b.jpg"
        store.append(source, Turn("原文\n附件:$first(2 B)\n附件:$missing(2 B)", "好", "succeeded"))
        val workspace = object : BranchWorkspace {
            override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long) = BranchCopyResult(0)
            override suspend fun copyInboxForRetry(sourceId: String, targetId: String, paths: List<String>, maxBytes: Long) =
                listOf(RetryAttachmentResult(first, RetryAttachmentStatus.COPIED, 2), RetryAttachmentResult(missing, RetryAttachmentStatus.MISSING))
        }
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, branchWorkspace = workspace).also { runCurrent() }
        vm.openConversation(source)
        vm.input("原会话草稿")
        val result = vm.branchBefore(0)!!
        assertEquals(listOf(missing), result.failures.map { it.path })
        assertEquals(listOf(first), vm.ui.value.attachments.map { it.workspacePath })
        vm.openConversation(source)
        assertEquals("原会话草稿", vm.ui.value.input)
        assertTrue(vm.ui.value.attachments.isEmpty())
        vm.openConversation(result.conversationId)
        assertEquals("原文", vm.ui.value.input)
        assertEquals(listOf(first), vm.ui.value.attachments.map { it.workspacePath })
    }

    @Test fun retryReportsFailuresForAttachmentsInEarlierTurns() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        val previous = "/workspace/inbox/old.pdf"
        val current = "/workspace/inbox/new.jpg"
        store.append(source, Turn("以前\n附件:$previous(2 B)", "好", "succeeded"))
        store.append(source, Turn("现在\n附件:$current(2 B)", "好", "succeeded"))
        val workspace = object : BranchWorkspace {
            override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long) = BranchCopyResult(0)
            override suspend fun copyInboxForRetry(sourceId: String, targetId: String, paths: List<String>, maxBytes: Long): List<RetryAttachmentResult> {
                assertEquals(listOf(current, previous), paths)
                return listOf(RetryAttachmentResult(current, RetryAttachmentStatus.COPIED, 2), RetryAttachmentResult(previous, RetryAttachmentStatus.OVER_LIMIT))
            }
        }
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, branchWorkspace = workspace).also { runCurrent() }
        vm.openConversation(source)
        val result = vm.branchBefore(1)!!
        assertEquals(listOf(previous), result.failures.map { it.path })
        assertEquals(listOf(current), vm.ui.value.attachments.map { it.workspacePath })
    }

    @Test fun retryDoesNotSendWhenRestoredFileDisappears() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        val path = "/workspace/inbox/a.pdf"
        store.append(source, Turn("看看\n附件:$path(2 B)", "好", "succeeded"))
        val workspace = object : BranchWorkspace {
            override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long) = BranchCopyResult(0)
            override suspend fun copyInboxForRetry(sourceId: String, targetId: String, paths: List<String>, maxBytes: Long) =
                paths.map { RetryAttachmentResult(it, RetryAttachmentStatus.COPIED, 2) }
            override suspend fun checkInboxAttachments(targetId: String, paths: List<String>) =
                paths.map { RetryAttachmentResult(it, RetryAttachmentStatus.MISSING) }
        }
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, branchWorkspace = workspace).also { runCurrent() }
        vm.openConversation(source)
        vm.branchBefore(0)!!
        assertTrue(vm.send("看看", ok))
        runCurrent()
        assertTrue(calls.none { it.startsWith("start:") })
        assertEquals("看看", vm.ui.value.input)
        assertEquals(listOf("a.pdf"), vm.ui.value.rejectedAttachments.map { it.name })
        assertEquals(1, vm.ui.value.attachments.size)
    }

    /** 第一轮之前没有轮次:新对话是空的;原消息带附件时提示重新添加。 */
    @Test fun branchBeforeTheFirstTurnIsEmptyAndFlagsAttachments() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("看这个\n附件:/workspace/inbox/a.pdf(1 KB)", "好", "succeeded"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, branchTitle = { it + " · 分支" }).also { runCurrent() }
        vm.openConversation(source)

        val result = vm.branchBefore(0)

        assertNotNull(result)
        assertEquals("看这个", result!!.prefill)
        assertTrue(result.needsReattach)
        assertTrue(store.turns(result.conversationId).isEmpty())
        assertTrue(store.list().none { it.id == result.conversationId })
        assertEquals(result.conversationId, vm.ui.value.conversationId)
        assertTrue(vm.ui.value.exchanges.isEmpty())
    }

    /** 菜单显示的入口和点了之后的判断一致:这一轮没结束时两个入口都不显示,动作也不做;在跑、正忙时同样。 */
    @Test fun branchMenuMatchesWhatTheActionAllows() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val activeNow = MutableStateFlow<List<String>>(emptyList())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, activeConversations = activeNow).also { runCurrent() }
        assertNull(vm.branchTurn("r0"))
        assertNull(vm.retryTurn("u0"))
        vm.openConversation(source)

        assertEquals(0, vm.branchTurn("r0"))
        assertEquals(0, vm.retryTurn("u0"))
        assertNull(vm.branchTurn("u0"))
        assertNull(vm.retryTurn("r0"))

        activeNow.value = listOf(source)
        runCurrent()
        assertNull(vm.branchTurn("r0"))
        assertNull(vm.retryTurn("u0"))

        activeNow.value = emptyList()
        runCurrent()
        // 发出新一句:第 1 轮还没结束,输入框正忙,两个入口都不显示,动作也不做
        assertTrue(vm.send("新一句", ok))
        assertNull(vm.branchTurn("r1"))
        assertNull(vm.retryTurn("u1"))
        assertNull(vm.retryTurn("u0"))
        assertNull(vm.branchBefore(1))
        assertNull(vm.branchFrom(1))
    }

    /** 任务在跑、轮次越界、输入框正忙:都不重新问。 */
    @Test fun branchBeforeRefusesRunningBusyAndBadTurns() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val activeNow = MutableStateFlow<List<String>>(emptyList())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, activeConversations = activeNow).also { runCurrent() }
        vm.openConversation(source)

        activeNow.value = listOf(source)
        runCurrent()
        assertNull(vm.branchBefore(0))

        activeNow.value = emptyList()
        runCurrent()
        assertNull(vm.branchBefore(1))
        assertNull(vm.branchBefore(-1))

        assertTrue(vm.send("新一句", ok))
        assertNull(vm.branchBefore(0))
    }
    /** 归档屏上这个对话:换到新的空对话,原来的草稿留着,打开后还能接着改。 */
    @Test fun archiveCurrentConversationStartsNewOneAndKeepsDraft() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        val vm = vm(store)
        vm.openConversation("a")
        vm.input("还没发出去的补充")
        runCurrent()

        assertTrue(vm.archiveConversation("a"))

        assertTrue(vm.ui.value.conversationId != "a")
        assertNotNull(store.archivedAt("a"))
        assertTrue(store.list().none { it.id == "a" })
        vm.openConversation("a")
        assertEquals("还没发出去的补充", vm.ui.value.input)
    }

    /** 归档别的对话不动当前对话,只把它从列表里收起来。 */
    @Test fun archiveOtherConversationKeepsCurrentAndHidesIt() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.append("b", Turn("订票", "好了", "succeeded"))
        val vm = vm(store)
        vm.openConversation("a")

        assertTrue(vm.archiveConversation("b"))

        assertEquals("a", vm.ui.value.conversationId)
        assertNotNull(store.archivedAt("b"))
        assertEquals(listOf("a"), store.list().map { it.id })
    }

    /** 跑着或排着任务的对话不归档。 */
    @Test fun archiveRunningConversationIsRefused() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        val running = MutableStateFlow(listOf("a"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, activeConversations = running).also { runCurrent() }

        assertFalse(vm.archiveConversation("a"))
        assertNull(store.archivedAt("a"))
    }

    /** 取消归档后回到列表,原来的置顶还在。 */
    @Test fun unarchiveConversationBringsItBack() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        val vm = vm(store)
        vm.setPinned("a", true)
        assertTrue(vm.archiveConversation("a"))

        vm.unarchiveConversation("a")

        assertNull(store.archivedAt("a"))
        assertEquals(listOf("a"), store.list().map { it.id })
        assertNotNull(store.pinnedAt("a"))
    }

    /** 归档视图里多选恢复:选中的回到列表,没选的还收着。 */
    @Test fun unarchiveConversationsRestoresSelectedOnes() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.append("b", Turn("订票", "好了", "succeeded"))
        store.append("c", Turn("记账", "记好了", "succeeded"))
        val vm = vm(store)
        listOf("a", "b", "c").forEach { assertTrue(store.setArchived(it, true)) }

        vm.unarchiveConversations(listOf("a", "c"))

        assertNull(store.archivedAt("a"))
        assertNull(store.archivedAt("c"))
        assertNotNull(store.archivedAt("b"))
        assertEquals(setOf("a", "c"), store.list().map { it.id }.toSet())
    }

    /** 普通侧栏多选归档:在跑的和当前正在看的跳过,其余收起;返回实际归档了几个。 */
    @Test fun archiveConversationsSkipsRunningAndCurrentOnes() = runTest {
        val store = ConversationStore(tmp.newFolder())
        listOf("a", "b", "c", "d").forEach { store.append(it, Turn("第 $it 个", "好", "succeeded")) }
        val running = MutableStateFlow(listOf("c"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, activeConversations = running).also { runCurrent() }
        vm.openConversation("a")

        assertEquals(2, vm.archiveConversations(listOf("a", "b", "c", "d")))

        assertEquals("a", vm.ui.value.conversationId)
        assertNull(store.archivedAt("a"))
        assertNotNull(store.archivedAt("b"))
        assertNull(store.archivedAt("c"))
        assertNotNull(store.archivedAt("d"))
    }

    @Test fun batchArchiveSkipsUnsentDraftButArchivesSavedConversationWithDraft() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("saved", Turn("已有消息", "回答", "succeeded"))
        val vm = vm(store)
        vm.input("尚未发送")
        val unsent = vm.ui.value.conversationId!!
        vm.openConversation("saved")
        vm.input("已有对话的草稿")
        vm.newConversation()
        runCurrent()

        assertTrue(vm.withDrafts(store.list()).any { it.id == unsent })
        assertEquals(1, vm.archiveConversations(listOf(unsent, "saved")))
        assertNull(store.archivedAt(unsent))
        assertNotNull(store.archivedAt("saved"))
        vm.openConversation(unsent)
        assertEquals("尚未发送", vm.ui.value.input)
        vm.openConversation("saved")
        assertEquals("已有对话的草稿", vm.ui.value.input)
    }

    @Test fun personalTaskCanBeOpenedWhileOrdinaryConversationIsBusyWithoutStartingWork() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("personal-work", Turn("整理资料", "整理完成", "succeeded"))
        val vm = vm(store)
        vm.send("正在做原来的事", ok)
        emit(TaskState.Running(1, "处理中"))
        val originalId = vm.ui.value.conversationId
        vm.openConversation("personal-work")
        assertEquals(originalId, vm.ui.value.conversationId)
        vm.openPersonalTask("personal-work")
        assertEquals("personal-work", vm.ui.value.conversationId)
        assertEquals("整理资料", vm.ui.value.exchanges.first().goal)
        assertEquals(1, calls.count { it.startsWith("start:") })
    }

    @Test fun projectDraftPersistsOnSendAndDeletesWithProject() = runTest {
        val conversations = ConversationStore(tmp.newFolder("chats"))
        val projects = com.wangzan101.mika.chat.ProjectStore(tmp.newFolder("projects"))
        val resolver = com.wangzan101.mika.chat.WorkspaceResolver(conversations, projects)
        val p1 = projects.create("科研").getOrThrow()
        var deletedProjectId: String? = null
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            appLabel = { it }, activities = acts, steers = steerFlow,
            projectStore = projects,
            removeProject = { pid -> deletedProjectId = pid },
            workspaceResolver = resolver,
        ).also { runCurrent() }

        vm.newConversation(p1.id)
        assertEquals(p1.id, vm.ui.value.projectId)
        vm.input("先建个草稿")
        val draftId = vm.ui.value.conversationId!!
        assertEquals(com.wangzan101.mika.cap.sandbox.WorkspaceKey.Project(p1.id), resolver.resolve(draftId))
        assertTrue(vm.send("先建个草稿", ok))
        assertEquals(p1.id, conversations.projectId(draftId))

        vm.deleteProject(p1.id)
        runCurrent()
        assertEquals(p1.id, deletedProjectId)
        assertNull(vm.ui.value.conversationId)
        assertNull(vm.ui.value.projectId)
    }
}
