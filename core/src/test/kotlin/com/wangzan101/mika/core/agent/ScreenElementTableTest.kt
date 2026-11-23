package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.testing.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScreenElementTableTest {
    private fun unpack(screen: JsonObject): JsonArray = buildJsonArray {
        screen.getValue("elementTables").jsonArray.forEach { block ->
            val columns = block.jsonObject.getValue("columns").jsonArray.map { it.jsonPrimitive.content }
            block.jsonObject.getValue("rows").jsonArray.forEach { row ->
                add(JsonObject(columns.zip(row.jsonArray).toMap()))
            }
        }
    }

    @Test fun fullRowsRoundTripWithoutChangingTreeMetadataOrDiffs() {
        val rows = buildJsonArray {
            repeat(24) { i -> addJsonObject {
                if (i == 3) put("name", "酒店$i")
                put("ref", "@e$i"); if (i != 3) put("name", "酒店$i")
                put("bounds", JsonArray(listOf(JsonPrimitive(i))))
                if (i in 4..13) put("hint", JsonNull)
                if (i > 13) put("enabled", false)
            } }
        }
        val screen = buildJsonObject { put("snapshotId","s2"); put("tree","exact tree"); put("elements",rows) }
        fun wrap(s: JsonObject) = buildJsonObject { put("screen",s); put("obsIds", JsonArray(listOf(JsonPrimitive(17)))) }
        val raw = wrap(screen)
        val packed = Json.parseToJsonElement(ScreenElementTable.text(raw, true)).jsonObject
        val inner = packed.getValue("screen").jsonObject
        assertTrue("elementTables" in inner); assertFalse("elements" in inner)
        assertEquals(rows, unpack(inner))
        assertEquals(rows.toString(), unpack(inner).toString())
        assertEquals(screen - "elements", inner - "elementTables")
        assertEquals(raw["obsIds"], packed["obsIds"])
        assertEquals(raw.toString(), ScreenElementTable.text(raw, false))
        assertTrue(TokenEstimate.of(packed.toString()) < TokenEstimate.of(raw.toString()))
        val error = JsonObject(raw + ("ok" to JsonPrimitive(false)))
        assertEquals(error.toString(), ScreenElementTable.text(error, true))
        val small = JsonObject(screen + ("elements" to JsonArray(rows.take(1))))
        assertEquals(small.toString(), ScreenElementTable.text(small, true))
        val noSavings = buildJsonObject {
            put("snapshotId", "s1"); put("tree", "x".repeat(500))
            put("elements", JsonArray(List(3) { buildJsonObject { put("x", it) } }))
        }
        assertEquals(noSavings.toString(), ScreenElementTable.text(noSavings, true))
        for (field in listOf("base" to JsonPrimitive("s1"), "full" to JsonPrimitive(false))) {
            val diff = wrap(JsonObject(screen + field)).toString()
            assertEquals(diff, ScreenElementTable.text(Json.parseToJsonElement(diff).jsonObject, true))
        }
    }

    @Test fun firstObservationSupportsRefTapAndRawSourceRemainsInRecord() = runTest {
        val original = observeAndTap(false)
        val packed = observeAndTap(true)
        assertTrue(TokenEstimate.of(packed) < TokenEstimate.of(original))
    }

    private suspend fun observeAndTap(enabled: Boolean): String {
        val screen = ScriptedScreen(listOf(Screen("com.example.fixture", (1..14).map { button("@e$it","选项$it") })))
        val dispatcher = toolDispatcher(screen)
        val events = mutableListOf<RunEvent>()
        val views = mutableListOf<Part.ToolResult>()
        var step = 0
        val model = object : ChatModel by FakeModel() {
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                val result = messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().firstOrNull { it.callId == "read" }
                result?.let(views::add)
                val call = when (++step) {
                    1 -> Part.ToolCall("read", "phone_observe", "{}")
                    2 -> {
                        val s = Json.parseToJsonElement(result!!.content).jsonObject
                        assertEquals(enabled, "elementTables" in s); assertEquals(!enabled, "elements" in s)
                        val target = (if (enabled) unpack(s) else s.getValue("elements").jsonArray).single { it.jsonObject["name"] == JsonPrimitive("选项7") }.jsonObject
                        Part.ToolCall("tap", "phone_tap", buildJsonObject {
                            put("ref",target.getValue("ref")); put("snapshotId",s.getValue("snapshotId"))
                        }.toString())
                    }
                    else -> return answerReply("已选择")
                }
                return ModelReply(listOf(call), StopReason.ToolUse, null)
            }
        }
        val outcome = AgentLoop(model, dispatcher, "BASE", flags = SpeedFlags(tablesAtBirth = enabled),
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } }).run("选择选项7")
        assertTrue(outcome.toString(), outcome is TaskState.Succeeded)
        assertEquals(listOf("tap @e7 s1"), screen.calls)
        assertEquals(2, views.size); assertEquals(views.first().content, views.last().content)
        val recorded = events.filterIsInstance<RunEvent.ToolResult>().single { it.tool == "phone_observe" }
        assertTrue(recorded.resultJson.contains("\"elements\"")); assertFalse(recorded.resultJson.contains("elementTables"))
        assertTrue("s1" in visibleReads(listOf(Message(Role.Tool, listOf(views.first()), Author.Mika))))
        return views.first().content
    }
}
