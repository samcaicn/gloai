package com.wangzan101.mika.core.cap.mcp

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.ConfirmDecision
import com.wangzan101.mika.core.action.Decision
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.screen.observation.Trust
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.fakeModel
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientTest {
    private class FakeEndpoint : McpEndpoint {
        override val serverId = "demo"
        override var instructions: String? = null
        var offered = listOf(McpToolDescriptor("search", "Search", buildJsonObject { put("type", "object") }, McpAnnotations(readOnlyHint = true)))
        var result = McpResult("found")
        var calls = 0
        var offeredResources: List<McpResourceDescriptor> = emptyList()
        var offeredPrompts: List<McpPromptDescriptor> = emptyList()
        var resourcesError: Exception? = null
        var lastArguments: JsonObject? = null
        override fun tools() = offered
        override fun resources() = resourcesError?.let { throw it } ?: offeredResources
        override fun prompts() = offeredPrompts
        override suspend fun call(name: String, arguments: JsonObject): McpResult {
            calls++
            lastArguments = arguments
            return result
        }
        override suspend fun readResource(uri: String) = McpResult("resource:$uri")
        override suspend fun getPrompt(name: String, arguments: JsonObject) =
            McpResult("prompt:$name\n$arguments")
    }

    private fun endpointWithSchema(schema: String) = FakeEndpoint().apply {
        offered = listOf(McpToolDescriptor("search", "Search", Json.parseToJsonElement(schema).jsonObject))
    }

    private fun parameters(tools: ToolDispatcher): JsonObject = Json.parseToJsonElement(
        tools.definitions().single { it.name == "mcp_demo__search" }.parametersJsonSchema,
    ).jsonObject

    @Test fun serverInstructionsReachTheModelWithoutGrantingApproval() {
        val endpoint = FakeEndpoint().apply { instructions = "Use search before fetching a result." }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        val sections = tools.promptSections(tools.definitions()).joinToString("\n")
        assertTrue(sections, com.wangzan101.mika.core.screen.observation.Untrusted.wrap(endpoint.instructions!!) in sections)
        assertEquals(ActionRisk.Change, tools.approvalRisk("mcp_demo__search")?.level)
    }

    @Test fun blankInstructionsAddNoSectionAndLongInstructionsStayBounded() {
        val endpoint = FakeEndpoint()
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        val baseline = tools.promptSections(tools.definitions())
        endpoint.instructions = "  \n  "
        assertEquals(baseline, tools.promptSections(tools.definitions()))
        endpoint.instructions = "x".repeat(5000)
        val section = tools.promptSections(tools.definitions()).single { "MCP server guidance" in it }
        assertTrue(section, section.length < 4200)
        assertFalse(section, "x".repeat(4001) in section)
    }

    @Test fun nullPropertiesIsTreatedAsNoParameters() = runBlocking {
        val endpoint = endpointWithSchema("""{"type":"object","properties":null}""")
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        assertEquals(setOf("reason"), parameters(tools)["properties"]!!.jsonObject.keys)
        val result = tools.execute(Part.ToolCall("c", "mcp_demo__search", "{}"), ActionPass())
        assertEquals(JsonPrimitive(true), result.content["ok"])
        assertEquals(1, endpoint.calls)
    }

    @Test fun booleanPropertySchemaBecomesPermissiveObject() = runBlocking {
        val endpoint = endpointWithSchema(
            """{"type":"object","properties":{"q":true,"n":{"type":"number","minimum":0}},"required":["q"]}""",
        )
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        val properties = parameters(tools)["properties"]!!.jsonObject
        assertEquals(JsonObject(emptyMap()), properties["q"])
        assertEquals(Json.parseToJsonElement("""{"type":"number","minimum":0}"""), properties["n"])
        val result = tools.execute(Part.ToolCall("c", "mcp_demo__search", """{"q":42}"""), ActionPass())
        assertEquals(JsonPrimitive(true), result.content["ok"])
        assertEquals(1, endpoint.calls)
    }

    @Test fun malformedRequiredIsIgnoredAndNonStringEntriesAreDropped() {
        val scalar = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(
            endpointWithSchema("""{"type":"object","properties":{"q":{"type":"string"}},"required":"q"}"""),
        )))
        assertEquals(emptyList<String>(), parameters(scalar)["required"]!!.jsonArray.map { it.jsonPrimitive.content })

        val mixed = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(
            endpointWithSchema("""{"type":"object","properties":{"q":{},"n":{}},"required":["q",1,true,null,"n"]}"""),
        )))
        assertEquals(listOf("q", "n"), parameters(mixed)["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun wellFormedSchemaKeepsPropertyConstraintsAndRequired() {
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpointWithSchema(
            """{"type":"object","properties":{"q":{"type":"string","description":"Query","minLength":1,"enum":["a","b"],"default":"a"},"n":{"type":"integer","minimum":1}},"required":["q"],"additionalProperties":false}""",
        ))))
        val schema = parameters(tools)
        val properties = schema["properties"]!!.jsonObject
        assertEquals(Json.parseToJsonElement(
            """{"type":"string","description":"Query","minLength":1,"enum":["a","b"],"default":"a"}""",
        ), properties["q"])
        assertEquals(Json.parseToJsonElement("""{"type":"integer","minimum":1}"""), properties["n"])
        assertEquals(listOf("q"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun requiredParameterMissingFromPropertiesStaysCallable() = runBlocking {
        val endpoint = endpointWithSchema(
            """{"type":"object","properties":{"q":{"type":"string"}},"required":["missing"]}""",
        )
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        val schema = parameters(tools)
        // 缺席的 required 补成空的宽松 schema,Args 的 allowed 才会包含它
        assertEquals(JsonObject(emptyMap()), schema["properties"]!!.jsonObject["missing"])
        assertEquals(listOf("missing"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val result = tools.execute(Part.ToolCall("c", "mcp_demo__search", """{"missing":"x"}"""), ActionPass())
        assertEquals(JsonPrimitive(true), result.content["ok"])
        assertEquals(1, endpoint.calls)
    }

    @Test fun blankRequiredNamesAreDropped() = runBlocking {
        val endpoint = endpointWithSchema(
            """{"type":"object","properties":{"q":{"type":"string"}},"required":["","  ","q"]}""",
        )
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        assertEquals(listOf("q"), parameters(tools)["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val result = tools.execute(Part.ToolCall("c", "mcp_demo__search", """{"q":"x"}"""), ActionPass())
        assertEquals(JsonPrimitive(true), result.content["ok"])
        assertEquals(1, endpoint.calls)
    }

    @Test fun serverCallForwardsOnlyServerArgumentsAndKeepsAnExplicitServerReason() = runBlocking {
        // reason 是我们给工具统一加的用意说明;服务端自己声明了(属性或 required)才当服务端参数原样转发
        val query = Part.ToolCall("c", "mcp_demo__search", """{"q":"lights","reason":"check status"}""")
        for ((schema, expected) in listOf(
            """{"properties":{"q":{}}}""" to """{"q":"lights"}""",
            """{"properties":{"q":{},"reason":{}}}""" to query.argsJson,
            """{"properties":{"q":{}},"required":["reason"]}""" to query.argsJson,
        )) {
            val endpoint = endpointWithSchema(schema)
            val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
            assertEquals(JsonPrimitive(true), tools.execute(query, ActionPass()).content["ok"])
            assertEquals(Json.parseToJsonElement(expected), endpoint.lastArguments)
        }
    }

    @Test fun lookupStyleSectionOnlyAppearsWithMcpTools() {
        val withMcp = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(FakeEndpoint())))
        val sections = withMcp.promptSections(withMcp.definitions()).joinToString("\n")
        assertTrue(sections, "the largest batch" in sections)
        assertTrue(sections, "fullResult" in sections)
        val without = toolDispatcher(FakeScreen())
        val baseline = without.promptSections(without.definitions()).joinToString("\n")
        assertFalse("the largest batch" in baseline)
        assertFalse("fullResult" in baseline)
    }

    @Test fun showsConfiguredConnectorWhileTheRemoteCallRunsAndRecordsItsResult() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val endpoint = object : McpEndpoint {
            override val serverId = "internal-id"
            override fun tools() = listOf(McpToolDescriptor("search", "Search", buildJsonObject { put("type", "object") }))
            override suspend fun call(name: String, arguments: JsonObject): McpResult {
                started.complete(Unit)
                release.await()
                throw IllegalStateException("remote request failed")
            }
        }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(
            McpConnection(endpoint, trust = McpTrust.TrustAnnotations, displayName = "Team notes"),
        ))
        val name = tools.definitions().single { it.name.startsWith("mcp_") }.name
        val model = fakeModel {
            call(name)
            answer("Search failed.")
        }
        val loop = AgentLoop(model, tools, "BASE")
        val events = mutableListOf<RunEvent>()
        loop.events.subscribe(object : RunSink {
            override fun event(e: RunEvent) { events += e }
        })
        val job = async { loop.run("Search project notes.") }

        started.await()
        assertEquals("Team notes", (loop.state.value as TaskState.Running).mcpConnector)
        // 调用还在进行:这次调用还没记进运行事件
        assertTrue(events.filterIsInstance<RunEvent.Activity>().none { it.label == name })
        release.complete(Unit)
        job.await()

        val activity = events.filterIsInstance<RunEvent.Activity>().single { it.label == name }
        assertEquals("Team notes", activity.mcpConnector)
        assertTrue(activity.failed)
        assertFalse(loop.state.value is TaskState.Running)
    }

    @Test fun oneServerAssemblyFailureKeepsOtherCapabilitiesAndReportsCause() = runBlocking {
        val broken = object : McpEndpoint {
            override val serverId = "broken"
            override fun tools(): List<McpToolDescriptor> = throw IllegalStateException("catalog exploded")
            override suspend fun call(name: String, arguments: JsonObject) = McpResult("unused")
        }
        val healthy = FakeEndpoint()
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(broken), McpConnection(healthy)))
        val names = tools.definitions().map { it.name }
        assertTrue("phone_observe" in names)
        assertTrue("mcp_demo__search" in names)
        assertFalse(names.any { it.startsWith("mcp_broken__") })
        val diagnostics = tools.promptSections(tools.definitions()).joinToString("\n")
        assertTrue(diagnostics, "broken" in diagnostics)
        assertTrue(diagnostics, "catalog exploded" in diagnostics)
        assertTrue(diagnostics, "other capabilities remain available" in diagnostics)

        val result = tools.execute(Part.ToolCall("c", "mcp_demo__search", "{}"), ActionPass())
        assertEquals(JsonPrimitive(true), result.content["ok"])
        assertEquals(1, healthy.calls)
    }

    @Test fun oneToolAssemblyFailureBecomesDiagnosableToolError() = runBlocking {
        val endpoint = FakeEndpoint()
        val descriptor = endpoint.offered.single()
        val assembled = assembleMcpTool("mcp:demo", endpoint, descriptor, "mcp_demo__search") {
            throw IllegalStateException("schema adapter exploded")
        }
        assertEquals("mcp_demo__search", assembled.tool.name)
        assertTrue(assembled.failure.orEmpty(), "schema adapter exploded" in assembled.failure.orEmpty())
        val result = assembled.tool.execute(
            Args(assembled.tool.def, buildJsonObject {}),
            toolDispatcher(FakeScreen()).ctx,
        )
        assertEquals("mcp_tool_assembly_failed", result.content["error"]?.jsonPrimitive?.content)
        assertEquals("demo", result.content["server"]?.jsonPrimitive?.content)
        assertEquals("search", result.content["tool"]?.jsonPrimitive?.content)
        assertTrue(result.content["message"]?.jsonPrimitive?.content.orEmpty().contains("schema adapter exploded"))
    }

    @Test fun syntheticToolAssemblyFailureDoesNotRemoveServerTools() {
        val endpoint = FakeEndpoint().apply { resourcesError = IllegalStateException("resources exploded") }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        val names = tools.definitions().map { it.name }
        assertTrue("mcp_demo__search" in names)
        assertFalse("mcp_demo__resource_read" in names)
        val diagnostics = tools.promptSections(tools.definitions()).joinToString("\n")
        assertTrue(diagnostics, "resources exploded" in diagnostics)
        assertTrue(diagnostics, "resource_read" in diagnostics)
    }

    @Test fun annotationRiskDefaultsToWriteAndEscalatesOpenWorldAndDestructive() {
        assertEquals(ActionRisk.Change, mcpApprovalRisk(McpAnnotations()).level)
        assertEquals(ActionRisk.Read, mcpApprovalRisk(McpAnnotations(readOnlyHint = true)).level)
        assertEquals(ActionRisk.External, mcpApprovalRisk(McpAnnotations(readOnlyHint = true, openWorldHint = true)).level)
        assertEquals(ActionRisk.Irreversible, mcpApprovalRisk(McpAnnotations(destructiveHint = true)).level)
    }

    @Test fun runCatalogIsFrozenAndCapabilitySubsetFiltersMcp() {
        val endpoint = FakeEndpoint()
        val spec = RunSpec(RunId("r"), "task", "thread")
        val tools = toolDispatcher(FakeScreen(), runSpec = spec, mcpServers = listOf(McpConnection(endpoint)))
        val first = tools.definitions().map { it.name }
        assertTrue("mcp_demo__search" in first)
        assertEquals(ActionRisk.Change, tools.approvalRisk("mcp_demo__search")?.level)
        val trusted = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint, trust = McpTrust.TrustAnnotations)))
        assertEquals(ActionRisk.Read, trusted.approvalRisk("mcp_demo__search")?.level)
        endpoint.offered = listOf(McpToolDescriptor("changed", "Changed", buildJsonObject {}))
        assertEquals(first, tools.definitions().map { it.name })
        val subset = toolDispatcher(FakeScreen(), runSpec = spec.copy(capabilities = setOf("phone")), mcpServers = listOf(McpConnection(endpoint)))
        assertFalse(subset.definitions().any { it.name.startsWith("mcp_") })
    }

    @Test fun allowedToolsLimitsExposedTools() {
        val endpoint = FakeEndpoint().apply {
            offered = listOf(
                McpToolDescriptor("search", "Search", buildJsonObject { put("type", "object") }),
                McpToolDescriptor("write", "Write", buildJsonObject { put("type", "object") }),
            )
        }
        fun mcpNames(connection: McpConnection) =
            toolDispatcher(FakeScreen(), mcpServers = listOf(connection)).definitions().map { it.name }.filter { it.startsWith("mcp_") }
        assertEquals(listOf("mcp_demo__search", "mcp_demo__write"), mcpNames(McpConnection(endpoint)))
        assertEquals(listOf("mcp_demo__write"), mcpNames(McpConnection(endpoint, allowedTools = setOf("write"))))
        assertEquals(emptyList<String>(), mcpNames(McpConnection(endpoint, allowedTools = emptySet())))
    }

    @Test fun resourcesAndPromptsBecomeReadOnlyTools() = runBlocking {
        val endpoint = FakeEndpoint().apply {
            offeredResources = listOf(McpResourceDescriptor("file://a", "A", "first"))
            offeredPrompts = listOf(
                McpPromptDescriptor("summarize", "Summarize", listOf(McpPromptArgument("topic", "Topic", true))),
            )
        }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        val names = tools.definitions().map { it.name }
        assertTrue("mcp_demo__resource_read" in names)
        assertTrue("mcp_demo__prompt_get" in names)
        assertEquals(ActionRisk.Read, tools.approvalRisk("mcp_demo__resource_read")?.level)
        assertEquals(ActionRisk.Read, tools.approvalRisk("mcp_demo__prompt_get")?.level)

        val resource = tools.execute(
            Part.ToolCall("c", "mcp_demo__resource_read", """{"uri":"file://a"}"""),
            ActionPass(),
        )
        assertEquals("resource:file://a", resource.observations.single().text)
        assertEquals(Trust.Untrusted, resource.observations.single().trust)

        val prompt = tools.execute(
            Part.ToolCall("c", "mcp_demo__prompt_get", """{"name":"summarize","arguments":{"topic":"x"}}"""),
            ActionPass(),
        )
        assertTrue(prompt.observations.single().text.contains("prompt:summarize"))
    }

    @Test fun largeResultUsesReferenceAndUntrustedObservation() = runBlocking {
        val endpoint = FakeEndpoint().apply { result = McpResult("x".repeat(40)) }
        var saved: String? = null
        val store = McpResultStore { _, _, body -> saved = body; "result://one" }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint, store, previewChars = 10)))
        val result = tools.execute(Part.ToolCall("c", "mcp_demo__search", "{}"), ActionPass())
        assertEquals("x".repeat(40), saved)
        assertEquals("result://one", result.content["fullResult"]?.toString()?.trim('"'))
        assertTrue(result.content["text"].toString().contains("<untrusted>"))
        assertEquals(Trust.Untrusted, result.observations.single().trust)
        assertEquals(10, result.observations.single().text.length)
    }

    @Test fun unattendedExternalMcpCallIsNotAskedAndNotCalledByTheJudge() = runBlocking {
        val endpoint = FakeEndpoint().apply {
            offered = listOf(McpToolDescriptor("send", "Send", buildJsonObject { put("type", "object") },
                McpAnnotations(openWorldHint = true)))
        }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        // 不花钱的对外调用:无人值守也直接做(只记审批),不在判定时就调服务端
        val decision = ConfirmDecision(tools, null, provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended))
            .judge("mcp_demo__send", buildJsonObject {}, Origin.Model(1))
        assertEquals(Decision.Clear, decision)
        assertEquals(0, endpoint.calls)
    }

    private class NamedEndpoint(override val serverId: String, names: List<String>) : McpEndpoint {
        val descriptors = names.map { McpToolDescriptor(it, it, buildJsonObject { put("type", "object") }) }
        val called = mutableListOf<String>()
        override fun tools() = descriptors
        override suspend fun call(name: String, arguments: JsonObject): McpResult {
            called += name
            return McpResult("ok")
        }
    }

    private fun namesByOriginal(tools: ToolDispatcher): Map<String, String> =
        tools.definitions().filter { it.name.startsWith("mcp_") }.associate { it.description to it.name }

    private val validName = Regex("^[A-Za-z0-9_]{1,$MCP_TOOL_NAME_MAX}$")

    @Test fun longAndSanitizedNamesStayUniqueAndRouteToOriginalTools() = runBlocking {
        val uuid = "3f2b6c1e-8d4a-4b7e-9c21-5a6f0e1d2c3b"
        val originals = listOf(
            "list_repository_collaborators",
            "list_allowed_directories",
            "get-user",
            "get_user",
            "查询天气",
            "搜索新闻",
            "search",
        )
        val endpoint = NamedEndpoint(uuid, originals)
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        val names = namesByOriginal(tools)
        assertEquals(originals.toSet(), names.keys)
        assertEquals(originals.size, names.values.toSet().size)
        assertTrue(names.toString(), names.values.all(validName::matches))
        assertEquals("mcp_${mcpSafe(uuid)}__search", names["search"])
        assertEquals("mcp_demo__get_user_186aa1ca", mcpToolName("demo", "get-user", short = true))
        assertEquals(MCP_TOOL_NAME_MAX, mcpToolName(uuid, "a".repeat(100)).length)
        for (original in originals) {
            val result = tools.execute(Part.ToolCall("call-$original", names.getValue(original), "{}"), ActionPass())
            assertEquals(JsonPrimitive(true), result.content["ok"])
        }
        assertEquals(originals, endpoint.called)
    }

    @Test fun aPlainNameOccupyingAGeneratedShortNameDoesNotBreakRegistration() = runBlocking {
        val occupied = "get_user_186aa1ca"
        val originals = listOf("get-user", "get_user", occupied)
        fun create(listed: List<String>) = toolDispatcher(
            FakeScreen(),
            mcpServers = listOf(McpConnection(NamedEndpoint("demo", listed))),
        )
        val tools = create(originals)
        val names = namesByOriginal(tools)
        assertEquals(3, names.values.toSet().size)
        assertTrue(names.toString(), names.values.all(validName::matches))
        assertEquals("mcp_demo__$occupied", names[occupied])
        assertEquals(names, namesByOriginal(create(originals.reversed())))
        for (original in originals) {
            assertEquals(JsonPrimitive(true), tools.execute(
                Part.ToolCall("call-$original", names.getValue(original), "{}"), ActionPass(),
            ).content["ok"])
        }
    }

    @Test fun aPlainNameOccupyingALongToolsShortNameAlsoGetsResolved() {
        val long = "a".repeat(100)
        val occupied = mcpToolName("demo", long).removePrefix("mcp_demo__")
        val originals = listOf(long, occupied)
        val names = mcpToolNames("demo", originals)
        assertEquals(2, names.toSet().size)
        assertTrue(names.all(validName::matches))
        assertEquals(mcpToolName("demo", occupied), names[1])
        assertEquals(names, mcpToolNames("demo", originals.reversed()).reversed())
    }

    @Test fun syntheticToolsShareTheSameUniqueNamespace() {
        val endpoint = object : McpEndpoint {
            override val serverId = "demo"
            private val originals = listOf("get-user", "get_user", "get_user_186aa1ca")
            override fun tools() = originals.map {
                McpToolDescriptor(it, it, buildJsonObject { put("type", "object") })
            }
            override suspend fun call(name: String, arguments: JsonObject) = McpResult("ok")
            override fun resources() = listOf(McpResourceDescriptor("file://a", "A"))
            override fun prompts() = listOf(McpPromptDescriptor("summarize", "Summarize"))
        }
        val definitions = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
            .definitions().filter { it.name.startsWith("mcp_") }
        assertEquals(5, definitions.size)
        assertEquals(5, definitions.map { it.name }.toSet().size)
        assertTrue("mcp_demo__resource_read" in definitions.map { it.name })
        assertTrue("mcp_demo__prompt_get" in definitions.map { it.name })
    }

    @Test fun duplicateListingsKeepTheFirstDeclarationAndReportDegradation() {
        fun descriptor(name: String, description: String) =
            McpToolDescriptor(name, description, buildJsonObject { put("type", "object") })
        val endpoint = FakeEndpoint().apply {
            offered = listOf(
                descriptor("search", "first"),
                descriptor("search", "second"),
                descriptor("write", "Write"),
                descriptor("search", "third"),
            )
        }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        val definitions = tools.definitions().filter { it.name.startsWith("mcp_") }
        assertEquals(listOf("mcp_demo__search", "mcp_demo__write"), definitions.map { it.name })
        assertEquals("first", definitions.first().description)
        val diagnostics = tools.promptSections(tools.definitions()).joinToString("\n")
        assertTrue(diagnostics, "'search': listed 3 times, only the first is used" in diagnostics)
    }
}
