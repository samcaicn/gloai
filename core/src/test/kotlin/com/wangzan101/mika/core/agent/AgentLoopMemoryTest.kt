package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.learn.memory.MemoryKind
import com.wangzan101.mika.core.learn.memory.MemorySource
import com.wangzan101.mika.core.learn.memory.UserUtterances
import com.wangzan101.mika.core.learn.memory.finishReply
import com.wangzan101.mika.core.learn.memory.memStore
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.proactive.ObservedContext
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunInput
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.cap.web.FakeWebPort
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.assembly.toolDispatcher

class AgentLoopMemoryTest {
    private val manual = MemorySource("manual", atMs = 1)
    private fun Message.text() = plainText()

    private fun store() = memStore().apply {
        add(MemoryKind.preference, "用户点外卖不要香菜", listOf("外卖"), manual).getOrThrow()
        add(MemoryKind.fact, "用户的公司在张江", listOf("公司"), manual).getOrThrow()
    }

    @Test fun blockInsertedBeforeTaskAfterContext() {
        val s = store()
        val model = FakeModel(finishReply)
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
                .run("帮我点个外卖", listOf(Turn("查一下天气", "晴", "succeeded")))
        }
        val msgs = model.requests.first().withoutRuntime().map { it.text() }
        assertTrue(msgs[0].startsWith(Conversation.HEADER))
        assertTrue(msgs[1].startsWith("Memories about the user"))
        // 记忆总量装得下就全量注入:与本次任务词面无关的「公司在张江」也在(以前会被 BM25 检索丢掉)
        assertTrue(msgs[1].contains("用户点外卖不要香菜"))
        assertTrue(msgs[1].contains("张江"))
        assertEquals("帮我点个外卖", msgs[2])
    }

    @Test fun unrelatedMemoryStillInjectedInFullMode() {
        val s = store()
        val model = FakeModel(finishReply)
        val loop = AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
        runBlocking { loop.run("看看明天天气") }
        val msgs = model.requests.first().withoutRuntime().map { it.text() }
        assertTrue(msgs[0].startsWith("Memories about the user"))
        assertEquals("看看明天天气", msgs[1])
        assertEquals(s.items.value.map { it.id }, loop.usedMemoryIds)
        assertTrue(s.items.value.all { it.useCount == 1 })
    }

    @Test fun usedIdsRecordedAndMarked() {
        val s = store()
        val events = mutableListOf<RunEvent>()
        val loop = AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", sink = object : RunSink { override fun event(e: RunEvent) { events += e } }, memory = s)
        runBlocking { loop.run("帮我点个外卖") }
        val ids = s.items.value.map { it.id }
        assertEquals(ids, loop.usedMemoryIds)
        assertTrue(s.items.value.all { it.useCount == 1 })
        assertEquals(listOf(ids), events.filterIsInstance<RunEvent.MemoryUsed>().map { it.ids })
    }

    // 用户让记住一件事,意图判断却会把这句话当成讨论:写记忆只动 Mika 自己的记忆,不经过那个判断,照常执行、存得下来
    @Test fun savingAMemoryIsNotStrippedAsDiscussion() {
        val s = store()
        val said = UserUtterances()
        val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
            """{"kind":"preference","content":"用户希望回答简短","quote":"回答简短一点"}""")), StopReason.ToolUse, null)
        val model = FakeModel(save, finishReply)
        val judge = FakeModel(ModelReply(listOf(Part.Text("DISCUSS")), StopReason.EndTurn, null))
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s, intentJudge = judge)
                .run("你自己总结一下:以后回答简短一点,写进记忆")
        }
        assertEquals("没有去问意图", 0, judge.requests.size)
        assertTrue("存下来了", "用户希望回答简短" in s.items.value.map { it.content })
        val result = model.requests[1].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single().content
        assertTrue(result, "\"ok\":true" in result)
    }

    // 改、删记忆也不经过意图判断(先查到那一条,再按用户这一轮的话删掉):真机上正常的修改请求会被它判成讨论
    @Test fun editingAMemoryIsNotStrippedAsDiscussion() {
        val s = store()
        val target = s.items.value.first { "香菜" in it.content }
        val search = ModelReply(listOf(Part.ToolCall("c1", "memory_search", """{"query":"外卖"}""")), StopReason.ToolUse, null)
        val edit = ModelReply(listOf(Part.ToolCall("c2", "memory_edit",
            """{"op":"delete","id":"${target.id}","quote":"外卖那条记忆删掉"}""")), StopReason.ToolUse, null)
        val model = FakeModel(search, edit, finishReply)
        val judge = FakeModel(ModelReply(listOf(Part.Text("DISCUSS")), StopReason.EndTurn, null))
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s, intentJudge = judge)
                .run("把外卖那条记忆删掉")
        }
        assertEquals("没有去问意图", 0, judge.requests.size)
        val found = model.requests[1].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single().content
        val result = model.requests[2].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single().content
        assertTrue("$found -> $result", "\"ok\":true" in result)
        assertTrue(s.items.value.none { it.id == target.id && it.status == com.wangzan101.mika.core.learn.memory.MemoryStatus.active })
    }

    // 只是在问「如果让你删你会怎么做」:模型万一真提了删除,工具自己不执行,那条记忆还在
    @Test fun aDeleteProposedDuringADiscussionIsNotCarriedOut() {
        val s = store()
        val target = s.items.value.first { "香菜" in it.content }
        val search = ModelReply(listOf(Part.ToolCall("c1", "memory_search", """{"query":"外卖"}""")), StopReason.ToolUse, null)
        val edit = ModelReply(listOf(Part.ToolCall("c2", "memory_edit",
            """{"op":"delete","id":"${target.id}","quote":"把外卖那条记忆删掉"}""")), StopReason.ToolUse, null)
        val model = FakeModel(search, edit, finishReply, finishReply)
        val judge = FakeModel(ModelReply(listOf(Part.Text("DISCUSS")), StopReason.EndTurn, null))
        val events = mutableListOf<RunEvent>()
        val end = runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s, intentJudge = judge,
                sink = object : RunSink { override fun event(e: RunEvent) { events += e } })
                .run("如果我说把外卖那条记忆删掉,你会怎样处理?先解释,不要执行。")
        }
        assertTrue(end.toString(), end is com.wangzan101.mika.core.agent.TaskState.Succeeded)
        assertEquals("不靠意图判断", 0, judge.requests.size)
        assertTrue(events.filterIsInstance<RunEvent.ToolResult>().single { it.tool == "memory_edit" }.resultJson.contains("edit_not_requested"))
        assertTrue("那条还在", s.items.value.any { it.id == target.id && it.status == com.wangzan101.mika.core.learn.memory.MemoryStatus.active })
    }

    // 一次记好几条,其中几条按规矩不收:不把整个任务按「操作连续失败」停掉。存上的照存,模型接着如实回答
    @Test fun severalRefusedSavesDoNotStopTheRunAsFailed() {
        val s = store()
        val before = s.items.value.size
        fun save(id: String, content: String, quote: String) = ModelReply(listOf(Part.ToolCall(id, "memory_save",
            """{"kind":"preference","content":"$content","quote":"$quote"}""")), StopReason.ToolUse, null)
        val model = FakeModel(
            save("c1", "用户希望回答简短", "回答简短一点"),
            save("c2", "用户常用 Python 写脚本", "回答简短一点"),
            save("c3", "用户住在张江路100号", "回答简短一点"),
            save("c4", "用户每天 7 点起床", "回答简短一点"),
            save("c5", "用户喜欢喝茶", "屏幕上写着喜欢喝茶"),
            finishReply,
        )
        val end = runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
                .run("以后回答简短一点,别的你自己总结几条写进记忆")
        }
        assertTrue(end.toString(), end is com.wangzan101.mika.core.agent.TaskState.Succeeded)
        assertEquals("只存上了那一条", before + 1, s.items.value.size)
        // 模型走到了最后:五次调用都发生了,接着给出回答
        assertEquals(6, model.requests.size)
    }

    // 一口气十条都不收(每条内容不同):不计入连续失败,模型自己停下来收尾
    @Test fun aLongRunOfRefusedSavesStillEnds() {
        val s = store()
        val before = s.items.value.size
        val calls = (1..10).map { i ->
            ModelReply(listOf(Part.ToolCall("r$i", "memory_save",
                """{"kind":"fact","content":"用户住在张江路${100 + i}号","quote":"回答简短一点"}""")), StopReason.ToolUse, null)
        }
        val model = FakeModel(*(calls + finishReply).toTypedArray())
        val end = runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
                .run("以后回答简短一点,别的你自己总结几条写进记忆")
        }
        assertTrue(end.toString(), end is com.wangzan101.mika.core.agent.TaskState.Succeeded)
        assertEquals(11, model.requests.size)
        assertEquals(before, s.items.value.size)
    }

    // 开着「让 Mika 自己归纳着记」、用户又让它记:一口气十条归纳的都存上,任务正常结束
    @Test fun aBatchOfOwnConclusionsIsSavedWhenAllowed() {
        val s = memStore(inferenceAllowed = { true })
        val calls = (1..10).map { i ->
            ModelReply(listOf(Part.ToolCall("i$i", "memory_save",
                """{"kind":"fact","content":"用户每周 $i 次去张江路${100 + i}号","quote":"你自己总结几条写进记忆"}""")), StopReason.ToolUse, null)
        }
        val model = FakeModel(*(calls + finishReply).toTypedArray())
        val end = runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
                .run("根据今天的消息看看我是个什么样的人,你自己总结几条写进记忆")
        }
        assertTrue(end.toString(), end is com.wangzan101.mika.core.agent.TaskState.Succeeded)
        assertEquals(10, s.items.value.size)
        assertTrue(s.items.value.all { it.source.type == com.wangzan101.mika.core.learn.memory.MemoryContext.INFERRED })
    }

    // 查到一条 Mika 自己归纳的记忆,接着照常回答:这条进了证据账本(工具查到的事实),任务正常结束
    @Test fun aRunThatFindsAnInferredMemoryFinishesNormally() {
        val s = memStore()
        s.add(MemoryKind.fact, "用户常点张江路那家外卖", listOf("外卖"),
            MemorySource(com.wangzan101.mika.core.learn.memory.MemoryContext.INFERRED, "你自己总结几条写进记忆", atMs = 1)).getOrThrow()
        val events = mutableListOf<RunEvent>()
        val model = FakeModel(
            ModelReply(listOf(Part.ToolCall("s1", "memory_search", """{"query":"外卖"}""")), StopReason.ToolUse, null),
            finishReply,
        )
        val end = runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B",
                sink = object : RunSink { override fun event(e: RunEvent) { events += e } }, memory = s).run("我平时点哪家外卖")
        }
        assertTrue(end.toString(), end is com.wangzan101.mika.core.agent.TaskState.Succeeded)
        val seen = events.filterIsInstance<RunEvent.Observed>().single { "张江路" in it.text }
        assertEquals("fact", seen.kind)
        assertTrue("不是用户说的" in seen.text)
        // 模型看到的工具结果里这条带着标记;开局注入的那段也标了
        assertTrue(events.filterIsInstance<RunEvent.ToolResult>().single { it.tool == "memory_search" }.resultJson.contains("\"inferred\":true"))
        assertTrue(model.requests.first().joinToString("\n") { it.plainText() }.contains("the user did not say this"))
    }

    // 答复里说了别再试,模型还是把同一条原样再提:第二次起照常计失败,连着三次由守卫停下。
    // 手机上默认不限步数(这里也不限),不能靠步数上限兜底
    @Test fun theSameRefusedSaveRepeatedIsStoppedByTheFailureGuard() {
        val s = memStore()
        val same = ModelReply(listOf(Part.ToolCall("r", "memory_save",
            """{"kind":"fact","content":"用户住在张江路100号","quote":"回答简短一点"}""")), StopReason.ToolUse, null)
        val model = FakeModel(*(List(100) { same } + finishReply).toTypedArray())
        val end = runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s,
                maxSteps = AgentLoop.UNLIMITED_STEPS).run("以后回答简短一点")
        }
        assertTrue(end.toString(), end is com.wangzan101.mika.core.agent.TaskState.Failed)
        assertTrue(end.toString(), "too_many_failures" in end.toString())
        assertTrue("没有一直转下去:${model.requests.size}", model.requests.size <= 6)
        assertTrue(s.items.value.isEmpty())
    }

    // 每条都不一样、但一条也不收:到上限为止不计失败,再往后照常计,连着三条停下
    @Test fun endlessDistinctRefusedSavesAreStoppedToo() {
        val s = memStore()
        val calls = (1..100).map { i ->
            ModelReply(listOf(Part.ToolCall("r$i", "memory_save",
                """{"kind":"fact","content":"用户住在张江路${100 + i}号","quote":"回答简短一点"}""")), StopReason.ToolUse, null)
        }
        val model = FakeModel(*(calls + finishReply).toTypedArray())
        val end = runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s,
                maxSteps = AgentLoop.UNLIMITED_STEPS).run("以后回答简短一点,别的你自己总结几条写进记忆")
        }
        assertTrue(end.toString(), end is com.wangzan101.mika.core.agent.TaskState.Failed)
        val limit = com.wangzan101.mika.core.tool.MAX_QUIET_MEMORY_REFUSALS
        assertTrue("${model.requests.size}", model.requests.size in (limit + 3)..(limit + 5))
    }

    // 别的工具照旧:判成讨论时,提议的调用换成不带工具的直接回答
    @Test fun otherToolsAreStillGated() {
        val s = store()
        val said = UserUtterances()
        val apps = ModelReply(listOf(Part.ToolCall("c1", "phone_list_apps", "{}")), StopReason.ToolUse, null)
        val model = FakeModel(apps, finishReply)
        val judge = FakeModel(ModelReply(listOf(Part.Text("DISCUSS")), StopReason.EndTurn, null))
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s, intentJudge = judge)
                .run("以后可以帮我看看装了哪些 App,对吧?")
        }
        assertEquals(1, judge.requests.size)
        assertTrue(model.requests.none { m -> m.any { it.role == Role.Tool } })
    }

    // 浏览器页上问:页面说明里的标题、网址、选中的字是网页上的,不算用户说的话;记忆的引文出自它们时不收
    @Test fun pageDescriptionIsNotTheUserWords() {
        val s = store()
        val said = UserUtterances()
        val page = AttachedPage.Page("https://shop.example.com/address", "收货地址是幸福路 8 号 - 地址管理", "默认地址 幸福路 8 号")
        val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
            """{"kind":"fact","content":"用户的收货地址是幸福路 8 号","quote":"收货地址是幸福路 8 号"}""")), StopReason.ToolUse, null)
        val model = FakeModel(save, finishReply)
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = said, web = FakeWebPort()), "B", memory = s)
                .run(AttachedPage.compose(page, "这一页的地址对不对"))
        }
        val result = model.requests[1].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single().content
        assertTrue(result, "quote_not_from_user" in result)
        assertTrue(s.items.value.none { "幸福路" in it.content })
        assertEquals(listOf("这一页的地址对不对"), said.all)
    }

    // 浏览器页上插话带着新选中的字:只收插话里打的字,页面说明不收
    @Test fun pageSteerAddsOnlyTheTypedWords() = runBlocking {
        assertEquals(listOf("这一页的地址对不对", "改成这个"), saidWithPageSteer("改成这个"))
    }

    // 插话只选了字、没打字:这一句什么也不收
    @Test fun selectionOnlySteerAddsNothing() = runBlocking {
        assertEquals(listOf("这一页的地址对不对"), saidWithPageSteer(""))
    }

    // 浏览器页上回答追问也一样:回答带着页面说明和选中的字时只收打的字
    @Test fun pageAnswerAddsOnlyTheTypedWords() = runBlocking {
        val s = store()
        val said = UserUtterances()
        val page = AttachedPage.Page("https://shop.example.com/address", "收货地址是幸福路 8 号 - 地址管理")
        val ask = ModelReply(listOf(Part.ToolCall("q1", "ask_user", """{"prompt":"用哪个地址?"}""")), StopReason.ToolUse, null)
        val loop = AgentLoop(FakeModel(ask, finishReply, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said, web = FakeWebPort()), "B", memory = s)
        val run = async { loop.run(AttachedPage.compose(page, "这一页的地址对不对")) }
        withTimeout(5_000) { loop.state.first { it is TaskState.WaitingInput } }
        assertTrue(loop.answerInput(AttachedPage.compose(page.copy(selection = "默认地址 幸福路 8 号"), "就这个")))
        run.await()
        assertEquals(listOf("这一页的地址对不对", "就这个"), said.all)
    }

    /** 在浏览器页上开局,插话带着新选中的字和 [words];返回这次记下的用户的话。 */
    private suspend fun saidWithPageSteer(words: String): List<String> {
        val s = store()
        val said = UserUtterances()
        val page = AttachedPage.Page("https://shop.example.com/address", "收货地址是幸福路 8 号 - 地址管理")
        val inbox = RunInbox().apply { send(RunInput.Steer("m1", AttachedPage.compose(page.copy(selection = "默认地址 幸福路 8 号"), words))) }
        AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said, web = FakeWebPort()), "B", memory = s)
            .run(RunSpec(RunId("r1"), AttachedPage.compose(page, "这一页的地址对不对"), "thread-1"), inbox)
        return said.all
    }

    // 引了 Mika 的回答:引的那段是 Mika 说的,记忆的引文不认,只收用户打的字
    @Test fun quotedReplyIsNotTheUserWords() {
        val s = store()
        val said = UserUtterances()
        val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
            """{"kind":"fact","content":"用户的收货地址是幸福路 8 号","quote":"收货地址是幸福路 8 号"}""")), StopReason.ToolUse, null)
        val model = FakeModel(save, finishReply)
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
                .run(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, MIKA_ADDRESS), "这个地址对不对"))
        }
        val result = model.requests[1].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single().content
        assertTrue(result, "quote_not_from_user" in result)
        assertTrue(s.items.value.none { "幸福路" in it.content })
        assertEquals(listOf("这个地址对不对"), said.all)
    }

    // 引了自己说过的话:对话页引的是他当时打的字,连那段一起收,记忆的引文照样认
    @Test fun ownQuoteIsTheUserWords() {
        val s = store()
        val said = UserUtterances()
        val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
            """{"kind":"fact","content":"用户家的猫叫团子","quote":"我家猫叫团子"}""")), StopReason.ToolUse, null)
        runBlocking {
            AgentLoop(FakeModel(save, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
                .run(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.You, "我家猫叫团子"), "这个记一下"))
        }
        assertTrue(s.items.value.any { it.content == "用户家的猫叫团子" })
        assertEquals(listOf("我家猫叫团子\n这个记一下"), said.all)
    }

    // 带了附件:附件那几行是 App 写的路径和大小,不收
    @Test fun attachmentLinesAreNotTheUserWords() {
        val s = store()
        val said = UserUtterances()
        runBlocking {
            AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
                .run(AttachmentLines.compose("总结一下", listOf("/workspace/inbox/Quarterly_Financial_Report_Final_v2.pdf" to "2 KB")))
        }
        assertEquals(listOf("总结一下"), said.all)
    }

    // 插话引了 Mika 的回答:只收插话里打的字
    @Test fun quotedSteerAddsOnlyTheTypedWords() = runBlocking {
        val s = store()
        val said = UserUtterances()
        val inbox = RunInbox().apply { send(RunInput.Steer("m1", QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, MIKA_ADDRESS), "换成这个"))) }
        AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
            .run(RunSpec(RunId("r1"), "帮我下单", "thread-1"), inbox)
        assertEquals(listOf("帮我下单", "换成这个"), said.all)
    }

    // 回答追问时引了 Mika 的回答:也只收打的字
    @Test fun quotedAnswerAddsOnlyTheTypedWords() = runBlocking {
        val s = store()
        val said = UserUtterances()
        val ask = ModelReply(listOf(Part.ToolCall("q1", "ask_user", """{"prompt":"送到哪个地址?"}""")), StopReason.ToolUse, null)
        val loop = AgentLoop(FakeModel(ask, finishReply, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
        val run = async { loop.run("帮我下单") }
        withTimeout(5_000) { loop.state.first { it is TaskState.WaitingInput } }
        assertTrue(loop.answerInput(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, MIKA_ADDRESS), "就这个")))
        run.await()
        assertEquals(listOf("帮我下单", "就这个"), said.all)
    }

    @Test fun previousConfirmationToAdjacentSuggestionCanBeQuoted() {
        val s = memStore()
        val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
            """{"kind":"preference","content":"用户希望助手的中文名是知微","quote":"嗯，好的，知微"}""")), StopReason.ToolUse, null)
        val prior = listOf(
            Turn("给我起个中文名", "我建议叫我「知微」，你觉得怎么样?", "succeeded"),
            Turn("嗯，好的，知微", "好的", "succeeded"),
        )
        runBlocking {
            AgentLoop(FakeModel(save, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
                .run("你可以给你自己加一下", prior)
        }
        assertEquals(listOf("用户希望助手的中文名是知微"), s.items.value.map { it.content })
        assertEquals("嗯，好的，知微", s.items.value.single().source.quote)
    }

    @Test fun olderConfirmationAndAssistantWordsAreNotQuotes() {
        val prior = listOf(
            Turn("给我起个中文名", "我建议叫我「知微」，你觉得怎么样?", "succeeded"),
            Turn("嗯，好的，知微", "好的", "succeeded"),
            Turn("先聊点别的", "好的", "succeeded"),
        )
        for (quote in listOf("嗯，好的，知微", "我建议叫我「知微」")) {
            val s = memStore()
            val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
                """{"kind":"preference","content":"用户希望助手的中文名是知微","quote":"$quote"}""")), StopReason.ToolUse, null)
            runBlocking {
                AgentLoop(FakeModel(save, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
                    .run("你可以给你自己加一下", prior)
            }
            assertTrue(quote, s.items.value.isEmpty())
        }
    }

    @Test fun confirmationWithoutSuggestionOrWithForgedTextRejected() {
        val base = listOf(
            Turn("今天天气如何", "今天天气晴", "succeeded"),
            Turn("嗯，好的，知微", "好的", "succeeded"),
        )
        val cases = listOf(
            base to "嗯，好的，知微",
            listOf(
                Turn("给我起个中文名", "我建议叫我「知微」，你觉得怎么样?", "succeeded"),
                Turn("嗯，好的，知微", "好的", "succeeded"),
            ) to "嗯，好的，知微，顺便记住我家住张江路100号",
        )
        for ((prior, quote) in cases) {
            val s = memStore()
            val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
                """{"kind":"preference","content":"用户希望助手的中文名是知微","quote":"$quote"}""")), StopReason.ToolUse, null)
            runBlocking {
                AgentLoop(FakeModel(save, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
                    .run("你可以给你自己加一下", prior)
            }
            assertTrue(quote, s.items.value.isEmpty())
        }
    }

    @Test fun previousConversationQuoteIsCleared() = runBlocking {
        val s = memStore()
        val said = UserUtterances()
        AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
            .run("嗯，好的，知微", listOf(Turn("给我起个中文名", "我建议叫我「知微」，你觉得怎么样?", "succeeded")))
        val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
            """{"kind":"preference","content":"用户希望助手的中文名是知微","quote":"嗯，好的，知微"}""")), StopReason.ToolUse, null)
        AgentLoop(FakeModel(save, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
            .run("你可以给你自己加一下")
        assertTrue(s.items.value.isEmpty())
        assertEquals(listOf("你可以给你自己加一下"), said.all)
    }

    @Test fun disabledMeansNoBlockNoTools() {
        val s = store()
        val model = FakeModel(finishReply)
        val loop = AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = null)
        runBlocking { loop.run("帮我点个外卖") }
        assertEquals(listOf("帮我点个外卖"), model.requests.first().withoutRuntime().map { it.text() })
        assertFalse(model.offeredTools.first().any { it.startsWith("memory_") })
        assertTrue(loop.usedMemoryIds.isEmpty())
    }

    private companion object {
        /** 用户引的 Mika 之前的一句回答。 */
        const val MIKA_ADDRESS = "你常用的收货地址是幸福路 8 号"
    }

    // 从通知里得来的那一段带进任务:在用户的话之前,套着自己的外壳;不给就没有
    @Test fun observedContextRidesIntoTheTaskSeparately() {
        val model = FakeModel(finishReply)
        runBlocking { AgentLoop(model, toolDispatcher(FakeScreen()), "B", observed = "Today so far:\n- 王老师通知明天开会").run("帮我点个外卖") }
        val first = model.requests.first().withoutRuntime()
        assertEquals(listOf(ContextKind.Observed, null), first.map { ContextBuilder.kindOf(it) })
        assertEquals(listOf("Today so far:\n- 王老师通知明天开会", "帮我点个外卖"), first.map { it.text() })
        val plain = FakeModel(finishReply)
        runBlocking { AgentLoop(plain, toolDispatcher(FakeScreen()), "B").run("帮我点个外卖") }
        assertEquals(listOf("帮我点个外卖"), plain.requests.first().withoutRuntime().map { it.text() })
    }

    // 前台的补充说明进任务上下文,但不是用户的话:用户原话的记录里只有任务原话
    @Test fun deskNoteNeverEntersUserUtterances() {
        val model = FakeModel(finishReply)
        val said = UserUtterances()
        runBlocking { AgentLoop(model, toolDispatcher(FakeScreen(), utterances = said), "B", deskNote = "前台整理:用户要靠窗的座位").run("帮我订明早的票") }
        val first = model.requests.first().withoutRuntime()
        assertEquals(listOf(ContextKind.DeskNote, null), first.map { ContextBuilder.kindOf(it) })
        assertEquals(listOf("帮我订明早的票"), said.all)
        assertTrue(said.all.none { "靠窗" in it })
    }

    // 任务带着「从通知里得来的」那一段时,「上一轮确认」的例外不开:上一轮的建议可能就是照着那一段说的
    @Test fun previousConfirmationIsNotUsedWhenObservedContextIsCarried() {
        val prior = listOf(
            Turn("给我起个中文名", "我建议叫我「知微」，你觉得怎么样?", "succeeded"),
            Turn("嗯，好的，知微", "好的", "succeeded"),
        )
        val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
            """{"kind":"preference","content":"用户希望助手的中文名是知微","quote":"嗯，好的，知微"}""")), StopReason.ToolUse, null)
        val s = memStore()
        runBlocking {
            AgentLoop(FakeModel(save, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s,
                observed = "Today so far:\n- x").run("你可以给你自己加一下", prior)
        }
        assertTrue(s.items.value.isEmpty())
    }

    // 带了那一段就在运行记录里记一笔(只有长度,没有内容),任务自己也说得出带没带;没带的不记
    @Test fun carryingObservedContextIsRecordedWithoutItsText() {
        fun run(observed: String?): Pair<AgentLoop, List<RunEvent>> {
            val events = mutableListOf<RunEvent>()
            val agent = AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen()), "B", observed = observed,
                sink = object : RunSink { override fun event(e: RunEvent) { events += e } })
            runBlocking { agent.run("帮我点个外卖") }
            return agent to events
        }
        val text = "Today so far:\n- 王老师通知明天开会"
        val (carried, events) = run(text)
        assertTrue(carried.carriedObserved)
        val mark = events.filterIsInstance<RunEvent.State>().single { it.name == ObservedContext.RUN_STATE }
        assertEquals(text.length.toString(), mark.detail)
        assertFalse("王老师" in RunEvent.encode(mark))
        val (plain, none) = run(null)
        assertFalse(plain.carriedObserved)
        assertTrue(none.filterIsInstance<RunEvent.State>().none { it.name == ObservedContext.RUN_STATE })
    }
}

class MemoryBlockCompressionTest {
    @Test fun memoryBlockSurvivesAggressiveCompression() {
        val block = Message(Role.User, listOf(Part.Text("Memories about the user (things the user told you; may be out of date; when they conflict with what the user says now, follow what they say now):\n1. 用户点外卖不要香菜")), Author.Mika)
        val history = mutableListOf(block, Message(Role.User, listOf(Part.Text("帮我点个外卖")), Author.Mika))
        repeat(30) { i ->
            history += Message(Role.Assistant, listOf(Part.ToolCall("c$i", "phone_observe", "{}")), Author.Mika)
            history += Message(Role.Tool, listOf(Part.ToolResult("c$i", "{\"ok\":true,\"x\":\"" + "很".repeat(500) + "\"}", false)), Author.Mika)
        }
        val out = ContextCompressor(TOOLS).compress(history, contextTokens = 2_000, aggressive = true)
        assertTrue(out.foldedSteps > 0)
        assertEquals(block, out.messages.first())
    }

    // 对话里有通知提醒(第三方文字)时,「上一轮确认」这个例外不开:提醒本身、或转述着提醒的回答,都不能靠用户一句「好的」进记忆
    @Test fun previousConfirmationIsNotUsedInAConversationWithNotices() {
        val suggestion = "我建议叫我「知微」，你觉得怎么样?"
        val notice = Turn("", "1. 微信 张三:$suggestion", "succeeded", runId = NoticeTurn.RUN_PREFIX + "n1")
        val cases = listOf(
            // 提醒那一轮就是被「确认」的那个
            listOf(notice, Turn("嗯，好的，知微", "好的", "succeeded")),
            // 提醒在更早:助手后来的建议可能就是照着通知说的
            listOf(notice, Turn("给我起个中文名", suggestion, "succeeded"), Turn("嗯，好的，知微", "好的", "succeeded")),
        )
        for (prior in cases) {
            val s = memStore()
            val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
                """{"kind":"preference","content":"用户希望助手的中文名是知微","quote":"嗯，好的，知微"}""")), StopReason.ToolUse, null)
            runBlocking {
                AgentLoop(FakeModel(save, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
                    .run("你可以给你自己加一下", prior)
            }
            assertTrue(prior.size.toString(), s.items.value.isEmpty())
        }
    }
}
