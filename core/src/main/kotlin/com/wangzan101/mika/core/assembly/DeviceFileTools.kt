package com.wangzan101.mika.core.assembly

import com.wangzan101.mika.core.cap.devicefiles.DeviceFileTool
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilesCapability
import com.wangzan101.mika.core.cap.devicefiles.guardDeviceFiles
import com.wangzan101.mika.core.cap.sandbox.FolderFiles
import com.wangzan101.mika.core.cap.sandbox.sandboxRunner
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.*
import com.wangzan101.mika.core.workspace.DeviceFileSearchQuery
import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.serialization.json.*

internal fun searchAndCopyTools(owner: String): List<AgentTool> = listOf(SearchDeviceFiles(owner), CopyDeviceFile(owner))

private fun unavailable(source: String): Nothing =
    throw SandboxError("device_access_denied", "$source access is unavailable for this task.", true)

private class CopyDeviceFile(override val capability: String) : DeviceFileTool {
    override fun available(ctx: ToolContext) = super.available(ctx) || ctx.sandboxRunner?.folderImportAvailable == true
    override val verb = Verb.CopyDeviceFile
    override val label = "复制设备文件"
    override val risk = Risk.Write
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("copy_device_file", Prompts.tool("copy_device_file"), mapOf(
        "path" to str("File path returned by a device folder listing or search."),
    ), listOf("path"))
    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("path")
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = guardDeviceFiles {
        val source = a.str("path", max = 4096)!!
        val imported = if (FolderFiles.owns(source)) {
            ctx.sandboxRunner?.importFolderFile(source) ?: unavailable("System folder")
        } else {
            val helper = ctx[DeviceFilesCapability.KEY] ?: unavailable("Phone helper")
            helper.port.importFile(source, helper.workspace())
        }
        ToolExecution(buildJsonObject {
            put("ok", true); put("path", imported.path); put("bytesCopied", imported.size)
        }, followUpTools = listOf(Verb.ReadFile))
    }
}

private class SearchDeviceFiles(override val capability: String) : DeviceFileTool {
    override fun available(ctx: ToolContext) = super.available(ctx) || ctx.sandboxRunner?.foldersAvailable == true
    override val verb = Verb.SearchDeviceFiles
    override val label = "搜索设备文件"
    override val risk = Risk.Read
    override val def = ToolDef("search_device_files", Prompts.tool("search_device_files"), mapOf(
        "path" to str("Directory to search."), "name" to str("Case-insensitive filename substring."),
        "extension" to str("File extension, with or without a leading dot."),
        "modified_after" to str("Inclusive Unix timestamp in milliseconds."), "modified_before" to str("Inclusive Unix timestamp in milliseconds."),
        "max_depth" to int("Maximum directory depth, 0-10 (default 3).", 0, 10),
        "max_entries" to int("Maximum visited entries, 1-5000 (default 1000).", 1, 5000),
        "timeout_ms" to int("Time budget in milliseconds, 1-5000 (default 2000).", 1, 5000),
    ), listOf("path"))
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = guardDeviceFiles {
        fun timestamp(key: String): Long? = a.str(key, max = 19)?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw SandboxError("invalid", "Invalid timestamp.", true) }
        val query = DeviceFileSearchQuery(a.str("path", max = 4096)!!, a.str("name", max = 255), a.str("extension", max = 255), timestamp("modified_after"), timestamp("modified_before"), a.int("max_depth", 0, 10) ?: 3, a.int("max_entries", 1, 5000) ?: 1000, a.int("timeout_ms", 1, 5000) ?: 2000)
        val result = if (FolderFiles.owns(query.path)) {
            ctx.sandboxRunner?.searchFolders(query) ?: unavailable("System folder")
        } else {
            val helper = ctx[DeviceFilesCapability.KEY] ?: unavailable("Phone helper")
            helper.port.search(query)
        }
        ToolExecution(buildJsonObject {
            put("ok", true); put("visited", result.visited); put("truncated", result.truncated)
            result.reason?.let { put("reason", it) }
            put("entries", JsonArray(result.entries.map { buildJsonObject { put("name", it.name); put("path", it.path) } }))
        })
    }
}
