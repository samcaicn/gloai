package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.device.DeviceAccess
import com.wangzan101.mika.core.device.toJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLDecoder
import java.io.IOException
import java.util.Base64

/** 普通 HTTP/JSON 适配器。无需 MCP 握手、工具发现或 tools/call;认证由 RemoteHost 统一执行。 */
class RemoteTaskApi(
    private val service: RemoteTaskService,
    private val serverVersion: String,
    private val device: DeviceAccess? = null,
    private val history: RemoteHistory? = null,
    private val companion: Boolean = false,
) {
    fun handle(req: HttpRequest): HttpResponse = try {
        req.header("accept")?.lowercase()?.let {
            val json = "application/json" in it || "*/*" in it || "application/*" in it
            val ndjson = EXPORT_PATH.matches(req.path) && "application/x-ndjson" in it
            if (!json && !ndjson) throw ApiError(406, "not_acceptable", "Accept must include application/json")
        }
        route(req)
    } catch (e: ApiError) {
        error(e.status, e.code, e.message.orEmpty(), e.headers)
    } catch (e: RemoteTaskError) {
        val status = when (e.code) {
            RemoteTaskError.Code.INVALID_ARGUMENT -> 400
            RemoteTaskError.Code.NOT_FOUND -> 404
            RemoteTaskError.Code.GONE -> 410
            RemoteTaskError.Code.CONFLICT -> 409
            RemoteTaskError.Code.UNAVAILABLE -> 503
        }
        error(status, e.code.name.lowercase(), e.message.orEmpty())
    }

    private fun route(req: HttpRequest): HttpResponse {
        val query = query(req)
        UPLOAD.matchEntire(req.path)?.let { match ->
            if (query.isNotEmpty()) invalid("Uploads do not accept query parameters")
            return upload(req, match.groupValues[1], match.groupValues[2])
        }
        RESULT.matchEntire(req.path)?.let { match ->
            method(req, "GET")
            if (query.keys != setOf("offset")) invalid("An offset is required")
            val offset = query["offset"]?.toLongOrNull() ?: invalid("Invalid file offset")
            val (taskId, fileId) = match.destructured
            val access = fileAccess()
            val file = service.get(taskId).files.find { it.id == fileId }
                ?: throw ApiError(404, "file_not_found", "No such file in this task")
            if (offset !in 0..file.size) invalid("Invalid file offset")
            val bytes = fileIO { access.read(taskId, file, offset) }
            return ok(buildJsonObject { put("offset", offset); put("data", Base64.getEncoder().encodeToString(bytes)) })
        }
        if (req.path.startsWith("$ROOT/history/")) return history(req, query)
        if (req.path != "$ROOT/tasks" && !EXPORT_PATH.matches(req.path) && query.isNotEmpty()) invalid("This endpoint accepts no query parameters")
        if (req.path == ROOT) {
            method(req, "GET")
            return ok(buildJsonObject {
                put("api_version", 1); put("server_version", serverVersion)
                putJsonArray("capabilities") {
                    add("tasks"); add("task_steer"); add("task_recovery")
                    if (device != null) add("device_state")
                    if (service.fileAccess != null) add("task_files")
                    if (service.exportEnabled) add("run_export")
                    if (history != null) add("history")
                    if (companion) { add("conversations"); add("running") }
                }
                if (history != null) put("history_enabled", history.enabled)
                putJsonObject("limits") {
                    put("max_wait_s", RemoteTaskService.MAX_WAIT_S)
                    put("max_goal", RemoteTaskService.MAX_GOAL)
                    put("max_answer", RemoteTaskService.MAX_ANSWER)
                    put("max_steer", RemoteTaskService.MAX_STEER)
                }
            })
        }
        if (req.path == "$ROOT/device") {
            method(req, "GET")
            val access = device ?: throw ApiError(503, "device_unavailable", "Device state is unavailable")
            return ok(runBlocking { access.state().toJson() })
        }
        if (req.path == "$ROOT/tasks") {
            return when (req.method) {
                "GET" -> {
                    if (query.keys.any { it !in setOf("limit", "before") }) invalid("Only limit and before are supported")
                    val limit = query["limit"]?.let { it.toIntOrNull() ?: invalid("limit must be an integer") }
                        ?: RemoteTaskService.DEFAULT_LIST
                    val result = service.page(limit, query["before"])
                    ok(buildJsonObject {
                        putJsonArray("tasks") { result.tasks.forEach { add(RemoteStatus.describe(it)) } }
                        result.nextBefore?.let { put("next_before", it) }
                    })
                }
                "POST" -> {
                    if (query.isNotEmpty()) invalid("Task submission accepts no query parameters")
                    val b = body(req, setOf("goal", "conversation", "attachments", "request_id"))
                    val ids = b["attachments"]?.let { value ->
                        (value as? JsonArray)?.map {
                            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid("Expected upload IDs")
                        } ?: invalid("attachments must be an array")
                    }.orEmpty()
                    val requestId = if ("request_id" in b) string(b, "request_id").orEmpty() else null
                    val task = fileIO { service.start(string(b, "goal").orEmpty(), string(b, "conversation"), requestId, ids) }
                    ok(RemoteStatus.describe(task), 201)
                }
                else -> wrongMethod("GET, POST")
            }
        }
        val parts = req.path.removePrefix("$ROOT/tasks/").split('/')
        if (!req.path.startsWith("$ROOT/tasks/") || parts.size !in 1..2 || !ID.matches(parts[0]))
            throw ApiError(404, "not_found", "No such endpoint")
        val id = parts[0]
        if (parts.size == 1) {
            method(req, "GET")
            return ok(RemoteStatus.describe(service.get(id)))
        }
        return when (parts[1]) {
            "wait" -> {
                method(req, "POST")
                val b = body(req, setOf("timeout_s"))
                val r = runBlocking { service.wait(id, integer(b, "timeout_s") ?: RemoteTaskService.DEFAULT_WAIT_S) }
                ok(buildJsonObject {
                    RemoteStatus.describe(r.task).forEach { (k, v) -> put(k, v) }; put("changed", r.changed)
                })
            }
            "answer" -> {
                method(req, "POST")
                val b = body(req, setOf("text"))
                ok(buildJsonObject { put("task_id", service.answer(id, string(b, "text").orEmpty())); put("accepted", true) })
            }
            "steer" -> {
                method(req, "POST")
                val b = body(req, setOf("text"))
                val r = service.steer(id, string(b, "text").orEmpty())
                ok(buildJsonObject {
                    put("task_id", r.taskId); put("message_id", r.messageId); put("accepted", true)
                }, 202)
            }
            "cancel" -> {
                method(req, "POST")
                body(req, emptySet())
                val r = service.cancel(id)
                ok(buildJsonObject {
                    put("task_id", r.taskId); put("accepted", r.accepted)
                    r.status?.let { put("status", it) }
                }, if (r.accepted) 202 else 200)
            }
            "export" -> export(req, id, query)
            else -> throw ApiError(404, "not_found", "No such endpoint")
        }
    }

    private fun export(req: HttpRequest, id: String, query: Map<String, String>): HttpResponse {
        if (!service.exportEnabled) throw ApiError(404, "not_found", "No such endpoint")
        method(req, "GET")
        if (query.keys.any { it != "events" }) invalid("Only events is supported")
        val jsonl = when (query["events"]) {
            null -> false
            "jsonl" -> true
            else -> invalid("events must be jsonl")
        }
        val payload = service.export(id)
        return if (!jsonl) ok(payload.json)
        else HttpResponse.json(200, payload.jsonl, mapOf("Content-Type" to "application/x-ndjson; charset=utf-8"))
    }

    private fun fileAccess() = service.fileAccess ?: throw ApiError(404, "unsupported", "Task files are unavailable")

    private fun upload(req: HttpRequest, id: String, action: String): HttpResponse {
        method(req, "POST")
        val uploads = fileAccess().uploads
        if (id.isEmpty()) {
            val b = body(req, setOf("name", "size"))
            val name = string(b, "name") ?: invalid("A file name is required")
            val size = integer(b, "size") ?: invalid("A file size is required")
            return ok(buildJsonObject { put("upload_id", fileIO { uploads.create(name, size.toLong()) }) }, 201)
        }
        if (action.isNotEmpty()) {
            body(req, emptySet())
            uploads.discard(id)
            return ok(buildJsonObject { put("discarded", true) })
        }
        val b = body(req, setOf("offset", "data"))
        val offset = integer(b, "offset") ?: invalid("An offset is required")
        val encoded = string(b, "data") ?: invalid("A file chunk is required")
        val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { invalid("Invalid file chunk") }
        val next = fileIO { uploads.append(id, offset.toLong(), bytes) }
        return ok(buildJsonObject { put("next_offset", next) })
    }

    private fun <T> fileIO(block: () -> T): T = try { block() }
        catch (_: IOException) { throw ApiError(409, "file_unavailable", "The file could not be read or stored on the phone") }

    private fun history(req: HttpRequest, query: Map<String, String>): HttpResponse {
        method(req, "GET")
        val source = history ?: throw ApiError(404, "not_found", "History is not supported by this phone")
        if (!source.enabled) throw ApiError(403, "history_disabled", "Allow history viewing in Connect a computer on the phone")
        if (query.keys.any { it !in setOf("offset", "limit") }) invalid("Only offset and limit are supported")
        val parts = req.path.removePrefix("$ROOT/history/").split('/')
        if (parts.firstOrNull() != "conversations" || parts.drop(1).any { !HISTORY_ID.matches(it) })
            throw ApiError(404, "not_found", "No such history endpoint")
        val bodyPage = parts.size == 6 && parts[2] == "runs" && parts[4] == "bodies"
        val max = if (bodyPage) 3_000 else 50
        val offset = query["offset"]?.let { it.toIntOrNull() ?: invalid("offset must be an integer") } ?: 0
        val limit = query["limit"]?.let { it.toIntOrNull() ?: invalid("limit must be an integer") } ?: if (bodyPage) max else 20
        if (offset < 0 || offset > Int.MAX_VALUE - max || limit !in 1..max) invalid("Invalid page bounds")
        val result = try {
            when {
                parts.size == 1 -> source.conversations(offset, limit)
                parts.size == 2 -> source.turns(parts[1], offset, limit)
                parts.size == 4 && parts[2] == "runs" -> source.run(parts[1], parts[3], offset, limit)
                bodyPage && SPAN_ID.matches(parts[5]) -> source.body(parts[1], parts[3], parts[5], offset, limit)
                else -> throw ApiError(404, "not_found", "No such history endpoint")
            }
        } catch (_: IOException) {
            throw ApiError(503, "history_unavailable", "This record could not be read; try again")
        } ?: throw ApiError(404, "not_found", "The conversation, run or request is no longer available")
        if (!source.enabled) throw ApiError(403, "history_disabled", "History viewing was turned off on the phone")
        return ok(result)
    }

    private fun body(req: HttpRequest, allowed: Set<String>): JsonObject {
        if (req.header("content-type")?.substringBefore(';')?.trim()?.lowercase() != "application/json")
            throw ApiError(415, "unsupported_media_type", "Content-Type must be application/json")
        val b = runCatching { Json.parseToJsonElement(req.body.toString(Charsets.UTF_8)) }.getOrNull() as? JsonObject
            ?: invalid("Body must be a JSON object")
        if (b.keys.any { it !in allowed }) invalid("Unsupported request fields")
        return b
    }

    private fun string(body: JsonObject, key: String): String? = when (val v = body[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (v.isString) v.content else invalid("$key must be a string")
        else -> invalid("$key must be a string")
    }

    private fun integer(body: JsonObject, key: String): Int? = when (val v = body[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (!v.isString) v.intOrNull ?: invalid("$key must be an integer") else invalid("$key must be an integer")
        else -> invalid("$key must be an integer")
    }

    private fun query(req: HttpRequest): Map<String, String> {
        if (req.query.isEmpty()) return emptyMap()
        val fields = req.query.split('&').map {
            val pair = it.split('=', limit = 2)
            if (pair.size != 2) invalid("Malformed query")
            try { URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") }
            catch (_: IllegalArgumentException) { invalid("Malformed query") }
        }
        if (fields.map { it.first }.distinct().size != fields.size) invalid("Duplicate query parameter")
        return fields.toMap()
    }

    private fun method(req: HttpRequest, expected: String) { if (req.method != expected) wrongMethod(expected) }
    private fun wrongMethod(allow: String): Nothing = throw ApiError(405, "method_not_allowed", "Method not allowed", mapOf("Allow" to allow))
    private fun invalid(message: String): Nothing = throw ApiError(400, "invalid_argument", message)
    private fun ok(body: JsonObject, status: Int = 200) = HttpResponse.json(status, body.toString())
    private class ApiError(val status: Int, val code: String, message: String, val headers: Map<String, String> = emptyMap()) : Exception(message)

    companion object {
        const val ROOT = "/api/v1"
        private val ID = Regex("[A-Za-z0-9_-]+")
        private val EXPORT_PATH = Regex("$ROOT/tasks/$ID/export")
        private val UPLOAD = Regex("$ROOT/uploads(?:/([A-Za-z0-9_-]+)(/discard)?)?")
        private val RESULT = Regex("$ROOT/tasks/($ID)/files/($ID)")
        private val HISTORY_ID = Regex("[A-Za-z0-9_-]{1,80}")
        private val SPAN_ID = Regex("[a-f0-9]{16}")
        fun error(status: Int, code: String, message: String, headers: Map<String, String> = emptyMap()) =
            HttpResponse.json(status, buildJsonObject { putJsonObject("error") {
                put("code", code); put("message", message)
            } }.toString(), headers)
    }
}
