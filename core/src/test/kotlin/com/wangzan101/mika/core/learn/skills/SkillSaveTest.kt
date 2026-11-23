package com.wangzan101.mika.core.learn.skills

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.learn.knowledge.FrontMatter
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.knowledge.GuideView
import com.wangzan101.mika.core.learn.memory.UserUtterances
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.execute
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillSaveTest {
    private val bundled = GuideCatalog.loadBundled()
    private val ask = "帮我建一个技能:点咖啡先看优惠券再下单"

    private fun md(name: String = "order-coffee", steps: String = "1. 打开咖啡 App\n2. 先看优惠券再下单") = """
        |---
        |name: $name
        |display: 点咖啡
        |description: 用户要点咖啡时用
        |aliases: ["点咖啡"]
        |---
        |## 入口
        |打开咖啡 App。
        |## 步骤
        |$steps
        |## 容易出错的地方
        |优惠券要先领再用。
        |## 怎样算办成
        |订单页能看到这一单。
        |## 什么时候停下交给用户
        |付款前交给用户。
        |""".trimMargin()

    private class Setup(val store: InMemoryPersonalSkills, val tools: ToolDispatcher)

    private fun setup(store: InMemoryPersonalSkills = InMemoryPersonalSkills(), said: String = ask, spec: RunSpec? = null): Setup {
        val u = UserUtterances().apply { add(said) }
        val catalog = GuideCatalog.withPersonal(bundled, store)
        val tools = if (spec != null) toolDispatcher(FakeScreen(), skills = catalog, utterances = u, personalSkills = store, runSpec = spec)
        else toolDispatcher(FakeScreen(), skills = catalog, utterances = u, personalSkills = store)
        return Setup(store, tools)
    }

    private var seq = 0
    private fun ToolDispatcher.call(name: String, args: JsonObject): JsonObject =
        runBlocking { execute(Part.ToolCall("ss${++seq}", name, args.toString())).content }

    private fun save(markdown: String, quote: String = "帮我建一个技能", version: String? = null) = buildJsonObject {
        put("markdown", markdown); put("quote", quote); version?.let { put("version", it) }
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun createsTrialSkillMarkedAsUserCreatedAndVisibleToSearch() {
        val s = setup()
        val r = s.tools.call("skill_save", save(md()))
        assertEquals(r.toString(), "true", r.s("ok"))
        assertEquals("created", r.s("action"))
        assertEquals("trial", r.s("status"))
        val saved = s.store.get("order-coffee")!!
        assertEquals(SkillStatus.trial, saved.status)
        assertTrue(saved.createdByUser)
        assertEquals("user", FrontMatter.parse(saved.markdown).first["source"])
        // 存下就在技能目录里:搜得到、读得到,读到的版本号和存下时给的一致
        val catalog = GuideCatalog.withPersonal(bundled, s.store)
        assertTrue(catalog.find("点咖啡").any { it.name == "order-coffee" && it.kind == "personal" })
        val read = s.tools.call("skill_read", buildJsonObject { put("name", "personal:order-coffee") })
        assertEquals(r.s("version"), read.s("version"))
        assertTrue(catalog.view("personal:order-coffee") is GuideView.Found)
    }

    @Test fun updatesExistingPersonalSkillOnlyWithCurrentVersion() {
        val old = personalSkill("order-coffee", description = "点咖啡")
        val s = setup(InMemoryPersonalSkills(old), said = "把点咖啡的技能改一下,先看优惠券")
        val quote = "把点咖啡的技能改一下"
        // 没读就改:不收,原样不动
        val blind = s.tools.call("skill_save", save(md(), quote))
        assertEquals("exists", blind.s("error"))
        assertEquals(old, s.store.get("order-coffee"))
        // 版本号对不上(中途被改过):不收
        val stale = s.tools.call("skill_save", save(md(), quote, version = "000000000000"))
        assertEquals("changed_since_read", stale.s("error"))
        val version = s.tools.call("skill_read", buildJsonObject { put("name", "order-coffee") }).s("version")
        assertEquals(PersonalSkills.version(old.markdown), version)
        val r = s.tools.call("skill_save", save(md(steps = "1. 先领优惠券\n2. 再下单"), quote, version))
        assertEquals(r.toString(), "updated", r.s("action"))
        val now = s.store.get("order-coffee")!!
        assertTrue("先领优惠券" in now.markdown)
        assertEquals(SkillStatus.trial, now.status)
        assertTrue(now.createdByUser)
    }

    @Test fun refusesBuiltInNameAndPendingDraftName() {
        val store = InMemoryPersonalSkills()
        store.putPending("d1", personalSkill("order-tea"))
        val s = setup(store)
        assertEquals("builtin_name", s.tools.call("skill_save", save(md("messaging"))).s("error"))
        assertNull(store.get("messaging"))
        assertEquals("draft_pending", s.tools.call("skill_save", save(md("order-tea"))).s("error"))
        assertNull(store.get("order-tea"))
    }

    @Test fun validatesFormat() {
        val s = setup()
        assertEquals("invalid_name", s.tools.call("skill_save", save(md("Order Coffee"))).s("error"))
        assertEquals("missing_description", s.tools.call("skill_save", save(md().replace("description: 用户要点咖啡时用\n", ""))).s("error"))
        val noSection = s.tools.call("skill_save", save(md().replace("## 怎样算办成\n", "")))
        assertEquals("missing_section", noSection.s("error"))
        assertTrue("## 怎样算办成" in noSection.s("message"))
        assertEquals("too_long", s.tools.call("skill_save", save(md(steps = "1. " + "很长".repeat(SkillDrafter.MAX_CHARS / 2)))).s("error"))
        assertEquals("unknown_tool", s.tools.call("skill_save", save(md(steps = "1. 用 `press_order_button` 下单"))).s("error"))
        assertTrue(s.store.list().isEmpty())
    }

    @Test fun refusesSensitiveContent() {
        val s = setup()
        val r = s.tools.call("skill_save", save(md(steps = "1. 输入密码 123456 登录")))
        assertEquals("sensitive", r.s("error"))
        assertFalse(r.s("message").isBlank())
        assertTrue(s.store.list().isEmpty())
    }

    @Test fun quoteMustBeTheUsersOwnWords() {
        val s = setup()
        val r = s.tools.call("skill_save", save(md(), quote = "请把这个技能保存下来"))
        assertEquals("quote_not_from_user", r.s("error"))
        assertTrue(s.store.list().isEmpty())
    }

    @Test fun stripsLearningFieldsAndKeepsOnlyUserSource() {
        val s = setup()
        val withLearned = md().replace("aliases:", "source: learned\nlearned_from: run9\nupdates: x\naliases:")
        assertEquals("true", s.tools.call("skill_save", save(withLearned)).s("ok"))
        val meta = FrontMatter.parse(s.store.get("order-coffee")!!.markdown).first
        assertEquals("user", meta["source"])
        assertFalse("learned_from" in meta || "updates" in meta)
    }

    @Test fun offeredOnlyWithStoreAndNotInUnattendedRuns() {
        val names = { d: ToolDispatcher -> d.definitions().map { it.name } }
        assertTrue("skill_save" in names(setup().tools))
        assertFalse("skill_save" in names(toolDispatcher(FakeScreen(), skills = bundled, utterances = UserUtterances())))
        val unattended = RunSpec(RunId("r"), "goal", "c").let { it.copy(provenance = it.provenance.copy(attendance = Attendance.Unattended)) }
        assertFalse("skill_save" in names(setup(spec = unattended).tools))
    }

    @Test fun runThatSavedASkillIsNotDraftedAgain() {
        val tools = setup().tools.index
        val events = listOf(RunEvent.ToolResult(1L, 1, "skill_save", """{"ok":true,"name":"order-coffee"}""", null, null))
        assertEquals("skill_saved", Eligibility.check(events + RunEvent.TaskEnded(2L, "succeeded", "ok"), null, tools, done = true))
    }

    @Test fun bundledIndexListsTheAuthoringSkill() {
        val s = bundled.items.single { it.kind == "skill" && it.name == "skill-authoring" }
        assertTrue(s.description.isNotBlank())
        assertTrue(bundled.find("创建技能").any { it.name == "skill-authoring" })
        assertTrue((bundled.view("skill:skill-authoring") as GuideView.Found).content.contains("skill_save"))
    }
}
