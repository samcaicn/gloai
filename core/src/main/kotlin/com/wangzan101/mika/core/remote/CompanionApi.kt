package com.wangzan101.mika.core.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URLDecoder

/**
 * 普通 HTTP/JSON 的只读 Web 控制台入口,和任务 API 并列挂在 [RemoteTaskApi.ROOT] 下。
 * 认证由调用方统一完成(局域网 [RemoteHost]、出站隧道 [RelayClient]),这里只管路由、分页和截断。
 */
class CompanionApi(private val companion: RemoteCompanion) {

    fun handle(req: HttpRequest): HttpResponse = try {
        req.header("accept")?.lowercase()?.let {
            if ("application/json" !in it && "*/*" !in it && "application/*" !in it)
                throw ApiError(406, "not_acceptable", "Accept must include application/json")
        }
        route(req)
    } catch (e: ApiError) {
        RemoteTaskApi.error(e.status, e.code, e.message.orEmpty(), e.allow?.let { mapOf("Allow" to it) }.orEmpty())
    }

    private fun route(req: HttpRequest): HttpResponse {
        val query = query(req)
        if (req.path == CONVERSATIONS) {
            method(req, "GET")
            if (query.keys.any { it != "limit" }) invalid("Only limit is supported")
            val limit = query["limit"]?.let(::positive) ?: RemoteCompanion.DEFAULT_LIST
            if (limit > RemoteCompanion.MAX_LIST) invalid("limit must be between 1 and ${RemoteCompanion.MAX_LIST}")
            return ok(buildJsonObject {
                putJsonArray("conversations") {
                    companion.conversations(limit).forEach { c ->
                        add(buildJsonObject {
                            put("conversation", c.id)
                            put("title", c.title)
                            put("updated_at_ms", c.updatedAtMs)
                            c.pinnedAtMs?.let { put("pinned_at_ms", it) }
                            put("turn_count", c.turnCount)
                        })
                    }
                }
            })
        }
        if (req.path == RUNNING) {
            method(req, "GET")
            if (query.isNotEmpty()) invalid("This endpoint accepts no query parameters")
            return ok(buildJsonObject {
                putJsonArray("running") {
                    companion.running().forEach { r ->
                        add(buildJsonObject {
                            put("conversation", r.conversationId)
                            put("goal", r.goal)
                            put("status", r.status)
                            r.runId?.let { put("run_id", it) }
                            r.step?.let { put("step", it) }
                            r.progress?.let { put("progress", it) }
                            r.question?.let { put("question", it) }
                            if (r.options.isNotEmpty()) putJsonArray("options") { r.options.forEach { add(it) } }
                            put("needs_you", r.needsYou)
                            put("truncated", r.truncated)
                        })
                    }
                }
            })
        }
        if (!req.path.startsWith("$CONVERSATIONS/")) throw ApiError(404, "not_found", "No such endpoint")
        val id = req.path.removePrefix("$CONVERSATIONS/")
        if (!ID.matches(id)) throw ApiError(404, "not_found", "No such endpoint")
        method(req, "GET")
        if (query.keys.any { it != "from" && it != "limit" }) invalid("Only from and limit are supported")
        val from = query["from"]?.let(::nonNegative) ?: RemoteCompanion.TAIL
        val limit = query["limit"]?.let(::positive) ?: RemoteCompanion.DEFAULT_PAGE
        if (limit > RemoteCompanion.MAX_PAGE) invalid("limit must be between 1 and ${RemoteCompanion.MAX_PAGE}")
        val page = companion.conversation(id, from, limit) ?: throw ApiError(404, "not_found", "No such conversation")
        return ok(buildJsonObject {
            put("conversation", page.id)
            put("title", page.title)
            put("total", page.total)
            put("from", page.from)
            putJsonArray("turns") {
                page.turns.forEach { t ->
                    add(buildJsonObject {
                        put("index", t.index)
                        put("user_text", t.userText)
                        put("outcome", t.outcome)
                        put("status", t.status)
                        t.note?.let { put("note", it) }
                        t.runId?.let { put("run_id", it) }
                        if (t.steers.isNotEmpty()) putJsonArray("steers") { t.steers.forEach { add(it) } }
                        put("truncated", t.truncated)
                    })
                }
            }
        })
    }

    private fun positive(raw: String): Int =
        raw.toIntOrNull()?.takeIf { it >= 1 } ?: invalid("limit must be a positive integer")

    private fun nonNegative(raw: String): Int =
        raw.toIntOrNull()?.takeIf { it >= 0 } ?: invalid("from must be a non-negative integer")

    private fun method(req: HttpRequest, expected: String) {
        if (req.method != expected) throw ApiError(405, "method_not_allowed", "Method not allowed", expected)
    }

    private fun invalid(message: String): Nothing = throw ApiError(400, "invalid_argument", message)

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

    private fun ok(body: JsonObject) = HttpResponse.json(200, body.toString())

    private class ApiError(val status: Int, val code: String, message: String, val allow: String? = null) : Exception(message)

    companion object {
        const val CONVERSATIONS = "${RemoteTaskApi.ROOT}/conversations"
        const val RUNNING = "${RemoteTaskApi.ROOT}/running"
        private val ID = Regex("[A-Za-z0-9_-]+")

        /** [RemoteEndpoints] 据此把只读控制台的路径交给这里,其余仍走任务 API。 */
        fun owns(path: String) = path == CONVERSATIONS || path == RUNNING || path.startsWith("$CONVERSATIONS/")
    }
}
