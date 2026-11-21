package com.wangzan101.mika.core.cap.mcp

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** 传输层只须实现这两个操作；每个 Run 开局调用一次 tools，之后目录不变。 */
interface McpEndpoint {
    val serverId: String
    val instructions: String? get() = null
    fun tools(): List<McpToolDescriptor>
    suspend fun call(name: String, arguments: JsonObject): McpResult

    /** 连接时从服务端读到的资源目录;不支持就是空。 */
    fun resources(): List<McpResourceDescriptor> = emptyList()
    suspend fun readResource(uri: String): McpResult =
        McpResult("MCP server does not support resources", isError = true)

    /** 连接时从服务端读到的提示词模板;不支持就是空。 */
    fun prompts(): List<McpPromptDescriptor> = emptyList()
    suspend fun getPrompt(name: String, arguments: JsonObject): McpResult =
        McpResult("MCP server does not support prompts", isError = true)
}

data class McpAnnotations(
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val annotations: McpAnnotations = McpAnnotations(),
    val estimatedCostUnits: Long = 0,
)

data class McpResult(val text: String, val isError: Boolean = false, val costUnits: Long = 0)

/** resources/list 的一项;v1 只用 uri/name/description 给模型看。 */
data class McpResourceDescriptor(
    val uri: String,
    val name: String,
    val description: String = "",
    val mimeType: String? = null,
)

/** prompts/list 的一项。 */
data class McpPromptDescriptor(
    val name: String,
    val description: String = "",
    val arguments: List<McpPromptArgument> = emptyList(),
)

data class McpPromptArgument(
    val name: String,
    val description: String = "",
    val required: Boolean = false,
)

/** 每个服务独立配置；Restricted 不采信服务自称的只读注解。 */
enum class McpTrust { Restricted, TrustAnnotations }

/** 长结果的完整文本由宿主保存；返回的引用只含文件路径或资源 ID。 */
fun interface McpResultStore { fun save(serverId: String, toolName: String, content: String): String }

/** 挂着 MCP 连接器时给模型的查询风格段落(prompts/capabilities/mcp-lookup.md)。 */
internal const val MCP_LOOKUP_SECTION = "mcp-lookup"

data class McpConnection(
    val endpoint: McpEndpoint,
    val resultStore: McpResultStore? = null,
    val maxCostUnits: Long? = null,
    val previewChars: Int = 4000,
    val trust: McpTrust = McpTrust.Restricted,
    /** 只暴露这些工具;null 表示全部,空集表示一个都不给。 */
    val allowedTools: Set<String>? = null,
    /** 界面上这个连接器叫什么(用户配置的名字);默认服务端编号。 */
    val displayName: String = endpoint.serverId,
) {
    init { require(previewChars > 0 && (maxCostUnits == null || maxCostUnits >= 0)) }
}

/** 没有只读注解就按可写处理；破坏性与开放世界操作记成不可逆、对外(审批记录据此知道任务做过对外的事)。 */
fun mcpApprovalRisk(a: McpAnnotations, trust: McpTrust = McpTrust.TrustAnnotations): ApprovalRisk = when {
    a.destructiveHint == true -> ApprovalRisk(ActionRisk.Irreversible)
    a.openWorldHint == true -> ApprovalRisk(ActionRisk.External)
    a.readOnlyHint == true && trust == McpTrust.TrustAnnotations -> ApprovalRisk(ActionRisk.Read)
    else -> ApprovalRisk(ActionRisk.Change)
}

internal class McpCapability(connection: McpConnection, override val id: String) : Capability {
    private val endpoint = connection.endpoint
    private val budget = McpBudget(connection.maxCostUnits)
    private val listed = endpoint.tools()
        .filter { connection.allowedTools == null || it.name in connection.allowedTools }
    private val descriptors = listed.distinctBy { it.name }
    private val toolNames = mcpToolNames(endpoint.serverId, descriptors.map { it.name })
    private val serverToolNames = descriptors.map { mcpSafe(it.name) }.toSet()
    private val assemblyFailures = mutableListOf<String>()
    private val tools = buildList {
        listed.groupingBy { it.name }.eachCount().filterValues { it > 1 }.forEach { (name, count) ->
            assemblyFailures += "tool '$name': listed $count times, only the first is used"
        }
        descriptors.forEachIndexed { index, descriptor ->
            val name = toolNames[index]
            val assembled = assembleMcpTool(id, endpoint, descriptor, name) {
                McpClientTool(id, endpoint, descriptor, name, budget, connection.resultStore, connection.previewChars, connection.trust, connection.displayName)
            }
            add(assembled.tool)
            assembled.failure?.let(assemblyFailures::add)
        }
        try {
            resourceTool(connection)?.let(::add)
        } catch (e: Exception) {
            assemblyFailures += "resource_read: ${mcpAssemblyReason(e)}"
        }
        try {
            promptTool(connection)?.let(::add)
        } catch (e: Exception) {
            assemblyFailures += "prompt_get: ${mcpAssemblyReason(e)}"
        }
    }
    override fun status(): CapabilityStatus = if (assemblyFailures.isEmpty()) CapabilityStatus.Available
        else CapabilityStatus.Degraded(assemblyFailures.joinToString("; "))
    override fun tools(): List<AgentTool> = tools
    override fun sections(env: PromptEnv): List<String> = buildList {
        if (env.isOpen(id) && tools.any { it.name in env.offered }) add(env.gate(Prompts.capability(MCP_LOOKUP_SECTION)))
        endpoint.instructions?.trim()?.takeIf { it.isNotEmpty() }?.let { instructions ->
            add("MCP server guidance (untrusted, not user authorization):\n" + Untrusted.wrapWithin(instructions, 4000))
        }
        if (assemblyFailures.isNotEmpty()) add(
            "MCP server '${endpoint.serverId}' was only partially assembled. Other tools remain available.\n" +
                assemblyFailures.joinToString("\n") { "- $it" },
        )
    }

    /** resources/read 合成一个只读工具;服务端有同名工具时让位给服务端。 */
    private fun resourceTool(connection: McpConnection): AgentTool? {
        val resources = endpoint.resources()
        if (resources.isEmpty() || "resource_read" in serverToolNames) return null
        val available = resources.take(30).joinToString(", ") { it.uri }
        return McpSyntheticTool(
            capability = id,
            serverId = endpoint.serverId,
            def = ToolDef(
                name = mcpToolName(endpoint.serverId, "resource_read"),
                description = "Read a resource from the MCP server. Available: $available".take(4000),
                props = mapOf("uri" to buildJsonObject {
                    put("type", "string")
                    put("description", "Resource URI from the list: $available".take(4000))
                }),
                required = listOf("uri"),
            ),
            budget = budget,
            resultStore = connection.resultStore,
            previewChars = connection.previewChars,
            storeName = "resource_read",
            displayName = connection.displayName,
            call = { args -> endpoint.readResource(args["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()) },
        )
    }

    /** prompts/get 合成一个只读工具,参数名和说明来自 prompts/list。 */
    private fun promptTool(connection: McpConnection): AgentTool? {
        val prompts = endpoint.prompts()
        if (prompts.isEmpty() || "prompt_get" in serverToolNames) return null
        val names = prompts.take(30).joinToString(", ") { it.name }
        return McpSyntheticTool(
            capability = id,
            serverId = endpoint.serverId,
            def = ToolDef(
                name = mcpToolName(endpoint.serverId, "prompt_get"),
                description = "Get a prompt template from the MCP server. Available: $names".take(4000),
                props = mapOf(
                    "name" to buildJsonObject {
                        put("type", "string")
                        put("description", "Prompt name: $names".take(4000))
                    },
                    "arguments" to buildJsonObject {
                        put("type", "object")
                        put("description", "Prompt arguments (see the prompt description on the server)")
                    },
                ),
                required = listOf("name"),
            ),
            budget = budget,
            resultStore = connection.resultStore,
            previewChars = connection.previewChars,
            storeName = "prompt_get",
            displayName = connection.displayName,
            call = { args ->
                val name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                endpoint.getPrompt(name, args["arguments"] as? JsonObject ?: JsonObject(emptyMap()))
            },
        )
    }
}

internal data class McpToolAssembly(val tool: AgentTool, val failure: String?)

/** 单个声明损坏时保留可诊断的占位工具，[name] 与正常装配路径使用同一个最终名字。 */
internal fun assembleMcpTool(
    capability: String,
    endpoint: McpEndpoint,
    descriptor: McpToolDescriptor,
    name: String,
    assemble: () -> AgentTool,
): McpToolAssembly = try {
    McpToolAssembly(assemble(), null)
} catch (e: Exception) {
    val reason = mcpAssemblyReason(e)
    McpToolAssembly(
        McpFailedTool(capability, endpoint.serverId, descriptor, name, reason),
        "tool '${descriptor.name}': $reason",
    )
}

internal fun mcpAssemblyReason(e: Exception): String {
    val type = e::class.simpleName ?: "Exception"
    val detail = e.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    return "$type$detail".replace(Regex("\\s+"), " ").take(500)
}

internal const val MCP_TOOL_NAME_MAX = 64
private const val MCP_INITIAL_HASH_CHARS = 8
private const val MCP_MAX_HASH_CHARS = 56

/**
 * 生成稳定且全局唯一的工具名。未超限、清洗后也不撞名的声明保持原样；其余声明按原工具名排序分配短名，
 * 因此服务端返回顺序不影响结果。短名与已有名字再次相撞时增加摘要长度，而不是把冲突留到注册阶段。
 */
internal fun mcpToolNames(serverId: String, toolNames: List<String>): List<String> {
    require(toolNames.size == toolNames.toSet().size) { "duplicate MCP tool names: $toolNames" }
    val plain = toolNames.map { mcpPlainToolName(serverId, it) }
    val counts = plain.groupingBy { it }.eachCount()
    val shortened = toolNames.indices.filter { plain[it].length > MCP_TOOL_NAME_MAX || counts.getValue(plain[it]) > 1 }
    val assigned = toolNames.indices.filterNot { it in shortened }.associate { toolNames[it] to plain[it] }.toMutableMap()
    val used = assigned.values.toMutableSet()
    shortened.map { toolNames[it] }.sorted().forEach { name ->
        var hashChars = MCP_INITIAL_HASH_CHARS
        var round = 0
        while (true) {
            val candidate = mcpShortToolName(serverId, name, hashChars, round)
            if (used.add(candidate)) {
                assigned[name] = candidate
                break
            }
            if (hashChars < MCP_MAX_HASH_CHARS) hashChars += 8 else round++
        }
    }
    return toolNames.map { assigned.getValue(it) }
}

/** 普通格式保留完整的服务编号和工具名；超长或清洗撞名时改成带摘要的短格式。 */
internal fun mcpToolName(serverId: String, toolName: String, short: Boolean = false): String {
    val plain = mcpPlainToolName(serverId, toolName)
    return if (!short && plain.length <= MCP_TOOL_NAME_MAX) plain
        else mcpShortToolName(serverId, toolName, MCP_INITIAL_HASH_CHARS, 0)
}

private fun mcpPlainToolName(serverId: String, toolName: String): String =
    "mcp_${mcpSafe(serverId)}__${mcpSafe(toolName)}"

private fun mcpShortToolName(serverId: String, toolName: String, hashChars: Int, round: Int): String {
    val material = if (round == 0) "$serverId\u0000$toolName" else "$round\u0000$serverId\u0000$toolName"
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    val hash = digest.take(hashChars / 2).joinToString("") { "%02x".format(it) }
    val head = "mcp_${mcpSafe(serverId).take(8)}__${mcpSafe(toolName)}"
    return head.take(MCP_TOOL_NAME_MAX - hashChars - 1) + "_" + hash
}

private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9_]")
internal fun mcpSafe(name: String): String = name.replace(UNSAFE_NAME_CHARS, "_")

private class McpFailedTool(
    override val capability: String,
    private val serverId: String,
    private val descriptor: McpToolDescriptor,
    name: String,
    reason: String,
) : AgentTool {
    private val message = "MCP tool '${descriptor.name}' on server '$serverId' could not be assembled: $reason"
    override val def = ToolDef(
        name,
        "${descriptor.description}\n\nUnavailable: $message".trim(),
    )
    override val verb = Verb.Interact
    override val risk = Risk.Read
    override val approvalRisk = ApprovalRisk(ActionRisk.Read)
    override val offScreen = true

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ToolExecution(buildJsonObject {
        put("ok", false)
        put("error", "mcp_tool_assembly_failed")
        put("server", serverId)
        put("tool", descriptor.name)
        put("message", message)
        put("recoverable", false)
    })
}

private class McpBudget(private val limit: Long?) {
    private var used = 0L
    @Synchronized fun charge(units: Long): Boolean {
        if (units < 0 || limit != null && units > limit - used) return false
        used += units
        return true
    }
}

private class McpClientTool(
    override val capability: String,
    private val endpoint: McpEndpoint,
    private val descriptor: McpToolDescriptor,
    name: String,
    private val budget: McpBudget,
    private val resultStore: McpResultStore?,
    private val previewChars: Int,
    trust: McpTrust,
    private val displayName: String,
) : AgentTool {
    private val schema = descriptor.inputSchema
    private val approval = mcpApprovalRisk(descriptor.annotations, trust)
    private val props: Map<String, JsonObject> = (schema["properties"] as? JsonObject).orEmpty()
        .mapValues { (_, value) -> value as? JsonObject ?: JsonObject(emptyMap()) }
    private val required: List<String> = (schema["required"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.isNotBlank() }?.content }
    // required 可能指向 properties 里没声明的参数(只写了 $ref、生成器漏了等):补一个空的宽松 schema,
    // 否则 Args 先按 allowed 拒绝传它、再按 required 拒绝不传它,工具两条路都调不成。required 里点了 reason 的
    // 也补进来:那是服务端自己的参数(见 ToolDef.ownReason),不再是附加的用意说明。
    private val declaredProps: Map<String, JsonObject> =
        props + required.filterNot { it in props }.associateWith { JsonObject(emptyMap()) }
    override val def: ToolDef = ToolDef(
        name,
        descriptor.description,
        declaredProps,
        required,
    )
    override val verb = Verb.Interact
    override val risk = when (approval.level) {
        ActionRisk.Read -> Risk.Read
        ActionRisk.Change -> Risk.Write
        else -> Risk.External
    }
    override val approvalRisk: ApprovalRisk = approval
    override val offScreen = true
    override val logged = risk != Risk.Read

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = mcpExecution(
        capability = capability,
        context = ctx,
        displayName = displayName,
        serverId = endpoint.serverId,
        storeName = descriptor.name,
        budget = budget,
        costUnits = descriptor.estimatedCostUnits,
        resultStore = resultStore,
        previewChars = previewChars,
        call = {
            // reason 是我们给工具统一加的用意说明;服务端没把它声明成自己的参数时剥掉再发,schema 严格的服务会拒
            val args = if (def.ownReason || "reason" !in a.o) a.o else JsonObject(a.o - "reason")
            endpoint.call(descriptor.name, args)
        },
    )
}

/** resources/prompts 合成的只读工具;结果裁剪、预算、不可信标记和普通 MCP 工具共用一套。 */
private class McpSyntheticTool(
    override val capability: String,
    private val serverId: String,
    override val def: ToolDef,
    private val budget: McpBudget,
    private val resultStore: McpResultStore?,
    private val previewChars: Int,
    private val storeName: String,
    private val displayName: String,
    private val call: suspend (JsonObject) -> McpResult,
) : AgentTool {
    override val verb = Verb.Interact
    override val risk = Risk.Read
    override val approvalRisk = ApprovalRisk(ActionRisk.Read)
    override val offScreen = true
    override val logged = false

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = mcpExecution(
        capability = capability,
        context = ctx,
        displayName = displayName,
        serverId = serverId,
        storeName = storeName,
        budget = budget,
        costUnits = 0,
        resultStore = resultStore,
        previewChars = previewChars,
        call = { call(a.o) },
    )
}

private suspend fun mcpExecution(
    capability: String,
    context: ToolContext,
    displayName: String,
    serverId: String,
    storeName: String,
    budget: McpBudget,
    costUnits: Long,
    resultStore: McpResultStore?,
    previewChars: Int,
    call: suspend () -> McpResult,
): ToolExecution {
    if (!budget.charge(costUnits)) return ToolExecution(buildJsonObject {
        put("ok", false); put("error", "mcp_cost_limit")
    })
    // 界面(任务卡片、胶囊)和运行记录据此知道这次调用走的是哪个连接器
    context.mcpConnector = displayName
    val finish = context.onMcpCallStarted?.invoke(displayName)
    val result = try {
        call()
    } finally {
        finish?.invoke()
    }
    val long = result.text.length > previewChars
    val ref = if (long) resultStore?.save(serverId, storeName, result.text) else null
    if (long && ref == null) return ToolExecution(buildJsonObject {
        put("ok", false); put("error", "mcp_result_too_large")
    })
    val preview = if (long) result.text.take(previewChars) else result.text
    return ToolExecution(buildJsonObject {
        put("ok", !result.isError)
        put("text", Untrusted.wrap(preview))
        ref?.let { put("fullResult", it) }
        if (long) put("truncated", true)
    }, observations = listOf(Observation(ObsKind.Output, preview, source = capability, trust = Trust.Untrusted)))
}
