package com.wangzan101.mika.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.DeleteImpact
import com.wangzan101.mika.chat.ProjectDeleteImpact
import com.wangzan101.mika.chat.ProjectStore
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.RecommendedTaskStore
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import com.wangzan101.mika.chat.ui.ChatScreen
import com.wangzan101.mika.chat.ui.ChatViewModel
import com.wangzan101.mika.chat.ui.ChatWithDrawer
import com.wangzan101.mika.chat.ui.TaskActions

/**
 * 侧栏项目分组 UI 测试:新建项目并在其中发起对话、聊天页顶部显示项目胶囊、
 * 折叠/展开项目、重命名项目、以及删除项目时清理成员对话并保留独立对话。
 */
@RunWith(AndroidJUnit4::class)
class ProjectDrawerUiTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ConversationStore(File(context.cacheDir, "project-drawer-chat-" + UUID.randomUUID()))
    private val projects = ProjectStore(File(context.cacheDir, "project-drawer-proj-" + UUID.randomUUID()))
    private val recommendations = RecommendedTaskStore(File(context.cacheDir, "recommendations-" + UUID.randomUUID())).also {
        it.ensureLoaded { emptyList() }
    }
    private val removedProjects = mutableListOf<String>()

    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) {}
        override fun cancel() {}
        override fun resume() {}
        override fun takeOver() {}
        override fun handBack() {}
        override fun finishedMyself() {}
        override fun skip() {}
        override fun answer(text: String) {}
        override fun approve(key: Int) {}
        override fun decline(key: Int) {}
    }

    private fun vm(
        projectImpact: ProjectDeleteImpact = ProjectDeleteImpact(conversations = 0, runningTasks = 0, schedules = 0),
        parallel: Boolean = false,
    ) = ChatViewModel(
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        MutableStateFlow(TaskState.Idle),
        MutableStateFlow(Takeover.Stage.None),
        actions,
        store,
        deletionImpact = { DeleteImpact(runningTask = false, schedules = 0) },
        removeConversation = { store.delete(it) },
        branchTitle = { "$it · 分支" },
        parallelFlag = { parallel },
        sandboxReady = MutableStateFlow(false),
        io = Dispatchers.Main.immediate,
        projectStore = projects,
        projectDeletionImpact = { projectImpact },
        removeProject = { id ->
            removedProjects += id
            store.list(includeArchived = true).filter { it.projectId == id }.forEach { store.delete(it.id) }
            projects.delete(id)
        },
    )

    private fun show(vm: ChatViewModel, drawerOpen: Boolean) {
        compose.setContent {
            ChatWithDrawer(
                vm, Prerequisites.Check(emptyList()), reduceMotion = true,
                conversations = { store.list(includeArchived = true) }, conversationChanges = store.changes, conversationTitle = store::title,
                onSettings = {}, reopen = drawerOpen,
            ) { open ->
                ChatScreen(
                    vm, Prerequisites.Check(emptyList()), MutableStateFlow(null), MutableStateFlow(false),
                    reduceMotion = true, onOpenDrawer = open, openSettings = {}, recommendations = recommendations,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun createProjectShowsPillAndSupportsQuickNewChatFromHeader() {
        val vm = vm(parallel = true)
        show(vm, drawerOpen = true)

        compose.onNodeWithTag("drawer-new-project").performClick()
        compose.onNodeWithTag("project-name-field").performTextReplacement("Mika 研发")
        compose.onNodeWithTag("confirm-create-project").performClick()
        compose.waitForIdle()

        val created = projects.projects.value.single()
        assertEquals("Mika 研发", created.name)
        assertEquals(created.id, vm.ui.value.projectId)
        compose.onNode(hasText("Mika 研发") and hasAnyAncestor(hasTestTag("chat-project-pill"))).assertIsDisplayed()

        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNode(hasTestTag("project-header") and hasText("Mika 研发")).assertExists()
        compose.onNode(hasTestTag("project-new-chat") and hasAnyAncestor(hasTestTag("drawer"))).performClick()
        compose.waitForIdle()

        assertEquals(created.id, vm.ui.value.projectId)
        compose.onNode(hasText("Mika 研发") and hasAnyAncestor(hasTestTag("chat-project-pill"))).assertIsDisplayed()
    }

    @Test fun collapseRenameAndDeleteProjectRemoveMembersOnly() {
        val existing = projects.create("客户端").getOrThrow()
        store.append("c1", Turn("架构讨论", "好", "succeeded"))
        store.setTitle("c1", "架构讨论")
        store.setProject("c1", existing.id)
        store.append("c2", Turn("成员聊天", "好", "succeeded"))
        store.setTitle("c2", "成员聊天")
        store.setProject("c2", existing.id)
        store.append("c3", Turn("独立对话", "好", "succeeded"))
        store.setTitle("c3", "独立对话")

        val vm = vm(projectImpact = ProjectDeleteImpact(conversations = 2, runningTasks = 1, schedules = 2))
        show(vm, drawerOpen = true)

        // 展开/收起项目会隐藏和重新显示成员对话
        compose.onNode(hasTestTag("conversation-row") and hasText("成员聊天")).assertExists()
        compose.onNode(hasTestTag("project-header") and hasText("客户端")).performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("成员聊天").fetchSemanticsNodes().size)
        compose.onNode(hasTestTag("project-header") and hasText("客户端")).performClick()
        compose.waitForIdle()
        compose.onNode(hasTestTag("conversation-row") and hasText("成员聊天")).assertExists()

        // 长按项目头重命名为「端侧内核」
        compose.onNode(hasTestTag("project-header") and hasText("客户端")).performTouchInput { longClick() }
        compose.onNodeWithTag("project-menu-rename").performClick()
        compose.onNodeWithTag("project-name-field").performTextReplacement("端侧内核")
        compose.onNodeWithTag("confirm-rename-project").performClick()
        compose.waitForIdle()
        compose.onNode(hasTestTag("project-header") and hasText("端侧内核")).assertExists()

        // 长按项目头删除项目:确认弹窗写明影响并保留原有对话
        compose.onNode(hasTestTag("project-header") and hasText("端侧内核")).performTouchInput { longClick() }
        compose.onNodeWithTag("project-menu-delete").performClick()
        compose.onNodeWithTag("delete-project-body").assertExists()
        val body = compose.onNodeWithTag("delete-project-body").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
        assertTrue(body, "端侧内核" in body && "停止" in body && "2" in body)

        compose.onNodeWithTag("confirm-delete-project").performClick()
        compose.waitUntil(5_000) { existing.id in removedProjects }
        compose.waitForIdle()

        assertEquals(0, compose.onAllNodesWithText("端侧内核").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("架构讨论").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("成员聊天").fetchSemanticsNodes().size)
        compose.onNode(hasTestTag("conversation-row") and hasText("独立对话")).assertExists()
    }

    @Test fun archivedListShowsProjectNameForProjectChats() {
        val project = projects.create("科研项目").getOrThrow()
        store.append("c-arch", Turn("归档成员", "好", "succeeded"))
        store.setTitle("c-arch", "归档成员")
        store.setProject("c-arch", project.id)
        store.setArchived("c-arch", true)
        store.append("c-solo", Turn("归档独立", "好", "succeeded"))
        store.setTitle("c-solo", "归档独立")
        store.setArchived("c-solo", true)

        val vm = vm()
        show(vm, drawerOpen = true)

        compose.onNodeWithTag("drawer-archive").performClick()
        compose.waitForIdle()

        compose.onNode(hasTestTag("conversation-row") and hasText("归档成员") and hasText("科研项目")).assertExists()
        compose.onNode(hasTestTag("conversation-row") and hasText("归档独立")).assertExists()
    }
}
