package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * 一个插件工具的描述:app 侧从 manifest 读出后交给 core。[name] 是拼好的全名(`plugin_<id去点>_<工具名>`)。
 * [scriptDir]/[scriptPath] 是脚本在插件目录里的位置;[inputSchema] 是插件给的工具参数 schema。
 */
data class PluginToolSpec(
    val name: String,
    val description: String,
    val scriptPath: String,
    val scriptDir: File,
    val inputSchema: JsonObject,
    val timeoutSec: Int,
    val risk: Risk,
)

/** 跑一条插件脚本(在 App 进程内跑 sh)。[env] 是额外要加进进程环境的环境变量。 */
interface ScriptRunner {
    suspend fun run(script: File, argsFile: File, env: Map<String, String>, timeoutMs: Long): ScriptResult

    /** 把 [stdin] 写进脚本的 stdin、读 stdout 当结果(提示词钩子用);默认不支持。 */
    suspend fun runStdin(script: File, stdin: String, env: Map<String, String>, timeoutMs: Long): ScriptResult =
        ScriptResult(1, "", "stdin execution not supported", timedOut = false)
}

/** 脚本跑完的样子:退出码、输出、有没有超时被掐掉。 */
data class ScriptResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

/**
 * 插件工具:把参数 JSON 写进临时文件,交给 [runner] 跑脚本,再把脚本的 stdout(JSON)当结果。
 * 脚本不退出、输出非 JSON、或返回 `ok:false` 时都变成干净的失败,绝不把任务搞挂。
 */
internal class PluginScriptTool(
    private val spec: PluginToolSpec,
    private val runner: ScriptRunner,
    private val argsDir: File = File(System.getProperty("java.io.tmpdir"), "mika-plugin-args"),
) : AgentTool {
    override val def: ToolDef = spec.let { s ->
        val props = (s.inputSchema["properties"] as? JsonObject)?.entries
            ?.associate { (k, v) -> k to (v as? JsonObject ?: JsonObject(emptyMap())) }
            ?: emptyMap()
        val required = (s.inputSchema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
        ToolDef(s.name, s.description, props, required)
    }
    override val verb = Verb.PluginScript
    override val risk: Risk get() = spec.risk
    override val offScreen: Boolean get() = true

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        argsDir.mkdirs()
        val argsFile = File(argsDir, "args-${System.nanoTime()}.json")
        argsFile.writeText(a.o.toString())
        val result = try {
            runner.run(File(spec.scriptDir, spec.scriptPath), argsFile, emptyMap(), spec.timeoutSec * 1000L)
        } finally {
            argsFile.delete()
        }
        if (result.timedOut) return fail("timed_out", "script did not finish within ${spec.timeoutSec}s")
        if (result.exitCode != 0) return fail("script_failed", result.stderr.ifBlank { result.stdout }.take(2000))
        val parsed = runCatching { Json.parseToJsonElement(result.stdout) as? JsonObject }.getOrNull()
            ?: return fail("bad_output", "script returned non-JSON output: ${result.stdout.take(200)}")
        if (parsed["ok"] == JsonPrimitive(false)) {
            val error = (parsed["error"] as? JsonPrimitive)?.contentOrNull ?: "script_error"
            val message = (parsed["message"] as? JsonPrimitive)?.contentOrNull ?: error
            return fail(error, message)
        }
        return ToolExecution(parsed)
    }
}

/** 插件作为一个能力:无屏、无守卫、无节奏,只把自己的工具放进任务。 */
internal class PluginCapability(private val tools: List<AgentTool>) : Capability {
    override val id = "plugin"
    override fun status() = CapabilityStatus.Available
    override fun tools() = tools
    override fun sections(env: PromptEnv) = emptyList<String>()
}
