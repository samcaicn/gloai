package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.Risk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginScriptToolTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeRunner(var result: ScriptResult) : ScriptRunner {
        var script: File? = null
        var argsContent: String? = null
        var argsPath: String? = null
        override suspend fun run(script: File, argsFile: File, env: Map<String, String>, timeoutMs: Long): ScriptResult {
            this.script = script
            this.argsContent = argsFile.readText()
            this.argsPath = argsFile.absolutePath
            return result
        }
    }

    private fun spec(name: String = "plugin_mika_translate_translate", risk: Risk = Risk.Read) = PluginToolSpec(
        name = name,
        description = "translate text",
        scriptPath = "scripts/translate.sh",
        scriptDir = tmp.root,
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("text", buildJsonObject { put("type", "string"); put("description", "text to translate") })
            })
            put("required", JsonArray(listOf(JsonPrimitive("text"))))
        },
        timeoutSec = 30,
        risk = risk,
    )

    private fun call(name: String, args: String) = Part.ToolCall("c1", name, args)

    @Test fun executesScriptAndParsesJsonResult() = runTest {
        val runner = FakeRunner(ScriptResult(0, """{"ok":true,"translation":"你好"}""", "", false))
        val d = toolDispatcher(FakeScreen(), pluginTools = listOf(spec()), pluginScriptRunner = runner)
        val r = d.execute(call("plugin_mika_translate_translate", """{"text":"hi"}"""), ActionPass()).content
        assertEquals("你好", (r["translation"] as JsonPrimitive).content)
        assertEquals("true", (r["ok"] as JsonPrimitive).content)
    }

    @Test fun argsWrittenToFileAndPassed() = runTest {
        val runner = FakeRunner(ScriptResult(0, """{"ok":true}""", "", false))
        val d = toolDispatcher(FakeScreen(), pluginTools = listOf(spec()), pluginScriptRunner = runner)
        d.execute(call("plugin_mika_translate_translate", """{"text":"hi"}"""), ActionPass())
        val parsed = Json.parseToJsonElement(runner.argsContent!!) as JsonObject
        assertEquals("hi", parsed["text"]!!.jsonPrimitive.content)
        assertEquals("translate.sh", runner.script!!.name)
    }

    @Test fun argsFileWrittenToProvidedDir() = runTest {
        val runner = FakeRunner(ScriptResult(0, """{"ok":true}""", "", false))
        val d = toolDispatcher(FakeScreen(), pluginTools = listOf(spec()), pluginScriptRunner = runner, pluginArgsDir = tmp.root)
        d.execute(call("plugin_mika_translate_translate", """{"text":"hi"}"""), ActionPass())
        assertTrue(runner.argsPath!!.startsWith(tmp.root.absolutePath + File.separator))
    }

    @Test fun nonJsonStdoutIsCleanFailure() = runTest {
        val runner = FakeRunner(ScriptResult(0, "garbage", "", false))
        val d = toolDispatcher(FakeScreen(), pluginTools = listOf(spec()), pluginScriptRunner = runner)
        val r = d.execute(call("plugin_mika_translate_translate", """{"text":"hi"}"""), ActionPass()).content
        assertEquals("false", (r["ok"] as JsonPrimitive).content)
        assertEquals("bad_output", (r["error"] as JsonPrimitive).content)
    }

    @Test fun timeoutReported() = runTest {
        val runner = FakeRunner(ScriptResult(124, "", "", timedOut = true))
        val d = toolDispatcher(FakeScreen(), pluginTools = listOf(spec()), pluginScriptRunner = runner)
        val r = d.execute(call("plugin_mika_translate_translate", """{"text":"hi"}"""), ActionPass()).content
        assertEquals("false", (r["ok"] as JsonPrimitive).content)
        assertEquals("timed_out", (r["error"] as JsonPrimitive).content)
    }

    @Test fun toolNamePrefixedAndOffered() = runTest {
        val runner = FakeRunner(ScriptResult(0, """{"ok":true}""", "", false))
        val d = toolDispatcher(FakeScreen(), pluginTools = listOf(spec()), pluginScriptRunner = runner)
        assertTrue(d.definitions().any { it.name == "plugin_mika_translate_translate" })
    }
}
