package com.wangzan101.mika.core.learn.teach

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachDraftTest {
    private val id = "b".repeat(32)

    private class FixedModel(private val reply: String) : ChatModel {
        override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 8_000, reasoning = false)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
            ModelReply(listOf(Part.Text(reply)), StopReason.EndTurn, null)
    }

    private suspend fun drafted(reply: String) = TeachDraft.draft(
        session(), route(), FixedModel(reply), emptySet(), null, emptySet(), { false }, 1_000L,
    )

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

    /** 目标是什么语言，模型就按什么语言写；技能正文不带中文的「观察到」也不能因此丢掉模型稿。 */
    @Test fun modelDraftInTheGoalLanguageIsKeptWithoutChineseEvidenceLabels() = runBlocking {
        val reply = """
            ---
            name: xhs-search-save
            display: Search Xiaohongshu and save the first note
            description: When the user wants to search Xiaohongshu and save a note.
            apps: [com.xingin.xhs]
            aliases: [save a xiaohongshu note]
            source: demonstrated
            ---
            ## 入口
            Open Xiaohongshu directly. The search icon is at the top of the home feed.
            ## 步骤
            1. Open Xiaohongshu.
            2. Search for the requested keyword and open the first note.
            3. Tap the star at the bottom right to save it.
            ## 容易出错的地方
            The star is the third icon in the bottom bar, not the heart.
            ## 怎样算办成(核实证据)
            The star turns yellow and a "Saved" toast appears.
            ## 什么时候停下交给用户
            Stop if a login wall appears.
        """.trimIndent()
        val skill = drafted(reply)
        assertNotNull(skill)
        assertTrue(skill!!.skill.markdown.contains("The star is the third icon in the bottom bar"))
        assertFalse(skill.skill.markdown.contains("这份由本机按示范路线整理"))
        assertTrue("保留的模型稿要标成模型写的", skill.byModel)
    }

    /** 模型稿不成形时仍然退回本机整理，不因此丢掉整次示范。 */
    @Test fun brokenModelDraftFallsBackToLocalAssembly() = runBlocking {
        val skill = drafted("Just a sentence, no sections at all.")
        assertNotNull(skill)
        assertTrue(skill!!.skill.markdown.contains("这份由本机按示范路线整理"))
        assertFalse("本机整理的稿不能被标成模型写的", skill.byModel)
    }

    /** 模型说「试跑成功」就不收它的稿：补上的出处提示不能把这句话洗白。 */    @Test fun modelClaimingATrialRunFallsBackToTheLocalAssembly() = runBlocking {
        val reply = """
            ---
            name: xhs-search-save
            display: 小红书搜装修并收藏
            description: 当我说「帮我找装修案例并收藏第一条」时用
            apps: [com.xingin.xhs]
            aliases: []
            source: demonstrated
            ---
            ## 入口
            直接打开小红书。
            ## 步骤
            1. 点搜索，输入关键词。
            ## 容易出错的地方
            收藏在右下角，不是小心心。
            ## 怎样算办成(核实证据)
            已经试跑成功，底部显示已收藏。
            ## 什么时候停下交给用户
            遇到登录页就停。
        """.trimIndent()
        val skill = drafted(reply)
        assertNotNull(skill)
        assertFalse("声称试跑成功的模型稿不能收", skill!!.skill.markdown.contains("已经试跑成功"))
        assertTrue(skill.skill.markdown.contains("这份由本机按示范路线整理"))
    }

    /** 只剩「启动 App」的路线整不出技能:没有真实操作的示范不该变成一份空壳。 */
    @Test fun aRouteWithoutAnyRealActionProducesNoSkill() = runBlocking {
        val onlyLaunch = TeachRoute(
            steps = listOf(RouteStep("com.xingin.xhs", "启动 com.xingin.xhs", null)),
            apps = listOf(RouteApp("com.xingin.xhs", "com.xingin.xhs", false)),
            folded = emptyList(),
        )
        assertNull(TeachDraft.draft(session(), onlyLaunch, null, emptySet(), null, emptySet(), { false }, 1_000L))
    }
}
