package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.model.AnthropicModel
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.GeminiModel
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.model.PromptCaching
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.core.testing.Screen
import com.wangzan101.mika.core.testing.ScriptedScreen
import com.wangzan101.mika.core.testing.label
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [PrefixStabilityTest] 的同一个假任务,换成另外几种接口的适配器(Responses、Anthropic、Gemini,以及带显式缓存标记的
 * OpenAI 兼容接口),按线上字节查同样几条:区 0(系统提示词、工具)只在展开一组时变,展开时排在前面的工具逐字不变;
 * 历史只追加,只有压缩的那几次改写;回放的思考(加密思考、带签名的思考、thoughtSignature)每一步都带齐;
 * 区 0 和消息以外的顶层字段(思考档位、输出上限等)每次相同。
 * 缓存标记(cache_control)每次挪到最后一条,标记本身不进缓存的键,比对前去掉;Anthropic 一次请求的标记不超过 4 个。
 */
class PrefixStabilityAdaptersTest {
    private val servers = mutableListOf<MockWebServer>()
    private val pad = "第几项内容".repeat(30)

    @After fun tearDown() = servers.forEach { it.shutdown() }

    /** 一种接口:怎么建适配器、请求怎么拆成区 0 与逐条消息、怎么回工具调用和最终回答。 */
    private interface Api {
        fun model(url: String, vision: Boolean, contextTokens: Int): ChatModel
        /** 区 0 与逐条消息占的顶层字段;其余顶层字段每次请求都该一样。 */
        val keys: Set<String>
        /** 假回复里每一步回放值的前缀(第 n 步是前缀加 n);没有回放的接口为 null。 */
        val replay: String?
        /** 80K 窗口下改写前缀的请求(压缩),见 [PrefixStabilityTest.compaction]。 */
        val compactions: List<Int>
        fun zone0(body: JsonObject): String
        /** 逐条消息(条目),去掉了缓存标记。 */
        fun messages(body: JsonObject): List<String>
        /** 逐个工具的原样字节与名字。 */
        fun tools(body: JsonObject): List<Pair<String, String>>
        fun call(step: Int, name: String, args: JsonObject, promptTokens: Int): MockResponse
        fun answer(promptTokens: Int): MockResponse
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun sse(vararg data: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(data.joinToString("") { "data: $it\n\n" })

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun strip(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterKeys { it != "cache_control" }.mapValues { strip(it.value) })
        is JsonArray -> JsonArray(e.map(::strip))
        else -> e
    }

    private val responses = object : Api {
        override fun model(url: String, vision: Boolean, contextTokens: Int) = ResponsesModel(
            "$url/v1", BearerToken.fixed("k"), "m", Capabilities(true, vision, contextTokens, true), OkHttpClient(), reasoningEffort = "medium",
        )
        override val keys = setOf("instructions", "tools", "input")
        override val replay = "ENC"
        override val compactions = listOf(12, 21, 30)
        override fun zone0(body: JsonObject) = body["instructions"].toString() + body["tools"]
        override fun messages(body: JsonObject) = body["input"]!!.jsonArray.map { it.toString() }
        override fun tools(body: JsonObject) = body["tools"]!!.jsonArray.map { it.toString() to it.jsonObject.str("name")!! }
        override fun call(step: Int, name: String, args: JsonObject, promptTokens: Int): MockResponse {
            val call = buildJsonObject {
                put("type", "function_call"); put("id", "fc_$step"); put("call_id", "call_$step"); put("name", name); put("arguments", args.toString())
            }
            return sse(
                """{"type":"response.output_item.done","item":{"type":"reasoning","id":"rs_$step","summary":[],"encrypted_content":"ENC$step"}}""",
                """{"type":"response.output_item.done","item":$call}""",
                completed(promptTokens),
            )
        }
        override fun answer(promptTokens: Int) = sse(
            """{"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"看完了"}]}}""",
            completed(promptTokens),
        )
        private fun completed(promptTokens: Int) =
            """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":$promptTokens,"output_tokens":10}}}"""
    }

    private val anthropic = object : Api {
        override fun model(url: String, vision: Boolean, contextTokens: Int) =
            AnthropicModel(url, "k", "m", Capabilities(true, vision, contextTokens, true), OkHttpClient(), reasoningEffort = "medium")
        override val keys = setOf("system", "tools", "messages")
        override val replay = "SIG"
        override val compactions = listOf(12, 21, 30)
        override fun zone0(body: JsonObject) = strip(body["system"]!!).toString() + strip(body["tools"]!!)
        override fun messages(body: JsonObject) = body["messages"]!!.jsonArray.map { strip(it).toString() }
        override fun tools(body: JsonObject) = body["tools"]!!.jsonArray.map { strip(it).toString() to it.jsonObject.str("name")!! }
        override fun call(step: Int, name: String, args: JsonObject, promptTokens: Int) = json(
            """{"content":[{"type":"thinking","thinking":"想第${step}步","signature":"SIG$step"},""" +
                """{"type":"tool_use","id":"tu_$step","name":"$name","input":$args}],""" +
                """"stop_reason":"tool_use","usage":{"input_tokens":$promptTokens,"output_tokens":10}}""",
        )
        override fun answer(promptTokens: Int) = json(
            """{"content":[{"type":"text","text":"看完了"}],"stop_reason":"end_turn","usage":{"input_tokens":$promptTokens,"output_tokens":10}}""",
        )
    }

    private val gemini = object : Api {
        override fun model(url: String, vision: Boolean, contextTokens: Int) =
            GeminiModel(url, "k", "m", Capabilities(true, vision, contextTokens, true), OkHttpClient(), reasoningEffort = "medium")
        override val keys = setOf("systemInstruction", "tools", "contents")
        override val replay = "TS"
        // 请求字节比另外三种略少,离触发点只差一点:提示词或工具说明长短一变,越过触发点的那一步就可能和另外三种差一步
        // (曾经是 13、24);现在和另外三种同一步越过
        override val compactions = listOf(12, 21, 30)
        override fun zone0(body: JsonObject) = body["systemInstruction"].toString() + body["tools"]
        override fun messages(body: JsonObject) = body["contents"]!!.jsonArray.map { it.toString() }
        override fun tools(body: JsonObject) = body["tools"]!!.jsonArray.flatMap { t ->
            t.jsonObject["functionDeclarations"]!!.jsonArray.map { it.toString() to it.jsonObject.str("name")!! }
        }
        override fun call(step: Int, name: String, args: JsonObject, promptTokens: Int) =
            reply("""{"functionCall":{"name":"$name","args":$args},"thoughtSignature":"TS$step"}""", promptTokens)
        override fun answer(promptTokens: Int) = reply("""{"text":"看完了"}""", promptTokens)
        private fun reply(part: String, promptTokens: Int) = json(
            """{"candidates":[{"content":{"role":"model","parts":[$part]},"finishReason":"STOP"}],""" +
                """"usageMetadata":{"promptTokenCount":$promptTokens,"candidatesTokenCount":10}}""",
        )
    }

    /** OpenAI 兼容接口里要标缓存的那种(部分服务商的兼容接口如此):每次把标记挪到最后一条。 */
    private val explicitCompat = object : Api {
        override fun model(url: String, vision: Boolean, contextTokens: Int) = OpenAiCompatModel(
            "$url/v1", "k", "m", Capabilities(true, vision, contextTokens, false, promptCaching = PromptCaching.Explicit), OkHttpClient(),
        )
        override val keys = setOf("messages", "tools")
        override val replay = null
        override val compactions = listOf(12, 21, 30)
        override fun zone0(body: JsonObject) = strip(body["messages"]!!.jsonArray.first()).toString() + body["tools"]
        override fun messages(body: JsonObject) = body["messages"]!!.jsonArray.drop(1).map { plain(strip(it).jsonObject).toString() }
        override fun tools(body: JsonObject) =
            body["tools"]!!.jsonArray.map { it.toString() to it.jsonObject["function"]!!.jsonObject.str("name")!! }

        /**
         * 标记时字符串内容换成一段文字块,不再是最后一条时又是字符串:比对时当成同一条。依据是某服务商兼容接口的
         * 上下文缓存文档(2026-11-01 查的)的例子:第一次以数组带标记发 system 消息,
         * 第二次同样的内容以字符串发,照样命中;文档只举了 system 消息,别的角色同样处理是推断。
         */
        private fun plain(m: JsonObject): JsonObject {
            val block = (m["content"] as? JsonArray)?.singleOrNull() as? JsonObject ?: return m
            if (block.keys != setOf("type", "text") || block.str("type") != "text") return m
            return JsonObject(m + ("content" to block["text"]!!))
        }
        override fun call(step: Int, name: String, args: JsonObject, promptTokens: Int) = json(
            """{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"call_$step","type":"function",""" +
                """"function":{"name":"$name","arguments":${JsonPrimitive(args.toString())}}}]}}],""" +
                """"usage":{"prompt_tokens":$promptTokens,"completion_tokens":10}}""",
        )
        override fun answer(promptTokens: Int) =
            json("""{"choices":[{"finish_reason":"stop","message":{"content":"看完了"}}],"usage":{"prompt_tokens":$promptTokens,"completion_tokens":10}}""")
    }

    /** 跑一遍 [PrefixStabilityTest] 的假任务,返回服务端收到的请求。 */
    private fun run(api: Api, vision: Boolean = false, contextTokens: Int = 1_000_000): List<JsonObject> {
        val bodies = CopyOnWriteArrayList<JsonObject>()
        val server = MockWebServer().also { servers += it }.apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val raw = request.body.readUtf8()
                    val body = Json.parseToJsonElement(raw).jsonObject
                    bodies += body
                    val msgs = api.messages(body)
                    val step = STEP.findAll(msgs.last()).last().groupValues[1].toInt()
                    val tokens = TokenEstimate.of(raw)
                    if (step > STEPS) return api.answer(tokens)
                    val (name, args) = when {
                        step % 3 == 1 -> "phone_observe" to buildJsonObject {}
                        step % 3 == 2 -> lastRead(msgs.joinToString("")).let { (ref, snap) ->
                            "phone_tap" to buildJsonObject { put("ref", ref); put("snapshotId", snap) }
                        }
                        else -> "run_command" to buildJsonObject { put("command", "cat part$step.txt") }
                    }
                    return api.call(step, name, args, tokens)
                }
            }
            start()
        }
        val model = api.model(server.url("/").toString().trimEnd('/'), vision, contextTokens)
        val screens = (1..40).map { n -> Screen("com.x", (1..12).map { label("@e$it", "第${n}屏第${it}项$pad", 100 + it * 50) }) }
        val scripted = ScriptedScreen(screens)
        val port: ScreenPort = if (!vision) scripted else object : ScreenPort by scripted {
            override suspend fun screenshotPng() = byteArrayOf(1, 2, 3)
        }
        val sb = FakeSandbox().apply { next = { r -> CommandResult(0, false, false, 10, "输出${r.argv}" + "行".repeat(6000), 0, false) } }
        runTest {
            AgentLoop(model, toolDispatcher(port, sandbox = sb, workspace = { "w" }, visionEnabled = vision),
                SystemPrompt.identity(), STEPS + 5, clock = { 0L }, zone = ZoneOffset.UTC,
            ).run("把每一屏都看一遍").also { assertTrue("$it", it is TaskState.Succeeded) }
        }
        return bodies.toList()
    }

    /** 最近一次读屏里第一个元素的编号与读屏编号;结果在各家的线上格式里转义层数不同,按转义不限地找。 */
    private fun lastRead(text: String): Pair<String, String> {
        val at = ELEMENTS.findAll(text).last().range.first
        return REF.find(text, at)!!.groupValues[1] to SNAPSHOT.findAll(text.substring(0, at)).last().groupValues[1]
    }

    private val entries = setOf(
        "phone_launch_app", "phone_list_apps", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event", "phone_calendar_bulk_insert", "web_navigate", "web_read_page", "run_command",
    )
    private val markers = listOf(
        HistoryPruner.OMITTED, HistoryPruner.OMITTED_READ, HistoryPruner.olderImage(0).substringBefore(";"), HistoryPruner.OMITTED_IMAGE, ContextCompressor.HEADER,
    )

    /**
     * 两条消息里第一处不同:内容是块(或 part)的列表时细到块。同一角色的连续消息并成一条的接口(Anthropic、Gemini)
     * 把摘要并进开头那条用户消息,那条也装着开局上下文;细到块才分得清改的是摘要还是开局上下文。
     */
    private fun firstDiff(old: String, new: String): Pair<String, String> {
        val (a, b) = Json.parseToJsonElement(old).jsonObject to Json.parseToJsonElement(new).jsonObject
        val key = listOf("content", "parts").firstOrNull { a[it] is JsonArray && b[it] is JsonArray } ?: return old to new
        val (x, y) = a[key]!!.jsonArray to b[key]!!.jsonArray
        val same = x.zip(y).takeWhile { (p, q) -> p == q }.size
        return x.getOrNull(same).toString() to y.getOrNull(same).toString()
    }

    private class Result(val changed: List<Int>, val share: Double, val opened: List<Int>)

    /** 同 [PrefixStabilityTest] 的几条;[appendOnly]:没越过触发点,一次也不该改写。 */
    private fun check(api: Api, bodies: List<JsonObject>, label: String, appendOnly: Boolean = true): Result {
        val zone0 = bodies.map(api::zone0)
        val msgs = bodies.map(api::messages)
        val opened = (1 until bodies.size).filter { zone0[it] != zone0[it - 1] }
        assertTrue("$label:区 0 变了 $opened 次", opened.size <= 2)
        for (i in opened) {
            val (a, b) = api.tools(bodies[i - 1]) to api.tools(bodies[i])
            val same = a.zip(b).takeWhile { (x, y) -> x.first == y.first }.size
            assertTrue("$label 第 ${i + 1} 次请求:展开时改了一组前面的工具 ${a.getOrNull(same)?.second}", same == a.size || a[same].second in entries)
            assertTrue("$label 第 ${i + 1} 次请求:展开只加不减", b.size > a.size)
        }
        val changed = mutableListOf<Int>()
        for (i in 1 until bodies.size) {
            val (a, b) = msgs[i - 1] to msgs[i]
            val same = a.zip(b).takeWhile { (x, y) -> x == y }.size
            if (same == a.size) continue
            changed += i
            // 第一处改写的就是被裁掉的那条,或者摘要本身(再压一次时摘要换成新的)
            val (old, new) = firstDiff(a[same], b[same])
            assertTrue("$label 第 ${i + 1} 次请求第 $same 条改写不是压缩:${new.take(300)}",
                markers.any { it in new && it !in old } || ContextCompressor.HEADER in new)
        }
        if (appendOnly) assertEquals("$label:没越过触发点却改写了前缀", emptyList<Int>(), changed)
        val rest = bodies.map { b -> JsonObject(b.filterKeys { it !in api.keys }) }.distinct()
        assertEquals("$label:区 0 和消息以外的字段变了", 1, rest.size)
        val full = bodies.indices.map { zone0[it] + msgs[it].joinToString("") }
        val pairs = full.zipWithNext().withIndex().filter { (i, _) -> i + 1 !in opened }.map { it.value }
        val share = pairs.sumOf { (a, b) -> a.commonPrefixWith(b).length.toLong() }.toDouble() / pairs.sumOf { (_, b) -> b.length.toLong() }
        return Result(changed, share, opened)
    }

    /** 最后一次请求带齐了 30 步的回放:前后两次一致地都不带,上面几条查不出来。 */
    private fun replaysAll(api: Api, bodies: List<JsonObject>, label: String) {
        val prefix = api.replay ?: return
        val last = bodies.last().toString()
        val missing = (1..STEPS).filter { "\"$prefix$it\"" !in last }
        assertEquals("$label:最后一次请求缺了这几步的回放", emptyList<Int>(), missing)
    }

    // 读屏、大结果(和画面):30 步里一次也不改写,区 0 只在第一次用手机、第一次用沙箱时展开
    private fun appendOnly(api: Api, label: String) {
        for (vision in listOf(false, true)) {
            val l = if (vision) "$label vision" else label
            val bodies = run(api, vision = vision)
            val r = check(api, bodies, l)
            assertEquals(l, listOf(1, 3), r.opened)
            assertTrue("$l ${r.share}", r.share > 0.95)
            replaysAll(api, bodies, l)
        }
    }

    // 80K 窗口:只在压缩的那几次改写,序号和 [PrefixStabilityTest.compaction] 的算法一样(Gemini 见它的 compactions)
    private fun compaction(api: Api, label: String) {
        val bodies = run(api, contextTokens = 80_000)
        val r = check(api, bodies, "$label compaction", appendOnly = false)
        assertTrue(bodies.any { ContextCompressor.HEADER in it.toString() })
        assertEquals(api.compactions, r.changed)
        assertTrue("${r.share}", r.share > 0.85)
    }

    @Test fun responsesAppendOnly() = appendOnly(responses, "responses")
    @Test fun responsesCompaction() = compaction(responses, "responses")
    @Test fun anthropicAppendOnly() = appendOnly(anthropic, "anthropic")
    @Test fun anthropicCompaction() = compaction(anthropic, "anthropic")
    @Test fun geminiAppendOnly() = appendOnly(gemini, "gemini")
    @Test fun geminiCompaction() = compaction(gemini, "gemini")
    @Test fun explicitCompatAppendOnly() = appendOnly(explicitCompat, "compat explicit")
    @Test fun explicitCompatCompaction() = compaction(explicitCompat, "compat explicit")

    // 系统提示词、最后一个工具、最后一条用户消息各一个;Anthropic 一次请求最多 4 个,多了整次请求被拒(400)
    @Test fun anthropicCacheMarksStayWithinLimit() {
        for (body in run(anthropic, contextTokens = 80_000)) {
            assertEquals("缓存标记个数", 3, CACHE_MARK.findAll(body.toString()).count())
        }
    }

    private companion object {
        const val STEPS = 30
        val STEP = Regex("Step: (\\d+) of at most")
        val ELEMENTS = Regex("""elements\\*":\[\{""")
        val REF = Regex("""ref\\*":\\*"([^"\\]+)""")
        val SNAPSHOT = Regex("""snapshotId\\*":\\*"([^"\\]+)""")
        val CACHE_MARK = Regex("\"cache_control\"")
    }
}
