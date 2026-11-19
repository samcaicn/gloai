package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TaskStagesTest {
    private val coffee = "我今天约了朋友去天目里喝咖啡，帮我在高德搜索查看详细地址，打开大众点评，找附近 1 公里内的咖啡馆，找人气最高的那家记下店名，再去小红书总结前五条笔记，看看大家推荐这家店的哪款咖啡。"
    private val amap = "com.autonavi.minimap"
    private val dianping = "com.dianping.v1"
    private val xhs = "com.xingin.xhs"
    // Ordinary words in the coffee request can also be launcher app names.
    private val installed = listOf(amap to "高德地图", dianping to "大众点评", xhs to "小红书",
        "example.search" to "搜索", "example.notes" to "笔记")
    private fun stages(words: String) = TaskStages().apply { prepare(words, emptyList(), emptyList()) }

    @Test fun coffeeFollowsTaskToolsWithoutAnyProgressReports() = runTest {
        var pkg = "android"
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
                pkg = packageName
                return ActionResult.Ok()
            }
            override suspend fun snapshot() = ScreenSnapshot("page", pkg, emptyList(), 1080, 2400)
            override suspend fun peek() = snapshot()
        }
        fun launch(p: String) = ModelReply(listOf(Part.ToolCall(p, "phone_launch_app", """{"package":"$p"}""")), StopReason.ToolUse, null)
        val model = FakeModel(launch(amap), launch(dianping), launch(xhs), answerReply("查询结束"))
        val seen = mutableListOf<String>()
        lateinit var tracker: TaskStages
        tracker = TaskStages { seen += tracker.current!!.compact }
        val end = AgentLoop(model, toolDispatcher(screen), "base", stages = tracker, reviewFinalAnswer = false,
            installedApps = { installed }, knowledge = KnowledgeStore(KnowledgeStore.loadBundled())).run(coffee)
        assertTrue(end.toString(), end is TaskState.Succeeded)
        assertEquals(listOf("1/3 高德", "2/3 点评", "3/3 小红书"), seen)
        assertEquals(4, model.requests.size)
    }

    @Test fun incidentalAppNamesAreNotNavigationButGenuinelyNamedNotesStillCount() {
        fun first(words: String, apps: List<Pair<String, String>> = installed): String? =
            TaskStages().apply { prepare(words, apps, emptyList()) }.current?.compact
        assertEquals("1/3 高德", first(coffee))
        assertEquals("1/3 高德", first(coffee, installed.filter { it.second != "笔记" }))
        assertEquals("1/3 高德", first(coffee, installed.filter { it.second != "搜索" }))
        assertEquals("1/2 笔记", first("打开笔记写提纲，再去小红书查资料"))
        assertEquals("1/3 高德", first("高德 点评 小红书"))
        assertNull(first("在高德或大众点评找店，再去小红书查资料"))
        assertNull(first("看看高德、大众点评和小红书有什么区别"))
        assertNull(first("比较一下高德、大众点评、小红书的区别"))
    }

    @Test fun fallbackCanAdvanceAcrossKnownAppsEvenWhenTheModelNeverReportsAStage() = runTest {
        val tracker = stages("帮我查个适合见面的咖啡馆")
        val model = FakeModel(answerReply("""[
            {"goal":"确认地址","label":"查址","app":"高德地图"},
            {"goal":"选咖啡馆","label":"选店","app":"大众点评"},
            {"goal":"看笔记","label":"攻略","app":"小红书"},
            {"goal":"整理推荐","label":"汇总","app":"小红书"}]"""))
        tracker.planIfNeeded("帮我查个适合见面的咖啡馆", model)
        fun observe(pkg: String, ok: Boolean = true) = tracker.action(Verb.Observe, buildJsonObject {},
            ToolExecution(buildJsonObject { put("ok", ok) }, place = Place.app(pkg)))
        assertEquals("1/4 查址", tracker.current?.compact)
        observe(amap)
        observe(xhs)
        observe(dianping, ok = false)
        assertEquals("1/4 查址", tracker.current?.compact)
        observe(dianping)
        assertEquals("2/4 选店", tracker.current?.compact)
        observe(amap)
        observe(xhs)
        repeat(3) { observe(xhs) }
        assertEquals("3/4 攻略", tracker.current?.compact)
        tracker.reported(4)
        assertEquals("4/4 汇总", tracker.current?.compact)
    }

    @Test fun onlyTaskOwnedEvidenceOfNextAppCanAdvanceAnExplicitPlan() {
        val tracker = stages(coffee)
        fun launch(p: String, failed: Boolean = false) = tracker.action(Verb.LaunchApp,
            buildJsonObject { put("package", p) }, ToolExecution(buildJsonObject { put("ok", !failed) }))
        fun observe(place: Place) = tracker.action(Verb.Observe, buildJsonObject {},
            ToolExecution(buildJsonObject { put("ok", true) }, place = place))
        launch(xhs)
        launch(dianping, failed = true)
        launch("android")
        tracker.reported(2)
        observe(Place.site(dianping))
        assertEquals(1, tracker.current!!.number)
        observe(Place.app(dianping))
        launch(amap)
        assertEquals("2/3 点评", tracker.current!!.compact)
        launch(xhs)
        assertEquals("3/3 小红书", tracker.current!!.compact)
        assertEquals("1/3 高德", stages(coffee).current!!.compact)
    }

    @Test fun usesNamedOrderAliasesAndTheLocalRailwayAction() {
        assertEquals("1/3 查票", stages("先在铁路12306查余票，再去携程看酒店，最后去小红书找攻略").current?.compact)
        assertEquals("1/2 退票", stages("用12306退票，再去携程订酒店").current?.compact)
        assertEquals("1/2", stages("12306 → 携程").current?.compact)
        assertEquals("铁路12306", stages("12306 → 携程").current?.detail)
        assertEquals("1/2 携程", stages("携程 → 12306").current?.compact)
        assertEquals("1/3 高德", stages("按高德、大众点评、小红书的顺序查咖啡馆").current?.compact)
        assertEquals("1/3 查票", stages("12306 查票，携程看酒店，小红书找攻略").current?.compact)
        assertEquals("1/2 查询", stages("12306 看退票规则，携程看酒店").current?.compact)
        assertNull(stages("高德、大众点评、小红书哪个更好用？").current)
        assertNull(stages("可以用携程或者小红书查攻略").current)
        assertNull(stages("打开携程之前先去小红书找攻略").current)
        assertNull(stages("看看携程和小红书的区别").current)
        val tracker = TaskStages()
        tracker.prepare("用轻笔记写提纲，再打开月历选日期", listOf("example.notes" to "轻笔记", "example.cal" to "月历"), emptyList())
        assertEquals("1/2 轻笔记", tracker.current?.compact)
    }

    @Test fun ambiguousTaskUsesOneToolFreePlanAndCanChangeOutcomeWithinOneApp() = runTest {
        fun wait(step: Int, say: String) = ModelReply(listOf(Part.Text(say), Part.ToolCall("c$step", "wait", """{"seconds":0.1}""")), StopReason.ToolUse, null)
        val model = FakeModel(wait(1, "开始"), answerReply("""[{"label":"查票","goal":"查列车余票"},{"label":"比价","goal":"比较票价"}]"""),
            wait(2, """<task_progress>{"stage":2}</task_progress>开始比价"""), answerReply("已整理"))
        val seen = mutableListOf<String>()
        lateinit var tracker: TaskStages
        tracker = TaskStages { seen += tracker.current!!.compact }
        val end = AgentLoop(model, toolDispatcher(FakeScreen()), "base", stages = tracker, reviewFinalAnswer = false).run("帮我安排周末的出行")
        assertTrue(end.toString(), end is TaskState.Succeeded)
        assertEquals(listOf("1/2 查票", "2/2 比价"), seen)
        assertEquals(emptyList<String>(), model.offeredTools[1])
        assertTrue(model.requests[2].last().parts.filterIsInstance<Part.Text>().any { "比较票价" in it.text })
        assertTrue(CompletionPlanBlock.extract("""<task_progress>{"stage":2}</task_progress>""").updates.isEmpty())
    }

    @Test fun missingOrMalformedPlansNeverInventTotalsAndUnknownLabelsStayOffChip() = runTest {
        for (raw in listOf("bad", "[]", """[{"label":"查票"}]""")) {
            val tracker = stages("查一查")
            val model = FakeModel(answerReply(raw))
            repeat(2) { tracker.planIfNeeded("查一查", model) }
            assertNull(tracker.current)
            assertEquals(1, model.requests.size)
        }
        val tracker = stages("查一查")
        tracker.planIfNeeded("查一查", FakeModel(answerReply("""[{"goal":"第一段","label":"张先生"},{"goal":"第二段","label":"核对"}]""")))
        assertEquals("1/2", tracker.current!!.compact)
        for (n in listOf(0, 3, 1)) tracker.reported(n)
        assertEquals(1, tracker.current!!.number)
        tracker.reported(2)
        tracker.reported(1)
        assertEquals(2, tracker.current!!.number)
    }
}
