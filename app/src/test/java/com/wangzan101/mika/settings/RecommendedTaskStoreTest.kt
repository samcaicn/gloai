package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecommendedTaskStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private var nextId = 0
    private fun store(name: String = "recommended-tasks.json") =
        RecommendedTaskStore(File(tmp.root, name)) { "id-${nextId++}" }

    @Test fun seedsOnceAndKeepsEditsAcrossReload() {
        val file = File(tmp.root, "recommended-tasks.json")
        val first = RecommendedTaskStore(file) { "id-${nextId++}" }
        first.ensureLoaded { listOf("总结群消息" to "打开微信，总结今天的消息。", "  " to " ") }
        assertEquals(listOf("总结群消息"), first.tasks.value.map { it.title })
        assertNull(first.update("id-0", "总结", "打开微信，总结今天的消息。\n列出要回复的。"))
        assertNull(first.add("另一条", "另一条提示词"))
        assertNull(first.delete("id-0"))

        val second = RecommendedTaskStore(file) { "新的" }
        second.ensureLoaded { listOf("不该回来" to "不该回来") }
        assertEquals(listOf("另一条" to "另一条提示词"), second.tasks.value.map { it.title to it.prompt })
        assertFalse(file.readText().contains("不该回来"))
        assertFalse(File(tmp.root, "recommended-tasks.json.tmp").exists())
    }

    @Test fun reorderedTasksPersistAndStaleOrdersCannotLoseTasks() {
        val tasks = store()
        tasks.ensureLoaded { listOf("一" to "提示一", "二" to "提示二", "三" to "提示三") }
        val original = tasks.tasks.value
        val order = original.map { it.id }.reversed()
        assertNull(tasks.reorder(order))
        val reloaded = store()
        reloaded.ensureLoaded { emptyList() }
        assertEquals(order, reloaded.tasks.value.map { it.id })
        assertEquals(RecommendedTaskError.SaveFailed, tasks.reorder(order.dropLast(1)))
        assertEquals(RecommendedTaskError.SaveFailed, tasks.reorder(listOf(order[0], order[0], order[1])))
        assertEquals(order, tasks.tasks.value.map { it.id })
        val file = File(tmp.root, "recommended-tasks.json")
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        assertEquals(RecommendedTaskError.SaveFailed, tasks.reorder(original.map { it.id }))
        assertEquals(order, tasks.tasks.value.map { it.id })
    }

    @Test fun emptyListStaysEmpty() {
        val file = File(tmp.root, "recommended-tasks.json")
        val first = RecommendedTaskStore(file) { "id" }
        first.ensureLoaded { listOf("默认" to "默认提示词") }
        assertNull(first.delete("id"))
        assertEquals("[]", file.readText())

        val second = RecommendedTaskStore(file) { "新的" }
        second.ensureLoaded { listOf("默认" to "默认提示词") }
        assertTrue(second.tasks.value.isEmpty())
        assertFalse(second.unreadable.value)
    }

    @Test fun trimsEndsButKeepsInternalSpacingAndDuplicateTitles() {
        val tasks = store()
        tasks.ensureLoaded { emptyList() }
        assertNull(tasks.add("  总结  ", "  第一段\n\n第二段  "))
        assertNull(tasks.add("总结", "另一段"))
        assertEquals(listOf("总结" to "第一段\n\n第二段", "总结" to "另一段"), tasks.tasks.value.map { it.title to it.prompt })
        val reloaded = RecommendedTaskStore(File(tmp.root, "recommended-tasks.json")) { "新的" }
        reloaded.ensureLoaded { emptyList() }
        assertEquals(tasks.tasks.value, reloaded.tasks.value)
    }

    @Test fun rejectsBlankAndOverlongTextWithoutWriting() {
        val tasks = store()
        tasks.ensureLoaded { emptyList() }
        assertEquals(RecommendedTaskError.BlankTitle, tasks.add("  ", "提示词"))
        assertEquals(RecommendedTaskError.BlankPrompt, tasks.add("标题", " \n "))
        assertEquals(RecommendedTaskError.TooLong, tasks.add("标".repeat(RecommendedTaskStore.TITLE_MAX + 1), "提示词"))
        assertEquals(RecommendedTaskError.TooLong, tasks.add("😀".repeat(RecommendedTaskStore.TITLE_MAX + 1), "提示词"))
        assertNull(tasks.add("😀".repeat(RecommendedTaskStore.TITLE_MAX), "短"))
        assertEquals(RecommendedTaskError.TooLong, tasks.update("id-0", "标题", "字".repeat(RecommendedTaskStore.PROMPT_MAX + 1)))
        assertEquals("短", tasks.tasks.value.single().prompt)
    }

    @Test fun stopsAtTheTaskLimit() {
        val tasks = store()
        tasks.ensureLoaded { emptyList() }
        repeat(RecommendedTaskStore.MAX_TASKS) { assertNull(tasks.add("标题$it", "提示词$it")) }
        assertEquals(RecommendedTaskError.TooMany, tasks.add("多一条", "多一条"))
        assertEquals(RecommendedTaskStore.MAX_TASKS, tasks.tasks.value.size)
    }

    @Test fun corruptFileIsKeptUntilTheUserDiscardsIt() {
        val file = File(tmp.root, "recommended-tasks.json")
        file.writeText("{不是列表")
        val tasks = RecommendedTaskStore(file) { "id" }
        tasks.ensureLoaded { listOf("默认" to "默认提示词") }
        assertTrue(tasks.unreadable.value)
        assertTrue(tasks.tasks.value.isEmpty())
        assertEquals(RecommendedTaskError.Unreadable, tasks.add("新的", "新的提示词"))
        assertEquals("{不是列表", file.readText())

        assertNull(tasks.discardUnreadable())
        assertFalse(tasks.unreadable.value)
        assertEquals("[]", file.readText())
        val reloaded = RecommendedTaskStore(file) { "新的" }
        reloaded.ensureLoaded { listOf("默认" to "默认提示词") }
        assertTrue(reloaded.tasks.value.isEmpty())
    }

    @Test fun failedSaveLeavesThePreviousListUntouched() {
        val file = File(tmp.root, "recommended-tasks.json")
        val tasks = RecommendedTaskStore(file) { "id-${nextId++}" }
        tasks.ensureLoaded { listOf("原来" to "原来的提示词") }
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        assertEquals(RecommendedTaskError.SaveFailed, tasks.add("新的", "新的提示词"))
        assertEquals(listOf("原来"), tasks.tasks.value.map { it.title })
    }
    @Test fun legacyMarkersStayLiteralAndFillInOptInSurvivesReloadAndEdit() {
        val file = File(tmp.root, "tasks.json")
        file.writeText("""[{"id":"old","title":"旧任务","prompt":"保留{{原样}}"}]""")
        val tasks = RecommendedTaskStore(file)
        tasks.ensureLoaded { emptyList() }
        assertFalse(tasks.tasks.value.single().fillIn)
        assertNull(tasks.add("整理需求", "查看{{联系人}}", fillIn = true))
        val id = tasks.tasks.value.last().id
        assertNull(tasks.update(id, "整理新需求", "查看{{联系人}}的近期消息"))
        val reopened = RecommendedTaskStore(file).also { it.ensureLoaded { emptyList() } }
        assertFalse(reopened.tasks.value.first().fillIn)
        assertTrue(reopened.tasks.value.last().fillIn)
        assertEquals(tasks.tasks.value, reopened.tasks.value)
        assertNull(reopened.update(id, "普通任务", "查看{{联系人}}", fillIn = false))
        assertFalse(reopened.tasks.value.last().fillIn)
    }

    @Test fun tooManyFieldsDoNotOverwriteStoredTasks() {
        val tasks = store()
        tasks.ensureLoaded { emptyList() }
        assertNull(tasks.add("已有", "{{联系人}}", fillIn = true))
        val before = tasks.tasks.value
        val prompt = (1..TaskTemplate.MAX_FIELDS + 1).joinToString { "{{字段$it}}" }
        assertEquals(RecommendedTaskError.TooManyFields, tasks.add("新增", prompt, fillIn = true))
        assertEquals(RecommendedTaskError.TooManyFields, tasks.update(before.single().id, "更改", prompt))
        assertEquals(before, tasks.tasks.value)
        assertNull(tasks.add("普通文字", prompt))
    }

}
