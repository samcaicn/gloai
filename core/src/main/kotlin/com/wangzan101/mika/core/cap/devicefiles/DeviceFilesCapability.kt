package com.wangzan101.mika.core.cap.devicefiles

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.*
import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.serialization.json.*
import java.io.IOException

internal const val DEVICE_FILES = "device_files"

internal class DeviceFilesCapability(val port: DeviceFilesPort, val workspace: () -> String, private val fileTools: List<AgentTool>) : Capability {
    override val id = DEVICE_FILES
    override fun status() = CapabilityStatus.Available
    override fun tools(): List<AgentTool> = fileTools
    override fun onTaskStart(ctx: ToolContext) { ctx[KEY] = this }
    override fun sections(env: PromptEnv): List<String> =
        if (env.isOpen(id) && tools().any { it.name in env.offered }) listOf(env.gate(Prompts.capability(id))) else emptyList()
    companion object { val KEY = CapabilityKey<DeviceFilesCapability>(DEVICE_FILES) }
}

internal interface DeviceFileTool : AgentTool {
    override val capability get() = DEVICE_FILES
    override val offScreen get() = true
    override fun available(ctx: ToolContext) = ctx[DeviceFilesCapability.KEY] != null
}

private fun error(code: String, message: String, recoverable: Boolean = true) = ToolExecution(buildJsonObject {
    put("ok", false); put("error", code); put("message", message); put("recoverable", recoverable)
})

internal suspend fun guardDeviceFiles(block: suspend () -> ToolExecution): ToolExecution = try { block() }
catch (e: SandboxError) { error(e.code, e.message ?: e.code, e.recoverable) }
catch (e: SecurityException) { error("device_access_denied", "Device file access was denied.") }
catch (e: IOException) { error("device_file_io", "Could not access or copy the file.") }

private object ListDeviceFiles : DeviceFileTool {
    override val verb = Verb.ListDeviceFiles
    override val label = "查看设备文件"
    override val risk = Risk.Read
    override val def = ToolDef("list_device_files", Prompts.tool("list_device_files"), mapOf(
        "path" to str("Directory identifier from a previous listing; omit to list accessible roots."),
        "cursor" to str("Cursor returned by the preceding listing."),
        "limit" to int("Maximum entries, 1-100 (default 100).", 1, 100),
    ), emptyList())
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = guardDeviceFiles {
        val path = a.str("path", max = 4096)
        val cursor = a.str("cursor", max = 4096)
        val limit = a.int("limit", 1, 100) ?: 100
        val page = ctx[DeviceFilesCapability.KEY]!!.port.list(path, cursor, limit)
        ToolExecution(buildJsonObject {
            put("ok", true)
            put("entries", JsonArray(page.entries.map { entry -> buildJsonObject {
                put("name", entry.name); put("path", entry.path); put("isDirectory", entry.isDirectory)
                entry.size?.let { put("size", it) }
            } }))
            page.nextCursor?.let { put("nextCursor", it) }
        })
    }
}

private fun hintJson(h: DeviceDirectoryHint) = buildJsonObject {
    put("path", h.path); put("name", h.name); put("description", h.description); put("source", h.source); put("status", h.status)
}
private object ListDirectoryHints : DeviceFileTool {
    override val verb = Verb.ListDeviceDirectoryHints
    override val risk = Risk.Read
    override val def = ToolDef("list_device_directory_hints", Prompts.tool("list_device_directory_hints"), emptyMap(), emptyList())
    override suspend fun execute(a: Args, ctx: ToolContext) = guardDeviceFiles {
        ToolExecution(buildJsonObject { put("ok", true); put("directories", JsonArray(ctx[DeviceFilesCapability.KEY]!!.port.listDirectoryHints().map(::hintJson))) })
    }
}
private object UpdateDirectoryDescription : DeviceFileTool {
    override val verb = Verb.UpdateDeviceDirectoryDescription
    override val risk = Risk.Write
    override val def = ToolDef("update_device_directory_description", Prompts.tool("update_device_directory_description"), mapOf("path" to str("Existing directory identifier."), "description" to str("Short directory description.")), listOf("path", "description"))
    override suspend fun execute(a: Args, ctx: ToolContext) = guardDeviceFiles {
        val hint = ctx[DeviceFilesCapability.KEY]!!.port.updateDirectoryDescription(a.str("path", max = 4096)!!, a.str("description", max = 500)!!)
        ToolExecution(buildJsonObject { put("ok", true); put("directory", hintJson(hint)) })
    }
}

internal object DeviceFilesGroup {
    fun withSearchAndCopy(shared: List<AgentTool>): List<AgentTool> =
        listOf(ListDeviceFiles) + shared + listOf(ListDirectoryHints, UpdateDirectoryDescription)
}
