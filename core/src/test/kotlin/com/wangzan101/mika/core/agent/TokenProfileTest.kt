package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.testing.FakeChatModel
import com.wangzan101.mika.core.testing.FakeRequest
import com.wangzan101.mika.core.testing.Screen
import com.wangzan101.mika.core.testing.ScriptedScreen
import com.wangzan101.mika.core.testing.button
import com.wangzan101.mika.core.testing.fakeModel
import com.wangzan101.mika.core.testing.label
import com.wangzan101.mika.core.assembly.toolDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剖析工具:用假模型驱动真的 AgentLoop 跑一个典型多步手机任务,逐轮打印发给模型的
 * 输入 token 结构(固定开销 = 系统提示词 + 工具定义;历史 = 消息列表;图片数)。
 * 口径与 ModelCaller 完全一致:overhead = TokenEstimate.of(system) + Σ TokenEstimate.of(name+desc+schema),
 * history = TokenEstimate.of(messages)。
 * 注意:假模型无视觉,history 里没有截图图片;实机每看一次屏还附一张 ~1280px 截图(约 983 token),
 * 报告末尾单独估了这块。跑法:./gradlew :core:test --tests '*TokenProfileTest' --info
 */
class TokenProfileTest {

    private fun overheadTokens(system: String, tools: List<ToolSpec>): Int =
        TokenEstimate.of(system) + tools.sumOf { TokenEstimate.of(it.name + it.description + it.parametersJsonSchema) }

    private fun historyTokens(req: FakeRequest): Int = TokenEstimate.of(req.messages)

    private fun imageCount(req: FakeRequest): Int =
        req.messages.sumOf { m -> m.parts.count { it is Part.Image } }

    @Test fun profileTypicalPhoneTask() = runTest {
        // 一个常见的「查 WLAN 连接状态」任务:看屏 → 点 → 看屏 → 点 → 看屏 → 回答(3 次看屏)。
        val screen = ScriptedScreen(listOf(
            Screen(SETTINGS, listOf(label("@e1", "设置", 80), button("@e2", "WLAN", 300), button("@e3", "蓝牙", 400))),
            Screen(SETTINGS, listOf(label("@e1", "WLAN", 80), label("@e2", "已开启", 200), label("@e3", "home-5G", 320), label("@e4", "已连接", 360))),
            Screen(SETTINGS, listOf(label("@e1", "home-5G", 80), label("@e2", "已连接", 200), label("@e3", "信号强", 300))),
        ))
        val main = fakeModel("main") {
            reply { thinking("先看设置首页"); text("看一下设置首页"); call("phone_observe") }
            call("phone_tap", "ref" to "@e2", "snapshotId" to "s1")
            call("phone_observe")
            call("phone_tap", "ref" to "@e3", "snapshotId" to "s2")
            call("phone_observe")
            answer("手机连的是 home-5G,已连接,信号强。")
        }
        val catalog by lazy { GuideCatalog.loadBundled() }
        val bundled by lazy { KnowledgeStore(KnowledgeStore.loadBundled()) }
        val loop = AgentLoop(
            main, toolDispatcher(screen, skills = catalog), SystemPrompt.identity(), maxSteps = 20,
            knowledge = bundled, skills = catalog,
        )
        val end = loop.run("看看手机连的哪个 WLAN")
        assertTrue("$end", end is TaskState.Succeeded)

        val sb = StringBuilder()
        sb.append("=== 典型任务 token 剖析(假模型驱动, 口径=ModelCaller) ===\n")
        sb.append("任务:看看手机连的哪个 WLAN;共 ${main.requests.size} 轮\n\n")
        sb.append("%-3s %8s %8s %8s %5s %8s\n".format("轮", "系统", "工具", "历史", "图片", "合计"))
        var totalInput = 0
        var totalSystem = 0
        var totalTools = 0
        var totalHistory = 0
        main.requests.forEachIndexed { i, req ->
            val sys = TokenEstimate.of(req.system)
            val tools = overheadTokens(req.system, req.tools) - sys
            val hist = historyTokens(req)
            val img = imageCount(req)
            val total = sys + tools + hist
            totalInput += total; totalSystem += sys; totalTools += tools; totalHistory += hist
            sb.append("%-3d %8d %8d %8d %5d %8d\n".format(i + 1, sys, tools, hist, img, total))
        }
        sb.append("\n合计输入 token: $totalInput(系统 $totalSystem + 工具 $totalTools + 历史 $totalHistory)\n")
        sb.append("固定开销(每轮都重发的 系统+工具): ${totalSystem + totalTools},占 ${"%.1f".format((totalSystem + totalTools) * 100.0 / totalInput)}%%\n")

        // 实机图片:每次 phone_observe 附一张截图,ModelImage 缩到 1280 边、JPEG 80,约 983 token/张。
        val observesReal = main.requests.count { "phone_observe" in it.toolNames }
        val imageEstimate = observesReal * 983
        sb.append("实机看屏 $observesReal 次 ≈ 图片 $imageEstimate token(估,不在上面历史列里)\n")
        sb.append("实机合计(含图片): ${totalInput + imageEstimate}\n")

        println(sb)
    }

    @Test fun toolCostAudit() {
        // 纯手机任务(和 simplePhoneTask 同口径)全展开后的工具集 = 第 2 轮起每轮都发给模型的那份。
        val dispatcher = toolDispatcher(FakeScreen())
        val specs = dispatcher.offeredNow(all = true)
        data class Row(val name: String, val nameT: Int, val descT: Int, val schemaT: Int, val total: Int)
        val rows = specs.map { s ->
            val nameT = TokenEstimate.of(s.name)
            val descT = TokenEstimate.of(s.description)
            val schemaT = TokenEstimate.of(s.parametersJsonSchema)
            Row(s.name, nameT, descT, schemaT, nameT + descT + schemaT)
        }.sortedByDescending { it.total }
        val total = rows.sumOf { it.total }
        val nameSum = rows.sumOf { it.nameT }
        val descSum = rows.sumOf { it.descT }
        val schemaSum = rows.sumOf { it.schemaT }

        val sb = StringBuilder()
        sb.append("=== 工具定义成本审计(手机任务全展开, ${rows.size} 个工具) ===\n")
        sb.append("合计 ${total} token = 名字 $nameSum + 描述 $descSum + schema $schemaSum\n\n")
        sb.append("最肥的 15 个:\n")
        sb.append("%-24s %6s %6s %6s %6s\n".format("工具", "名字", "描述", "schema", "合计"))
        rows.take(15).forEach { sb.append("%-24s %6d %6d %6d %6d\n".format(it.name, it.nameT, it.descT, it.schemaT, it.total)) }

        // reason 字段的重复成本:每个非 ownReason 工具都带一段 ~24 token 的说明。
        val reasonTokens = TokenEstimate.of(com.wangzan101.mika.core.tool.REASON_NOTE)
        val withReason = specs.count { "\"reason\"" in it.parametersJsonSchema }
        sb.append("\n每个工具都重复的 reason 说明:${reasonTokens} token × $withReason 个工具 ≈ ${reasonTokens * withReason} token/轮\n")
        println(sb)
    }

    private companion object { const val SETTINGS = "com.android.settings" }
}
