package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.learn.skills.InMemoryPersonalSkills
import com.wangzan101.mika.core.learn.skills.PersonalSkill
import com.wangzan101.mika.core.learn.skills.SkillStatus
import com.wangzan101.mika.core.learn.skills.personalSkill
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

class AgentLoopSkillTest {
    private class ListSink : RunSink {
        val events = mutableListOf<RunEvent>()
        override fun event(e: RunEvent) { events += e }
    }

    private fun call(name: String, args: String = "{}") = Part.ToolCall("k$name${args.hashCode()}", name, args)
    private fun reply(vararg c: Part.ToolCall) = ModelReply(c.toList(), StopReason.ToolUse, null)
    private val finish = com.wangzan101.mika.core.testing.answerReply("ok")
    private val bundled = GuideCatalog.loadBundled()

    private class Run(val requests: List<List<Message>>, val events: List<RunEvent>)

    private fun run(task: String, vararg personal: PersonalSkill, recommend: Boolean = true, script: List<Any> = listOf(finish)): Run {
        val catalog = GuideCatalog.withPersonal(bundled, InMemoryPersonalSkills(*personal))
        val model = FakeModel(*script.toTypedArray())
        val sink = ListSink()
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), skills = catalog), "B", sink = sink,
                skills = catalog, recommendSkills = recommend).run(task)
        }
        return Run(model.requests, sink.events)
    }

    private fun List<Message>.userTexts() = filter { it.role == Role.User }.map { it.plainText() }
    private fun List<Message>.index() = userTexts().filter { it.startsWith(SKILL_INDEX_HEADER) || it.startsWith(KNOWLEDGE_INDEX_HEADER) }
    private fun List<Message>.skillLines() = index().flatMap { it.substringAfter(SKILL_INDEX_HEADER + "\n", "").lines() }.filter { it.startsWith("- ") }
    private val zhihu = personalSkill("zhihu-collections", display = "查看知乎收藏夹列表", description = "打开知乎我的收藏,数收藏夹和条数",
        extraMeta = "aliases: [知乎收藏夹, 我的收藏, 收藏夹条数]\n")

    @Test fun knowledgeIndexStillByTaskWords() {
        val r = run("用闲鱼查手机,再发微信给小王,再在知乎搜一下")
        val idx = r.requests.first().index().single()
        // 知识和技能各在自己的标题下(标题里写着用哪个工具读全文)
        assertTrue(idx.lines().first() == KNOWLEDGE_INDEX_HEADER && idx.lines().any { it.startsWith("- app:xianyu(闲鱼):") })
        assertTrue(SKILL_INDEX_HEADER in idx.lines())
        // 索引行在任务原文之后
        val texts = r.requests.first().userTexts()
        assertTrue(texts.indexOf(idx) > texts.indexOfFirst { it.startsWith("用闲鱼") })
    }

    // 目标和技能名一个字面也对不上:技能照样全部列出,由模型按描述判断
    @Test fun allSkillsListedEvenWithoutSharedWords() {
        val r = run("知乎上我一共建了几个收藏夹？哪个收藏夹里的内容最多？", zhihu, personalSkill("a-trip", display = "我的出行"))
        val lines = r.requests.first().skillLines()
        val builtIn = bundled.items.filter { it.kind == "skill" }.map { it.name }.sorted()
        assertEquals(builtIn.map { "skill:$it" } + listOf("personal:a-trip", "personal:zhihu-collections"), lines.map { it.removePrefix("- ").substringBefore('(') })
        assertTrue(lines.contains("- personal:zhihu-collections(查看知乎收藏夹列表):打开知乎我的收藏,数收藏夹和条数"))
        // 和任务毫不相干的也列出:不按字面挑
        assertTrue(run("把屏幕亮度调高一点", zhihu).requests.first().skillLines().any { it.startsWith("- personal:zhihu-collections(") })
    }

    @Test fun suspendedAndPendingNotListedTrialMarked() {
        val store = InMemoryPersonalSkills(zhihu.copy(status = SkillStatus.trial), personalSkill("old-one", status = SkillStatus.suspended))
        store.putPending("d1", personalSkill("draft-one"))
        val catalog = GuideCatalog.withPersonal(bundled, store)
        val lines = GuideRecommender.index(catalog, "随便")!!.lines()
        assertTrue(lines.any { it.startsWith("- personal:zhihu-collections(查看知乎收藏夹列表):[unverified") })
        assertTrue(lines.none { "old-one" in it || "draft-one" in it })
    }

    // 开头只给索引,个人技能的全文要模型自己用 skill_read 读
    @Test fun noPersonalFullTextAtStart() {
        val mine = personalSkill("lao-ma-msg", body = "## 入口\n打开微信找老妈", display = "给老妈发消息")
        val r = run("给老妈发消息说晚点到", mine)
        val first = r.requests.first()
        assertTrue(first.userTexts().none { "打开微信找老妈" in it })
        assertTrue(first.userTexts().any { "- personal:lao-ma-msg(给老妈发消息):" in it })
        assertTrue(r.events.none { it is RunEvent.SkillUsed })
    }

    @Test fun skillReadOfPersonalRecordsSkillUsed() {
        val r = run("知乎上我一共建了几个收藏夹？", zhihu, personalSkill("my-trip", display = "我的出行"), script = listOf(
            reply(call("skill_read", """{"name":"zhihu-collections"}""")),
            reply(call("knowledge_read", """{"name":"app:wechat"}""")), // 内置的不记
            finish,
        ))
        // 全文由 skill_read 的结果给出
        assertTrue(r.requests.last().any { m -> m.parts.any { it is Part.ToolResult && "打开 App" in it.content } })
        val used = r.events.filterIsInstance<RunEvent.SkillUsed>().map { it.name to it.how }
        assertEquals(listOf("zhihu-collections" to "viewed"), used)
    }

    @Test fun indexOffWhenRecommendDisabled() {
        val off = run("给老妈发消息", personalSkill("lao-ma-msg", display = "给老妈发消息"), recommend = false)
        assertTrue(off.requests.first().index().isEmpty())
    }
}
