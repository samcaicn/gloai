package com.wangzan101.mika.core.learn.teach

import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.knowledge.GuideView
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.learn.skills.InMemoryPersonalSkills
import com.wangzan101.mika.core.learn.skills.PersonalSkill
import com.wangzan101.mika.core.learn.skills.SkillDrafter
import com.wangzan101.mika.core.learn.skills.SkillStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachTraceTest {
    private val own = "com.wangzan101.mika"
    private val id = "a".repeat(32)
    private val secret = "验证码是 123456"
    private val card = "4242424242424242"

    private fun session(phase: TeachPhase = TeachPhase.Recording) =
        TeachSession(id, "找到筛选入口", phase)

    private fun obs(
        kind: TeachEventKind,
        pkg: String = "com.example.app",
        at: Long = 1_000,
        text: String? = null,
        description: String? = null,
        viewId: String? = null,
        title: String? = null,
        className: String? = null,
        password: Boolean = false,
        displayId: Int = TEACH_MAIN_DISPLAY,
    ) = TeachObservation(at, kind, pkg, className, title, text, description, viewId, password, displayId)

    @Test fun doesNotCollectBeforeStartOrWhilePaused() {
        listOf(TeachPhase.Idle, TeachPhase.Ready, TeachPhase.Paused, TeachPhase.Interrupted, TeachPhase.Reviewing).forEach { phase ->
            val s = session(phase)
            assertSame(s, TeachTrace.reduce(s, obs(TeachEventKind.Click, description = "筛选"), own))
        }
        assertFalse(TeachPhase.Ready.collects())
        assertFalse(TeachPhase.Paused.collects())
        assertTrue(TeachPhase.Recording.blocksTasks())
        assertTrue(TeachPhase.Paused.blocksTasks())
        assertFalse(TeachPhase.Interrupted.blocksTasks())
        assertFalse(TeachPhase.Reviewing.blocksTasks())
    }

    @Test fun ignoresOwnUiAndOtherDisplays() {
        val s = session()
        assertSame(s, TeachTrace.reduce(s, obs(TeachEventKind.Click, pkg = own, description = "正在学习"), own))
        assertSame(s, TeachTrace.reduce(s, obs(TeachEventKind.Click, pkg = own, description = "暂停"), own))
        assertSame(s, TeachTrace.reduce(s, obs(TeachEventKind.Click, description = "筛选", displayId = 5), own))
        assertSame(s, TeachTrace.reduce(s, obs(TeachEventKind.Click, pkg = "  "), own))
    }

    @Test fun recordingRequiresAVisibleStopControl() {
        assertFalse(TeachWatch.allowsCollecting(false))
        assertTrue(TeachWatch.allowsCollecting(true))
        val recording = session(TeachPhase.Recording)
        val stopped = recording.copy(phase = TeachWatch.afterControlLost(recording.phase))
        assertEquals(TeachPhase.Interrupted, stopped.phase)
        assertFalse(stopped.phase.collects())
        assertSame(stopped, TeachTrace.reduce(stopped, obs(TeachEventKind.Click, description = "筛选"), own))
        assertEquals(TeachPhase.Interrupted, TeachWatch.afterControlLost(TeachPhase.Paused))
        assertEquals(TeachPhase.Reviewing, TeachWatch.afterControlLost(TeachPhase.Reviewing))
    }

    @Test fun onlyDemonstratedSkillsRetireTheirTrace() {
        val demo = com.wangzan101.mika.core.learn.skills.PersonalSkill(
            "demo-aaaaaaaa", "---\nsource: demonstrated\n---\n正文", com.wangzan101.mika.core.learn.skills.SkillStatus.pending,
            "teach:$id", null, 0L, emptyList(), source = TeachSkill.SOURCE,
        )
        val learned = demo.copy(source = null, learnedFrom = "run-1", markdown = "---\nsource: learned\n---\n正文")
        assertTrue(TeachSkill.retiresTrace(demo, id))
        assertFalse(TeachSkill.retiresTrace(learned, id))
        assertFalse(TeachSkill.retiresTrace(demo.copy(learnedFrom = "teach:short"), id))
        assertFalse(TeachSkill.retiresTrace(demo.copy(source = "user", markdown = "---\nsource: user\n---\n正文"), id))
    }

    @Test fun mergesRedundantEventsAndKeepsWindowPackage() {
        var s = session()
        repeat(5) { i ->
            s = TeachTrace.reduce(s, obs(TeachEventKind.Scroll, at = 1_000L + i * 100, title = "列表"), own)
        }
        s = TeachTrace.reduce(s, obs(TeachEventKind.Click, at = 3_000, description = "筛选", pkg = "com.example.app"), own)
        s = TeachTrace.reduce(s, obs(TeachEventKind.Click, at = 3_100, description = "筛选"), own)
        s = TeachTrace.reduce(s, obs(TeachEventKind.Text, at = 4_000, description = "关键词", text = "上海"), own)
        s = TeachTrace.reduce(s, obs(TeachEventKind.Text, at = 4_400, description = "关键词", text = "上海南"), own)
        s = TeachTrace.reduce(s, obs(TeachEventKind.Window, at = 6_000, pkg = "com.other.app", title = "结果", className = "com.other.app.ResultActivity"), own)
        assertEquals(listOf(TeachKind.Scroll, TeachKind.Click, TeachKind.Type, TeachKind.Window), s.steps.map { it.kind })
        assertEquals("com.example.app", s.steps[1].packageName)
        assertEquals("com.other.app", s.steps[3].packageName)
        assertEquals("结果", s.steps[3].window)
        assertFalse(TeachCodec.encode(s).contains("上海"))
    }

    @Test fun omitsSecretsAndDoesNotInventCoordinates() {
        var s = session()
        s = TeachTrace.reduce(s, obs(TeachEventKind.Text, description = "验证码", text = secret, password = true, viewId = "com.example.app:id/otp"), own)
        s = TeachTrace.reduce(s, obs(TeachEventKind.Click, at = 5_000, text = card), own)
        s = TeachTrace.reduce(s, obs(TeachEventKind.Click, at = 9_000), own)
        val encoded = TeachCodec.encode(s)
        assertFalse(encoded.contains("123456"))
        assertFalse(encoded.contains(card))
        assertTrue(s.steps[0].sensitiveOmitted)
        assertTrue(s.steps[1].sensitiveOmitted)
        assertTrue(s.steps[2].insufficient)
        assertTrue(s.steps[2].needsClarification)
        assertFalse(s.steps[0].needsClarification && s.steps[0].nodeName == null && s.steps[0].viewId != null)
        val line = teachLine(s.steps[2])
        assertTrue(line.contains("没有可靠"))
        assertFalse(Regex("\\d+\\s*,\\s*\\d+").containsMatchIn(line))
        assertFalse(teachGoalAccepted("密码是 123456"))
        val edited = TeachTrace.annotate(s, s.steps[0].id, secret, null)
        assertTrue(edited is TeachEdit.Secret)
        assertFalse(TeachCodec.encode(s).contains("123456"))
    }

    @Test fun namedClickNeedsNoClarificationAndRemovalDropsTheStep() {
        var s = TeachTrace.reduce(session(), obs(TeachEventKind.Click, description = "筛选"), own)
        assertFalse(s.steps.single().needsClarification)
        assertFalse(s.steps.single().insufficient)
        val note = TeachTrace.annotate(s, s.steps.single().id, null, "右上角才是筛选，不是底部标签")
        s = (note as TeachEdit.Ok).session
        assertTrue(teachLine(s.steps.single()).contains("[你说明的]"))
        s = TeachTrace.remove(s, s.steps.single().id)
        assertTrue(s.steps.isEmpty())
    }

    @Test fun allPasswordEventsOmitUnmaskedContent() {
        val raw = "FakePasswordValue79"
        for (kind in TeachEventKind.entries) {
            val captured = TeachTrace.reduce(session(), obs(kind, text = raw,
                description = raw, title = raw, viewId = "app:id/$raw", password = true), own)
            assertTrue(kind.name, captured.steps.single().sensitiveOmitted)
            assertFalse(kind.name, TeachCodec.encode(captured).contains(raw))
            assertFalse(kind.name, teachLine(captured.steps.single()).contains(raw))
        }
    }

    @Test fun legacySensitiveStepsLoseStoredLabelsWithoutChangingOtherSteps() {
        val session = TeachTrace.reduce(session(), obs(TeachEventKind.Click, text = "ordinary label"), own)
        val ordinary = session.steps.single()
        val legacy = ordinary.copy(id = 2, nodeName = "legacy secret", sensitiveOmitted = true)
        val redacted = TeachTrace.redactStored(session.copy(steps = listOf(ordinary, legacy)))
        assertEquals(ordinary, redacted.steps.first())
        assertFalse(TeachCodec.encode(redacted).contains("legacy secret"))
        assertTrue(redacted.steps.last().needsClarification)
    }

    @Test fun draftStaysPendingAndUnverifiedUntilAccepted() = runBlocking {
        val s = TeachTrace.reduce(session(), obs(TeachEventKind.Click, description = "筛选", title = "列表"), own)
        val model = FakeModel(ModelReply(listOf(Part.Text("试跑成功了，入口不存在。")), StopReason.EndTurn, null))
        val drafted = TeachDraft.draft(s, TeachRoute.fold(s.steps, emptySet(), emptySet()) { it }, model, emptySet(), null, emptySet(), { false }, 10L)
        assertTrue(drafted != null)
        val skill = drafted!!.skill
        assertEquals(SkillStatus.pending, skill.status)
        assertEquals(TeachSkill.SOURCE, skill.source)
        assertTrue(skill.markdown.contains(TeachSkill.NOT_TRIAL_RUN))
        assertTrue(skill.markdown.contains(TeachSkill.NOT_ABSENT))
        assertTrue(skill.markdown.contains(TeachSkill.STOP_IF_UNMATCHED))
        assertTrue(skill.markdown.contains("[观察到]"))
        assertFalse(skill.markdown.contains("试跑成功了"))
        assertNull(SkillDrafter.validate(skill.markdown, emptySet(), null))
        val request = model.requests.single().joinToString { it.parts.filterIsInstance<Part.Text>().joinToString { p -> p.text } }
        assertTrue(request.contains("筛选"))
        assertFalse(request.contains(secret))

        val store = InMemoryPersonalSkills()
        TeachPublish.toPending(store, "draft1", skill)
        val pendingCatalog = GuideCatalog.withPersonal(GuideCatalog.loadBundled(), store)
        assertTrue(pendingCatalog.items.none { it.name == skill.name })
        val read = pendingCatalog.view(skill.name)
        assertTrue(read is GuideView.Failed)

        val trial = skill.copy(status = SkillStatus.trial)
        val live = GuideCatalog.withPersonal(GuideCatalog.loadBundled(), InMemoryPersonalSkills(trial))
        val found = live.view(skill.name) as GuideView.Found
        assertTrue(found.content.contains("not trial-run"))
        assertTrue(found.content.contains("stop using this skill"))
        assertTrue(found.info.description.contains("not trial-run"))
        assertFalse(found.content.contains("trial-run succeeded"))
    }

    @Test fun directEnableIsRejected() {
        val skill = PersonalSkill("demo-aaaaaaaa", "x", SkillStatus.active, "teach:$id", null, 0L, emptyList(), source = TeachSkill.SOURCE)
        val failed = runCatching { TeachPublish.toPending(InMemoryPersonalSkills(), "d", skill) }
        assertTrue(failed.isFailure)
    }
}
