package com.wangzan101.mika.core.learn.skills

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillOutcomesTest {
    @Test fun trialNeedsVerifiedUseBeforeBecomingActive() {
        val draft = personalSkill("trial-skill", status = SkillStatus.trial)
        assertEquals(SkillStatus.trial, SkillOutcomes.record(draft, SkillResult.unverified, followed = true).status)
        assertEquals(SkillStatus.active, SkillOutcomes.record(draft, SkillResult.verified, followed = true).status)
        assertEquals(SkillStatus.suspended, SkillOutcomes.record(draft, SkillResult.verifier_rejected, followed = true).status)
    }

    @Test fun trialSingleFailureStaysTrial() {
        // 一次失败(可能是任务别处的问题)不停用试用技能,只记下这次结果(#569)
        val draft = personalSkill("trial-skill", status = SkillStatus.trial)
        val once = SkillOutcomes.record(draft, SkillResult.failed, followed = false)
        assertEquals(SkillStatus.trial, once.status)
        assertEquals(listOf("failed"), once.outcomes)
        // 照着做被核查退回仍是强信号:一次就停
        assertEquals(SkillStatus.suspended, SkillOutcomes.record(draft, SkillResult.verifier_rejected, followed = true).status)
    }

    @Test fun trialTwoBadInThreeSuspends() {
        // 试用和正式一样:最近几次里多次不灵才停用
        var cur = personalSkill("trial-skill", status = SkillStatus.trial)
        cur = SkillOutcomes.record(cur, SkillResult.failed, followed = false)
        assertEquals(SkillStatus.trial, cur.status)
        cur = SkillOutcomes.record(cur, SkillResult.failed, followed = false)
        assertEquals(SkillStatus.suspended, cur.status)
    }
    private val s = personalSkill("wechat-msg")

    @Test fun keepsLast5() {
        var cur = s
        repeat(7) { cur = SkillOutcomes.record(cur, SkillResult.verified, followed = true) }
        assertEquals(SkillOutcomes.KEEP, cur.outcomes.size)
        assertEquals(SkillStatus.active, cur.status)
    }

    @Test fun twoOfLastThreeSuspends() {
        var cur = SkillOutcomes.record(s, SkillResult.unverified, followed = false)
        assertEquals(SkillStatus.active, cur.status)
        cur = SkillOutcomes.record(cur, SkillResult.verified, followed = false)
        assertEquals(SkillStatus.active, cur.status)
        cur = SkillOutcomes.record(cur, SkillResult.failed, followed = false)
        assertEquals(SkillStatus.suspended, cur.status)
        assertEquals(listOf("unverified", "verified", "failed"), cur.outcomes)
    }

    @Test fun followedRejectionSuspendsImmediately() {
        assertEquals(SkillStatus.suspended, SkillOutcomes.record(s, SkillResult.verifier_rejected, followed = true).status)
        // 没照着它做(只是推荐过)时一次退回不停用
        assertEquals(SkillStatus.active, SkillOutcomes.record(s, SkillResult.verifier_rejected, followed = false).status)
    }

    @Test fun cancelledNotRecorded() {
        assertNull(SkillOutcomes.resultOf(TaskState.Cancelled, RunOutcome.Unknown))
        assertEquals(SkillResult.verified, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = true), RunOutcome.Done))
        assertEquals(SkillResult.unverified, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = false, note = "x"), RunOutcome.Done))
        // 判下来办成、没跑核查:和核实过一样算好(试用据此转正式)
        assertEquals(SkillResult.verified, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = false, reviewed = false), RunOutcome.Done))
        assertEquals(SkillResult.unreviewed, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = false, reviewed = false), RunOutcome.Unknown))
        assertEquals(SkillResult.verifier_rejected, SkillOutcomes.resultOf(TaskState.Failed("核查未通过:没发出去", FailKind.GaveUp), RunOutcome.Unknown))
        // 回答如实说没办成、只办了一部分:算失败;没判成:不算灵也不算不灵
        assertEquals(SkillResult.failed, SkillOutcomes.resultOf(TaskState.Succeeded("找不到这个菜单", verified = true), RunOutcome.NotPossible))
        assertEquals(SkillResult.failed, SkillOutcomes.resultOf(TaskState.Succeeded("只看了两条", verified = true), RunOutcome.Partial))
        assertEquals(SkillResult.unreviewed, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = true), RunOutcome.Unknown))
        assertEquals(SkillResult.failed, SkillOutcomes.resultOf(TaskState.Failed("找不到", FailKind.GaveUp), RunOutcome.Unknown))
        assertEquals(SkillResult.failed, SkillOutcomes.resultOf(TaskState.Failed("步数用完", FailKind.StepLimit), RunOutcome.Unknown))
        // 模型出错、运行环境出问题、操作保护停下、Token 预算用完:说明不了技能或知识灵不灵
        for (k in listOf(FailKind.Model, FailKind.Environment, FailKind.Guard, FailKind.Interrupted, FailKind.TokenLimit))
            assertEquals(k.name, SkillResult.unreviewed, SkillOutcomes.resultOf(TaskState.Failed("x", k), RunOutcome.Unknown))
    }
}
