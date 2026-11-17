package com.wangzan101.mika.core.search

/**
 * 网页搜索由哪家服务提供。各家的请求方式和返回格式各不相同,没有通用约定,所以按服务商分别实现,不设「自定义端点」:
 * 要接别的搜索,可以自建 SearXNG(它本身就能聚合几十个搜索引擎),或者接一个提供搜索工具的 MCP 服务器。
 * 几家托管的服务地址固定,只填密钥;SearXNG 填实例地址、不要密钥。[wire] 存进设置,只能往后加、不能改名或删。
 */
enum class WebSearchProvider(val wire: String) {
    /** You.com:不填密钥走免费接口(每天 100 次),填了走付费接口。 */
    You("you"),
    /** Jina 搜索(s.jina.ai):必须有密钥。 */
    Jina("jina"),
    /** Exa 搜索(api.exa.ai):必须有密钥。 */
    Exa("exa"),
    /** Keirolabs 搜索(api.keirolabs.cloud):必须有密钥。 */
    Keirolabs("keirolabs"),
    /** TinyFish 搜索(api.search.tinyfish.ai):必须有密钥。 */
    TinyFish("tinyfish"),
    /** 自建的 SearXNG 实例:填实例地址,不要密钥;实例要打开 JSON 输出。 */
    SearXNG("searxng");

    /** 要用户填地址:SearXNG 的实例地址。 */
    val needsEndpoint: Boolean get() = this == SearXNG

    /** 有密钥框。 */
    val takesKey: Boolean get() = this != SearXNG

    /** 不填密钥不能用,也不让保存。 */
    val keyRequired: Boolean get() = this == Jina || this == Exa || this == Keirolabs || this == TinyFish

    /** 照 [endpoint]、[apiKey] 这一家能不能用;能用为 null。 */
    fun problem(endpoint: String, apiKey: String): Problem? = when {
        needsEndpoint && WebSearch.parseEndpoint(endpoint) == null -> Problem.Endpoint
        keyRequired && apiKey.isBlank() -> Problem.Key
        else -> null
    }

    enum class Problem { Endpoint, Key }

    companion object {
        /** 设置里存的值;认不出时为 null。 */
        fun of(wire: String?): WebSearchProvider? = entries.firstOrNull { it.wire == wire }
    }
}
