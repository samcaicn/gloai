package com.wangzan101.mika.core.learn.skills

import com.wangzan101.mika.core.assembly.knownToolNames
import com.wangzan101.mika.core.learn.knowledge.FrontMatter
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 别人分享来的 SKILL.md 进待收下(#165):出处、校验、内置名、同名更新、已有草稿。 */
class SkillImportTest {
    private val builtin = GuideCatalog.loadBundled().items.map { it.name }.toSet()

    private fun md(name: String = "meeting-notes", extra: String = "", steps: String = "1. 提取决定、负责人和截止时间\n2. 按主题整理待办") = """
        |---
        |name: $name
        |display: 会议纪要整理
        |description: 把会议记录整理成决定和待办
        |$extra---
        |## 入口
        |打开一份会议记录。
        |## 步骤
        |$steps
        |## 容易出错的地方
        |决定和待办要分开整理。
        |## 怎样算办成
        |纪要里有明确的决定、负责人和待办。
        |## 什么时候停下交给用户
        |内容不清楚时交给用户确认。
        |""".trimMargin()

    private fun prepare(raw: String, store: PersonalSkills = InMemoryPersonalSkills()) =
        SkillImport.prepare(raw, store, builtin, knownToolNames(), scriptTool = null, id = "imp1", now = 42L)

    @Test fun becomesAPendingDraftMarkedImported() {
        val r = prepare(md()) as SkillImport.Result.Ready
        assertEquals("imp1", r.id)
        assertEquals(SkillStatus.pending, r.skill.status)
        assertTrue(r.skill.imported)
        assertNull(r.skill.learnedFrom)
        assertNull(r.skill.replacesDraftOf)
        assertEquals(PersonalSkill.SOURCE_IMPORTED, FrontMatter.parse(r.skill.markdown).first["source"])
    }

    @Test fun localFieldsFromTheSenderAreDropped() {
        // 重复或带空白的本机字段也不能留下,出处只由代码写
        val raw = md(extra = "source: user\nsource: learned\nlearned_from: run-42\nlearned_from : run-43\nupdates: other-skill\nupdates : another-skill\n")
        val result = (prepare(raw) as SkillImport.Result.Ready).skill
        val meta = FrontMatter.parse(result.markdown).first
        assertEquals(PersonalSkill.SOURCE_IMPORTED, meta["source"])
        assertNull(meta["learned_from"])
        assertNull(meta["updates"])
        assertEquals(1, result.markdown.lines().count { it.substringBefore(':').trim() == "source" })
    }

    @Test fun fencedAndCrlfTextIsAccepted() {
        val raw = "```markdown\n" + md().replace("\n", "\r\n") + "\n```"
        assertTrue(prepare(raw) is SkillImport.Result.Ready)
    }

    @Test fun sameChecksAsLearnedDrafts() {
        assertEquals(SkillImport.Result.Rejected("missing_section"), prepare(md().substringBefore("## 容易出错的地方")))
        assertEquals(SkillImport.Result.Rejected("invalid_name"), prepare(md(name = "Meeting Notes")))
        assertEquals(SkillImport.Result.Rejected("unknown_tool"), prepare(md(steps = "1. 调用 `missing_tool` 整理记录")))
        assertEquals(SkillImport.Result.Rejected("too_long"), prepare(md(steps = "1. " + "很长".repeat(SkillDrafter.MAX_CHARS))))
    }

    @Test fun builtinNamesAreRefused() {
        val name = builtin.first()
        assertEquals(SkillImport.Result.Rejected("builtin_name"), prepare(md(name = name)))
    }

    @Test fun sameNameAsMySkillBecomesAnUpdateDraft() {
        val mine = personalSkill("meeting-notes")
        val r = prepare(md(), InMemoryPersonalSkills(mine)) as SkillImport.Result.Ready
        assertEquals("meeting-notes", r.skill.replacesDraftOf)
        assertEquals(PersonalSkills.fingerprint(mine.markdown), r.skill.baseHash)
    }

    @Test fun refusedWhileADraftWithThatNameWaits() {
        val store = InMemoryPersonalSkills().apply { putPending("d1", personalSkill("meeting-notes", status = SkillStatus.pending)) }
        assertEquals(SkillImport.Result.Rejected("draft_pending"), prepare(md(), store))
    }

    @Test fun refusedWhileAnUpdateForThatSkillWaits() {
        val store = InMemoryPersonalSkills().apply {
            putPending("d1", personalSkill("other-notes", status = SkillStatus.pending).copy(replacesDraftOf = "meeting-notes"))
        }
        assertEquals(SkillImport.Result.Rejected("draft_pending"), prepare(md(), store))
    }

    @Test fun shareableDropsLocalFieldsOnly() {
        val shared = SkillImport.shareable(md(extra = "source: user\nsource: learned\nlearned_from: run-42\nlearned_from : run-43\n"))
        val meta = FrontMatter.parse(shared).first
        assertNull(meta["source"])
        assertNull(meta["learned_from"])
        assertEquals("meeting-notes", meta["name"])
        assertTrue(shared, "## 步骤" in shared)
        assertFalse(shared, "\r" in shared)
    }

    @Test fun importedSkillGoesBackToTrialWhenRestored() {
        val s = (prepare(md()) as SkillImport.Result.Ready).skill.copy(status = SkillStatus.suspended)
        assertEquals(SkillStatus.trial, s.restoredStatus())
    }
}
