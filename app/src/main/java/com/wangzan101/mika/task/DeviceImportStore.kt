package com.wangzan101.mika.task

import android.content.Context
import android.content.SharedPreferences
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Persisted import history. Workspace paths are sandbox paths, not host paths. */
data class DeviceImportRecord(
    val id: String, val conversationId: String, val sourcePath: String,
    val workspacePath: String, val size: Long, val importedAt: Long,
    val sourceModifiedSeconds: Long?, val sourceModifiedNanos: Long?,
    val workspaceId: String = conversationId,
)

class DeviceImportStore(private val prefs: SharedPreferences) {
    /** Records are history; a copy may have since been removed. */
    fun allRecords(): List<DeviceImportRecord> = synchronized(lock) { read() }

    fun list(conversationId: String): List<DeviceImportRecord> = synchronized(lock) {
        read().filter { it.conversationId == conversationId }
    }

    fun listByWorkspace(workspaceId: String): List<DeviceImportRecord> = synchronized(lock) {
        read().filter { it.workspaceId == workspaceId }
    }

    /** Emits the current snapshot and updates after successful writes in this process. */
    fun recordsFlow(): Flow<List<DeviceImportRecord>> = changes.map { allRecords() }

    /** Removes a conversation's history; persistence failures are thrown to the caller. */
    fun removeConversation(conversationId: String) = synchronized(lock) {
        val current = read()
        val remaining = current.filterNot { it.conversationId == conversationId }
        if (remaining.size != current.size) write(remaining)
    }

    /** Removes all import records belonging to a workspace (e.g., deleted project workspace). */
    fun removeWorkspace(workspaceId: String) = synchronized(lock) {
        val current = read()
        val remaining = current.filterNot { it.workspaceId == workspaceId }
        if (remaining.size != current.size) write(remaining)
    }

    internal fun add(record: DeviceImportRecord) = synchronized(lock) { write(read() + record) }

    private fun write(records: List<DeviceImportRecord>) {
        val json = buildJsonObject {
            put("schemaVersion", 1)
            put("records", buildJsonArray { records.forEach { r -> add(buildJsonObject {
                put("id", r.id); put("conversationId", r.conversationId); put("sourcePath", r.sourcePath)
                put("workspacePath", r.workspacePath); put("size", r.size); put("importedAt", r.importedAt)
                if (r.workspaceId != r.conversationId) put("workspaceId", r.workspaceId)
                r.sourceModifiedSeconds?.let { put("sourceModifiedSeconds", it) }
                r.sourceModifiedNanos?.let { put("sourceModifiedNanos", it) }
            }) } })
        }
        val committed = try { prefs.edit().putString(KEY, json.toString()).commit() }
        catch (e: Exception) { failed.add(prefs); throw IllegalStateException("Could not save device import record", e) }
        if (!committed) { failed.add(prefs); throw IllegalStateException("Could not save device import record") }
        changes.value++
    }

    private fun read(): List<DeviceImportRecord> {
        check(prefs !in failed) { "Device import records could not be saved; restart to reload them" }
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val root = Json.parseToJsonElement(raw).jsonObject
        check(root.getValue("schemaVersion").jsonPrimitive.int == 1) { "Unsupported device import schema" }
        return root.getValue("records").jsonArray.map { element ->
            val o = element.jsonObject
            val conversationId = o.getValue("conversationId").jsonPrimitive.content
            DeviceImportRecord(o.getValue("id").jsonPrimitive.content, conversationId,
                o.getValue("sourcePath").jsonPrimitive.content, o.getValue("workspacePath").jsonPrimitive.content,
                o.getValue("size").jsonPrimitive.long, o.getValue("importedAt").jsonPrimitive.long,
                o["sourceModifiedSeconds"]?.jsonPrimitive?.long, o["sourceModifiedNanos"]?.jsonPrimitive?.long,
                o["workspaceId"]?.jsonPrimitive?.contentOrNull ?: conversationId)
        }
    }

    companion object {
        private const val KEY = "records"
        private val lock = Any()
        private val changes = MutableStateFlow(0L)
        private val failed = Collections.newSetFromMap(WeakHashMap<SharedPreferences, Boolean>())
        fun get(context: Context) = DeviceImportStore(context.applicationContext.getSharedPreferences("device_imports", Context.MODE_PRIVATE))
    }
}
