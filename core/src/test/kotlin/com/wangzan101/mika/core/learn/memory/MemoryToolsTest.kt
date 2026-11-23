package com.wangzan101.mika.core.learn.memory

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.core.tool.HandBackOutcome
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

private class MemPersistence : MemoryPersistence {
    override fun load() = emptyList<MemoryItem>()
    override fun save(items: List<MemoryItem>) {}
}

internal fun memStore(inferenceAllowed: () -> Boolean = { false }): MemoryStore {
    var seq = 0
    return MemoryStore(MemPersistence(), clock = { 1L }, ids = { "id${++seq}" }, inferenceAllowed = inferenceAllowed)
}

private var callSeq = 0
internal fun memCall(name: String, args: JsonObject = JsonObject(emptyMap())) = Part.ToolCall("mc${++callSeq}", name, args.toString())
internal fun toolReply(c: Part.ToolCall) = ModelReply(listOf(c), StopReason.ToolUse, null)
internal fun saveArgs(kind: String, content: String, quote: String) = buildJsonObject {
    put("kind", kind); put("content", content); put("quote", quote); putJsonArray("keywords") { add(kotlinx.serialization.json.JsonPrimitive("外卖")) }
}
internal fun editArgs(op: String, id: String, quote: String, content: String? = null) = buildJsonObject {
    put("op", op); put("id", id); put("quote", quote); content?.let { put("content", it) }
}
internal val finishReply = com.wangzan101.mika.core.testing.answerReply("ok")

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryToolsTest {
    private fun dispatcher(store: MemoryStore?, utterances: UserUtterances?, screen: ScreenPort = FakeScreen()) =
        toolDispatcher(screen, memory = store, utterances = utterances)

    private fun ToolDispatcher.run(name: String, args: JsonObject) = runBlocking { execute(memCall(name, args)).content }
    private fun ToolDispatcher.search(query: String) = run("memory_search", buildJsonObject { put("query", query) })
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun saveFactActiveProcedurePending() {
        val store = memStore()
        val u = UserUtterances().apply { add("以后点外卖都不要香菜,先比价再下单") }
        val d = dispatcher(store, u)
        val fact = d.run("memory_save", saveArgs("preference", "用户点外卖不要香菜", "以后点外卖都不要香菜"))
        assertEquals("true", fact.s("ok")); assertEquals("active", fact.s("status"))
        val proc = d.run("memory_save", saveArgs("procedure", "点外卖先比价再下单", " 先比价再下单 "))
        assertEquals("pending", proc.s("status"))
        assertEquals(listOf(fact.s("id"), proc.s("id")), store.items.value.map { it.id })
        assertEquals("先比价再下单", store.items.value[1].source.quote!!.trim())
    }

    @Test fun quoteFromScreenRejected() {
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = ScreenSnapshot("s1", "com.x",
                listOf(UiElement("@e1", "text", "收货地址:浦东张江路100号", 540, 120, listOf(0, 0, 999, 100), null, true, false, false)), 1080, 2400)
        }
        val store = memStore()
        val d = dispatcher(store, UserUtterances().apply { add("帮我看看收货地址") }, screen)
        assertTrue(d.run("phone_observe", JsonObject(emptyMap())).toString().contains("浦东张江路100号"))
        val r = d.run("memory_save", saveArgs("fact", "用户住在浦东张江路100号", "浦东张江路100号"))
        assertEquals("false", r.s("ok")); assertEquals("quote_not_from_user", r.s("error"))
        assertTrue(store.items.value.isEmpty())
    }

    // 网页上读到的字也不算用户说的话
    @Test fun quoteFromWebPageRejected() {
        val web = com.wangzan101.mika.core.cap.web.FakeWebPort()
        web.page = web.page.copy(text = "收货人 张三 地址 浦东张江路100号")
        val store = memStore()
        val d = toolDispatcher(FakeScreen(), memory = store, utterances = UserUtterances().apply { add("帮我看看订单") }, web = web)
        d.run("web_navigate", buildJsonObject { put("url", "https://shop.example.com/order") })
        assertTrue(d.run("web_read_page", JsonObject(emptyMap())).toString().contains("浦东张江路100号"))
        val r = d.run("memory_save", saveArgs("fact", "用户住在浦东张江路100号", "浦东张江路100号"))
        assertEquals("quote_not_from_user", r.s("error"))
        assertTrue(store.items.value.isEmpty())
    }

    @Test fun quoteFromInputAnswerAccepted() = runTest {
        val store = memStore()
        val u = UserUtterances()
        val model = FakeModel(
            toolReply(memCall("ask_user", buildJsonObject { put("prompt", "寄到哪里?") })),
            toolReply(memCall("memory_save", saveArgs("fact", "用户家在张江路100号", "寄到我家张江路100号"))),
            finishReply,
        )
        val loop = AgentLoop(model, toolDispatcher(FakeScreen(), memory = store, utterances = u), "B", memory = store)
        val run = async { loop.run("帮我寄个快递") }
        runCurrent()
        assertTrue(loop.answerInput("寄到我家张江路100号,以后都寄这里"))
        run.await()
        assertEquals(listOf("用户家在张江路100号"), store.items.value.map { it.content })
        assertTrue("寄到我家张江路100号,以后都寄这里" in u.all)
    }

    @Test fun takeoverNoteAccepted() = runTest {
        val store = memStore()
        val model = FakeModel(
            toolReply(memCall("hand_to_user", buildJsonObject { put("reason", "login"); put("message", "请登录") })),
            toolReply(memCall("memory_save", saveArgs("preference", "用户的外卖账号用手机号登录", "外卖账号用手机号登录"))),
            finishReply,
        )
        val loop = AgentLoop(model, toolDispatcher(FakeScreen(), memory = store, utterances = UserUtterances()), "B", memory = store)
        val run = async { loop.run("登录一下") }
        runCurrent()
        loop.resumeAfterTakeover(HandBack(HandBackOutcome.complete, note = "外卖账号用手机号登录就行"))
        run.await()
        assertEquals(1, store.items.value.size)
    }

    @Test fun sensitiveRejected() {
        val store = memStore()
        val d = dispatcher(store, UserUtterances().apply { add("记住我的支付密码是123456") })
        val r = d.run("memory_save", saveArgs("fact", "用户的支付密码是123456", "支付密码是123456"))
        assertEquals("sensitive", r.s("error"))
        assertTrue(store.items.value.isEmpty())
    }

    @Test fun memoryFullReported() {
        val store = MemoryStore(MemPersistence(), { 1L }, { "x" }, limit = 0)
        val d = dispatcher(store, UserUtterances().apply { add("以后外卖都不要香菜") })
        assertEquals("memory_full", d.run("memory_save", saveArgs("fact", "外卖不要香菜", "外卖都不要香菜")).s("error"))
    }

    @Test fun searchExcludesPending() {
        val store = memStore()
        store.add(MemoryKind.preference, "点外卖不要香菜", listOf("外卖"), MemorySource("manual", atMs = 1)).getOrThrow()
        store.add(MemoryKind.procedure, "点外卖先比价", listOf("外卖"), MemorySource("manual", atMs = 1)).getOrThrow()
        val d = dispatcher(store, UserUtterances())
        val r = d.run("memory_search", buildJsonObject { put("query", "外卖") })
        val found = r["memories"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("点外卖不要香菜"), found.map { it.s("content") })
        assertEquals(setOf("id", "kind", "content"), found.single().keys)
    }

    @Test fun noStoreNoTools() {
        val d = toolDispatcher(FakeScreen())
        assertFalse(d.definitions().any { it.name.startsWith("memory_") })
        assertEquals("unknown_tool", d.run("memory_save", saveArgs("fact", "x", "x")).s("error"))
        // 只给其中一样也不提供
        assertFalse(dispatcher(memStore(), null).definitions().any { it.name.startsWith("memory_") })
        assertTrue(dispatcher(memStore(), UserUtterances()).definitions().map { it.name }.containsAll(listOf("memory_search", "memory_save", "memory_edit")))
    }

    @Test fun utterancesNormalizeWidthAndShortQuotes() {
        val u = UserUtterances().apply { add("  我的ＱＱ号码以后都用这个  ") }
        assertTrue(u.contains("QQ号码"))
        assertTrue(u.contains(" ＱＱ号码 "))
        assertFalse(u.contains("我"))
        assertFalse(u.contains("别人的号码"))
    }

    @Test fun runRecordHasNoMemoryText() = runTest {
        val store = memStore()
        store.add(MemoryKind.preference, "用户点外卖不要香菜", listOf("外卖"), MemorySource("manual", atMs = 1)).getOrThrow()
        val events = mutableListOf<RunEvent>()
        val model = FakeModel(
            toolReply(memCall("memory_search", buildJsonObject { put("query", "外卖") })),
            toolReply(memCall("memory_save", saveArgs("fact", "用户的公司在漕河泾开发区", "公司在漕河泾开发区"))),
            toolReply(memCall("hand_to_user", buildJsonObject { put("reason", "login"); put("message", "请登录") })),
            finishReply,
        )
        val loop = AgentLoop(model, toolDispatcher(FakeScreen(), memory = store, utterances = UserUtterances()), "B",
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } }, memory = store)
        val run = async { loop.run("帮我订外卖送到公司,公司在漕河泾开发区") }
        runCurrent()
        loop.resumeAfterTakeover(HandBack(HandBackOutcome.complete, note = "账号是我老婆的手机号"))
        run.await()
        assertEquals(2, store.items.value.size)
        val tools = events.filter { it is RunEvent.ModelReply || it is RunEvent.ToolResult }.joinToString("\n") { RunEvent.encode(it) }
        for (secret in listOf("不要香菜", "漕河泾", "老婆的手机号")) assertFalse(secret, tools.contains(secret))
        assertTrue(tools.contains("preference")) // 编号、种类照记
    }

    @Test fun sourceCarriesConversationAndStoreClock() {
        val store = MemoryStore(MemPersistence(), clock = { 4242L }, ids = { "s1" })
        val d = toolDispatcher(FakeScreen(), memory = store, utterances = UserUtterances().apply { add("以后外卖都不要香菜") }, conversationId = "conv-9")
        d.run("memory_save", saveArgs("preference", "用户外卖不要香菜", "外卖都不要香菜"))
        val src = store.items.value.single().source
        assertEquals("conv-9", src.conversationId)
        assertEquals(4242L, src.atMs)
    }

    @Test fun contentDetailsFromScreenRejected() {
        val store = memStore()
        val d = dispatcher(store, UserUtterances().apply { add("帮我把这个地址记下来,以后都寄这里") })
        val r = d.run("memory_save", saveArgs("fact", "用户的收货地址是浦东张江路100号", "以后都寄这里"))
        assertEquals("content_not_from_user", r.s("error"))
        assertTrue(store.items.value.isEmpty())
        // 用户自己说出的具体值可以
        val ok = dispatcher(store, UserUtterances().apply { add("以后都寄到张江路100号") })
            .run("memory_save", saveArgs("fact", "用户的收货地址是浦东张江路100号", "以后都寄到张江路100号"))
        assertEquals("true", ok.s("ok"))
    }

    // 转述时换个说法,难免带出「知道」「公园」「味道」这类以「道、园、里、市…」结尾的普通词:它们不是地址,不能算成用户没说过的具体值
    @Test fun ordinaryWordsThatEndLikeAPlaceNameAreNotTakenForDetails() {
        val cases = listOf(
            "记住我周末喜欢逛公园" to "用户周末喜欢去公园散步",
            "以后回答简短点,背景我都知道" to "用户已经知道背景,希望回答简短",
            "记住我喜欢重口味" to "用户喜欢重口味,偏好味道浓的菜",
            "记住我上班的地方在城西" to "用户的公司在城市西边",
            "记住我平时在家办公" to "用户平时在家里办公,很少去办公室",
            "记住我不爱逛街" to "用户不喜欢上街购物,也不常去超市",
            "记住我换了新号码" to "用户最近更换了手机号",
            "记住我想多了解这个频道" to "用户想了解这个频道的内容,问过哪里可以看",
        )
        for ((said, content) in cases) {
            val u = UserUtterances().apply { add(said) }
            assertEquals("$said -> $content", emptyList<String>(), u.unsupportedMemorySpecifics(content, said))
        }
        val store = memStore()
        val ok = dispatcher(store, UserUtterances().apply { add("记住我周末喜欢逛公园") })
            .run("memory_save", saveArgs("preference", "用户周末喜欢去公园散步", "我周末喜欢逛公园"))
        assertEquals("true", ok.s("ok"))
    }

    // 真的地址片段照旧要用户说过才行:路名、小区名、行政区、带号的楼
    @Test fun realAddressFragmentsStillNeedTheUsersWords() {
        val said = "帮我把这个地址记下来,以后都寄这里"
        fun bad(content: String) = UserUtterances().apply { add(said) }.unsupportedMemorySpecifics(content, said)
        assertEquals(listOf("张江路"), bad("用户的收货地址在张江路"))
        assertEquals(listOf("一品苑"), bad("用户住在一品苑"))
        assertEquals(listOf("东新区"), bad("用户住在浦东新区"))
        assertEquals(listOf("幸福村"), bad("用户老家在幸福村"))
        assertEquals(listOf("万科小区"), bad("用户住在万科小区"))
        assertTrue(bad("用户住在人民路附近,常去公园").contains("人民路"))
        // 同时也是普通词的地名后缀、跟在真地名后面的方位说法:照样算
        assertEquals(listOf("安大街"), bad("用户住在长安大街"))
        assertEquals(listOf("福家园"), bad("用户住在幸福家园"))
        assertEquals(listOf("贸大楼"), bad("用户在国贸大楼上班"))
        assertEquals(listOf("福社区"), bad("用户住在幸福社区"))
        assertTrue(bad("用户常去人民路上的店").contains("人民路"))
        // 行政区划后面直接跟着机构名:拆开看像「市民」「区分」「省事」「村民」,地名照样算
        assertEquals(listOf("南京市"), bad("用户在南京市民政局工作"))
        assertEquals(listOf("朝阳区"), bad("用户在朝阳区分局上班"))
        assertEquals(listOf("广东省"), bad("用户在广东省事业单位工作"))
        assertEquals(listOf("李家村"), bad("用户在李家村民委员会办过事"))
        // 以「一路」「马路」「山区」结尾的真地名
        assertEquals(listOf("技一路"), bad("用户的公司在科技一路"))
        assertEquals(listOf("宝山区"), bad("用户住在宝山区"))
        assertEquals(listOf("大马路"), bad("用户住在大马路"))
        assertTrue(bad("用户家在张江路口").contains("张江路"))
        assertTrue(bad("用户住在李家村里").contains("李家村"))
        assertTrue("3" in bad("用户住在3号楼"))
        // 用户自己说过的照收
        val own = "记住我住在张江路的一品苑"
        assertEquals(emptyList<String>(), UserUtterances().apply { add(own) }.unsupportedMemorySpecifics("用户住在张江路一品苑", own))
    }

    // 按规矩不收的是对这一条的答复,不是操作故障:结果里标明不计入连续失败,仍是不可恢复(同一条别再试)
    @Test fun rulesBasedRefusalsDoNotCountAsOperationFailures() {
        val store = memStore()
        val d = dispatcher(store, UserUtterances().apply { add("帮我把这个地址记下来,以后都寄这里") })
        val notSaid = d.run("memory_save", saveArgs("fact", "用户的收货地址是浦东张江路100号", "以后都寄这里"))
        val badQuote = d.run("memory_save", saveArgs("fact", "用户喜欢喝茶", "屏幕上写着喜欢喝茶"))
        for (r in listOf(notSaid, badQuote)) {
            assertEquals("false", r.s("ok"))
            assertEquals("false", r.s("counts_as_failure"))
            assertEquals("false", r.s("recoverable"))
        }
        assertEquals("content_not_from_user", notSaid.s("error"))
        assertEquals("quote_not_from_user", badQuote.s("error"))
        assertTrue(store.items.value.isEmpty())
    }

    // 同一条不收的原样再来:第一次是答复,第二次起照常算失败(模型没听「别再试」)
    @Test fun repeatingARefusedSaveCountsAsAFailure() {
        val d = dispatcher(memStore(), UserUtterances().apply { add("帮我把这个地址记下来,以后都寄这里") })
        val args = saveArgs("fact", "用户的收货地址是浦东张江路100号", "以后都寄这里")
        assertEquals("false", d.run("memory_save", args).s("counts_as_failure"))
        val again = d.run("memory_save", args)
        assertEquals("content_not_from_user", again.s("error"))
        assertFalse(again.containsKey("counts_as_failure"))
        // 换一条内容,又是头一回
        assertEquals("false", d.run("memory_save", saveArgs("fact", "用户的收货地址是浦东张江路200号", "以后都寄这里")).s("counts_as_failure"))
    }

    // 要改、要删的各种平常说法都认(「改得简洁一点」「精简一下」「合并」「去掉」);说了不要的、只是问问的、没提要改的不认
    @Test fun everydayWaysOfAskingToEditAreRecognised() {
        fun attempt(said: String, op: String): JsonObject {
            val store = memStore()
            val item = store.add(MemoryKind.preference, "用户点外卖不要香菜", listOf("外卖"), MemorySource("manual", atMs = 1)).getOrThrow().item
            val d = dispatcher(store, UserUtterances().apply { add(said) })
            d.search("外卖")
            return d.run("memory_edit", editArgs(op, item.id, said, if (op == "update") "用户点外卖不要香菜" else null))
        }
        for (ask in listOf(
            "把外卖那条记忆的改的简洁一点", "把外卖那条改得短一些", "外卖那条改一下", "外卖那条精简一下", "把外卖这几条合并成一条",
            "整理一下外卖那条记忆", "把外卖那条换成新的说法", "把外卖那条修改一下", "Please shorten the takeaway memory", "Rewrite that memory",
            // 「能不能改」「要不要改」是在请它改,不是不让改
            "外卖那条能不能改得简洁一点", "外卖那条可不可以改短",
        )) assertEquals(ask, "true", attempt(ask, "update").s("ok"))
        for (ask in listOf("把外卖那条删了", "外卖那条去掉", "把外卖那条清掉", "外卖那条不用留了,移除吧", "Erase the takeaway memory", "能不能删掉外卖那条")) {
            assertEquals(ask, "true", attempt(ask, "delete").s("ok"))
        }
        for (no in listOf("外卖那条先别改", "不要改外卖那条", "外卖那条改天再说", "外卖那条记得对吗", "Don't change the takeaway memory",
            "如果我说把外卖那条改得简洁一点,你会怎么改?")) {
            assertEquals(no, "edit_not_requested", attempt(no, "update").s("error"))
        }
        for (no in listOf("不要删外卖那条", "外卖那条别去掉", "如果我说把外卖那条记忆删掉,你会怎样处理?先解释,不要执行。",
            "If I asked you to delete that memory, what would you do?")) {
            assertEquals(no, "edit_not_requested", attempt(no, "delete").s("error"))
        }
        // 条件句本身是要记的内容、没有在问「你会怎么做」:照改
        assertEquals("true", attempt("把外卖那条改成:如果我说老样子,就是不要香菜", "update").s("ok"))
    }

    // 「把那条改得简洁一点」:条目里本来就有的地址、数字照样带着,不算新补的;另外补进来的还是不收
    @Test fun rewordingAnEntryMayKeepTheDetailsItAlreadyHas() {
        val store = memStore()
        val item = store.add(MemoryKind.fact, "用户常在张江路100号附近点外卖,一周 3 次", listOf("外卖", "Meituan"), MemorySource("manual", atMs = 1)).getOrThrow().item
        val d = dispatcher(store, UserUtterances().apply { add("把外卖那条改的简洁一点") })
        d.search("外卖")
        val shorter = d.run("memory_edit", editArgs("update", item.id, "把外卖那条改的简洁一点", "用户常点张江路100号的外卖,每周 3 次"))
        assertEquals("true", shorter.s("ok"))
        assertEquals("用户常点张江路100号的外卖,每周 3 次", store.items.value.single().content)
        // 原来没有、用户这一轮也没说的:不收,条目不动
        val added = d.run("memory_edit", editArgs("update", item.id, "把外卖那条改的简洁一点", "用户常点张江路100号和世纪大道200号的外卖"))
        assertEquals("content_not_from_user", added.s("error"))
        assertEquals("用户常点张江路100号的外卖,每周 3 次", store.items.value.single().content)
        // 关键词里原来就有的也算
        val args = buildJsonObject {
            put("op", "update"); put("id", item.id); put("quote", "把外卖那条改的简洁一点"); put("content", "用户常点张江路100号的外卖")
            putJsonArray("keywords") { add(kotlinx.serialization.json.JsonPrimitive("Meituan")) }
        }
        assertEquals("true", d.run("memory_edit", args).s("ok"))
    }

    // Mika 自己归纳的那条改写之后还是它归纳的;用户这一轮把内容亲口说了的,才改记成用户说的
    @Test fun rewordingMikasOwnConclusionKeepsItMarkedAsSuch() {
        val store = memStore(inferenceAllowed = { true })
        val item = store.add(MemoryKind.fact, "用户常在张江路100号附近点外卖", listOf("外卖"),
            MemorySource(MemoryContext.INFERRED, "你自己总结几条写进记忆", atMs = 1)).getOrThrow().item
        val d = dispatcher(store, UserUtterances().apply { add("把外卖那条改的简洁一点"); add("把外卖那条改成我常点世纪大道200号的外卖") })
        d.search("外卖")
        assertEquals("true", d.run("memory_edit", editArgs("update", item.id, "把外卖那条改的简洁一点", "用户常点张江路100号的外卖")).s("ok"))
        assertEquals(MemoryContext.INFERRED, store.items.value.single().source.type)
        assertEquals("true", d.search("外卖")["memories"]!!.jsonArray.single().jsonObject.s("inferred"))
        // 用户亲口说了新的内容:这一条现在是用户说的
        assertEquals("true", d.run("memory_edit", editArgs("update", item.id, "把外卖那条改成我常点世纪大道200号的外卖", "用户常点世纪大道200号的外卖")).s("ok"))
        assertEquals("user_said", store.items.value.single().source.type)
    }

    // 改记忆也一样:没明确要改、新内容里有用户没说过的具体值,都是对这一条的答复,不计入连续失败
    @Test fun refusedEditsDoNotCountAsOperationFailuresEither() {
        val store = memStore()
        val item = store.add(MemoryKind.fact, "用户家住浦东", listOf("地址"), MemorySource("manual", atMs = 1)).getOrThrow().item
        val d = dispatcher(store, UserUtterances().apply { add("那条地址记得不对"); add("把那条地址改成我现在住的地方") })
        assertTrue(item.id in d.search("地址").toString())
        val notAsked = d.run("memory_edit", editArgs("update", item.id, "那条地址记得不对", "用户家住张江"))
        val notSaid = d.run("memory_edit", editArgs("update", item.id, "把那条地址改成我现在住的地方", "用户家住张江路100号"))
        assertEquals("edit_not_requested", notAsked.s("error"))
        assertEquals("content_not_from_user", notSaid.s("error"))
        for (r in listOf(notAsked, notSaid)) {
            assertEquals("false", r.s("counts_as_failure"))
            assertEquals("false", r.s("recoverable"))
        }
        assertTrue("改的那条说的是「没改」,不是「没存」", "Nothing was changed" in notSaid.s("message"))
        assertEquals("用户家住浦东", store.items.value.single().content)
        // 真的故障照旧计数:没搜过就改,属于用法不对,模型应当重来
        val missing = d.run("memory_edit", editArgs("update", "nope", "把那条地址改成我现在住的地方", "用户家住张江"))
        assertEquals("not_found", missing.s("error"))
        assertFalse(missing.containsKey("counts_as_failure"))
    }

    // ---- 「让 Mika 自己归纳着记」:开着、用户这一轮又亲口让它记时,带着用户没说过的具体值的内容照存,来源标成 Mika 归纳的 ----

    private val askedToSum = "根据今天的消息看看我是个什么样的人,然后写入一些你觉得合适的东西到记忆里"

    @Test fun ownConclusionsAreSavedAsInferredWhenAllowedAndAsked() {
        val store = memStore(inferenceAllowed = { true })
        val d = dispatcher(store, UserUtterances().apply { add(askedToSum) })
        val r = d.run("memory_save", saveArgs("fact", "用户常在张江路一带活动,每周 3 次健身", "写入一些你觉得合适的东西到记忆里"))
        assertEquals("true", r.s("ok"))
        assertEquals("true", r.s("inferred"))
        assertTrue(r.s("note").contains("not as something the user said"))
        val item = store.items.value.single()
        assertEquals(MemoryContext.INFERRED, item.source.type)
        // 引的是用户提要求的那句话
        assertEquals("写入一些你觉得合适的东西到记忆里", item.source.quote)
    }

    // 关着(默认)时照旧不收:上游原来的规矩不变
    @Test fun ownConclusionsAreRefusedByDefault() {
        val store = memStore()
        val r = dispatcher(store, UserUtterances().apply { add(askedToSum) })
            .run("memory_save", saveArgs("fact", "用户常在张江路一带活动", "写入一些你觉得合适的东西到记忆里"))
        assertEquals("content_not_from_user", r.s("error"))
        assertEquals("false", r.s("counts_as_failure"))
        assertTrue(store.items.value.isEmpty())
    }

    // 开关每次保存时现读:用户在页面上一改就生效
    @Test fun theSwitchIsReadOnEverySave() {
        var on = false
        val store = memStore(inferenceAllowed = { on })
        val d = dispatcher(store, UserUtterances().apply { add(askedToSum) })
        val args = saveArgs("fact", "用户常在张江路一带活动", "写入一些你觉得合适的东西到记忆里")
        assertEquals("content_not_from_user", d.run("memory_save", args).s("error"))
        on = true
        assertEquals("true", d.run("memory_save", args).s("ok"))
        on = false
        assertEquals("content_not_from_user", d.run("memory_save", saveArgs("fact", "用户在浦东大道上班", "写入一些你觉得合适的东西到记忆里")).s("error"))
        assertEquals(1, store.items.value.size)
    }

    // 开着也只是多收「归纳的」:用户亲口说的照旧记成用户说的,不带归纳的标记
    @Test fun whatTheUserSaidStaysUserSaidWithTheSwitchOn() {
        val store = memStore(inferenceAllowed = { true })
        val r = dispatcher(store, UserUtterances().apply { add("记住以后都寄到张江路100号") })
            .run("memory_save", saveArgs("fact", "用户的收货地址是张江路100号", "以后都寄到张江路100号"))
        assertEquals("true", r.s("ok"))
        assertFalse(r.containsKey("inferred"))
        assertEquals("user_said", store.items.value.single().source.type)
    }

    // 只有关键词里带着用户没说过的具体值,也算归纳的
    @Test fun unsupportedKeywordsAloneMakeTheSaveInferred() {
        val store = memStore(inferenceAllowed = { true })
        val args = buildJsonObject {
            put("kind", "preference"); put("content", "用户喜欢喝茶"); put("quote", "写入一些你觉得合适的东西到记忆里")
            putJsonArray("keywords") { add(kotlinx.serialization.json.JsonPrimitive("Longjing")) }
        }
        val r = dispatcher(store, UserUtterances().apply { add(askedToSum) }).run("memory_save", args)
        assertEquals("true", r.s("inferred"))
        assertEquals(MemoryContext.INFERRED, store.items.value.single().source.type)
    }

    // 开着也不是见什么收什么:引文还得是用户的话,敏感信息还是不收
    @Test fun theSwitchDoesNotLoosenQuoteOrSensitiveChecks() {
        val store = memStore(inferenceAllowed = { true })
        val d = dispatcher(store, UserUtterances().apply { add(askedToSum) })
        assertEquals("quote_not_from_user", d.run("memory_save", saveArgs("fact", "用户常在张江路一带活动", "屏幕上写着张江路")).s("error"))
        assertEquals("sensitive", d.run("memory_save", saveArgs("fact", "用户的银行卡密码是 482913", "写入一些你觉得合适的东西到记忆里")).s("error"))
        assertTrue(store.items.value.isEmpty())
    }

    // 用户这一轮没让它记:随便一句话不能拿来当引文,把屏幕、网页上的字存进来
    @Test fun inferredSavesNeedARequestToRememberInTheQuotedMessage() {
        val store = memStore(inferenceAllowed = { true })
        val d = dispatcher(store, UserUtterances().apply { add("帮我看看这个网页上写了什么") })
        val r = d.run("memory_save", saveArgs("fact", "用户的收货地址是浦东张江路100号", "帮我看看这个网页"))
        assertEquals("inference_not_requested", r.s("error"))
        assertEquals("false", r.s("counts_as_failure"))
        assertEquals("false", r.s("recoverable"))
        // 说了别记的也不收
        val no = dispatcher(store, UserUtterances().apply { add("这些地址不要记到记忆里,帮我核对一下就行") })
            .run("memory_save", saveArgs("fact", "用户的收货地址是浦东张江路100号", "帮我核对一下就行"))
        assertEquals("inference_not_requested", no.s("error"))
        assertTrue(store.items.value.isEmpty())
    }

    // 一轮里说了两句:得引让它记的那一句
    @Test fun theRequestMustBeInTheMessageThatIsQuoted() {
        val store = memStore(inferenceAllowed = { true })
        val d = dispatcher(store, UserUtterances().apply { add("帮我看看这个网页上写了什么"); add("顺便总结几条写进记忆") })
        assertEquals("inference_not_requested", d.run("memory_save", saveArgs("fact", "用户关注张江路的房价", "帮我看看这个网页")).s("error"))
        assertEquals("true", d.run("memory_save", saveArgs("fact", "用户关注张江路的房价", "总结几条写进记忆")).s("inferred"))
    }

    // 上一轮的确认可以当用户原话的引文,但不算这一轮让它归纳着记
    @Test fun aPreviousConfirmationIsNotARequestToSaveConclusions() {
        val store = memStore(inferenceAllowed = { true })
        val said = UserUtterances().apply {
            addPreviousConfirmation("好的,记住吧", "我建议把这个地址记下来,你觉得怎么样?", "succeeded")
            add("继续刚才的事")
        }
        val r = dispatcher(store, said).run("memory_save", saveArgs("fact", "用户家住张江路100号", "好的,记住吧"))
        assertEquals("inference_not_requested", r.s("error"))
        assertTrue(store.items.value.isEmpty())
    }

    // 中文、英文里让它记的说法认得出来(句首大写、弯引号也认);别的话、别的语言一律按没让它记
    @Test fun requestsToRememberAreRecognisedInChineseAndEnglishOnly() {
        fun save(said: String, quote: String = said): JsonObject =
            dispatcher(memStore(inferenceAllowed = { true }), UserUtterances().apply { add(said) })
                .run("memory_save", saveArgs("fact", "用户常在张江路一带活动", quote))
        val asks = listOf(
            "根据今天的消息来觉认为我是个什么样的人,然后写,然后写入一些你觉得合适的东西到记忆里", "你自己总结一下记下来", "幫我歸納幾條存到記憶裡",
            "把你觉得重要的加到记忆里", "别忘了这些", "你能不能帮我记住这些", "Remember whatever you think matters about me", "Save a few notes about my habits",
            "Please add what matters to your memory",
            // 被拒之后用户顺口回的几种短句
            "那就记吧", "都存吧,没关系", "这几条都记上", "存起来吧",
            // 让记,只是其中一样不让记:整句引来,让记的那半句算数
            "总结几条写进记忆,别记我的手机号", "Save a summary of my habits, but don't save my phone number",
            "别记错了,把这些记到记忆里", "别忘了把这些记下来",
        )
        for (ask in asks) assertTrue("$ask -> ${save(ask)}", save(ask).toString().contains("\"inferred\":true"))
        val plain = listOf(
            "帮我看看聊天记录里有什么", "这个地址存在吗,查一下", "我不记得了,你查查看", "What is on this page?",
            "这些不要记到记忆里,帮我核对一下就行", "先别存,我再想想", "别把这些存进去", "不用帮我记",
            // 光提到「记忆」「memory」这个词不算让它记
            "你记忆功能是怎么工作的?", "This page has a memory error; explain what it means.", "Do you remember what I said yesterday?",
            // 不让记:直引号、弯引号都认
            "Don't remember any of this", "Don’t remember any of this", "Please do not save anything from this page",
            // 别的语言暂不认(改删记忆也只认中英文):让记的、不让记的都按没让它记
            "これまでの内容をまとめて覚えておいて", "この情報を記憶しないでください", "지금까지 내용을 정리해서 기억해 줘", "이 정보는 기억하지 마세요",
            "Recuerda lo que creas importante sobre mí", "No guardes nada de esta página", "Запомни, что считаешь важным обо мне", "Не сохраняй эту информацию",
        )
        for (p in plain) assertTrue("$p -> ${save(p)}", save(p).toString().contains("inference_not_requested"))
        // 引的是不让记的那半句:后半句的「记住」不能借给它;引让记的那半句才收
        val mixed = "别记我的地址,记住我喜欢喝茶"
        assertEquals("inference_not_requested", save(mixed, "别记我的地址").s("error"))
        assertTrue(save(mixed, "记住我喜欢喝茶").toString().contains("\"inferred\":true"))
        assertEquals("inference_not_requested", save("Don't save my address. Remember that I like tea.", "Don't save my address").s("error"))
    }

    // 查出来的时候标明哪几条是归纳的;给核查的材料里,它们是工具查到的事实,不算用户给的事实
    @Test fun inferredMemoriesAreMarkedInSearchAndKeptOutOfUserFacts() {
        val store = memStore(inferenceAllowed = { true })
        store.add(MemoryKind.preference, "用户点外卖不要香菜", listOf("外卖"), MemorySource("manual", atMs = 1)).getOrThrow()
        val d = dispatcher(store, UserUtterances().apply { add(askedToSum) })
        d.run("memory_save", saveArgs("fact", "用户常点张江路那家外卖", "写入一些你觉得合适的东西到记忆里"))
        val found = d.search("外卖")
        val rows = found["memories"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, rows.size)
        assertEquals("true", rows.single { "张江路" in it.s("content") }.s("inferred"))
        assertFalse(rows.single { "香菜" in it.s("content") }.containsKey("inferred"))
        val obs = com.wangzan101.mika.core.general.MemorySearch.observations(
            buildJsonObject { put("query", "外卖") }, com.wangzan101.mika.core.tool.ToolExecution(found), com.wangzan101.mika.core.tool.EvidenceLog())
        val said = obs.single { it.kind == com.wangzan101.mika.core.screen.observation.ObsKind.UserSaid }
        val own = obs.single { it.kind == com.wangzan101.mika.core.screen.observation.ObsKind.Fact }
        assertTrue("香菜" in said.text); assertFalse("张江路" in said.text)
        assertTrue("张江路" in own.text); assertTrue("不是用户说的" in own.text); assertFalse("香菜" in own.text)
    }

    @Test fun previousConfirmationDoesNotWeakenCurrentQuoteChecks() {
        val store = memStore()
        val said = UserUtterances().apply {
            addPreviousConfirmation("好的，我家住张江路100号", "我建议记住这个地址，你觉得怎么样?", "succeeded")
            add("记住这个")
        }
        val result = dispatcher(store, said).run("memory_save", saveArgs("fact", "用户家住张江路100号", "记住这个"))
        assertEquals("content_not_from_user", result.s("error"))
        assertTrue(store.items.value.isEmpty())
    }

    private fun saveWith(said: String, content: String, quote: String, keywords: List<String> = emptyList()): JsonObject {
        val args = buildJsonObject {
            put("kind", "fact"); put("content", content); put("quote", quote)
            putJsonArray("keywords") { keywords.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }
        return dispatcher(memStore(), UserUtterances().apply { add(said) }).run("memory_save", args)
    }

    @Test fun separatedAndChineseDigitsChecked() {
        assertEquals("true", saveWith("我电话是13800138000", "用户的电话是 138 0013 8000", "我电话是13800138000").s("ok"))
        assertEquals("content_not_from_user", saveWith("记住我的电话", "用户的电话是 138-0013-8001", "记住我的电话").s("error"))
        assertEquals("content_not_from_user", saveWith("记住我的门牌号", "用户的门牌号是一零零八", "记住我的门牌号").s("error"))
        assertEquals("true", saveWith("我家门牌号是1008", "用户家门牌号是一零零八", "我家门牌号是1008").s("ok"))
        assertEquals("true", saveWith("我家门牌号是一零零八", "用户家门牌号是1008", "我家门牌号是一零零八").s("ok"))
    }

    @Test fun placeNamesAndKeywordsChecked() {
        assertEquals("content_not_from_user", saveWith("以后都寄到我家", "用户家在汤臣一品苑", "以后都寄到我家").s("error"))
        assertEquals("content_not_from_user", saveWith("以后都寄到我家", "用户的收货地址在家里", "以后都寄到我家", listOf("张江路100号")).s("error"))
        // 第三人称转述本身不算从屏幕抄来的
        assertEquals("true", saveWith("我家里有只猫", "用户家里有猫", "我家里有只猫").s("ok"))
    }

    @Test fun utterancesClearedAtRunStart() = runTest {
        val u = UserUtterances().apply { add("上一个任务里说的话") }
        val store = memStore()
        AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = store, utterances = u), "B", memory = store).run("这次的任务")
        assertEquals(listOf("这次的任务"), u.all)
    }

    @Test fun savedEventEmitted() = runTest {
        val store = memStore()
        val events = mutableListOf<RunEvent>()
        val model = FakeModel(toolReply(memCall("memory_save", saveArgs("procedure", "点外卖先比价", "点外卖先比价"))), finishReply)
        AgentLoop(model, toolDispatcher(FakeScreen(), memory = store, utterances = UserUtterances()), "B",
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } }, memory = store).run("记住:点外卖先比价")
        assertEquals(listOf(RunEvent.MemorySaved(0, "id1", "procedure", "pending")), events.filterIsInstance<RunEvent.MemorySaved>().map { it.copy(atMs = 0) })
    }

    @Test fun updateAndDeleteUseStableIdAndKeepUndoableRecords() {
        val store = memStore()
        val original = store.add(MemoryKind.preference, "用户点外卖不要香菜", listOf("外卖"), MemorySource("manual", atMs = 1)).getOrThrow().item
        val said = UserUtterances().apply {
            add("把外卖那条改成以后点外卖不要香菜和葱")
            add("再把那条记忆删掉")
        }
        val d = dispatcher(store, said)
        d.search("外卖")
        val updated = d.run("memory_edit", editArgs("update", original.id, "把外卖那条改成以后点外卖不要香菜和葱", "用户点外卖不要香菜和葱"))
        assertEquals("true", updated.s("ok")); assertEquals("update", updated.s("op")); assertEquals(original.id, updated.s("id"))
        assertEquals("用户点外卖不要香菜和葱", store.items.value.single().content)
        assertEquals(original.content, store.items.value.single().replaces)
        assertEquals("把外卖那条改成以后点外卖不要香菜和葱", store.items.value.single().source.quote)

        val deleted = d.run("memory_edit", editArgs("delete", original.id, "再把那条记忆删掉"))
        assertEquals("true", deleted.s("ok")); assertEquals("true", deleted.s("deleted"))
        assertTrue(store.items.value.isEmpty())
        assertEquals(listOf(NoteKind.updated, NoteKind.forgot), MemoryExtractor.notes(d.savedChanges).map { it.kind })
        assertTrue(store.undo((d.savedChanges[1] as Change.Forgot).undoToken))
        assertTrue(store.undo((d.savedChanges[0] as Change.Updated).undoToken))
        assertEquals(listOf(original), store.items.value)
    }

    @Test fun editRequiresCurrentExplicitRequest() {
        val store = memStore()
        val item = store.add(MemoryKind.fact, "用户家住浦东", emptyList(), MemorySource("manual", atMs = 1)).getOrThrow().item
        val said = UserUtterances().apply {
            addPreviousConfirmation("嗯，好的，知微", "我建议叫我知微，你觉得怎么样?", "succeeded")
            add("用户家住张江")
            add("不要删掉那条记忆")
            add("不要修改那条记忆")
            add("不要忘记那条记忆")
        }
        val d = dispatcher(store, said)
        d.search("浦东")
        val oldQuote = d.run("memory_edit", editArgs("delete", item.id, "嗯，好的，知微"))
        assertEquals("quote_not_from_user", oldQuote.s("error"))
        val noRequest = d.run("memory_edit", editArgs("update", item.id, "用户家住张江", "用户家住张江"))
        assertEquals("edit_not_requested", noRequest.s("error"))
        for ((op, quote) in listOf(
            "delete" to "不要删掉那条记忆",
            "delete" to "删掉那条记忆",
            "update" to "不要修改那条记忆",
            "update" to "修改那条记忆",
            "delete" to "不要忘记那条记忆",
        )) {
            val negated = d.run("memory_edit", editArgs(op, item.id, quote, if (op == "update") "用户家住张江" else null))
            assertEquals(quote, "edit_not_requested", negated.s("error"))
        }
        assertEquals(listOf(item), store.items.value)
        assertTrue(d.savedChanges.isEmpty())
    }

    @Test fun editMissingIdDoesNothing() {
        val store = memStore()
        val d = dispatcher(store, UserUtterances().apply { add("把那条记忆删掉") })
        d.search("不存在")
        val result = d.run("memory_edit", editArgs("delete", "missing", "把那条记忆删掉"))
        assertEquals("not_found", result.s("error"))
        assertTrue(d.savedChanges.isEmpty())
    }

    @Test fun editRequiresIdInLatestSearchCandidates() {
        val store = memStore()
        val first = store.add(MemoryKind.fact, "用户点外卖不要香菜", listOf("外卖"), MemorySource("manual", atMs = 1)).getOrThrow().item
        val second = store.add(MemoryKind.fact, "用户家住浦东", listOf("住址"), MemorySource("manual", atMs = 1)).getOrThrow().item
        val original = store.items.value
        val d = dispatcher(store, UserUtterances().apply { add("把那条记忆删掉") })

        val noSearch = d.run("memory_edit", editArgs("delete", first.id, "把那条记忆删掉"))
        assertEquals("not_found", noSearch.s("error"))
        assertEquals(original, store.items.value)

        d.search("没有匹配")
        val emptySearch = d.run("memory_edit", editArgs("delete", first.id, "把那条记忆删掉"))
        assertEquals("not_found", emptySearch.s("error"))
        assertEquals(original, store.items.value)

        d.search("外卖")
        val notCandidate = d.run("memory_edit", editArgs("delete", second.id, "把那条记忆删掉"))
        assertEquals("not_found", notCandidate.s("error"))
        assertEquals(original, store.items.value)
        assertTrue(d.savedChanges.isEmpty())
    }

    @Test fun multipleCandidatesNeedExactIdInCurrentQuote() {
        val store = memStore()
        val first = store.add(MemoryKind.fact, "用户家住浦东", listOf("住址"), MemorySource("manual", atMs = 1)).getOrThrow().item
        val second = store.add(MemoryKind.fact, "用户家住张江", listOf("住址"), MemorySource("manual", atMs = 1)).getOrThrow().item
        val generic = "把那条记忆删掉"
        val exact = "把 ${first.id} 那条记忆删掉"
        val d = dispatcher(store, UserUtterances().apply { add(generic); add(exact) })
        d.search("住址")

        val ambiguous = d.run("memory_edit", editArgs("delete", first.id, generic))
        assertEquals("ambiguous_target", ambiguous.s("error"))
        assertEquals(listOf(first, second), store.items.value)
        assertTrue(d.savedChanges.isEmpty())

        val deleted = d.run("memory_edit", editArgs("delete", first.id, exact))
        assertEquals("true", deleted.s("ok"))
        assertEquals(listOf(second), store.items.value)
        assertEquals(1, d.savedChanges.size)
    }

    @Test fun editRunRecordKeepsOperationButNoMemoryText() = runTest {
        val store = memStore()
        val item = store.add(MemoryKind.fact, "用户觉得旧名字不好", emptyList(), MemorySource("manual", atMs = 1)).getOrThrow().item
        val events = mutableListOf<RunEvent>()
        val model = FakeModel(
            toolReply(memCall("memory_search", buildJsonObject { put("query", "旧名字") })),
            toolReply(memCall("memory_edit", editArgs("delete", item.id, "把那条记忆删掉"))),
            finishReply,
        )
        AgentLoop(model, toolDispatcher(FakeScreen(), memory = store, utterances = UserUtterances()), "B",
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } }, memory = store).run("把那条记忆删掉")
        val record = events.filter { it is RunEvent.ModelReply || it is RunEvent.ToolResult }.joinToString("\n") { RunEvent.encode(it) }
        assertTrue(record.contains("memory_edit")); assertTrue(record.contains("delete")); assertTrue(record.contains(item.id))
        assertFalse(record.contains("用户觉得旧名字不好")); assertFalse(record.contains("把那条记忆删掉"))
        assertEquals(1, events.filterIsInstance<RunEvent.ToolResult>().count { "memory_edit" in RunEvent.encode(it) })
    }
}
