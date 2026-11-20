package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.mcp.FakeRemoteTasks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CompanionApiTest {
    private class FakeCompanion : RemoteCompanion {
        var lastListLimit = 0
        var lastPage: Triple<String, Int, Int>? = null
        override fun conversations(limit: Int): List<RemoteConversation> {
            lastListLimit = limit
            return listOf(
                RemoteConversation("c1", "第一个对话", 1_700_000_000_000, 1_700_000_001_000, 3),
                RemoteConversation("c2", "第二个对话", 1_600_000_000_000, turnCount = 1),
            ).take(limit)
        }

        override fun conversation(id: String, from: Int, limit: Int): RemoteConversationPage? {
            lastPage = Triple(id, from, limit)
            if (id != "c1") return null
            val all = listOf(
                RemoteTurn(0, "你好", "你好，有什么可以帮你？", "succeeded", runId = "r1"),
                RemoteTurn(1, "第二个问题", "回答二", "unverified", note = "没能核实", steers = listOf("插一句")),
                RemoteTurn(2, "第三个问题", "回答三", "failed"),
            )
            val start = if (from < 0) (all.size - limit).coerceAtLeast(0) else from
            return RemoteConversationPage(id, "第一个对话", all.size, start, all.drop(start).take(limit))
        }

        override fun running(): List<RemoteRunning> = listOf(
            RemoteRunning("c1", "帮我订一张票", RemoteStatus.RUNNING, runId = "r9", step = 4, progress = "正在填写", truncated = false),
            RemoteRunning("c3", "回答问题", RemoteStatus.WAITING_FOR_ANSWER, runId = "r8", question = "选哪个？", options = listOf("A", "B"), needsYou = true),
        )
    }

    private val companion = FakeCompanion()
    private val tasks = FakeRemoteTasks()
    private val endpoints = RemoteEndpoints(tasks, "test", companion = companion)
    private val host = RemoteHost(tasks, { TOKEN }, 0, false, "test", endpoints = endpoints)
    private val client = OkHttpClient()

    @Before fun start() = host.start()
    @After fun stop() {
        host.stop()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun get(path: String, token: String? = TOKEN): Pair<Int, JsonObject> {
        val builder = Request.Builder().url("http://127.0.0.1:${host.boundPort}$path").get()
        token?.let { builder.header("Authorization", "Bearer $it") }
        return client.newCall(builder.build()).execute().use { r ->
            r.code to Json.parseToJsonElement(r.body!!.string()).jsonObject
        }
    }

    @Test fun rootAdvertisesConsoleCapabilities() {
        val caps = get("/api/v1").second["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("conversations" in caps)
        assertTrue("running" in caps)
    }

    @Test fun conversationsAreListedWithLimit() {
        val (code, body) = get("/api/v1/conversations?limit=1")
        assertEquals(200, code)
        val list = body["conversations"]!!.jsonArray
        assertEquals(1, list.size)
        assertEquals(1, companion.lastListLimit)
        val first = list[0].jsonObject
        assertEquals("c1", first["conversation"]!!.jsonPrimitive.content)
        assertEquals("第一个对话", first["title"]!!.jsonPrimitive.content)
        assertEquals(1_700_000_000_000, first["updated_at_ms"]!!.jsonPrimitive.content.toLong())
        assertEquals(1_700_000_001_000, first["pinned_at_ms"]!!.jsonPrimitive.content.toLong())
        assertEquals(3, first["turn_count"]!!.jsonPrimitive.int)
    }

    @Test fun turnsArePaged() {
        val (code, body) = get("/api/v1/conversations/c1?from=1&limit=1")
        assertEquals(200, code)
        assertEquals(Triple("c1", 1, 1), companion.lastPage)
        assertEquals(3, body["total"]!!.jsonPrimitive.int)
        assertEquals(1, body["from"]!!.jsonPrimitive.int)
        val turns = body["turns"]!!.jsonArray
        assertEquals(1, turns.size)
        val turn = turns[0].jsonObject
        assertEquals(1, turn["index"]!!.jsonPrimitive.int)
        assertEquals("第二个问题", turn["user_text"]!!.jsonPrimitive.content)
        assertEquals("unverified", turn["status"]!!.jsonPrimitive.content)
        assertEquals("没能核实", turn["note"]!!.jsonPrimitive.content)
        assertEquals("插一句", turn["steers"]!!.jsonArray[0].jsonPrimitive.content)
        assertFalse(turn["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun omittedFromReturnsTheLastPage() {
        val (code, body) = get("/api/v1/conversations/c1?limit=2")
        assertEquals(200, code)
        assertEquals(1, body["from"]!!.jsonPrimitive.int)
        assertEquals(listOf(1, 2), body["turns"]!!.jsonArray.map { it.jsonObject["index"]!!.jsonPrimitive.int })
        assertEquals(-1, companion.lastPage!!.second)
    }

    @Test fun unknownConversationIs404AndBadQueryIs400() {
        assertEquals(404, get("/api/v1/conversations/nope").first)
        assertEquals(400, get("/api/v1/conversations?limit=0").first)
        assertEquals(400, get("/api/v1/conversations?limit=999").first)
        assertEquals(400, get("/api/v1/conversations/c1?from=-1").first)
        assertEquals(400, get("/api/v1/conversations/c1?offset=1").first)
        assertEquals(400, get("/api/v1/running?limit=1").first)
    }

    @Test fun runningTasksCarryQuestionAndPhoneNote() {
        val (code, body) = get("/api/v1/running")
        assertEquals(200, code)
        val running = body["running"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, running.size)
        assertEquals("running", running[0]["status"]!!.jsonPrimitive.content)
        assertEquals(4, running[0]["step"]!!.jsonPrimitive.int)
        assertEquals("正在填写", running[0]["progress"]!!.jsonPrimitive.content)
        assertEquals("waiting_for_answer", running[1]["status"]!!.jsonPrimitive.content)
        assertEquals("选哪个？", running[1]["question"]!!.jsonPrimitive.content)
        assertEquals("A", running[1]["options"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue(running[1]["needs_you"]!!.jsonPrimitive.boolean)
    }

    @Test fun consoleEndpointsStillNeedTheToken() {
        assertEquals(401, get("/api/v1/conversations", token = null).first)
        assertEquals(401, get("/api/v1/conversations", token = "wrong").first)
        assertEquals(401, get("/api/v1/running", token = null).first)
    }

    @Test fun methodAndPathAreChecked() {
        val request = Request.Builder().url("http://127.0.0.1:${host.boundPort}/api/v1/conversations")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $TOKEN")
            .header("Content-Type", "application/json")
            .build()
        client.newCall(request).execute().use { assertEquals(405, it.code) }
        assertEquals(404, get("/api/v1/conversations/c1/extra").first)
        assertEquals(404, get("/api/v1/conversations/a/b").first)
    }

    @Test fun textClippingKeepsSurrogatePairsWhole() {
        val plain = RemoteText.clip("abc", 5)
        assertEquals("abc", plain.text)
        assertFalse(plain.truncated)
        val clipped = RemoteText.clip("abcdef", 3)
        assertEquals("abc…", clipped.text)
        assertTrue(clipped.truncated)
        val emoji = "😀😀😀"
        val half = RemoteText.clip(emoji, 3)
        assertEquals("😀…", half.text)
        assertTrue(half.truncated)
    }

    @Test fun runningViewMapsStates() {
        val running = RemoteRunningView.of("c1", "做一件事", com.wangzan101.mika.core.agent.TaskState.Running(3, "note", narration = "正在填写"), "r1")
        assertEquals("running", running.status)
        assertEquals(3, running.step)
        assertEquals("正在填写", running.progress)
        assertFalse(running.needsYou)

        val queued = RemoteRunningView.of("c2", "排队", com.wangzan101.mika.core.agent.TaskState.Held(com.wangzan101.mika.core.agent.GateReason.queued("前面还有任务")))
        assertEquals("queued", queued.status)
        assertEquals("前面还有任务", queued.progress)
        assertFalse(queued.needsYou)

        val answer = RemoteRunningView.of("c3", "问一句", com.wangzan101.mika.core.agent.TaskState.WaitingInput("选哪个？", listOf("A", "B")))
        assertEquals("waiting_for_answer", answer.status)
        assertEquals("选哪个？", answer.question)
        assertEquals(listOf("A", "B"), answer.options)
        assertTrue(answer.needsYou)

        val confirmation = RemoteRunningView.of("c4", "买一件", com.wangzan101.mika.core.agent.TaskState.WaitingConfirmation(
            com.wangzan101.mika.core.gate.ConfirmationRequest(com.wangzan101.mika.core.risk.IrrevAction.pay, "订单", "内容", "¥10", "购物 App")
        ))
        assertEquals("waiting_on_phone", confirmation.status)
        assertEquals("购物 App", confirmation.progress)
        assertTrue(confirmation.needsYou)

        val takeover = RemoteRunningView.of("c5", "付款", com.wangzan101.mika.core.agent.TaskState.WaitingTakeover("payment", "请你在手机上付款"))
        assertEquals("waiting_on_phone", takeover.status)
        assertEquals("请你在手机上付款", takeover.progress)
        assertTrue(takeover.needsYou)
    }

    @Test fun runningViewClipsLongGoal() {
        val view = RemoteRunningView.of("c1", "长".repeat(5_000), com.wangzan101.mika.core.agent.TaskState.Running(0, "note"))
        assertTrue(view.truncated)
        assertTrue(view.goal.endsWith("…"))
        assertEquals(RemoteCompanion.MAX_GOAL + 1, view.goal.length)
    }

    private companion object {
        const val TOKEN = "test-token"
    }
}
