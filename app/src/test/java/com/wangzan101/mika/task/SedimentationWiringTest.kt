package com.wangzan101.mika.task

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.skills.SkillDrafter
import com.wangzan101.mika.core.skills.SkillStatus
import com.wangzan101.mika.settings.MemorySwitches
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.knownTools

@OptIn(ExperimentalCoroutinesApi::class)
class SedimentationWiringTest {
    @get:Rule val texts = ChineseTexts()

    private val catalog = GuideCatalog.loadBundled()
    private val tools = setOf("phone_launch_app", "phone_observe", "phone_tap", "phone_type_text", "hand_to_user", "finish")
    private val draftMd = "---\nname: send-wechat-message\ndisplay: 发微信消息\ndescription: 给指定联系人发一条微信消息\napps: [com.tencent.mm]\nsource: learned\n---\n" +
        "## 入口\n用 `phone_launch_app` 打开。\n## 步骤\n1. 点搜索\n## 容易出错的地方\n同名群聊。\n## 怎样算办成(核实证据)\n消息出现在聊天里。\n## 什么时候停下交给用户\n要登录时。"

    /**
     * 一次发微信的运行:在微信([app])里做 6 个动作。动作从时刻 100 起,[extra] 里的事件按自己写的时刻算先后
     * (技能读在 41 这样的时刻,就是先读了技能再动手)。
     */
    private fun events(verdict: String = "pass", extra: List<RunEvent> = emptyList(), app: String = "com.tencent.mm"): List<RunEvent> {
        var t = 99L
        val ev = mutableListOf<RunEvent>(RunEvent.TaskStarted(1, "发微信", "m"))
        listOf("phone_launch_app", "phone_tap", "phone_tap", "phone_type_text", "phone_tap", "phone_tap").forEachIndexed { i, tool ->
            ev += RunEvent.ModelReply(++t, i + 1, "", tool, "{}", null, null, null)
            val result = if (tool == "phone_launch_app") """{"ok":true,"opened":{"package":"$app"}}"""
                else """{"ok":true,"screen":{"currentApp":"$app"}}"""
            ev += RunEvent.ToolResult(++t, i + 1, tool, result, true, null)
            ev += RunEvent.Observed(++t, i + 1, "界面", place = Place.app(app))
        }
        ev += extra
        ev += RunEvent.Verifier(++t, verdict, """{"reason":"","checks":[]}""")
        ev += RunEvent.TaskEnded(++t, "succeeded", "ok")
        return ev
    }

    private fun learning(skills: MapSkills, learn: Boolean, autoApprove: Boolean = false, model: TextModel = TextModel(draftMd)) =
        RunLearning(MemorySwitches(learnSkills = learn), null, skills, GuideCatalog.withPersonal(catalog, skills), null, SkillDrafter(model), { "draft-1" }, autoApprove)

    private fun after(ev: List<RunEvent>, end: TaskState = TaskState.Succeeded("ok"), outcome: RunOutcome = RunOutcome.Done) = RunAftermath(
        "c1", "run-1", "给老妈发微信", end, emptyList(), emptyList(), emptyList(), tools, knownTools(), events = { ev }, outcome = outcome,
    )

    @Test fun draftOnlyWhenEnabledAndEligible() {
        val off = MapSkills()
        val offModel = TextModel(draftMd)
        runBlocking { learning(off, learn = false, model = offModel).learn(after(events())) }
        assertTrue(off.drafts.isEmpty() && offModel.requests.isEmpty())

        // 学习看结局不看核查:没跑核查、判下来办成的照样起草;核查跑过却没通过的不起草
        val unreviewed = MapSkills()
        runBlocking { learning(unreviewed, learn = true).learn(after(events(), end = TaskState.Succeeded("ok", verified = false, reviewed = false))) }
        assertEquals(listOf("draft-1"), unreviewed.drafts.keys.toList())
        val doubtful = MapSkills()
        runBlocking { learning(doubtful, learn = true).learn(after(events(), end = TaskState.Succeeded("ok", verified = false, note = "x"))) }
        assertTrue(doubtful.drafts.isEmpty())

        // 回答核实过,但判下来只办了一部分、没办成或没判成:不起草
        for (o in listOf(RunOutcome.Partial, RunOutcome.NotPossible, RunOutcome.Unknown)) {
            val notDone = MapSkills()
            val model = TextModel(draftMd)
            runBlocking { learning(notDone, learn = true, model = model).learn(after(events(), outcome = o)) }
            assertTrue(o.wire, notDone.drafts.isEmpty() && model.requests.isEmpty())
        }

        // 走的是回放路线:不再起草
        val replayed = MapSkills()
        runBlocking { learning(replayed, learn = true).learn(after(events(extra = listOf(RunEvent.Replay(50, "r", 1, 3, true, ""))))) }
        assertTrue(replayed.drafts.isEmpty())

        val on = MapSkills()
        val notes = runBlocking { learning(on, learn = true).learn(after(events())) }
        assertEquals(listOf("draft-1"), on.drafts.keys.toList())
        assertEquals("Mika 学会了一项技能:给指定联系人发一条微信消息", notes.skillNotes.single().text)
        assertEquals("draft-1", notes.skillNotes.single().draftId)
    }

    // 模型说这次是对一份待确认草稿的更新:写回那份草稿的编号,不另起一份
    @Test fun draftUpdatingPendingDraftReplacesIt() {
        val skills = MapSkills()
        skills.putPending("draft-0", PersonalSkill("send-wechat-message", draftMd, SkillStatus.pending, "r0", null, 0, emptyList()))
        val updated = draftMd.replace("source: learned", "updates: send-wechat-message\nsource: learned")
        val notes = runBlocking { learning(skills, learn = true, model = TextModel(updated)).learn(after(events())) }
        assertEquals(listOf("draft-0"), skills.drafts.keys.toList())
        assertEquals("run-1", skills.drafts.getValue("draft-0").learnedFrom)
        assertEquals("draft-0", notes.skillNotes.single().draftId)
    }

    @Test fun evalAutoApproveHook() {
        val skills = MapSkills()
        val notes = runBlocking { learning(skills, learn = true, autoApprove = true).learn(after(events())) }
        assertTrue(skills.drafts.isEmpty())
        assertEquals(SkillStatus.active, skills.get("send-wechat-message")!!.status)
        assertTrue(notes.skillNotes.isEmpty())
    }

    @Test fun draftingRunsAfterResultNotification() = runTest {
        val order = mutableListOf<String>()
        RunLearning.launchAfter(this, notify = { order += "notify" }) { order += "learn" }
        assertEquals(listOf("notify"), order)
        runCurrent()
        assertEquals(listOf("notify", "learn"), order)
    }

    @Test fun usedSkillOutcomeRecordedAndSuspensionNoted() {
        val skill = PersonalSkill("wechat-msg", draftMd.replace("send-wechat-message", "wechat-msg"), SkillStatus.active, "r0", null, 0, emptyList())
        val skills = MapSkills(skill)
        val used = listOf(RunEvent.SkillUsed(41, "wechat-msg", "viewed"))
        val notes = runBlocking {
            learning(skills, learn = true).learn(after(events(verdict = "fail", extra = used), TaskState.Failed("核查未通过:消息没发出去", FailKind.GaveUp)))
        }
        assertEquals(SkillStatus.suspended, skills.get("wechat-msg")!!.status)
        assertEquals(listOf("verifier_rejected"), skills.get("wechat-msg")!!.outcomes)
        assertEquals("wechat-msg", notes.skillNotes.single().suspended)
        // 照着个人技能做成的:只记结果,不起草
        val ok = MapSkills(skill)
        runBlocking { learning(ok, learn = true).learn(after(events(extra = used))) }
        assertEquals(listOf("verified"), ok.get("wechat-msg")!!.outcomes)
        assertTrue(ok.drafts.isEmpty())
        // 没跑核查、判下来办成:也算照着做成了
        val unreviewed = MapSkills(skill)
        runBlocking { learning(unreviewed, learn = true).learn(after(events(extra = used), TaskState.Succeeded("ok", verified = false, reviewed = false))) }
        assertEquals(listOf("verified"), unreviewed.get("wechat-msg")!!.outcomes)
        // 回答如实说只办了一部分:照着技能做没办成,记 failed
        val partial = MapSkills(skill)
        runBlocking { learning(partial, learn = true).learn(after(events(extra = used), outcome = RunOutcome.Partial)) }
        assertEquals(listOf("failed"), partial.get("wechat-msg")!!.outcomes)
        // 取消的不记
        val cancelled = MapSkills(skill)
        runBlocking { learning(cancelled, learn = false).learn(after(events(extra = used), TaskState.Cancelled)) }
        assertNull(cancelled.get("wechat-msg")!!.outcomes.firstOrNull())
    }

    // 一次读了两份个人技能、这次没办成:分不清照着哪份做的,两份都不记、都不停用(#569)
    @Test fun skillsOnlyBrowsedTogetherAreNotBlamed() {
        val md = draftMd.replace("send-wechat-message", "wechat-msg")
        val keepOn = PersonalSkill("keep-screen-on", md.replace("wechat-msg", "keep-screen-on"), SkillStatus.active, "r0", null, 0, emptyList())
        val drama = PersonalSkill("drama-coins", md.replace("wechat-msg", "drama-coins"), SkillStatus.active, "r0", null, 0, emptyList())
        val browsed = listOf(RunEvent.SkillUsed(41, "keep-screen-on", "viewed"), RunEvent.SkillUsed(42, "drama-coins", "viewed"), RunEvent.SkillUsed(43, "keep-screen-on", "viewed"))
        val rejected = TaskState.Failed("核查未通过:没办成", FailKind.GaveUp)
        // 连着两次也一样(以前两次没办成就会停用)
        val both = MapSkills(keepOn, drama)
        repeat(2) {
            val notes = runBlocking { learning(both, learn = false).learn(after(events(verdict = "fail", extra = browsed), rejected)) }
            assertTrue(notes.skillNotes.isEmpty())
        }
        assertEquals(emptyList<String>(), both.get("keep-screen-on")!!.outcomes)
        assertEquals(emptyList<String>(), both.get("drama-coins")!!.outcomes)
        assertEquals(SkillStatus.active, both.get("keep-screen-on")!!.status)
        assertEquals(SkillStatus.active, both.get("drama-coins")!!.status)
        // 只读了一份(看了两次也是同一份):照旧记到它头上,提示里写明是哪一份
        val skills = MapSkills(keepOn, drama)
        val once = listOf(RunEvent.SkillUsed(41, "keep-screen-on", "viewed"), RunEvent.SkillUsed(43, "keep-screen-on", "viewed"))
        val notes = runBlocking { learning(skills, learn = false).learn(after(events(verdict = "fail", extra = once), rejected)) }
        assertEquals(listOf("verifier_rejected"), skills.get("keep-screen-on")!!.outcomes)
        assertEquals(SkillStatus.suspended, skills.get("keep-screen-on")!!.status)
        assertEquals(emptyList<String>(), skills.get("drama-coins")!!.outcomes)
        assertTrue(notes.skillNotes.single().text.endsWith("keep-screen-on"))
    }

    // 读过、但没照着做:读完之后在别的 App 里动手,或者读完没再动手,结果都不记到它头上(#569)
    @Test fun skillReadButNotFollowedIsNotBlamed() {
        val skill = PersonalSkill("wechat-msg", draftMd.replace("send-wechat-message", "wechat-msg"), SkillStatus.active, "r0", null, 0, emptyList())
        val rejected = TaskState.Failed("核查未通过:没办成", FailKind.GaveUp)
        val elsewhere = MapSkills(skill)
        val browsedWechat = listOf(RunEvent.SkillUsed(41, "wechat-msg", "viewed"),
            RunEvent.Observed(50, 1, "只读到微信界面", place = Place.app("com.tencent.mm")))
        runBlocking { learning(elsewhere, learn = false).learn(after(events(verdict = "fail", extra = browsedWechat, app = "com.ss.android.ugc.aweme"), rejected)) }
        assertEquals(emptyList<String>(), elsewhere.get("wechat-msg")!!.outcomes)
        // 动完手才去读,读完就收尾了
        val late = MapSkills(skill)
        runBlocking { learning(late, learn = false).learn(after(events(verdict = "fail", extra = listOf(RunEvent.SkillUsed(500, "wechat-msg", "viewed"))), rejected)) }
        assertEquals(emptyList<String>(), late.get("wechat-msg")!!.outcomes)
        assertEquals(SkillStatus.active, late.get("wechat-msg")!!.status)
    }

    // 读过一份没照着做、这次自己办成了:不记到它头上,也不挡起草
    @Test fun skillReadButNotFollowedStillDrafts() {
        val skill = PersonalSkill("wechat-msg", draftMd.replace("send-wechat-message", "wechat-msg"), SkillStatus.active, "r0", null, 0, emptyList())
        val skills = MapSkills(skill)
        runBlocking { learning(skills, learn = true).learn(after(events(extra = listOf(RunEvent.SkillUsed(41, "wechat-msg", "viewed")), app = "com.ss.android.ugc.aweme"))) }
        assertEquals(emptyList<String>(), skills.get("wechat-msg")!!.outcomes)
        assertEquals(listOf("draft-1"), skills.drafts.keys.toList())
    }

    // 读了两份,只进过其中一份写明的 App:照着做的就是那一份,结果记给它
    @Test fun outcomeGoesToTheSkillWhoseAppWasUsed() {
        val md = draftMd.replace("send-wechat-message", "wechat-msg")
        val wechat = PersonalSkill("wechat-msg", md, SkillStatus.active, "r0", null, 0, emptyList())
        val douyin = PersonalSkill("douyin-like", md.replace("wechat-msg", "douyin-like").replace("com.tencent.mm", "com.ss.android.ugc.aweme"), SkillStatus.active, "r0", null, 0, emptyList())
        val skills = MapSkills(wechat, douyin)
        val read = listOf(RunEvent.SkillUsed(41, "douyin-like", "viewed"), RunEvent.SkillUsed(42, "wechat-msg", "viewed"))
        runBlocking { learning(skills, learn = true).learn(after(events(extra = read))) }
        assertEquals(listOf("verified"), skills.get("wechat-msg")!!.outcomes)
        assertEquals(emptyList<String>(), skills.get("douyin-like")!!.outcomes)
    }

    // 试用中的个人技能:Agent 用 skill_read 读过、这次办成了,转为正式
    @Test fun viewedTrialSkillPromotesOnDoneRun() {
        val skill = PersonalSkill("wechat-msg", draftMd.replace("send-wechat-message", "wechat-msg"), SkillStatus.trial, "r0", null, 0, emptyList())
        val skills = MapSkills(skill)
        runBlocking { learning(skills, learn = true).learn(after(events(extra = listOf(RunEvent.SkillUsed(41, "wechat-msg", "viewed"))))) }
        assertEquals(SkillStatus.active, skills.get("wechat-msg")!!.status)
        assertEquals(listOf("verified"), skills.get("wechat-msg")!!.outcomes)
        assertTrue(skills.drafts.isEmpty())
    }

    // 旧运行记录里的 injected(以前开头塞进全文)照样读,和看过一样记结果
    @Test fun oldInjectedRecordCountsAsUse() {
        val skill = PersonalSkill("wechat-msg", draftMd.replace("send-wechat-message", "wechat-msg"), SkillStatus.active, "r0", null, 0, emptyList())
        val skills = MapSkills(skill)
        val rejected = TaskState.Failed("核查未通过:没发出去", FailKind.GaveUp)
        val notes = runBlocking { learning(skills, learn = false).learn(after(events(verdict = "fail", extra = listOf(RunEvent.SkillUsed(40, "wechat-msg", "injected"))), rejected)) }
        assertEquals(listOf("verifier_rejected"), skills.get("wechat-msg")!!.outcomes)
        assertEquals(SkillStatus.suspended, skills.get("wechat-msg")!!.status)
        assertEquals("wechat-msg", notes.skillNotes.single().suspended)
    }
}
