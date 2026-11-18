package com.wangzan101.mika.ui

import com.wangzan101.mika.ChineseUi
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryKind
import com.wangzan101.mika.core.memory.MemoryPersistence
import com.wangzan101.mika.core.memory.MemorySource
import com.wangzan101.mika.core.memory.MemoryStatus
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.skills.PersonalSkillFiles
import com.wangzan101.mika.settings.MemorySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MemoryScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val store = MemoryStore(object : MemoryPersistence {
        override fun load() = emptyList<MemoryItem>()
        override fun save(items: List<MemoryItem>) {}
    }, System::currentTimeMillis, { java.util.UUID.randomUUID().toString() })

    private fun show(opened: MutableList<String>? = null, skills: PersonalSkillFiles? = null) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val vm = MemoryViewModel(store, MemorySettings(File(dir, "memory-screen-test.json").apply { delete() }), skills,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        compose.setContent {
            MemoryScreen(
                vm, onBack = {},
                webSitesCount = opened?.let { 3 }, onOpenWebSites = { opened?.add("web") },
                routes = opened?.let { 8 }, onOpenRoutes = { opened?.add("routes") },
            )
        }
    }

    @Test fun addEditDeleteRoundTrip() {
        show()
        compose.onNodeWithText("手写一条").performClick()
        compose.onNodeWithTag("memory-content").performTextInput("用户住在张江")
        compose.onNodeWithTag("memory-save").performClick()
        // 点一行升起操作面板,选「改一改」
        compose.onNodeWithText("用户住在张江").performClick()
        compose.onNodeWithText("改一改").performClick()
        compose.onNodeWithTag("memory-content").performTextClearance()
        compose.onNodeWithTag("memory-content").performTextInput("用户住在张江高科")
        compose.onNodeWithTag("memory-save").performClick()
        compose.waitForIdle()
        assertEquals("用户住在张江高科", store.items.value.single().content)
        compose.onNodeWithText("用户住在张江高科").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.waitForIdle()
        assertTrue(store.items.value.isEmpty())
    }

    @Test fun approveProcedure() {
        store.add(MemoryKind.procedure, "点外卖先比价", emptyList(), MemorySource("user_said", "以后点外卖先比价", atMs = 1)).getOrThrow()
        show()
        compose.onNodeWithTag("segment-Pending").performClick()
        compose.onNodeWithText("好").performClick()
        compose.waitForIdle()
        assertEquals(MemoryStatus.active, store.items.value.single().status)
        // 批准后从「待确认」里拿走,出现在记忆列表里
        compose.onNodeWithText("没有等你确认的东西").assertExists()
        compose.onNodeWithTag("segment-Memories").performClick()
        assertEquals(1, compose.onAllNodesWithTag("memory-row").fetchSemanticsNodes().size)
    }

    /** Explicit device smoke test: runs against the public sample repository only when requested. */
    @Test fun liveMarketSkillGoesToPending() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveSkillMarket") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = PersonalSkillFiles(File(context.cacheDir, "market-test-" + java.util.UUID.randomUUID()))
        fun capture(page: String) {
            File(context.cacheDir, "market-$page.png").outputStream().use { output ->
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            }
        }
        show(skills = files)
        compose.onNodeWithTag("segment-Skills").performClick()
        compose.onNodeWithText("导入技能").assertExists()
        compose.onNodeWithText("技能市场").performScrollTo().performClick()
        val title = "会议纪要转行动清单"
        compose.waitUntil(30_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        capture("list")
        compose.onNodeWithText(title).performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("加入待确认").fetchSemanticsNodes().isNotEmpty() }
        capture("preview")
        compose.onNodeWithText("加入待确认").performScrollTo().performClick()
        compose.waitUntil(10_000) { files.pending().size == 1 }
        assertEquals("meeting-action-list", files.pending().single().name)
        assertTrue(files.list().isEmpty())
        compose.onNodeWithText(title).assertExists()
    }

    @Test fun savedDataRowsOpenWebsiteSignInsAndRoutes() {
        val opened = mutableListOf<String>()
        show(opened)
        compose.onNodeWithText("记忆与数据").assertExists()
        compose.onNodeWithText("网站登录").performScrollTo().performClick()
        compose.onNodeWithText("记住的操作路线").performScrollTo().performClick()
        assertEquals(listOf("web", "routes"), opened)
    }
}
