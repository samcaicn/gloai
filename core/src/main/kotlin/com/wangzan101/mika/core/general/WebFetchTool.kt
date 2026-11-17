package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.fetch.WebFetch
import com.wangzan101.mika.core.fetch.WebFetchResult
import com.wangzan101.mika.core.net.LocalHosts
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 经用户配置的抓取服务把一页取成文字;失败时明确让模型退回浏览器,不暗中换服务。 */
internal object WebFetchTool : AgentTool {
    /** 一次最多带这么多正文字符:不把整页不可信内容一次送进模型上下文。 */
    private const val MAX_CHARS = 30_000
    private const val DEFAULT_CHARS = 12_000

    override val verb = Verb.ReadPage
    override val label = "读网页"
    override val risk = Risk.Read
    override val offScreen = true
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef(
        "web_fetch", Prompts.tool("web_fetch", "maxChars" to MAX_CHARS),
        mapOf(
            "url" to str("The page address, starting with http:// or https://."),
            "maxChars" to int("Characters to return, 1-$MAX_CHARS (default $DEFAULT_CHARS).", 1, MAX_CHARS),
        ),
        listOf("url"),
    )

    override fun available(ctx: ToolContext) = ctx.config.webFetch != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val target = WebFetch.parseUrl(a.str("url", max = 4000)!!)
            ?: return failure("invalid_url", "Enter an HTTP or HTTPS address.")
        if (LocalHosts.isLocal(target.host)) {
            return failure("local_url", "Local and private addresses cannot be fetched through the service; use the browser instead.")
        }
        val max = a.int("maxChars", 1, MAX_CHARS) ?: DEFAULT_CHARS
        return when (val result = checkNotNull(ctx.config.webFetch).fetch(target.toString())) {
            is WebFetchResult.Success -> {
                val page = result.page
                val text = page.text
                val cut = text.take(max)
                ToolExecution(
                    buildJsonObject {
                        put("ok", true)
                        put("url", page.url)
                        if (page.finalUrl.isNotBlank() && page.finalUrl != page.url) put("finalUrl", page.finalUrl)
                        put("title", Untrusted.wrap(page.title))
                        put("text", Untrusted.wrap(cut))
                        put("chars", text.length)
                        if (cut.length < text.length) {
                            put("truncated", true)
                            put("hint", "Only the first $max of ${text.length} characters are returned; raise maxChars or use the browser to read the rest.")
                        }
                    },
                    observations = listOf(
                        Observation(
                            ObsKind.Read, listOf(page.title, cut).filter(String::isNotBlank).joinToString("\n"), source = "web_fetch",
                            place = Place.page(page.finalUrl.ifBlank { page.url }), title = page.title, trust = Trust.Untrusted,
                        ),
                    ),
                )
            }
            is WebFetchResult.Failure -> failure(result.code, result.message, result.retryAfterSeconds)
        }
    }

    private fun failure(code: String, message: String, retryAfterSeconds: Long? = null) = ToolExecution(buildJsonObject {
        put("ok", false)
        put("error", code)
        put("message", message)
        put("fallback", "Use the browser tools to read this page instead.")
        retryAfterSeconds?.let { put("retryAfterSeconds", it) }
    })
}