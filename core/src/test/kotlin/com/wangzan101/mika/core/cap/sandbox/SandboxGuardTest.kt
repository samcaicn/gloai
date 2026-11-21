package com.wangzan101.mika.core.cap.sandbox

import com.wangzan101.mika.core.model.Part
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxGuardTest {
    private fun ran(durationMs: Long): JsonObject = buildJsonObject {
        put("ok", true); put("durationMs", durationMs)
    }

    private fun args(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*pairs))

    private fun call(id: String, vararg pairs: Pair<String, JsonElement>): Part.ToolCall =
        Part.ToolCall(id, RunCommand.name, args(*pairs).toString())

    @Test fun defaultBudgetThirtyMinutes() {
        val g = SandboxGuard()
        g.result(RunCommand.name, args("command" to JsonPrimitive("ls")), ran(SANDBOX_BUDGET_MS - 1))
        assertNull(g.exhausted(RunCommand.name))
        g.result(RunCommand.name, args("command" to JsonPrimitive("ls")), ran(1))
        val e = g.exhausted(RunCommand.name)!!
        assertEquals("sandbox_budget_exhausted", (e["error"] as JsonPrimitive).content)
        assertEquals(SANDBOX_BUDGET_MS, (e["usedMs"] as JsonPrimitive).longOrNull)
        assertTrue((e["message"] as JsonPrimitive).content.contains("their 30 minutes"))
    }

    @Test fun customBudgetChangesThresholdAndMessage() {
        val g = SandboxGuard(budgetMs = 5 * 60_000L)
        g.result(RunCommand.name, args("command" to JsonPrimitive("ls")), ran(4 * 60_000L))
        assertNull(g.exhausted(RunCommand.name))
        g.result(RunCommand.name, args("command" to JsonPrimitive("ls")), ran(60_000L))
        val e = g.exhausted(RunCommand.name)!!
        assertTrue((e["message"] as JsonPrimitive).content.contains("their 5 minutes"))
    }

    @Test fun otherToolsDoNotAccumulateOrExhaust() {
        val g = SandboxGuard(budgetMs = 60_000L)
        g.result("web_read", args(), ran(10 * 60_000L))
        assertNull(g.exhausted(RunCommand.name))
        assertNull(g.exhausted("web_read"))
    }

    @Test fun capTimeoutClampsToRemainingBudget() {
        val g = SandboxGuard(budgetMs = 5 * 60_000L)
        g.result(RunCommand.name, args("command" to JsonPrimitive("ls")), ran(2 * 60_000L))
        val capped = g.capTimeout(call("c1", "command" to JsonPrimitive("make"), "timeoutSec" to JsonPrimitive(600)),
            args("command" to JsonPrimitive("make"), "timeoutSec" to JsonPrimitive(600)))
        val sec = Json.parseToJsonElement(capped.argsJson).jsonObject["timeoutSec"] as JsonPrimitive
        assertEquals(180L, sec.longOrNull)
    }

    @Test fun capTimeoutKeepsRequestsInsideBudgetAndOtherTools() {
        val g = SandboxGuard(budgetMs = 5 * 60_000L)
        val inside = call("c1", "command" to JsonPrimitive("make"), "timeoutSec" to JsonPrimitive(120))
        assertEquals(inside, g.capTimeout(inside, Json.parseToJsonElement(inside.argsJson).jsonObject))
        // 不带 timeoutSec 的按默认 60 秒算,仍在剩余时间内,原样放行
        val noTimeout = call("c2", "command" to JsonPrimitive("ls"))
        assertEquals(noTimeout, g.capTimeout(noTimeout, Json.parseToJsonElement(noTimeout.argsJson).jsonObject))
        val other = Part.ToolCall("c3", "web_read", args("url" to JsonPrimitive("x"), "timeoutSec" to JsonPrimitive(900)).toString())
        assertEquals(other, g.capTimeout(other, Json.parseToJsonElement(other.argsJson).jsonObject))
    }
}
