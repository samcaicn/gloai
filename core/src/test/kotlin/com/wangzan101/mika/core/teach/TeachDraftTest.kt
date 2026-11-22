package com.wangzan101.mika.core.teach

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachDraftTest {
    private val id = "b".repeat(32)

    private fun session() = TeachSession(
        id = id,
        goal = "在小红书搜索装修并收藏第一条",
        phase = TeachPhase.Reviewing,
        steps = listOf(
            TeachStep(
                id = 1, atMs = 1_000L, packageName = "com.xingin.xhs", window = "首页",
                kind = TeachKind.Click, nodeName = "搜索", viewId = null,
                insufficient = false, sensitiveOmitted = false, needsClarification = false,
            ),
        ),
    )

    private fun route() = TeachRoute(
        steps = listOf(
            RouteStep("com.xingin.xhs", "启动 com.xingin.xhs", null),
            RouteStep("com.xingin.xhs", "[观察到] 点了「搜索」（com.xingin.xhs · 首页）", TeachKind.Click),
        ),
        apps = listOf(RouteApp("com.xingin.xhs", "com.xingin.xhs", false)),
        folded = listOf("切到「桌面」", "点了「小红书」"),
    )

    @Test fun requestTellsTheModelToUseDirectLaunchesAndLinks() {
        val req = TeachDraft.request(session(), route())
        assertTrue(req.contains("启动 com.xingin.xhs"))
        assertTrue(req.contains("直接打开"))
        assertTrue(req.contains("不要写") && req.contains("桌面"))
    }

    @Test fun requestCarriesTheFoldedHumanSteps() {
        val req = TeachDraft.request(session(), route())
        assertTrue(req.contains("已按 Mika 的直达能力省掉"))
        assertTrue(req.contains("点了「小红书」"))
        assertTrue(req.contains("输入的文字没有保存"))
    }

    @Test fun localAssemblyNeverMentionsTheDesktop() {
        val md = TeachDraft.assemble(session(), route(), "xhs-search")
        assertFalse(md.contains("桌面"))
        assertTrue(md.contains("启动 com.xingin.xhs"))
    }

    @Test fun localAssemblyKeepsTheEvidenceCaveats() {
        val md = TeachDraft.assemble(session(), route(), "xhs-search")
        assertTrue(md.contains(TeachSkill.NOT_ABSENT))
        assertTrue(md.contains(TeachSkill.NOT_TRIAL_RUN))
        assertTrue(md.contains(TeachSkill.STOP_IF_UNMATCHED))
    }
}
