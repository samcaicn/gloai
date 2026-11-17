package com.wangzan101.mika.core.fetch

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 抓回来的一页;正文和标题只是不可信资料,不能当作指令。 */
data class WebPage(val url: String, val finalUrl: String, val title: String, val text: String)

sealed interface WebFetchResult {
    data class Success(val page: WebPage) : WebFetchResult
    data class Failure(val code: String, val message: String, val retryAfterSeconds: Long? = null) : WebFetchResult
}

/** 网页抓取端口:按用户选的服务商把一页取成文字(见 [WebFetchProvider]);没有配置时不会提供抓取工具。 */
interface WebFetch {
    suspend fun fetch(url: String): WebFetchResult

    companion object {
        /** 只接受 HTTP(S),不在地址里另带账号密码(查询串、锚点照常保留)。 */
        fun parseUrl(raw: String): HttpUrl? = raw.trim().toHttpUrlOrNull()?.takeIf {
            (it.scheme == "https" || it.scheme == "http") && it.username.isEmpty() && it.password.isEmpty()
        }
    }
}