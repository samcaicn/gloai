package com.wangzan101.mika.memory.ui

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.learn.memory.MemoryKind
import com.wangzan101.mika.core.learn.memory.MemorySource
import com.wangzan101.mika.settings.MemorySettings
import com.wangzan101.mika.task.memoryStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import com.wangzan101.mika.chat.ui.draft
import com.wangzan101.mika.settings.ui.models.edit

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModelTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()
    // 来历一行按本机时区显示日期:取本机时区那天的中午,在哪个时区跑都是 10 月 23 日
    private val said = MemorySource("user_said", "以后点外卖都不要香菜", "c1", LocalDateTime.of(2025, 10, 23, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    private val store = memoryStore().apply {
        add(MemoryKind.fact, "用户住在张江", listOf("家"), MemorySource("manual", atMs = 1)).getOrThrow()
        add(MemoryKind.preference, "用户点外卖不要香菜", listOf("外卖"), said).getOrThrow()
        add(MemoryKind.preference, "用户喝咖啡不加糖", listOf("咖啡"), said).getOrThrow()
        add(MemoryKind.procedure, "点外卖先比价", listOf("外卖"), said).getOrThrow()
    }
    private var wiped = 0
    private val broken = MutableStateFlow<String?>(null)
    private lateinit var settingsFile: File

    private fun TestScope.vm(): MemoryViewModel {
        settingsFile = File(tmp.newFolder(), "memory_settings.json")
        return MemoryViewModel(store, MemorySettings(settingsFile), null, backgroundScope, broken, wipe = { wiped++ }).also { runCurrent() }
    }

    // Mika 自己归纳出来的那条:来历写明是它归纳的,引的是用户提要求的那句,不写成「你说的」
    @Test fun inferredMemoryShowsItWasMikasConclusion() = runTest {
        store.add(MemoryKind.fact, "用户常在张江路一带活动", emptyList(), said.copy(type = com.wangzan101.mika.core.learn.memory.MemoryContext.INFERRED, quote = "你自己总结几条写进记忆")).getOrThrow()
        val vm = vm()
        assertEquals("Mika 在 10 月 23 日按你的要求归纳:『你自己总结几条写进记忆』", vm.ui.value.rows.first { it.content == "用户常在张江路一带活动" }.source)
        // 开关默认关,打开后存得住
        assertFalse(vm.ui.value.switches.inferMemories)
        vm.setSwitches { it.copy(inferMemories = true) }
        runCurrent()
        assertTrue(MemorySettings(settingsFile).switches.value.inferMemories)
    }

    @Test fun memoriesListAllKindsWithKind() = runTest {
        val vm = vm()
        assertEquals(
            setOf("用户住在张江", "用户点外卖不要香菜", "用户喝咖啡不加糖"),
            vm.ui.value.rows.map { it.content }.toSet(),
        )
        assertEquals(MemoryKind.fact, vm.ui.value.rows.first { it.content == "用户住在张江" }.kind)
        assertEquals("你手写的", vm.ui.value.rows.first { it.content == "用户住在张江" }.source)
        assertEquals("你在 10 月 23 日说:『以后点外卖都不要香菜』", vm.ui.value.rows.first { it.kind == MemoryKind.preference }.source)
    }

    @Test fun pendingCollectsProceduresAndCounts() = runTest {
        val vm = vm()
        vm.select(Segment.Pending); runCurrent()
        assertEquals(listOf("点外卖先比价"), vm.ui.value.pending.map { it.content })
        assertEquals(1, vm.ui.value.pendingCount)
        assertEquals(3, vm.ui.value.rows.size)
        vm.approve(vm.ui.value.pending.single().id); runCurrent()
        assertTrue(vm.ui.value.pending.isEmpty())
        assertEquals(0, vm.ui.value.pendingCount)
        assertTrue("点外卖先比价" in vm.ui.value.rows.map { it.content })
    }

    @Test fun searchUsesIndex() = runTest {
        val vm = vm()
        vm.search("咖啡"); runCurrent()
        assertEquals(listOf("用户喝咖啡不加糖"), vm.ui.value.rows.map { it.content })
        vm.search(""); runCurrent()
        // 空查询按更新时间倒序
        assertEquals(3, vm.ui.value.rows.size)
    }

    @Test fun editPersists() = runTest {
        val vm = vm()
        val id = vm.ui.value.rows.first { it.kind == MemoryKind.fact }.id
        vm.edit(id, "用户住在张江高科", listOf("家", "住址")); runCurrent()
        val row = vm.ui.value.rows.first { it.id == id }
        assertEquals("用户住在张江高科", row.content)
        assertEquals(listOf("家", "住址"), row.keywords)
        assertEquals("用户住在张江", row.replaces)
        vm.setPinned(id, true); runCurrent()
        assertTrue(vm.ui.value.rows.first { it.id == id }.pinned)
        vm.delete(id); runCurrent()
        assertTrue(vm.ui.value.rows.none { it.id == id })
    }

    @Test fun manualAddSensitiveShowsMessage() = runTest {
        val vm = vm()
        vm.add(MemoryKind.fact, "我的支付密码是123456", emptyList()); runCurrent()
        assertEquals("这类信息我不会记", vm.ui.value.message)
        assertEquals(3, vm.ui.value.rows.size)
        vm.dismissMessage()
        vm.add(MemoryKind.fact, "用户的妈妈叫老妈", emptyList()); runCurrent()
        assertEquals(4, vm.ui.value.rows.size)
        assertEquals("你手写的", vm.ui.value.rows.first { it.content == "用户的妈妈叫老妈" }.source)
    }

    @Test fun clearAllNeedsTwoConfirms() = runTest {
        val vm = vm()
        vm.requestClear(); runCurrent()
        assertEquals(1, vm.ui.value.clearStage)
        vm.confirmClear(); runCurrent()
        assertEquals(2, vm.ui.value.clearStage)
        assertEquals(0, wiped)
        vm.confirmClear(); runCurrent()
        assertEquals(1, wiped)
        assertEquals(0, vm.ui.value.clearStage)
        vm.requestClear(); vm.cancelClear(); runCurrent()
        assertEquals(1, wiped)
    }

    @Test fun brokenNoticeShown() = runTest {
        val vm = vm()
        broken.value = "memory.bin.broken-20261023120000"; runCurrent()
        assertEquals("memory.bin.broken-20261023120000", vm.ui.value.brokenNotice)
    }

    @Test fun switchesPersist() = runTest {
        val vm = vm()
        assertTrue(vm.ui.value.switches.useMemory && vm.ui.value.switches.autoRemember)
        assertTrue(vm.ui.value.switches.learnSkills)
        vm.setSwitches { it.copy(autoRemember = false, learnSkills = false) }; runCurrent()
        assertFalse(vm.ui.value.switches.autoRemember)
        val reread = MemorySettings(settingsFile).switches.value
        assertFalse(reread.autoRemember); assertFalse(reread.learnSkills); assertTrue(reread.useMemory)
    }

    private fun skillMd(name: String, body: String) = "---\nname: $name\ndisplay: 会议纪要整理\ndescription: 把会议记录整理成决定和待办\n---\n$body"
    private fun fullMd(name: String, steps: String) = skillMd(name, "## 入口\n打开一份会议记录\n## 步骤\n$steps\n## 容易出错的地方\n遗漏决定事项\n## 怎样算办成(核实证据)\n纪要包含决定和待办\n## 什么时候停下交给用户\n要登录时")

    private fun TestScope.skillsVm(skills: com.wangzan101.mika.task.MapSkills, builtin: Set<String> = emptySet()): MemoryViewModel =
        MemoryViewModel(store, MemorySettings(File(tmp.newFolder(), "s.json")), skills, backgroundScope, builtinSkillNames = builtin)
            .also { it.select(Segment.Skills); runCurrent() }

    @Test fun approveWithEdit() = runTest {
        val skills = com.wangzan101.mika.task.MapSkills()
        skills.putPending("d1", com.wangzan101.mika.core.learn.skills.PersonalSkill("meeting-notes", skillMd("meeting-notes", "## 步骤\n1. 点搜索"), com.wangzan101.mika.core.learn.skills.SkillStatus.pending, "r1", null, 0, emptyList()))
        val vm = skillsVm(skills)
        assertEquals(listOf("d1"), vm.ui.value.skills.drafts.map { it.id })
        // 改过的全文同样过校验:缺小节、带敏感信息、提到没有的工具都不收
        vm.approveSkill("d1", skillMd("meeting-notes", "## 步骤\n1. 点搜索框")); runCurrent()
        assertTrue(vm.ui.value.message!!.contains("五个小节"))
        vm.dismissMessage()
        vm.approveSkill("d1", fullMd("meeting-notes", "支付密码是123456")); runCurrent()
        assertEquals("这类信息我不会记", vm.ui.value.message)
        vm.dismissMessage()
        vm.approveSkill("d1", fullMd("meeting-notes", "用 `missing_tool` 打开")); runCurrent()
        assertEquals("里面提到了 Mika 没有的工具", vm.ui.value.message)
        assertEquals(1, vm.ui.value.skills.drafts.size)
        vm.approveSkill("d1", fullMd("renamed", "1. 用 `phone_tap` 点搜索框")); runCurrent()
        assertTrue(vm.ui.value.skills.drafts.isEmpty())
        val got = skills.get("meeting-notes")!!
        assertTrue(got.markdown.contains("点搜索框"))
        assertTrue(got.markdown.startsWith("---\nname: meeting-notes\n"))
        assertEquals(listOf("meeting-notes"), vm.ui.value.skills.installed.map { it.name })
    }

    @Test fun updateDraftShowsChangedLines() = runTest {
        val old = com.wangzan101.mika.core.learn.skills.PersonalSkill("meeting-notes", skillMd("meeting-notes", "## 步骤\n1. 点搜索\n2. 整理待办"), com.wangzan101.mika.core.learn.skills.SkillStatus.active, "r0", null, 0, emptyList())
        val skills = com.wangzan101.mika.task.MapSkills(old)
        skills.putPending("d2", old.copy(markdown = skillMd("meeting-notes", "## 步骤\n1. 点搜索\n2. 补充负责人"), replacesDraftOf = "meeting-notes"))
        val vm = skillsVm(skills)
        assertEquals(listOf("+ 2. 补充负责人", "- 2. 整理待办"), vm.ui.value.skills.drafts.single().changedLines)
    }

    @Test fun restoreSuspended() = runTest {
        val s = com.wangzan101.mika.core.learn.skills.PersonalSkill("meeting-notes", skillMd("meeting-notes", "## 步骤\n1. 点搜索"), com.wangzan101.mika.core.learn.skills.SkillStatus.suspended, "r0", null, 0, listOf("failed", "verified", "unverified"))
        val skills = com.wangzan101.mika.task.MapSkills(s)
        val vm = skillsVm(skills)
        val row = vm.ui.value.skills.installed.single()
        assertTrue(row.suspended)
        assertEquals(listOf("没办成", "核查通过", "没能核实"), row.recent)
        vm.restoreSkill("meeting-notes"); runCurrent()
        assertEquals(com.wangzan101.mika.core.learn.skills.SkillStatus.active, skills.get("meeting-notes")!!.status)
        assertTrue(skills.get("meeting-notes")!!.outcomes.isEmpty())
        assertTrue(!vm.ui.value.skills.installed.single().suspended)
        vm.suspendSkill("meeting-notes"); runCurrent()
        assertTrue(vm.ui.value.skills.installed.single().suspended)
        vm.deleteSkill("meeting-notes"); runCurrent()
        assertTrue(vm.ui.value.skills.installed.isEmpty())
    }

    // ---- 导入别人分享的技能(#165) ----

    @Test fun importedSkillWaitsInPendingThenTrial() = runTest {
        val skills = com.wangzan101.mika.task.MapSkills()
        val vm = skillsVm(skills)
        assertEquals(null, vm.importSkill(fullMd("meeting-notes", "1. 用 `phone_tap` 点搜索框"))); runCurrent()
        assertEquals(Segment.Pending, vm.ui.value.segment)
        assertEquals("已导入，到「待确认」里看看再收下。", vm.ui.value.message)
        val draft = vm.ui.value.skills.drafts.single()
        assertTrue(draft.imported)
        assertTrue(skills.get("meeting-notes") == null)
        vm.approveSkill(draft.id); runCurrent()
        val got = skills.get("meeting-notes")!!
        assertTrue(got.imported)
        assertTrue(vm.ui.value.skills.installed.single().imported)
    }

    @Test fun importRefusesBadOrClashingSkills() = runTest {
        val vm = skillsVm(com.wangzan101.mika.task.MapSkills(), builtin = setOf("meeting-notes"))
        assertEquals("和内置技能重名了，改个名字再试。", vm.importSkill(fullMd("meeting-notes", "1. 点搜索框")))
        assertTrue(vm.importSkill(skillMd("other", "## 步骤\n1. 点搜索框"))!!.contains("五个小节"))
        // 原因由导入框自己显示,页面上不另弹提示
        assertEquals(null, vm.ui.value.message)
        assertTrue(vm.ui.value.skills.drafts.isEmpty())
    }

    @Test fun importedDraftIsCheckedEvenWhenNotEdited() = runTest {
        val skills = com.wangzan101.mika.task.MapSkills()
        val imported = com.wangzan101.mika.core.learn.skills.PersonalSkill("meeting-notes", fullMd("meeting-notes", "用 `missing_tool` 打开"),
            com.wangzan101.mika.core.learn.skills.SkillStatus.pending, null, null, 0, emptyList(), source = com.wangzan101.mika.core.learn.skills.PersonalSkill.SOURCE_IMPORTED)
        skills.putPending("d1", imported)
        skills.putPending("d2", imported.copy(name = "learned", markdown = fullMd("learned", "用 `missing_tool` 打开"), learnedFrom = "r1", source = null))
        val vm = skillsVm(skills)
        vm.approveSkill("d1"); runCurrent()
        assertEquals("里面提到了 Mika 没有的工具", vm.ui.value.message)
        assertTrue(skills.get("meeting-notes") == null)
        // 自学的草稿起草时已经按那次运行的工具查过,没改过就照旧收下
        vm.approveSkill("d2"); runCurrent()
        assertTrue(skills.get("learned") != null)
    }
}
