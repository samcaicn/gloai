package com.wangzan101.mika.core.fetch

/**
 * 网页抓取由哪家服务提供。各家的请求方式和返回格式各不相同,所以按服务商分别实现,不设「自定义端点」。
 * 地址固定,只填密钥。[wire] 存进设置,只能往后加、不能改名或删。
 */
enum class WebFetchProvider(val wire: String) {
    /** TinyFish 抓取接口(api.fetch.tinyfish.ai):必须有密钥。 */
    TinyFish("tinyfish");

    /** 不填密钥不能用,也不让保存。 */
    val keyRequired: Boolean get() = true

    /** 照 [apiKey] 这一家能不能用;能用为 null。 */
    fun problem(apiKey: String): Problem? = when {
        keyRequired && apiKey.isBlank() -> Problem.Key
        else -> null
    }

    enum class Problem { Key }

    companion object {
        /** 设置里存的值;认不出时为 null。 */
        fun of(wire: String?): WebFetchProvider? = entries.firstOrNull { it.wire == wire }
    }
}